# PhoneStick mount-and-verify helper for Windows.
#
#   .\mount-and-verify.ps1 -Image qqq.img
#   .\mount-and-verify.ps1 -Image qqq.img -NoMount      # only verify, do not touch LUN
#   .\mount-and-verify.ps1 -Unmount                     # detach and restore usb config
#
# Output is ASCII-only so it survives any console code page.
# Requires: adb on PATH, phone connected, Magisk root granted, phone unlocked.

[CmdletBinding()]
param(
    [string]$Image = "",
    [switch]$NoMount,
    [switch]$Unmount,
    [int]$TimeoutSec = 40
)

$ErrorActionPreference = "Stop"
$DevLun   = "/config/usb_gadget/g1/functions/mass_storage.0/lun.0"
$DevImg   = "/data/media/0/PhoneStick"
$DevTmp   = "/data/local/tmp"
$RemoteSh = "$DevTmp/ps_verify.sh"
$RemoteLog = "$DevTmp/ps_verify.log"

function Say($m)  { Write-Host $m }
function Ok($m)   { Write-Host "  [ OK ] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  [WARN] $m" -ForegroundColor Yellow }
function Bad($m)  { Write-Host "  [FAIL] $m" -ForegroundColor Red }
function Head($m) { Write-Host ""; Write-Host "== $m ==" -ForegroundColor Cyan }

# adb shell arguments are joined with spaces before being handed to the device
# shell, so anything containing redirection must live inside a pushed script.
# Never build "su -c 'echo x > /f'" inline: the redirect would be evaluated by
# the OUTER device shell, before su even runs.
function Dev($cmd) {
    $out = & adb shell su -c $cmd 2>&1
    return ($out | Out-String).Trim()
}

function Wait-Adb([int]$sec) {
    $deadline = (Get-Date).AddSeconds($sec)
    while ((Get-Date) -lt $deadline) {
        $null = & adb devices 2>&1 | Select-String -Pattern "\sdevice$"
        if ($LASTEXITCODE -eq 0) {
            $d = (& adb devices 2>&1) -join "`n"
            if ($d -match "\sdevice") { return $true }
        }
        Start-Sleep -Milliseconds 700
    }
    return $false
}

function Get-UsbDisks {
    try {
        return @(Get-Disk | Where-Object { $_.BusType -eq 'USB' } |
                 Select-Object Number, FriendlyName, Size, PartitionStyle)
    } catch {
        try {
            return @(Get-WmiObject Win32_DiskDrive |
                     Where-Object { $_.InterfaceType -eq 'USB' } |
                     ForEach-Object {
                         [pscustomobject]@{
                             Number = $_.Index
                             FriendlyName = $_.Model
                             Size = [int64]$_.Size
                             PartitionStyle = ""
                         }
                     })
        } catch { return @() }
    }
}

Say "PhoneStick mount & verify"
Say "---------------------------------------------"

# ------------------------------------------------------------------ device
Head "DEVICE"
$devList = (& adb devices 2>&1) -join "`n"
if ($devList -notmatch "\sdevice") {
    Bad "no adb device. check cable / usb port, then run 'adb devices'"
    exit 1
}
Ok "adb device present"

$who = Dev "id"
if ($who -notmatch "uid=0") {
    Bad "root not available: $who"
    Say "         grant Root to the shell entry in Magisk -> Superuser"
    exit 1
}
Ok "root shell ok"

# ------------------------------------------------------------------ unmount path
if ($Unmount) {
    Head "UNMOUNT"
    $script = @'
#!/system/bin/sh
G=/config/usb_gadget/g1
echo "" > "$G/UDC" 2>/dev/null
sleep 1
echo "" > "$G/functions/mass_storage.0/lun.0/file" 2>/dev/null
sleep 1
UDC=$(ls /sys/class/udc/ 2>/dev/null | head -n1)
[ -z "$UDC" ] && UDC=$(getprop sys.usb.controller)
[ -n "$UDC" ] && echo "$UDC" > "$G/UDC" 2>/dev/null
setprop sys.usb.config adb
echo "DONE"
'@
    $tmp = Join-Path $env:TEMP "ps_unmount.sh"
    [IO.File]::WriteAllText($tmp, ($script -replace "`r`n", "`n"), (New-Object Text.UTF8Encoding $false))
    & adb push $tmp $RemoteSh | Out-Null
    Start-Sleep -Milliseconds 300
    $null = & adb shell su -c "nohup sh $RemoteSh > $RemoteLog 2>&1 &" 2>&1
    Start-Sleep -Seconds 4
    $null = Wait-Adb 15
    Ok "LUN cleared, usb config restored to adb"
    Say "         host should lose the disk within a few seconds"
    exit 0
}

