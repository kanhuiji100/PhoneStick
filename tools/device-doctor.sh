#!/system/bin/sh
# PhoneStick device doctor - run on the PHONE as root.
#
#   adb push device-doctor.sh /data/local/tmp/
#   adb shell su -c "sh /data/local/tmp/device-doctor.sh"
#
# Output is intentionally ASCII-only so it stays readable in a Windows console.
# Exit code: 0 = all checks passed, 1 = at least one problem found.

RC=0
IMG_DIR=/data/media/0/PhoneStick
G=/config/usb_gadget/g1
LUN=

say()  { echo "$*"; }
head_() { echo ""; echo "== $* =="; }
ok()   { echo "  [ OK ] $*"; }
warn() { echo "  [WARN] $*"; }
bad()  { echo "  [FAIL] $*"; RC=1; }

say "PhoneStick device doctor"
say "---------------------------------------------"

# ---------------------------------------------------------------- root
head_ "PRIVILEGE"
UID_NOW=$(id -u 2>/dev/null)
if [ "$UID_NOW" = "0" ]; then
    ok "running as root (uid 0)"
else
    bad "not root (uid=$UID_NOW) - run via: adb shell su -c \"sh ...\""
fi

# ---------------------------------------------------------------- configfs
head_ "CONFIGFS"
CFS=""
MNT_CFS=$(mount -t configfs 2>/dev/null | head -n1 | awk '{print $3}')
if [ -n "$MNT_CFS" ]; then
    CFS="$MNT_CFS"
    ok "configfs mounted at $CFS"
else
    for p in /sys/kernel/config /config; do
        if [ -d "$p/usb_gadget" ]; then CFS="$p"; break; fi
    done
    if [ -n "$CFS" ]; then
        ok "configfs found at $CFS (not in mount table)"
    else
        bad "configfs not found"
    fi
fi

if [ -n "$CFS" ]; then
    GDIR=""
    for p in "$CFS"/usb_gadget/*/; do
        if [ -d "$p" ]; then GDIR="$p"; break; fi
    done
    [ -z "$GDIR" ] && bad "no gadget directory under $CFS/usb_gadget"
fi
[ -n "$GDIR" ] && G="$GDIR" && say "  gadget dir: $G"

# ---------------------------------------------------------------- UDC
head_ "UDC"
UDC_NOW=$(cat "$G/UDC" 2>/dev/null)
UDC_SYS=$(ls /sys/class/udc/ 2>/dev/null | head -n1)
say "  gadget UDC : [${UDC_NOW}]"
say "  sysfs UDC  : [${UDC_SYS}]"
if [ -n "$UDC_NOW" ]; then
    ok "gadget is bound to a controller"
else
    warn "gadget not bound - host will not enumerate anything"
fi

# ---------------------------------------------------------------- usb config
head_ "USB CONFIG"
CFG=$(getprop sys.usb.config)
STATE=$(getprop sys.usb.state)
say "  sys.usb.config = [$CFG]"
say "  sys.usb.state  = [$STATE]"
case "$CFG" in
    *mass_storage*) ok "sys.usb.config contains mass_storage" ;;
    *) warn "sys.usb.config has no mass_storage -> host sees MTP/PTP, not a disk" ;;
esac

# ---------------------------------------------------------------- config links
head_ "CONFIG LINKS  ($G/configs/*)"
CFG_DIR=$(ls -d "$G"/configs/*/ 2>/dev/null | head -n1)
LINKED=0
if [ -n "$CFG_DIR" ]; then
    say "  config: $CFG_DIR"
    for f in "$CFG_DIR"*/; do
        [ -d "$f" ] || continue
        n=$(basename "$f")
        say "    function link: $n"
        case "$n" in
            *mass_storage*|*mass-storage*) LINKED=1 ;;
        esac
    done
    for f in "$CFG_DIR"function*; do
        [ -e "$f" ] || continue
        n=$(basename "$f")
        tgt=$(readlink "$f" 2>/dev/null)
        say "    $n -> $tgt"
        case "$tgt" in
            *mass_storage*) LINKED=1 ;;
        esac
    done
else
    bad "$G/configs is empty"
fi

if [ "$LINKED" = "1" ]; then
    ok "mass_storage is linked into the active config"
else
    bad "mass_storage NOT linked - host will not see a disk"
    say "         fix: su -c 'setprop sys.usb.config mass_storage,adb'"
    say "         this is the #1 known issue, see section 10 of the report"
