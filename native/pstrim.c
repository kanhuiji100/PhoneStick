/*
 * pstrim - 释放镜像里「已删除文件」占用的空间
 *
 * 原理：写零没用（会重新分配块），必须 fallocate(PUNCH_HOLE) 打洞。
 * 需要先知道哪些块是空闲的：
 *   - FAT32 : FAT 表项 == 0
 *   - exFAT : Allocation Bitmap（簇位图，1=已用）
 *   - NTFS  : $Bitmap 文件（MFT 记录 6 的数据流位图，1=已用）
 *   - ext4  : 各组 block bitmap（1=已用；BLOCK_UNINIT 的组保守跳过）
 *
 * 用法:
 *   pstrim <image>                 # 自动识别 FAT32/exFAT/NTFS/ext4 并打洞
 *   pstrim <image> --dry-run       # 只统计不落盘
 *   pstrim --fitrim <挂载点>        # 对已挂载的 fs 发 FITRIM（ext4 推荐路径）
 *
 * 输出: STATUS=OK fs=... ... / STATUS=ERR reason=...
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <linux/falloc.h>
#include <linux/fs.h>

#ifndef FALLOC_FL_PUNCH_HOLE
#define FALLOC_FL_PUNCH_HOLE 0x02
#define FALLOC_FL_KEEP_SIZE 0x01
#endif

#define BLK 4096ULL

static uint64_t punched_bytes, punched_runs;
static int stats_mode;
static int dry_run;
static int g_fd = -1;

static uint16_t le16(const unsigned char *b) { return (uint16_t)(b[0] | (b[1] << 8)); }
static uint32_t le32(const unsigned char *b)
{
    return (uint32_t)b[0] | ((uint32_t)b[1] << 8) | ((uint32_t)b[2] << 16) | ((uint32_t)b[3] << 24);
}
static uint64_t le64(const unsigned char *b)
{
    return (uint64_t)le32(b) | ((uint64_t)le32(b + 4) << 32);
}

static int read_at(int fd, void *buf, size_t len, uint64_t off)
{
    ssize_t r = pread(fd, buf, len, (off_t)off);
    return (r == (ssize_t)len) ? 0 : -1;
}

/* 把 [off, off+len) 按 4K 对齐打洞 */
static int punch_aligned(uint64_t off, uint64_t len)
{
    uint64_t s = (off + BLK - 1) & ~(BLK - 1);
    uint64_t e = (off + len) & ~(BLK - 1);
    if (e <= s)
        return 0;
    uint64_t n = e - s;
    if (!dry_run) {
        if (fallocate(g_fd, FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE, (off_t)s, (off_t)n) != 0)
            return -1;
    }
    punched_bytes += n;
    punched_runs++;
    return 0;
}

/* ---------------- FAT32 ---------------- */

static int trim_fat32(int fd, uint64_t part, uint64_t part_size)
{
    unsigned char bpb[512];
    if (read_at(fd, bpb, 512, part) != 0)
        return -2;
    unsigned bps = le16(bpb + 11);
    unsigned spc = bpb[13];
    unsigned rsvd = le16(bpb + 14);
    unsigned nfats = bpb[16];
    uint32_t total_sec = le32(bpb + 32);
    uint32_t fatsz = le32(bpb + 36);
    if (bps != 512 || spc == 0 || (spc & (spc - 1)) || nfats == 0 || fatsz == 0 || total_sec == 0)
        return -3;
    if (memcmp(bpb + 82, "FAT32", 5) != 0)
        return -3;

    uint64_t data_start = part + ((uint64_t)rsvd + (uint64_t)nfats * fatsz) * 512ULL;
    uint64_t cluster_bytes = (uint64_t)spc * bps;
    uint64_t data_secs = (uint64_t)total_sec - rsvd - (uint64_t)nfats * fatsz;
    uint64_t clusters = data_secs / spc;
    uint64_t fat_byte = part + (uint64_t)rsvd * 512ULL;
    if (clusters < 2 || (data_start - part) + clusters * cluster_bytes > part_size)
        return -4;

    const size_t WIN = 1u << 20;
    unsigned char *fat = malloc(WIN);
    if (!fat)
        return -5;
    uint64_t c = 2, run_start = 0, run_end = 0;
    int in_run = 0, rc = 0;
    while (c < clusters + 2 && rc == 0) {
        uint64_t ent_off = c * 4ULL;
        size_t want = WIN;
        if (ent_off + want > (uint64_t)fatsz * 512)
            want = (size_t)((uint64_t)fatsz * 512 - ent_off);
        if (want < 4)
            break;
        if (read_at(fd, fat, want, fat_byte + ent_off) != 0)
            break;
        size_t n = want / 4;
        for (size_t i = 0; i < n && c < clusters + 2; i++, c++) {
            uint32_t e = le32(fat + i * 4) & 0x0FFFFFFF;
            if (e == 0) {
                if (!in_run) { run_start = c; in_run = 1; }
                run_end = c;
            } else if (in_run) {
                if (punch_aligned(data_start + (run_start - 2) * cluster_bytes,
                                  (run_end - run_start + 1) * cluster_bytes) != 0)
                    rc = -6;
                in_run = 0;
            }
        }
    }
    if (rc == 0 && in_run)
        rc = punch_aligned(data_start + (run_start - 2) * cluster_bytes,
                           (run_end - run_start + 1) * cluster_bytes) ? -6 : 0;
    free(fat);
    return rc;
}