# ------------------------------------------------------------------ resolve image
Head "IMAGE"
if ([string]::IsNullOrWhiteSpace($Image)) {
    $listing = Dev "ls -1 $DevImg"
    Warn "no -Image given. images on device:"
    ($listing -split "`n") | ForEach-Object { if ($_.Trim()) { Say "         $_" } }
    Say ""
    Say "usage: .\mount-and-verify.ps1 -Image <name>"
    exit 1
}

$name = Split-Path $Image -Leaf
$raw  = "$DevImg/$name"

$exists = Dev "if [ -f '$raw' ]; then echo EXISTS; stat -c %s '$raw'; else echo MISSING; fi"
if ($exists -notmatch "EXISTS") {
    Bad "image not found on device: $raw"
    exit 1
}
$sizeLine = ($exists -split "`n" | Where-Object { $_ -match '^\d+$' } | Select-Object -First 1)
$sizeBytes = 0
if ($sizeLine) { $sizeBytes = [int64]$sizeLine }
if ($sizeBytes -le 0) {
    Bad "image is empty ($sizeBytes bytes): $raw"
    exit 1
}
Ok ("image found: {0} ({1:N1} MB)" -f $name, ($sizeBytes / 1MB))

# ------------------------------------------------------------------ baseline
Head "HOST BASELINE"
$before = Get-UsbDisks
$beforeNums = @($before | ForEach-Object { $_.Number })
Say ("  usb disks currently attached: {0}" -f $before.Count)
$before | ForEach-Object { Say ("    disk {0}  {1}  {2:N0} GB" -f $_.Number, $_.FriendlyName, ($_.Size / 1GB)) }

# ------------------------------------------------------------------ mount
if (-not $NoMount) {
    Head "MOUNT"
    # Strategy 1 mirrors what the app does: let the system usb init rebuild the
    # gadget so that a mass_storage function link gets created inside the active
    # config. A plain "mkdir configs/b.1/mass_storage.0" returns EPERM on
    # Android 13+ because that directory belongs to system:usb and Magisk is not
    # in the usb group -- with the denial swallowed by dontaudit, so dmesg stays
    # silent. That is why this looks like an unexplainable failure.
    $script = @"
#!/system/bin/sh
G=/config/usb_gadget/g1
L=$DevLun
IMG='$raw'

CFG=`$(getprop sys.usb.config)
echo "orig_config=`$CFG"
if [ -z "`$CFG" ]; then CFG=adb; fi
case "`$CFG" in
  *mass_storage*) echo "config already has mass_storage" ;;
  *) setprop sys.usb.config "mass_storage,`$CFG" ;;
esac

# init rebuilds the whole gadget here, adb will drop for a few seconds
sleep 4

echo "new_config=`$(getprop sys.usb.config)"
echo "links:"
for f in `$G/configs/*/function*; do
  [ -e "`$f" ] || continue
  echo "  `$(basename `$f) -> `$(readlink `$f 2>/dev/null)"
done

# wait for the lun node to show up (init creates it asynchronously)
i=0
while [ `$i -lt 30 ]; do
  if [ -f "`$L/file" ]; then break; fi
  sleep 0.5
  i=`$((i+1))
done

if [ ! -f "`$L/file" ]; then
  echo "RESULT=NO_LUN"
  echo "DONE"
  exit 0
fi

echo n > "`$L/ro"    2>/dev/null
echo n > "`$L/cdrom" 2>/dev/null
echo 1 > "`$L/nofua" 2>/dev/null
echo "`$IMG" > "`$L/file" 2>/dev/null

BACK=`$(cat "`$L/file" 2>/dev/null)
echo "lun_file=`$BACK"
if [ -n "`$BACK" ]; then echo "RESULT=OK"; else echo "RESULT=LUN_WRITE_FAILED"; fi
echo "DONE"
"@

    $tmp = Join-Path $env:TEMP "ps_verify.sh"
    [IO.File]::WriteAllText($tmp, ($script -replace "`r`n", "`n"), (New-Object Text.UTF8Encoding $false))
    & adb push $tmp $RemoteSh | Out-Null
    Start-Sleep -Milliseconds 300

    $null = & adb shell su -c "rm -f $RemoteLog" 2>&1
    $null = & adb shell su -c "nohup sh $RemoteSh > $RemoteLog 2>&1 &" 2>&1
    Say "  mount script launched (adb will drop for a few seconds, expected)"
    Start-Sleep -Seconds 3
    $null = Wait-Adb 20

    $log = ""
    for ($i = 0; $i -lt 20; $i++) {
        $log = Dev "cat $RemoteLog 2>/dev/null"
        if ($log -match "DONE") { break }
        Start-Sleep -Milliseconds 800
    }
    ($log -split "`n") | ForEach-Object { if ($_.Trim()) { Say "    $_" } }

    if ($log -match "RESULT=NO_LUN") {
        Bad "lun node never appeared - init does not support mass_storage here"
        Say "         try manually: adb shell su -c 'setprop sys.usb.config mass_storage,adb'"
        exit 1
    }
    if ($log -match "RESULT=LUN_WRITE_FAILED") {
        Bad "kernel rejected the image path written into lun.0/file"
        exit 1
    }
    if ($log -notmatch "RESULT=OK") {
        Bad "could not confirm mount result, raw log above"
        exit 1
    }
    Ok "LUN bound to $raw"
}