fi

# ---------------------------------------------------------------- LUN
head_ "LUN"
for l in "$G"/functions/mass_storage.*/lun.* "$CFS"/usb_gadget/*/functions/mass_storage.*/lun.*; do
    [ -f "$l/file" ] || continue
    LUN="$l"
    break
done

if [ -z "$LUN" ]; then
    bad "no mass_storage LUN found (function not created)"
else
    say "  lun path : $LUN"
    LFILE=$(cat "$LUN/file" 2>/dev/null)
    LRO=$(cat "$LUN/ro" 2>/dev/null)
    LCD=$(cat "$LUN/cdrom" 2>/dev/null)
    LNF=$(cat "$LUN/nofua" 2>/dev/null)
    say "  file     = [$LFILE]"
    say "  ro=$LRO cdrom=$LCD nofua=$LNF"
    if [ -z "$LFILE" ]; then
        warn "LUN file is empty - nothing is exported right now"
    elif [ -f "$LFILE" ]; then
        SZ=$(stat -c %s "$LFILE" 2>/dev/null)
        ok "backing file exists, size=$SZ bytes"
        [ "$LNF" = "1" ] && ok "nofua=1" || warn "nofua!=1 (may cause host fsync I/O errors)"
    else
        bad "LUN points to a NON-EXISTENT path: $LFILE"
        say "         leftover dirty path from an old version?"
    fi
fi

# ---------------------------------------------------------------- images
head_ "IMAGES  ($IMG_DIR)"
if [ -d "$IMG_DIR" ]; then
    FOUND=0
    for f in "$IMG_DIR"/*.img "$IMG_DIR"/*.iso "$IMG_DIR"/*.bin; do
        [ -f "$f" ] || continue
        FOUND=1
        SZ=$(stat -c %s "$f" 2>/dev/null)
        MB=$(expr "$SZ" / 1048576 2>/dev/null)
        LB=$(ls -Z "$f" 2>/dev/null | awk '{print $1}')
        say "  $(basename "$f")  ${MB}MB  ($SZ)  $LB"
        case "$LB" in
            *media_rw_data_file*) : ;;
            *) warn "    label is not media_rw_data_file - gadget write may be denied" ;;
        esac
    done
    [ "$FOUND" = "0" ] && warn "directory exists but contains no image files"
else
    bad "$IMG_DIR does not exist"
    say "         fix: mkdir -p $IMG_DIR && chown 1023:1023 $IMG_DIR"
fi

# ---------------------------------------------------------------- kernel errors
head_ "KERNEL ERRORS (recent)"
ERRS=$(dmesg 2>/dev/null | grep -iE 'I/O error|Medium Error|extends beyond EOD|avc:.*denied.*file-storage' | tail -n 8)
if [ -n "$ERRS" ]; then
    echo "$ERRS" | sed 's/^/  /'
    bad "kernel reported errors above"
else
    ok "no I/O errors, no truncated partition, no file-storage avc denial"
fi

# ---------------------------------------------------------------- memory
head_ "MEMORY"
MEM_AVAIL=$(grep MemAvailable /proc/meminfo 2>/dev/null | awk '{print $2}')
if [ -n "$MEM_AVAIL" ]; then
    MB=$((MEM_AVAIL / 1024))
    say "  MemAvailable: ${MB} MB"
    if [ "$MB" -lt 500 ]; then
        warn "less than 500MB free - large dd/fallocate may OOM and drop adb"
    else
        ok "enough free memory"
    fi
fi

# ---------------------------------------------------------------- tools
head_ "TOOLS"
for t in newfs_msdos mkfs.exfat mkfs.ext4 mke2fs blkid losetup blockdev fallocate od dd stat sync; do
    if command -v "$t" >/dev/null 2>&1; then
        say "  ok      $t"
    else
        warn "  MISSING $t"
    fi
done
command -v mkfs.vfat >/dev/null 2>&1 && say "  ok      mkfs.vfat" || say "  note    mkfs.vfat absent (expected on stock Android)"

# ---------------------------------------------------------------- summary
echo ""
echo "---------------------------------------------"
if [ "$RC" = "0" ]; then
    echo "RESULT: all critical checks passed"
else
    echo "RESULT: problems found, see [FAIL] lines above"
fi
echo "---------------------------------------------"
exit $RC
