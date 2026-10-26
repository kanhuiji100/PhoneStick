#!/system/bin/sh
# 把 LUN 切换到指定镜像。用法: switch_lun.sh <img路径>
IMG="$1"
L=/config/usb_gadget/g1/functions/mass_storage.0/lun.0
G=/config/usb_gadget/g1

[ -f "$IMG" ] || { echo "镜像不存在: $IMG"; exit 1; }

UDC=$(cat "$G/UDC" 2>/dev/null)
echo "原 UDC: $UDC"

# 解绑 UDC，让 LUN 不再 busy
echo "" > "$G/UDC" 2>/dev/null
sleep 1

# 清空再设新路径
echo "" > "$L/file" 2>/dev/null
sleep 1
echo "$IMG" > "$L/file"
echo 1 > "$L/nofua"
echo 0 > "$L/ro"
echo 0 > "$L/cdrom"

echo "file=$(cat "$L/file")"
echo "nofua=$(cat "$L/nofua")"

# 重新绑定
[ -n "$UDC" ] && echo "$UDC" > "$G/UDC"
echo "UDC=$(cat "$G/UDC")"