/* ---------------- exFAT ---------------- */

/* 按 FAT 链读一个簇链的内容到 buf（最多 max 字节） */
static int64_t exfat_read_chain(int fd, uint64_t heap, uint64_t fat_byte, uint32_t fat_len_bytes,
                                uint64_t cluster_bytes, uint32_t first, void *buf, uint64_t max,
                                int depth_limit)
{
    uint8_t *out = buf;
    uint64_t got = 0;
    uint32_t c = first;
    for (int guard = 0; guard < depth_limit && got < max; guard++) {
        uint64_t off = heap + ((uint64_t)c - 2) * cluster_bytes;
        size_t want = (size_t)((max - got < cluster_bytes) ? (max - got) : cluster_bytes);
        if (read_at(fd, out + got, want, off) != 0)
            return -1;
        got += want;
        uint64_t ent = (uint64_t)c * 4;
        if (ent + 4 > fat_len_bytes)
            break;
        uint32_t next = 0;
        if (read_at(fd, &next, 4, fat_byte + ent) != 0)
            break;
        next &= 0xFFFFFFFF;
        if (next < 2 || next >= 0xFFFFFFF7)
            break;
        c = next;
    }
    return (int64_t)got;
}

static int trim_exfat(int fd, uint64_t part, uint64_t part_size)
{
    unsigned char bs[512];
    if (read_at(fd, bs, 512, part) != 0)
        return -2;
    unsigned bps_shift = bs[108];
    unsigned spc_shift = bs[109];
    if (bps_shift < 9 || bps_shift > 12 || spc_shift > 25)
        return -3;
    uint64_t bps = 1ULL << bps_shift;
    uint64_t cluster_bytes = bps << spc_shift;
    uint32_t fat_off_sec = le32(bs + 80);
    uint32_t fat_len_sec = le32(bs + 84);
    uint32_t heap_off_sec = le32(bs + 88);
    uint32_t cluster_count = le32(bs + 92);
    uint32_t root_cluster = le32(bs + 96);
    if (!fat_len_sec || !cluster_count || root_cluster < 2)
        return -3;

    uint64_t fat_byte = part + (uint64_t)fat_off_sec * bps;
    uint64_t fat_len_bytes = (uint64_t)fat_len_sec * bps;
    uint64_t heap = part + (uint64_t)heap_off_sec * bps;
    if ((heap - part) + (uint64_t)cluster_count * cluster_bytes > part_size)
        return -4;

    /* 根目录里找 Allocation Bitmap 目录项（0x81） */
    uint64_t dir_max = cluster_bytes * 64; /* 根目录一般很小，给 64 簇上限 */
    unsigned char *dir = malloc(dir_max);
    if (!dir)
        return -5;
    int64_t got = exfat_read_chain(fd, heap, fat_byte, (uint32_t)(fat_len_bytes > UINT32_MAX ? UINT32_MAX : fat_len_bytes),
                                   cluster_bytes, root_cluster, dir, dir_max, 4096);
    if (got <= 0) {
        free(dir);
        return -6;
    }
    uint32_t bmp_cluster = 0;
    uint64_t bmp_len = 0;
    for (int64_t o = 0; o + 32 <= got; o += 32) {
        uint8_t t = dir[o];
        if (t == 0x00)
            break;
        if (t == 0x81) { /* Allocation Bitmap */
            bmp_cluster = le32(dir + o + 20);
            bmp_len = le64(dir + o + 24);
            break;
        }
    }
    free(dir);
    if (bmp_cluster < 2 || bmp_len == 0)
        return -7;

    uint64_t need = ((uint64_t)cluster_count + 7) / 8;
    if (bmp_len < need)
        need = bmp_len;
    unsigned char *bmp = calloc(1, need);
    if (!bmp)
        return -5;
    got = exfat_read_chain(fd, heap, fat_byte, (uint32_t)(fat_len_bytes > UINT32_MAX ? UINT32_MAX : fat_len_bytes),
                           cluster_bytes, bmp_cluster, bmp, need, 65536);
    if (got <= 0) {
        free(bmp);
        return -6;
    }

    /* 位图 bit i == 0 表示簇 (i+2) 空闲 */
    uint64_t run_start = 0, run_end = 0;
    int in_run = 0, rc = 0;
    for (uint64_t i = 0; i < cluster_count && rc == 0; i++) {
        int used = (bmp[i >> 3] >> (i & 7)) & 1;
        uint64_t cl = i + 2;
        if (!used) {
            if (!in_run) { run_start = cl; in_run = 1; }
            run_end = cl;
        } else if (in_run) {
            if (punch_aligned(heap + (run_start - 2) * cluster_bytes,
                              (run_end - run_start + 1) * cluster_bytes) != 0)
                rc = -6;
            in_run = 0;
        }
    }
    if (rc == 0 && in_run)
        rc = punch_aligned(heap + (run_start - 2) * cluster_bytes,
                           (run_end - run_start + 1) * cluster_bytes) ? -6 : 0;
    free(bmp);
    return rc;
}

