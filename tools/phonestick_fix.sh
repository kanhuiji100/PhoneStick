#!/system/bin/sh
#
# PhoneStick 镜像分区修复脚本（手机端 root shell 执行）
#
# 用法:
#   sh phonestick_fix.sh <镜像路径>
#
# ============================================================
# 根因（实测确认，2026-10-26）
# ============================================================
#
# 1. 【真正的写入杀手】SELinux 拦截
#    镜像默认放在 /data/data/mingww64.phonestick/files/，标签是
#    app_data_file。而 USB gadget 的写线程是 comm="file-storage"，
#    域为 u:r:kernel:s0，策略不允许它写 app_data_file：
#
#      avc: denied { write } for comm="file-storage"
#        scontext=u:r:kernel:s0
#        tcontext=u:object_r:app_data_file:s0
#        tclass=file permissive=0
#
#    主机侧表现为：
#      Sense Key : Medium Error [current]
#      Add. Sense: Write error - auto reallocation failed
#      fdisk: /dev/sdb: fsync device failed: 输入/输出错误
#
#    注意读权限是放行的，所以"能认盘、能显示容量"，一写就炸。
#
#    更坑的是 /data/local/tmp（shell_data_file）反过来：
#    允许写、不允许读，换过去会变成能写不能读。
#
#    唯一合适的位置是 /data/media/0/ 下的 media_rw_data_file，
#    实测 kernel 域对它零拒绝。
#
# 2. App 内置格式化静默失败
#    安卓的 newfs_msdos 只接受块设备，直接传普通 img 会报
#    ioctl(BLKSSZGET) failed；而设备上没有 mkfs.vfat / mkfs.fat，
#    其余兜底候选全部 exit 127。7 条全败，但 createBlankImage()
#    仍回调 success=true —— 于是产出全零镜像却显示"成功"。
#
# 3. 分区表扇区数必须与镜像实际容量一致
#    【早期误判，已用 dmesg 推翻】
#    曾经认为"f2fs 上 dd 到 loopNp1 会静默丢数据，必须按字节偏移写"。
#    真相不是文件系统问题，而是分区项里的扇区数写死成了 2GB 对应的
#    4192256。镜像若不是这个尺寸，内核会直接：
#
#      loop47: p1 size 4192256 extends beyond EOD, truncated
#
#    把分区截断成 0 长度。此时 /dev/block/loopNp1 节点照样存在、
#    [ -b ] 为真、newfs_msdos 返回 0 还打印正常 BPB，但所有写入被丢弃。
#    对照实验：128/256/512MB 全丢，只有 2048MB 恰好匹配才成功。
#
#    正确做法是按实际容量动态计算：
#      PART = (镜像字节数 / 512) - 2048
#    phonestick_parts.py 已经是这么做的；App 侧对应 ImageCreator.writeMbr()。
#
# 4. 校验读回值必须用 od -An -tx1 -v
#    od -An -c 会把 \0 输出成真 NUL 字节，被 $(...) 捕获即截断，
#    导致校验永远读到空，看起来像"没写进去"。
#
# ============================================================
# 修复流程
# ============================================================
#   第 1 步（本脚本）：把镜像搬到 media_rw_data_file 位置
#   第 2 步（本脚本）：写入标准 MBR + FAT32 分区头
#   第 3 步（主机端）：fdisk 正常分区，mkfs.vfat 格式化
#
# 参数:
#   $1  源镜像路径
#   $2  可选: 目标目录，默认 /data/media/0/PhoneStick

set -e

SRC="$1"
DSTDIR="${2:-/data/media/0/PhoneStick}"

MBR=/data/local/tmp/ps_parts.mbr
TAIL=/data/local/tmp/ps_parts.tail
START_LBA=2048
LUN=/config/usb_gadget/g1/functions/mass_storage.0/lun.0
G=/config/usb_gadget/g1

log() { echo "[phonestick] $*"; }

[ "$(id -u)" = "0" ] || { log "需要 root"; exit 1; }
[ -f "$SRC" ] || { log "镜像不存在: $SRC"; exit 1; }

