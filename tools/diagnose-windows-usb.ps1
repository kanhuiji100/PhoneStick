<#
.SYNOPSIS
  Verifies that the phone's virtual USB drive is visible on this Windows host,
  and explains exactly why it is not, when it is not.

.DESCRIPTION
  Read-only.  Checks, in order:
    1. adb sees the phone
    2. the phone has a non-empty LUN file and a bound UDC
    3. the gadget configuration links the mass_storage function
    4. Windows enumerated a USB mass-storage interface (class 08/06/50)
    5. that interface is bound to USBSTOR (and not to some OEM ADB/WinUSB INF)
    6. a USB disk / drive letter exists

  Exit code 0 = drive visible, 1 = not visible, 2 = cannot determine.

.EXAMPLE
  .\diagnose-windows-usb.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'
function Step($n, $t) { Write-Host "`n[$n] $t" -ForegroundColor Cyan }
function Ok($t)   { Write-Host "    OK   $t" -ForegroundColor Green }
function Bad($t)  { Write-Host "    FAIL $t" -ForegroundColor Red }
function Note($t) { Write-Host "    ..   $t" -ForegroundColor DarkGray }

$problems = @()

# 1 ---------------------------------------------------------------------------
Step 1 'adb / phone'
$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) {
    Bad 'adb not found in PATH'
    $problems += 'adb-missing'
} else {
    $devs = (& adb devices) 2>$null | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' }
    if ($devs) { Ok "adb device: $($devs -join ', ')" }
    else { Bad 'no adb device'; $problems += 'adb-no-device' }
}

function AdbRoot([string]$cmd) {
    (& adb shell "su -c '$cmd'") 2>$null
}

if (-not $problems) {
    # 2 -----------------------------------------------------------------------
    Step 2 'phone LUN / UDC'
    $lun = (AdbRoot 'cat /config/usb_gadget/g1/functions/mass_storage.0/lun.0/file') -join ''
    $udc = (AdbRoot 'cat /config/usb_gadget/g1/UDC') -join ''
    if ($lun.Trim()) { Ok "LUN file = $($lun.Trim())" } else { Bad 'LUN file is empty'; $problems += 'lun-empty' }
    if ($udc.Trim()) { Ok "UDC bound = $($udc.Trim())" } else { Bad 'UDC not bound'; $problems += 'udc-unbound' }

    # 3 -----------------------------------------------------------------------
    Step 3 'gadget configuration links'
    $links = (AdbRoot 'ls -l /config/usb_gadget/g1/configs/b.1/') -join "`n"
    if ($links -match 'mass_storage') { Ok 'mass_storage function is linked into configs/b.1' }
    else { Bad 'configs/b.1 has no mass_storage link'; $problems += 'no-gadget-link' }

    Step 3b 'usb ids'
    $idp = (AdbRoot 'od -An -tx1 /config/usb_gadget/g1/idProduct') -join ''
    if ($idp.Trim()) { Note "idProduct raw = $($idp.Trim())" }
}

# 4 ---------------------------------------------------------------------------
Step 4 'USB mass-storage interface on the host'
$ms = @(Get-CimInstance Win32_PnPEntity -Filter "PNPDeviceID LIKE 'USB%'" -ErrorAction SilentlyContinue | Where-Object {
    $c = (Get-PnpDeviceProperty -InstanceId $_.PNPDeviceID -KeyName 'DEVPKEY_Device_CompatibleIds' -ErrorAction SilentlyContinue).Data
    $c -contains 'USB\Class_08&SubClass_06&Prot_50'
})
if ($ms) {
    foreach ($d in $ms) {
        $svc  = (Get-PnpDeviceProperty -InstanceId $d.PNPDeviceID -KeyName 'DEVPKEY_Device_Service' -ErrorAction SilentlyContinue).Data
        $inf  = (Get-PnpDeviceProperty -InstanceId $d.PNPDeviceID -KeyName 'DEVPKEY_Device_DriverInfPath' -ErrorAction SilentlyContinue).Data
        $prov = (Get-PnpDeviceProperty -InstanceId $d.PNPDeviceID -KeyName 'DEVPKEY_Device_DriverProvider' -ErrorAction SilentlyContinue).Data
        if ($svc -eq 'USBSTOR') {
            Ok "$($d.PNPDeviceID) -> USBSTOR"
        } elseif ($svc -eq 'WinUSB' -or $d.Name -match 'ADB') {
            Bad "$($d.PNPDeviceID) -> service=$svc  inf=$inf [$prov]"
            Note 'Windows bound an ADB/WinUSB driver to the mass-storage interface.'
            Note 'Linux/macOS match this interface by class and work; Windows matches the'
            Note 'hardware ID first and that OEM INF wins over the inbox USBSTOR driver.'
            $problems += 'driver-hijack'
        } else {
            Note "$($d.PNPDeviceID) -> service=$svc (legit class driver, ignored)"
        }
    }
} else {
    Bad 'no mass-storage interface enumerated'
    $problems += 'no-ms-interface'
}

# 5 ---------------------------------------------------------------------------
Step 5 'disk / drive letter'
$disks = @(Get-CimInstance Win32_DiskDrive -ErrorAction SilentlyContinue | Where-Object { $_.PNPDeviceID -like 'USBSTOR\*' })
if ($disks) {
    Ok "$($disks.Count) USB disk(s)"
    $disks | ForEach-Object { Note ("{0}  {1} GB  {2}" -f $_.Model, [math]::Round($_.Size/1GB,2), $_.PNPDeviceID) }
    $vols = Get-CimInstance Win32_LogicalDisk -ErrorAction SilentlyContinue | Where-Object { $_.DriveType -eq 2 -and $_.DeviceID -ne 'C:' }
    if ($vols) { $vols | ForEach-Object { Ok ("drive {0} {1} {2}" -f $_.DeviceID, $_.VolumeName, $_.FileSystem) } }
    else { Note 'disk present but no volume mounted (unformatted image?)' }
} else {
    Bad 'no USBSTOR disk'
    $problems += 'no-disk'
}

# ---------------------------------------------------------------------------
Write-Host ''
if ($problems -contains 'driver-hijack') {
    Write-Host 'DIAGNOSIS: the phone is fine; Windows is binding an OEM ADB driver to the' -ForegroundColor Yellow
    Write-Host 'mass-storage interface.  Run tools\fix-windows-usb-driver.ps1 (as admin).' -ForegroundColor Yellow
} elseif ($problems.Count -eq 0) {
    Write-Host 'RESULT: drive is visible.' -ForegroundColor Green
} else {
    Write-Host "RESULT: not visible. Problems: $($problems -join ', ')" -ForegroundColor Red
}
if ($problems.Count -eq 0) { exit 0 } else { exit 1 }