/* ---------------- NTFS ---------------- */

struct run { uint64_t lcn; uint64_t len; };

/* 解析 NTFS runlist，返回 run 数量；lcn 为该属性实际起始簇 */
static int ntfs_parse_runlist(const unsigned char *p, const unsigned char *end,
                              struct run *runs, int max_runs)
{
    int n = 0;
    int64_t lcn = 0;
    while (p < end && *p != 0 && n < max_runs) {
        unsigned h = *p++;
        unsigned len_bytes = h & 0x0F;
        unsigned off_bytes = h >> 4;
        if (!len_bytes || len_bytes > 8 || off_bytes > 8)
            return -1;
        uint64_t len = 0;
        for (unsigned i = 0; i < len_bytes; i++)
            len |= (uint64_t)p[i] << (8 * i);
        p += len_bytes;
        int64_t delta = 0;
        if (off_bytes) {
            uint64_t v = 0;
            for (unsigned i = 0; i < off_bytes; i++)
                v |= (uint64_t)p[i] << (8 * i);
            /* 符号扩展 */
            if (off_bytes < 8 && (v & (1ULL << (off_bytes * 8 - 1))))
                v |= ~((1ULL << (off_bytes * 8)) - 1);
            delta = (int64_t)v;
            p += off_bytes;
        }
        lcn += delta;
        if (lcn < 0)
            return -1;
        runs[n].lcn = (uint64_t)lcn;
        runs[n].len = len;
        n++;
    }
    return n;
}

static int trim_ntfs(int fd, uint64_t part, uint64_t part_size)
{
    unsigned char bs[512];
    if (read_at(fd, bs, 512, part) != 0)
        return -2;
    unsigned bps = le16(bs + 11);
    unsigned spc = bs[13];
    uint64_t total_sectors = le64(bs + 40);
    uint64_t mft_lcn = le64(bs + 48);
    int8_t rec_clusters = (int8_t)bs[64];
    if (!bps || !spc || !total_sectors || !mft_lcn)
        return -3;

    uint64_t cluster_bytes = (uint64_t)bps * spc;
    uint64_t rec_size = rec_clusters > 0 ? (uint64_t)rec_clusters * cluster_bytes
                                        : (1ULL << (unsigned)(-rec_clusters));
    if (rec_size < 256 || rec_size > 65536)
        return -3;
    uint64_t total_clusters = total_sectors / spc;
    if (total_clusters * cluster_bytes > part_size + cluster_bytes)
        return -4;

    /* 读 MFT 记录 6（$Bitmap） */
    unsigned char *rec = malloc(rec_size);
    if (!rec)
        return -5;
    if (read_at(fd, rec, (size_t)rec_size, part + mft_lcn * cluster_bytes + 6 * rec_size) != 0) {
        free(rec);
        return -6;
    }
    if (memcmp(rec, "FILE", 4) != 0) {
        free(rec);
        return -7;
    }
    unsigned attr_off = le16(rec + 0x14);
    uint64_t data_bytes = 0;
    struct run runs[512];
    int nruns = 0;
    unsigned off = attr_off;
    while (off + 16 <= rec_size) {
        uint32_t type = le32(rec + off);
        if (type == 0xFFFFFFFF)
            break;
        uint32_t alen = le32(rec + off + 4);
        if (alen < 16 || off + alen > rec_size)
            break;
        int non_resident = rec[off + 8];
        if (type == 0x80 && non_resident) {
            uint16_t run_off = le16(rec + off + 0x20);
            data_bytes = le64(rec + off + 0x30);
            nruns = ntfs_parse_runlist(rec + off + run_off, rec + off + alen, runs, 512);
            break;
        }
        off += alen;
    }
    free(rec);
    if (nruns <= 0)
        return -7;

    uint64_t need = ((total_clusters + 7) / 8);
    if (data_bytes && data_bytes < need)
        need = data_bytes;
    unsigned char *bmp = calloc(1, need);
    if (!bmp)
        return -5;

    /* 按 runlist 拼出位图 */
    uint64_t pos = 0;
    for (int i = 0; i < nruns && pos < need; i++) {
        uint64_t bytes = runs[i].len * cluster_bytes;
        if (bytes > need - pos)
            bytes = need - pos;
        if (read_at(fd, bmp + pos, (size_t)bytes, part + runs[i].lcn * cluster_bytes) != 0)
            break;
        pos += bytes;
    }
    if (pos == 0) {
        free(bmp);
        return -6;
    }

    /* $Bitmap: bit=1 已用 */
    uint64_t run_start = 0, run_end = 0;
    int in_run = 0, rc = 0;
    for (uint64_t i = 0; i < total_clusters && rc == 0; i++) {
        int used = (bmp[i >> 3] >> (i & 7)) & 1;
        if (!used) {
            if (!in_run) { run_start = i; in_run = 1; }
            run_end = i;
        } else if (in_run) {
            if (punch_aligned(part + run_start * cluster_bytes,
                              (run_end - run_start + 1) * cluster_bytes) != 0)
                rc = -6;
            in_run = 0;
        }
    }
    if (rc == 0 && in_run)
        rc = punch_aligned(part + run_start * cluster_bytes,
                           (run_end - run_start + 1) * cluster_bytes) ? -6 : 0;
    free(bmp);
    return rc;
}