# ---------- 1. 解绑 LUN，释放占用 ----------
if [ -f "$LUN/file" ]; then
  CUR=$(cat "$LUN/file" 2>/dev/null)
  if [ -n "$CUR" ]; then
    log "解绑 LUN (原: $CUR)"
    UDC=$(cat "$G/UDC" 2>/dev/null)
    echo "" > "$G/UDC" 2>/dev/null || true
    sleep 1
    echo "" > "$LUN/file" 2>/dev/null || true
    sleep 1
    if [ -n "$UDC" ]; then echo "$UDC" > "$G/UDC" 2>/dev/null || true; fi
  fi
fi

# ---------- 2. 搬到 media_rw_data_file ----------
# 这是最关键的一步：只有这个标签允许 kernel 域读写
mkdir -p "$DSTDIR"
DST="$DSTDIR/$(basename "$SRC")"

if [ "$SRC" != "$DST" ]; then
  log "复制到 $DST"
  cp "$SRC" "$DST"
  chown 1023:1023 "$DST" 2>/dev/null || true
  chmod 660 "$DST" 2>/dev/null || true
  rm -f "$SRC"
  log "已移除原位置文件"
fi

LABEL=$(ls -Z "$DST" 2>/dev/null | awk '{print $1}')
log "目标标签: $LABEL"
case "$LABEL" in
  *media_rw_data_file*) : ;;
  *) log "警告: 标签不是 media_rw_data_file，kernel 可能无法写入" ;;
esac

IOSIZE=$(du -b "$DST" | cut -f1 | tr -d ' ')
log "镜像大小: $IOSIZE 字节"

# ---------- 3. 写入 MBR + 分区头 ----------
if [ -f "$MBR" ] && [ -f "$TAIL" ]; then
  dd if="$MBR" of="$DST" bs=512 count=1 conv=notrunc 2>/dev/null
  dd if="$TAIL" of="$DST" bs=512 seek=$START_LBA conv=notrunc 2>/dev/null
  sync

  sig_mbr=$(dd if="$DST" bs=1 skip=510 count=2 2>/dev/null | od -An -tx1 | tr -d ' \n')
  sig_bs=$(dd if="$DST" bs=1 skip=1049086 count=2 2>/dev/null | od -An -tx1 | tr -d ' \n')
  log "MBR 签名: $sig_mbr   分区引导签名: $sig_bs"
  if [ "$sig_mbr" != "55aa" ] || [ "$sig_bs" != "55aa" ]; then
    log "校验失败"
    exit 1
  fi
else
  log "未找到 ps_parts.mbr / ps_parts.tail，跳过分区头写入"
  log "可先在电脑上运行: python3 phonestick_parts.py <字节数> /tmp/ps_parts"
fi

# ---------- 4. 确保 gadget 配置里有 mass_storage 链接 ----------
# configs/b.1/ 属主 system:usb、mode drwxrwx---，Magisk 域不在 usb 组，
# 直接 mkdir 会 EPERM，而且该拒绝被 dontaudit 吞掉，dmesg 里没有记录。
# 唯一可行的办法是让系统 usb init 重建整份 gadget。
CFG=$(getprop sys.usb.config)
case "$CFG" in
  *mass_storage*) log "配置已含 mass_storage: $CFG" ;;
  *)
    log "配置缺少 mass_storage，触发 init 重建 gadget（adb 会断几秒）"
    [ -z "$CFG" ] && CFG=adb
    setprop sys.usb.config "mass_storage,$CFG"
    sleep 4
    ;;
esac
log "当前配置: $(getprop sys.usb.config)"
for f in $G/configs/*/function*; do
  [ -e "$f" ] || continue
  log "  链接 $(basename "$f") -> $(readlink "$f" 2>/dev/null)"
done

# ---------- 5. 重新绑定并设置 nofua ----------
UDC=$(cat "$G/UDC" 2>/dev/null || getprop sys.usb.controller)
if [ -n "$UDC" ]; then
  echo "$DST" > "$LUN/file" 2>/dev/null || true
  echo 1 > "$LUN/nofua" 2>/dev/null || true
  echo 0 > "$LUN/ro" 2>/dev/null || true
  echo 0 > "$LUN/cdrom" 2>/dev/null || true
  echo "" > "$G/UDC" 2>/dev/null || true
  sleep 1
  echo "$UDC" > "$G/UDC" 2>/dev/null || true
  log "LUN 已绑定: $(cat "$LUN/file" 2>/dev/null)"
fi

log "完成。现在可以在电脑上执行 fdisk /dev/sdX 正常分区了。"
