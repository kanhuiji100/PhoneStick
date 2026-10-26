#!/usr/bin/env python3
"""生成 PhoneStick 镜像的 MBR + FAT32 分区头数据块，输出为可直接 dd 的文件。

用法:
    python3 phonestick_parts.py <镜像总字节数> <输出前缀>

产出:
    <前缀>.mbr   512 字节，写入偏移 0
    <前缀>.tail  从 LBA 2048 开始的分区头（引导扇区/FSInfo/FAT/根目录）
"""
import struct
import sys

SECT = 512
START_LBA = 2048
RES_SECTORS = 32
FAT_COUNT = 2


def chs(lba):
    c = lba // (255 * 63)
    h = (lba // 63) % 255
    s = (lba % 63) + 1
    if c > 1023:
        c = 1023
    return bytes([h, ((c >> 2) & 0xC0) | s, c & 0xFF])


def build_mbr(total_sectors, start_lba=START_LBA):
    part_sectors = total_sectors - start_lba
    mbr = bytearray(SECT)
    entry = bytearray(16)
    entry[0] = 0x00
    entry[1:4] = chs(start_lba)
    entry[4] = 0x0C  # FAT32 LBA
    entry[5:8] = chs(start_lba + part_sectors - 1)
    entry[8:12] = struct.pack('<I', start_lba)
    entry[12:16] = struct.pack('<I', part_sectors)
    mbr[446:462] = entry
    mbr[510:512] = b'\x55\xAA'
    return bytes(mbr), part_sectors


def fat_size(part_sectors, res=RES_SECTORS, fat_count=FAT_COUNT):
    fatsecs = part_sectors // 256 + 16
    while True:
        data = part_sectors - res - fat_count * fatsecs
        need = (data * 4 + SECT - 1) // SECT
        if need <= fatsecs:
            return fatsecs
        fatsecs = need


def build_boot_sector(part_sectors, fatsecs, res=RES_SECTORS, fat_count=FAT_COUNT, spc=1):
    bs = bytearray(SECT)
    bs[0:3] = b'\xEB\x58\x90'
    bs[3:11] = b'MSWIN4.1'
    struct.pack_into('<H', bs, 11, SECT)
    bs[13] = spc
    struct.pack_into('<H', bs, 14, res)
    bs[16] = fat_count
    struct.pack_into('<H', bs, 17, 0)
    struct.pack_into('<H', bs, 19, 0)
    bs[21] = 0xF8
    struct.pack_into('<H', bs, 22, 0)
    struct.pack_into('<H', bs, 24, 63)
    struct.pack_into('<H', bs, 26, 255)
    struct.pack_into('<I', bs, 28, 0)
    struct.pack_into('<I', bs, 32, part_sectors)
    struct.pack_into('<I', bs, 36, fatsecs)
    struct.pack_into('<H', bs, 40, 0)
    struct.pack_into('<H', bs, 42, 0)
    struct.pack_into('<I', bs, 44, 2)
    struct.pack_into('<H', bs, 48, 1)
    struct.pack_into('<H', bs, 50, 6)
    bs[64] = 0x80
    bs[66] = 0x29
    struct.pack_into('<I', bs, 67, 0x20161026)
    bs[71:82] = b'PHONESTICK '
    bs[82:90] = b'FAT32   '
    bs[90:94] = b'\xFA\xF8\x00\x00'  # 占位引导代码
    bs[94:96] = b'\xCD\x18'
    bs[510:512] = b'\x55\xAA'
    return bytes(bs)


def build_fsinfo():
    fi = bytearray(SECT)
    struct.pack_into('<I', fi, 0, 0x41615252)
    struct.pack_into('<I', fi, 484, 0x61417272)
    struct.pack_into('<I', fi, 488, 0xFFFFFFFF)
    struct.pack_into('<I', fi, 492, 0xFFFFFFFF)
    struct.pack_into('<I', fi, 508, 0xAA550000)
    return bytes(fi)


def build_fat(fatsecs, clusters):
    fat = bytearray(fatsecs * SECT)
    struct.pack_into('<I', fat, 0, 0x0FFFFFF8)
    struct.pack_into('<I', fat, 4, 0x0FFFFFFF)
    struct.pack_into('<I', fat, 8, 0x0FFFFFFF)  # cluster 2 根目录 EOC
    return bytes(fat)


def build_tail(part_sectors, fatsecs, spc=1):
    fat_count = FAT_COUNT
    reserved = bytearray(RES_SECTORS * SECT)
    reserved[0:SECT] = build_boot_sector(part_sectors, fatsecs, spc=spc)
    reserved[SECT:2 * SECT] = build_fsinfo()
    reserved[6 * SECT:7 * SECT] = build_boot_sector(part_sectors, fatsecs, spc=spc)
    reserved[7 * SECT:8 * SECT] = build_fsinfo()

    data_sectors = part_sectors - RES_SECTORS - fat_count * fatsecs
    clusters = data_sectors // spc
    fat = build_fat(fatsecs, clusters)

    root = bytearray(spc * SECT)
    vol = bytearray(32)
    vol[0:11] = b'PHONESTICK '
    vol[11] = 0x08
    root[0:32] = vol

    return bytes(reserved) + fat + fat + bytes(root), clusters


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    total_bytes = int(sys.argv[1])
    prefix = sys.argv[2]

    total_sectors = total_bytes // SECT
    if total_sectors <= START_LBA + 1024:
        print("镜像太小")
        sys.exit(1)

    mbr, part_sectors = build_mbr(total_sectors)
    fatsecs = fat_size(part_sectors)
    tail, clusters = build_tail(part_sectors, fatsecs)

    with open(prefix + '.mbr', 'wb') as f:
        f.write(mbr)
    with open(prefix + '.tail', 'wb') as f:
        f.write(tail)

    print("总扇区     : %d" % total_sectors)
    print("分区起始   : LBA %d (偏移 %d)" % (START_LBA, START_LBA * SECT))
    print("分区扇区数 : %d (%.2f GB)" % (part_sectors, part_sectors * SECT / 1024 ** 3))
    print("FATSz32    : %d 扇区" % fatsecs)
    print("簇数       : %d" % clusters)
    print("tail 大小  : %d 字节 (%d 扇区)" % (len(tail), len(tail) // SECT))


if __name__ == '__main__':
    main()