/* ---------------- ext4 ---------------- */

#define EXT4_BG_BLOCK_UNINIT 0x0002

/* 该组是否带超级块备份（决定描述符块相对组首的偏移） */
static int ext4_bg_has_super(uint32_t group, int sparse_super, int sparse_super2,
                             uint32_t *backup_bgs)
{
    if (group == 0)
        return 1;
    if (sparse_super2)
        return group == backup_bgs[0] || group == backup_bgs[1];
    if (sparse_super) {
        if (group == 1)
            return 0;
        /* 3、5、7 的幂次有备份 */
        for (uint32_t base = 3; base <= 7; base += 2) {
            uint32_t t = group;
            while (t % base == 0)
                t /= base;
            if (t == 1)
                return 1;
        }
        return 0;
    }
    return 1;
}

static int trim_ext4(int fd, uint64_t part, uint64_t part_size)
{
    unsigned char sb[1024];
    if (read_at(fd, sb, 1024, part + 1024) != 0)
        return -2;
    uint32_t log_block = le32(sb + 0x18);
    uint32_t bpg = le32(sb + 0x20);
    uint32_t first_data_block = le32(sb + 0x14);
    uint32_t incompat = le32(sb + 0x60);   /* 注意：0x5C 是 compat，别搞混 */
    uint32_t ro_compat = le32(sb + 0x64);
    uint32_t first_meta_bg = le32(sb + 0x104);
    uint16_t desc_size = le16(sb + 0xFE);
    uint16_t reserved_gdt = le16(sb + 0xCE);
    uint32_t inodes_per_group = le32(sb + 0x28);
    uint16_t inode_size = le16(sb + 0x58);
    uint64_t blocks = le32(sb + 0x04);
    uint32_t backup_bgs[2] = { le32(sb + 0x24C), le32(sb + 0x250) };
    if (log_block > 6 || !bpg || !blocks || !inodes_per_group || inode_size < 128)
        return -3;

    int is64 = (incompat & 0x80) != 0;
    int has_meta_bg = (incompat & 0x10) != 0;
    int sparse_super = (ro_compat & 0x1) != 0;
    int sparse_super2 = (ro_compat & 0x200) != 0;
    if (is64) {
        blocks |= ((uint64_t)le32(sb + 0x150) << 32);
        if (desc_size < 64)
            desc_size = 64;
    } else {
        desc_size = 32;
    }

    uint64_t block_size = 1024ULL << log_block;
    if (block_size > BLK)
        return -9;
    uint64_t groups = (blocks + bpg - 1) / bpg;
    if (groups == 0 || groups > 1u << 21)
        return -3;
    uint32_t desc_per_blk = (uint32_t)(block_size / desc_size);
    if (!desc_per_blk)
        return -3;
    uint64_t itable_blocks = ((uint64_t)inodes_per_group * inode_size + block_size - 1) / block_size;
    uint64_t gdt_blocks = (groups * desc_size + block_size - 1) / block_size;

    /* 组描述符定位（meta_bg 与非 meta_bg 两套布局） */
#define DESC_BLOCK_LOCAL(g) ({                                            \
        uint64_t _blk_index = (g) / desc_per_blk;                         \
        uint64_t _blk;                                                    \
        if (!has_meta_bg || _blk_index < first_meta_bg) {                 \
            _blk = (uint64_t)first_data_block + _blk_index + 1;           \
        } else {                                                          \
            uint64_t _bg = (uint64_t)desc_per_blk * _blk_index;           \
            int _hs = ext4_bg_has_super((uint32_t)_bg, sparse_super,      \
                                        sparse_super2, backup_bgs);       \
            _blk = (uint64_t)first_data_block + _bg * bpg + _hs;          \
        }                                                                 \
        _blk; })

    /* ---------- 1. 收集所有"元数据块"，任何情况下都不碰 ---------- */
    uint64_t rsv_bytes = (blocks + 7) / 8;
    unsigned char *rsv = calloc(1, rsv_bytes);
    unsigned char *desc = malloc(desc_size);
    if (!rsv || !desc) { free(rsv); free(desc); return -5; }

