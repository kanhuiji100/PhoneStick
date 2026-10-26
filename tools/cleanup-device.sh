#!/system/bin/sh
# Remove leftover scratch files created during PhoneStick debugging.
# Runs on the PHONE as root.
#
# Touches ONLY /data/local/tmp (plus a stale /config/usb_gadget/__probe dir used
# by the SELinux write probe).  It never touches /data/media/0/PhoneStick,
# so your disk images are safe.
#
#   adb push cleanup-device.sh /data/local/tmp/
#   adb shell su -c "sh /data/local/tmp/cleanup-device.sh"
#
# Pass -n to preview without deleting.
#
# Deliberately KEPT: phonestick_drive.img.bak and phonestick_prefs_backup.xml
# (they are backup copies, not scratch files).  Delete them by hand if you are
# sure you no longer need them.

DRY=0
[ "$1" = "-n" ] || [ "$1" = "--dry-run" ] && DRY=1

TMP=/data/local/tmp

# Names this project created.  Globs are allowed.
PATTERNS="psfix.log psfix.sh ps_parts.mbr ps_parts.tail ps_verify.sh ps_verify.log \
ps_mount.sh ps_mount.log ps_unmount.sh chk.sh mnt.sh mnt.log link.sh link.log \
who.sh who.log probe_fmt.sh device-doctor.sh switch_lun.sh cleanup-device.sh \
cleanup.sh bind_appdata.sh runfix.sh ct.log e2e.log p.out curloop rebind.log \
_mbr.bin mbr.bin probe*.log ps_wintest* ps_swytest* ps_rebind* ps_checkback* \
ps_grepimg* pscheck trim_test* ps_sparse* mbr512.mbr e4t.sh fs_case.sh layout_case.sh e4.img p.img w.img mbr.tmp trim_mnt e4mnt m2"

echo "PhoneStick device cleanup"
echo "---------------------------------------------"
echo "target: $TMP"
[ "$DRY" = "1" ] && echo "mode  : DRY RUN (nothing will be deleted)"
[ "$DRY" = "0" ] && echo "mode  : DELETE"
echo ""

TOTAL=0
BYTES=0

for pat in $PATTERNS; do
    for f in $TMP/$pat; do
        [ -e "$f" ] || continue
        SZ=$(stat -c %s "$f" 2>/dev/null)
        [ -z "$SZ" ] && SZ=0
        if [ -d "$f" ]; then
            echo "  rmdir   $(basename $f)/  (directory)"
            [ "$DRY" = "0" ] && rm -rf "$f"
        else
            echo "  remove  $(basename $f)  (${SZ} bytes)"
            [ "$DRY" = "0" ] && rm -f "$f"
        fi
        TOTAL=$((TOTAL + 1))
        BYTES=$((BYTES + SZ))
    done
done

# empty probe dir left in configfs by SELinux tests
if [ -d /config/usb_gadget/__probe ]; then
    echo "  rmdir   /config/usb_gadget/__probe"
    [ "$DRY" = "0" ] && rmdir /config/usb_gadget/__probe 2>/dev/null
    TOTAL=$((TOTAL + 1))
fi

# apk staging directory used during manual install
if [ -d "$TMP/inst" ]; then
    N=$(ls -1 "$TMP/inst" 2>/dev/null | wc -l)
    echo "  remove  inst/  ($N files)"
    TOTAL=$((TOTAL + 1))
    [ "$DRY" = "0" ] && rm -rf "$TMP/inst"
fi

# loop mounts left behind by manual verification
for m in /mnt/psll /mnt/pstest /mnt/x /data/local/tmp/pscheck; do
    if mount 2>/dev/null | grep -q " $m "; then
        echo "  unmount $m"
        [ "$DRY" = "0" ] && umount "$m" 2>/dev/null
    fi
done

# stale loop devices backed by our images
for lo in $(losetup -a 2>/dev/null | grep "/data/media/0/PhoneStick/" | cut -d: -f1); do
    echo "  detach  $lo"
    [ "$DRY" = "0" ] && losetup -d "$lo" 2>/dev/null
    TOTAL=$((TOTAL + 1))
done

echo ""
echo "---------------------------------------------"
if [ "$TOTAL" = "0" ]; then
    echo "nothing to clean, already tidy"
else
    if [ "$DRY" = "1" ]; then
        echo "would remove $TOTAL item(s), ${BYTES} bytes"
        echo "run again without -n to apply"
    else
        echo "removed $TOTAL item(s), freed ${BYTES} bytes"
    fi
fi
echo "---------------------------------------------"

echo "backups kept on purpose:"
ls -l "$TMP/phonestick_drive.img.bak" "$TMP/phonestick_prefs_backup.xml" 2>/dev/null | sed 's/^/  /' || true
echo ""
echo "images left untouched:"
ls -1 /data/media/0/PhoneStick/ 2>/dev/null | sed 's/^/  /' || echo "  (no image directory)"