# ------------------------------------------------------------------ wait for host disk
Head "WAIT FOR HOST DISK"
Say ("  polling up to {0}s for a new usb disk..." -f $TimeoutSec)
$newDisk = $null
$deadline = (Get-Date).AddSeconds($TimeoutSec)
while ((Get-Date) -lt $deadline) {
    $now = Get-UsbDisks
    $cand = $now | Where-Object { $beforeNums -notcontains $_.Number }
    if ($cand) {
        $real = $cand | Where-Object { $_.Size -gt 0 }
        if ($real) { $newDisk = $real | Select-Object -First 1; break }
    }
    Start-Sleep -Milliseconds 800
}

if (-not $newDisk) {
    Bad "host never saw a disk within $TimeoutSec seconds"
    Say "         run the device doctor to find out why:"
    Say "         adb push .\device-doctor.sh /data/local/tmp/"
    Say "         adb shell su -c `"sh /data/local/tmp/device-doctor.sh`""
    exit 1
}

Ok ("disk {0} appeared" -f $newDisk.Number)
Say ("  model   : {0}" -f $newDisk.FriendlyName)
Say ("  size    : {0:N0} bytes ({1:N2} GB)" -f $newDisk.Size, ($newDisk.Size / 1GB))
Say ("  part tbl: {0}" -f $newDisk.PartitionStyle)

# ------------------------------------------------------------------ verdict
Head "VERDICT"
$expectedGB = $sizeBytes / 1GB
$actualGB   = [double]$newDisk.Size / 1GB
$delta = [Math]::Abs($actualGB - $expectedGB)
if ($delta -lt 0.05) {
    Ok ("capacity matches the image ({0:N2} GB)" -f $actualGB)
} else {
    Warn ("capacity mismatch: image {0:N2} GB vs host {1:N2} GB" -f $expectedGB, $actualGB)
    Say "         a value of 0 means the host enumerated before the LUN was ready"
}

if ($newDisk.PartitionStyle -eq 'RAW' -or $newDisk.PartitionStyle -eq '') {
    Warn "host sees no partition table (RAW)"
    Say "         expected for a freshly created image before formatting"
    Say "         the app writes a dynamic MBR, so a formatted image should show GPT/MBR"
} else {
    Ok ("partition table present: {0}" -f $newDisk.PartitionStyle)
}

try {
    $parts = Get-Partition -DiskNumber $newDisk.Number -ErrorAction Stop
    Say ""
    Say "  partitions:"
    $parts | ForEach-Object {
        Say ("    part {0}  {1:N0} MB  {2}" -f $_.PartitionNumber, ($_.Size / 1MB), $_.Type)
        if ($_.DriveLetter) {
            Say ("      drive letter {0}:" -f $_.DriveLetter)
        }
    }
    $letters = @($parts | Where-Object { $_.DriveLetter } | ForEach-Object { $_.DriveLetter })
    if ($letters.Count -gt 0) {
        Say ""
        Ok ("ready to test: {0}:\test.txt" -f $letters[0])
        Say ("         'hello' | Out-File {0}:\test.txt ; type {0}:\test.txt" -f $letters[0])
    }
} catch {
    Say "  (could not enumerate partitions)"
}

Head "DONE"
Say "  to detach:  .\mount-and-verify.ps1 -Unmount"