#define MARK(b) do { if ((b) < blocks) rsv[(b) >> 3] |= (unsigned char)(1u << ((b) & 7)); } while (0)
#define IS_RSV(b) (((b) < blocks) && ((rsv[(b) >> 3] >> ((b) & 7)) & 1))

    for (uint64_t g = 0; g < groups; g++) {
        uint64_t blk = DESC_BLOCK_LOCAL(g);
        uint64_t off = part + blk * block_size + (g % desc_per_blk) * desc_size;
        if (off + desc_size > part + part_size)
            break;
        if (read_at(fd, desc, desc_size, off) != 0)
            break;
        uint64_t bb = le32(desc + 0x00);
        uint64_t ib = le32(desc + 0x04);
        uint64_t it = le32(desc + 0x08);
        if (is64 && desc_size >= 0x30) {
            bb |= ((uint64_t)le32(desc + 0x20) << 32);
            ib |= ((uint64_t)le32(desc + 0x24) << 32);
            it |= ((uint64_t)le32(desc + 0x28) << 32);
        }
        MARK(bb);
        MARK(ib);
        for (uint64_t k = 0; k < itable_blocks; k++)
            MARK(it + k);

        /* 该组的超级块 / 组描述符备份 */
        uint64_t gfirst = (uint64_t)first_data_block + g * bpg;
        if (ext4_bg_has_super((uint32_t)g, sparse_super, sparse_super2, backup_bgs)) {
            MARK(gfirst);   /* 备份超级块 */
            for (uint64_t k = 0; k < gdt_blocks + reserved_gdt; k++)
                MARK(gfirst + 1 + k);
        }
    }

    uint64_t rsv_cnt = 0;
    for (uint64_t b = 0; b < blocks; b++)
        if (IS_RSV(b)) rsv_cnt++;
    if (stats_mode)
        printf("STATS rsv_blocks=%llu (%.1f MB) gdt_blocks=%llu\n",
               (unsigned long long)rsv_cnt, rsv_cnt * 4096.0 / 1048576.0,
               (unsigned long long)gdt_blocks);

    /* ---------- 2. 逐组打洞 ---------- */
    unsigned char *bmp = malloc(block_size);
    if (!bmp) { free(rsv); free(desc); return -5; }

    uint64_t cnt_free = 0, cnt_total = 0, groups_uninit = 0;
    int rc = 0;
    for (uint64_t g = 0; g < groups && rc == 0; g++) {
        uint64_t blk = DESC_BLOCK_LOCAL(g);
        uint64_t off = part + blk * block_size + (g % desc_per_blk) * desc_size;
        if (off + desc_size > part + part_size) { rc = -4; break; }
        if (read_at(fd, desc, desc_size, off) != 0) { rc = -6; break; }

        uint16_t flags = le16(desc + 0x12);
        int uninit = (flags & EXT4_BG_BLOCK_UNINIT) != 0;
        uint64_t bb = le32(desc + 0x00);
        if (is64 && desc_size >= 0x28)
            bb |= ((uint64_t)le32(desc + 0x20) << 32);

        if (uninit) {
            /* 位图未初始化 = 内核认为该组整组空闲（除元数据外）。
               这里按"除元数据外全部空闲"处理，能回收格式化后残留的旧数据块。 */
            groups_uninit++;
        } else {
            if (!bb || bb >= blocks) continue;
            if (read_at(fd, bmp, block_size, part + bb * block_size) != 0) { rc = -6; break; }
        }

        uint64_t base = (uint64_t)first_data_block + g * bpg;
        uint64_t run_start = 0, run_end = 0;
        int in_run = 0;
        for (uint64_t i = 0; i < bpg; i++) {
            uint64_t b = base + i;
            if (b >= blocks)
                break;
            int used;
            if (uninit)
                used = IS_RSV(b);
            else
                used = ((bmp[i >> 3] >> (i & 7)) & 1) || IS_RSV(b);
            cnt_total++;
            if (!used) {
                cnt_free++;
                if (!in_run) { run_start = b; in_run = 1; }
                run_end = b;
            } else if (in_run) {
                if (punch_aligned(part + run_start * block_size,
                                  (run_end - run_start + 1) * block_size) != 0)
                    rc = -6;
                in_run = 0;
            }
        }
        if (in_run && rc == 0)
            rc = punch_aligned(part + run_start * block_size,
                               (run_end - run_start + 1) * block_size) ? -6 : 0;
    }
    free(bmp);
    free(rsv);
    free(desc);
    if (stats_mode)
        printf("STATS fs=ext4 blocks=%llu free=%llu groups=%llu uninit=%llu rsv_gdt=%u\\n",
               (unsigned long long)cnt_total, (unsigned long long)cnt_free,
               (unsigned long long)groups, (unsigned long long)groups_uninit, reserved_gdt);
    return rc;
}

/* ---------------- FITRIM（挂载后交给文件系统自己打洞） ---------------- */

static int do_fitrim(const char *dir)
{
    struct fstrim_range range;
    memset(&range, 0, sizeof(range));
    range.start = 0;
    range.len = UINT64_MAX;
    range.minlen = BLK;
    int fd = open(dir, O_RDONLY | O_DIRECTORY);
    if (fd < 0) {
        printf("STATUS=ERR reason=open_mountpoint errno=%d\n", errno);
        return 1;
    }
    if (ioctl(fd, FITRIM, &range) != 0) {
        printf("STATUS=ERR reason=fitrim_failed errno=%d\n", errno);
        close(fd);
        return 1;
    }
    printf("STATUS=OK fitrim len=%llu\n", (unsigned long long)range.len);
    close(fd);
    return 0;
}


/* 分区描述 */
struct part {
    uint64_t off;
    uint64_t size;
};

/*
 * 收集镜像里的所有分区。
 *   - GPT（保护性 MBR 类型 0xEE）：读 GPT 头，遍历分区条目
 *   - 常规 MBR：最多 4 个主分区
 *   - 没有任何可用分区项：按"超级软盘"处理，整个文件就是一个数据区
 * 返回分区数量。
 */
static int locate_partitions(int fd, uint64_t file_size, struct part *parts, int max_parts)
{
    unsigned char mbr[512];
    if (read_at(fd, mbr, 512, 0) != 0 || mbr[510] != 0x55 || mbr[511] != 0xAA) {
        parts[0].off = 0;
        parts[0].size = file_size;
        return 1;
    }

    for (int i = 0; i < 4; i++) {
        const unsigned char *e = mbr + 446 + i * 16;
        if (e[4] != 0xEE)
            continue;
        /* GPT */
        unsigned char hdr[512];
        if (read_at(fd, hdr, 512, 512) != 0 || memcmp(hdr, "EFI PART", 8) != 0)
            break;
        uint64_t ents_lba = le64(hdr + 72);
        uint32_t num = le32(hdr + 80);
        uint32_t esz = le32(hdr + 84);
        if (!ents_lba || !num || esz < 128 || esz > 4096)
            break;
        unsigned char ent[4096];
        int n = 0;
        for (uint32_t k = 0; k < num && n < max_parts; k++) {
            if (read_at(fd, ent, esz, ents_lba * 512 + (uint64_t)k * esz) != 0)
                break;
            int empty = 1;
            for (int b = 0; b < 16; b++)
                if (ent[b]) { empty = 0; break; }
            if (empty)
                continue;
            uint64_t first = le64(ent + 32);
            uint64_t last = le64(ent + 40);
            if (!first || last < first || first * 512 >= file_size)
                continue;
            uint64_t off = first * 512;
            uint64_t sz = (last - first + 1) * 512;
            if (off + sz > file_size)
                sz = file_size - off;
            parts[n].off = off;
            parts[n].size = sz;
            n++;
        }
        if (n > 0)
            return n;
        break;
    }

    /* 常规 MBR */
    int n = 0;
    for (int i = 0; i < 4 && n < max_parts; i++) {
        const unsigned char *e = mbr + 446 + i * 16;
        uint32_t start = le32(e + 8);
        uint32_t secs = le32(e + 12);
        if (e[4] == 0 || !start || !secs)
            continue;
        if ((uint64_t)start * 512 + 1024 > file_size)
            continue;
        uint64_t sz = (uint64_t)secs * 512;
        if ((uint64_t)start * 512 + sz > file_size)
            sz = file_size - (uint64_t)start * 512;
        parts[n].off = (uint64_t)start * 512;
        parts[n].size = sz;
        n++;
    }
    if (n > 0)
        return n;

    parts[0].off = 0;
    parts[0].size = file_size;
    return 1;
}

/* 识别文件系统类型 */
static const char *detect_fs(int fd, uint64_t part)
{
    unsigned char bs[512];
    if (read_at(fd, bs, 512, part) != 0)
        return NULL;
    if (memcmp(bs + 3, "EXFAT   ", 8) == 0)
        return "exfat";
    if (memcmp(bs + 3, "NTFS    ", 4) == 0)
        return "ntfs";
    if (memcmp(bs + 82, "FAT32", 5) == 0)
        return "fat32";
    unsigned char m[2];
    if (read_at(fd, m, 2, part + 1024 + 0x38) == 0 && le16(m) == 0xEF53)
        return "ext4";
    return NULL;
}


/* 把 [from,to) 区间里全零的 4K 块合并成连续段后打洞（只碰纯零块，绝对安全） */
static void punch_zero_range(int fd, uint64_t from, uint64_t to)
{
    if (to <= from)
        return;
    const size_t CH = 1u << 20;
    unsigned char *buf = malloc(CH);
    if (!buf)
        return;
    uint64_t pos = from & ~(BLK - 1);
    uint64_t run_start = 0;
    int in_run = 0;
    while (pos < to) {
        ssize_t r = pread(fd, buf, CH, (off_t)pos);
        if (r <= 0)
            break;
        for (ssize_t i = 0; i + (ssize_t)BLK <= r; i += BLK) {
            uint64_t off = pos + (uint64_t)i;
            if (off + BLK > to)
                break;
            int zero = 1;
            for (size_t k = 0; k < BLK; k += 8)
                if (*(const uint64_t *)(buf + i + k) != 0) { zero = 0; break; }
            if (zero) {
                if (!in_run) { run_start = off; in_run = 1; }
            } else if (in_run) {
                if (punch_aligned(run_start, off - run_start) != 0) { free(buf); return; }
                in_run = 0;
            }
        }
        pos += (uint64_t)r;
    }
    if (in_run)
        punch_aligned(run_start, to - run_start);
    free(buf);
}


/*
 * 最后一遍：遍历文件实际分配的区间（SEEK_DATA/SEEK_HOLE），
 * 把其中全零的 4K 块打洞。
 *
 * 为什么安全：只碰内容全为零的块，打洞后读回来仍是零，语义完全不变。
 * 收益：ext4 的 inode 表、保留 GDT 块在被内核"懒初始化"后都是整块零，
 * 但被标记为已分配，普通空闲块回收碰不到它们。
 */
static void zero_pass(int fd, uint64_t file_size)
{
    const size_t CH = 1u << 20;
    unsigned char *buf = malloc(CH);
    if (!buf)
        return;
    uint64_t pos = 0;
    while (pos < file_size) {
        off_t d = lseek(fd, (off_t)pos, SEEK_DATA);
        if (d < 0)
            break;
        off_t h = lseek(fd, d, SEEK_HOLE);
        if (h < 0)
            h = (off_t)file_size;
        uint64_t cur = (uint64_t)d, end = (uint64_t)h;
        uint64_t run_start = 0;
        int in_run = 0;
        while (cur < end) {
            size_t want = (size_t)((end - cur < CH) ? (end - cur) : CH);
            if (read_at(fd, buf, want, cur) != 0)
                break;
            for (size_t i = 0; i + (size_t)BLK <= want; i += BLK) {
                int zero = 1;
                for (size_t k = 0; k < BLK; k += 8)
                    if (*(const uint64_t *)(buf + i + k) != 0) { zero = 0; break; }
                uint64_t off = cur + i;
                if (zero) {
                    if (!in_run) { run_start = off; in_run = 1; }
                } else if (in_run) {
                    if (punch_aligned(run_start, off - run_start) != 0) { free(buf); return; }
                    in_run = 0;
                }
            }
            cur += want;
        }
        if (in_run)
            punch_aligned(run_start, cur - run_start);
        pos = end;
    }
    free(buf);
}

int main(int argc, char **argv)
{
    if (argc >= 2 && strcmp(argv[1], "--map") == 0 && argc >= 3) {
        int f = open(argv[2], O_RDONLY);
        if (f < 0) { printf("STATUS=ERR reason=open_failed\n"); return 1; }
        struct stat stt;
        fstat(f, &stt);
        uint64_t total = (uint64_t)stt.st_size, pos = 0;
        int n = 0;
        while (pos < total && n < 400) {
            off_t d = lseek(f, (off_t)pos, SEEK_DATA);
            if (d < 0) break;
            off_t h = lseek(f, d, SEEK_HOLE);
            if (h < 0) h = (off_t)total;
            printf("EXT off=%llu len=%llu\n", (unsigned long long)d, (unsigned long long)(h - d));
            pos = (uint64_t)h;
            n++;
        }
        printf("MAP_END extents=%d\n", n);
        close(f);
        return 0;
    }

    if (argc >= 3 && strcmp(argv[1], "--fitrim") == 0)
        return do_fitrim(argv[2]);
    if (argc < 2) {
        fprintf(stderr, "usage: pstrim <image> [--dry-run]\n       pstrim --fitrim <mountpoint>\n");
        return 2;
    }
    if (argc > 2 && strcmp(argv[2], "--dry-run") == 0)
        dry_run = 1;
    if (argc > 2 && strcmp(argv[2], "--stats") == 0) {
        dry_run = 1;
        stats_mode = 1;
    }

    int fd = open(argv[1], O_RDWR);
    if (fd < 0) {
        printf("STATUS=ERR reason=open_failed errno=%d\n", errno);
        return 1;
    }
    g_fd = fd;

    struct stat st;
    fstat(fd, &st);
    uint64_t file_size = (uint64_t)st.st_size;

    struct part parts[128];
    int np = locate_partitions(fd, file_size, parts, 128);
    int superfloppy = (np == 1 && parts[0].off == 0 && parts[0].size == file_size);

    /* 分区表之前（MBR/GPT 头）的全零块 */
    punch_zero_range(fd, 0, parts[0].off < file_size ? parts[0].off : file_size);

    /* 最后一个分区之后到文件末尾：常见于"镜像比分区总和更大"，这里往往是全零 */
    {
        uint64_t tail_start = 0;
        for (int i = 0; i < np; i++) {
            uint64_t e = parts[i].off + parts[i].size;
            if (e > tail_start)
                tail_start = e;
        }
        if (tail_start < file_size)
            punch_zero_range(fd, tail_start, file_size);
    }

    char fs_list[128] = "";
    int trimmed = 0, skipped = 0, failed = 0;
    for (int i = 0; i < np; i++) {
        const char *fs = detect_fs(fd, parts[i].off);
        if (!fs) {
            /* 分区里没有可识别的文件系统：跳过（例如 EFI 分区是 FAT，通常能认） */
            skipped++;
            continue;
        }
        int rc = 0;
        if (strcmp(fs, "exfat") == 0)
            rc = trim_exfat(fd, parts[i].off, parts[i].size);
        else if (strcmp(fs, "ntfs") == 0)
            rc = trim_ntfs(fd, parts[i].off, parts[i].size);
        else if (strcmp(fs, "fat32") == 0)
            rc = trim_fat32(fd, parts[i].off, parts[i].size);
        else
            rc = trim_ext4(fd, parts[i].off, parts[i].size);
        if (rc != 0) {
            failed++;
            printf("PART%d=ERR fs=%s code=%d\n", i + 1, fs, rc);
            continue;
        }
        trimmed++;
        if (fs_list[0])
            strncat(fs_list, ",", sizeof(fs_list) - strlen(fs_list) - 1);
        strncat(fs_list, fs, sizeof(fs_list) - strlen(fs_list) - 1);
    }

    if (trimmed == 0) {
        if (failed)
            printf("STATUS=ERR reason=trim_failed fs=none code=parts_failed\n");
        else
            printf("STATUS=ERR reason=unknown_fs\n");
        return 1;
    }

    if (trimmed > 0)
        zero_pass(fd, file_size);

    if (!dry_run)
        fdatasync(fd);

    struct stat st2;
    fstat(fd, &st2);
    printf("STATUS=OK fs=%s layout=%s parts=%d trimmed=%d skipped=%d runs=%llu punched=%llu before=%llu after=%llu dry=%d\n",
           fs_list, superfloppy ? "superfloppy" : "partition", np, trimmed, skipped,
           (unsigned long long)punched_runs, (unsigned long long)punched_bytes,
           (unsigned long long)st.st_blocks * 512ULL,
           (unsigned long long)st2.st_blocks * 512ULL, dry_run);
    close(fd);
    return 0;
}
