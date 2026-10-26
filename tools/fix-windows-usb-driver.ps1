<#
.SYNOPSIS
  Fix the Windows driver collision that hides PhoneStick's virtual USB drive.

.DESCRIPTION
  Symptom this script fixes:
    The phone composes `mass_storage,adb`, so the USB mass-storage interface sits at
    MI_01 (class 08/06/50) with VID:PID 05C6:9015.  Some third-party "Android ADB"
    driver packages (OPLUS/OPPO, vivo, ...) claim
        USB\VID_05C6&PID_9015&MI_01
    by *hardware ID*, which outranks the class-based match of the inbox USBSTOR
    driver.  Windows therefore binds WinUSB to the mass-storage interface and never
    creates a disk: \\.\USBSTOR never appears, no drive letter, nothing in 磁盘管理.
    Linux/macOS match by interface class, which is why the same phone works there.

  What this script does:
    1. Finds every present USB interface that is a mass-storage interface
       (compatible ID USB\Class_08&SubClass_06&Prot_50) but is bound to a non-USBSTOR
       driver (e.g. WinUSB via an OEM ADB INF).
    2. Exports the offending driver package to a backup folder.
    3. Removes that binding (pnputil /delete-driver ... /uninstall /force) so Windows
       falls back to the inbox USBSTOR driver.
    4. Rescans and reports the disk that appears.

  Nothing is deleted permanently: the driver package is exported first and
  `-Restore` puts it back.

.PARAMETER Restore
  Re-install the previously exported driver package and rescan.

.PARAMETER BackupDir
  Where to keep the exported driver package.  Defaults to .\driver-backup next to
  this script.

.EXAMPLE
  # 以管理员身份运行
  .\fix-windows-usb-driver.ps1

.EXAMPLE
  .\fix-windows-usb-driver.ps1 -Restore
#>
[CmdletBinding()]
param(
    [switch]$Restore,
    [string]$BackupDir = ''
)

# $PSScriptRoot is not reliable inside a param() default on Windows PowerShell 5.1.
if (-not $BackupDir) {
    $scriptDir = Split-Path -Parent $PSCommandPath
    if (-not $scriptDir) { $scriptDir = (Get-Location).Path }
    $BackupDir = Join-Path $scriptDir 'driver-backup'
}

$ErrorActionPreference = 'Stop'

function Write-Step([string]$msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "    $msg" -ForegroundColor Green }
function Write-Warn2([string]$msg){ Write-Host "    $msg" -ForegroundColor Yellow }

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    return ([Security.Principal.WindowsPrincipal]$id).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not (Test-Admin)) {
    $argHint = ''
    if ($Restore) { $argHint = ' -Restore' }
    Write-Host "This script needs administrator rights." -ForegroundColor Red
    Write-Host "Right-click PowerShell -> 以管理员身份运行, then:"
    Write-Host "    $PSCommandPath$argHint"
    exit 2
}

# ---------------------------------------------------------------- helpers ----
function Get-Prop($instanceId, $key) {
    (Get-PnpDeviceProperty -InstanceId $instanceId -KeyName $key -ErrorAction SilentlyContinue).Data
}

function Get-MassStorageHijacks {
    <#
      Present USB interfaces whose interface class is mass storage (08/06/50) but
      whose current driver is not USBSTOR.  Returns PSCustomObjects.
    #>
    $result = @()
    # NOTE: these interfaces are *not* in the "USB" setup class once an OEM ADB INF
    # has claimed them (that INF also overrides the class, e.g. AndroidUsbDeviceClass),
    # so enumerate by device instance path instead of by class.
    $devices = Get-CimInstance Win32_PnPEntity -Filter "PNPDeviceID LIKE 'USB%'" -ErrorAction SilentlyContinue
    foreach ($d in $devices) {
        $compat = Get-Prop $d.PNPDeviceID 'DEVPKEY_Device_CompatibleIds'
        if (-not ($compat -contains 'USB\Class_08&SubClass_06&Prot_50')) { continue }

        $service = Get-Prop $d.PNPDeviceID 'DEVPKEY_Device_Service'
        if ($service -eq 'USBSTOR') { continue }          # already correct
        # Legitimate mass-storage devices (card readers, cameras) use their own
        # class driver.  The pathology we are after is specifically an *ADB/WinUSB*
        # driver sitting on a SCSI mass-storage interface.
        if (-not ($service -eq 'WinUSB' -or $d.Name -match 'ADB')) { continue }

        $result += [pscustomobject]@{
            InstanceId = $d.PNPDeviceID
            Name       = $d.Name
            Service    = $service
            InfPath    = Get-Prop $d.PNPDeviceID 'DEVPKEY_Device_DriverInfPath'
            Provider   = Get-Prop $d.PNPDeviceID 'DEVPKEY_Device_DriverProvider'
        }
    }
    return $result
}

function Get-UsbMassStorageDisks {
    Get-CimInstance Win32_DiskDrive -ErrorAction SilentlyContinue |
        Where-Object { $_.InterfaceType -eq 'USB' -or $_.PNPDeviceID -like 'USBSTOR\*' } |
        ForEach-Object {
            [pscustomobject]@{
                Model  = $_.Model
                SizeGB = [math]::Round($_.Size / 1GB, 2)
                Device = $_.DeviceID
                PnpId  = $_.PNPDeviceID
            }
        }
}

# ------------------------------------------------------------------ restore --
if ($Restore) {
    Write-Step "Restoring driver package(s) from $BackupDir"
    if (-not (Test-Path $BackupDir)) { throw "BackupDir not found: $BackupDir" }
    $infs = Get-ChildItem $BackupDir -Filter *.inf -File
    if (-not $infs) { throw "No .inf found in $BackupDir" }
    foreach ($inf in $infs) {
        Write-Host "    pnputil /add-driver $($inf.FullName) /install"
        & pnputil /add-driver $inf.FullName /install
    }
    & pnputil /scan-devices
    Write-Ok "Restore done. If the drive disappears again, that is expected: the OEM INF is back."
    exit 0
}

# --------------------------------------------------------------- diagnosis ---
Write-Step "Scanning for mass-storage interfaces bound to a non-USBSTOR driver"
$hijacks = @(Get-MassStorageHijacks)
if ($hijacks.Count -eq 0) {
    Write-Ok "No hijacked interface found.  If the drive is still missing, check:"
    Write-Host "    - phone actually mounted (LUN file non-empty)"
    Write-Host "    - Get-Disk | Where-Object BusType -eq 'USB'"
    Write-Host "    - configs/b.1 has a mass_storage link on the phone"
    exit 0
}
foreach ($h in $hijacks) {
    Write-Host ""
    Write-Host "    Device  : $($h.Name)"
    Write-Host "    Instance: $($h.InstanceId)"
    Write-Host "    Service : $($h.Service)  (should be USBSTOR)"
    Write-Host "    Driver  : $($h.InfPath)  [$($h.Provider)]"
}

# ------------------------------------------------------------------ backup ---
Write-Step "Exporting offending driver package(s) to $BackupDir"
New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
$infs = $hijacks | Where-Object { $_.InfPath } | Select-Object -ExpandProperty InfPath -Unique
foreach ($inf in $infs) {
    $name = Split-Path $inf -Leaf
    & pnputil /export-driver $name $BackupDir | Out-Host
}

# -------------------------------------------------------------------- fix ----
Write-Step "Removing the hijacking driver binding"
foreach ($inf in $infs) {
    $name = Split-Path $inf -Leaf
    # /uninstall pulls it off the device as well, so the next ranking can pick USBSTOR.
    & pnputil /delete-driver $name /uninstall /force | Out-Host
}
& pnputil /scan-devices | Out-Host

Write-Step "Waiting for a USB disk to appear (up to 25 s)"
$found = $null
for ($i = 0; $i -lt 25; $i++) {
    Start-Sleep -Seconds 1
    $disks = @(Get-UsbMassStorageDisks)
    if ($disks.Count -gt 0) { $found = $disks; break }
}

if ($found) {
    Write-Ok "USB disk appeared:"
    $found | Format-Table -AutoSize | Out-Host
    $letters = Get-CimInstance Win32_LogicalDisk |
        Where-Object { $_.DriveType -eq 2 -or $_.DriveType -eq 3 } |
        Select-Object DeviceID, VolumeName, FileSystem, Size
    $letters | Format-Table -AutoSize | Out-Host
    Write-Host ""
    Write-Host "Driver is now fixed permanently: USBSTOR owns the mass-storage interface." -ForegroundColor Green
    Write-Host "To undo: $PSCommandPath -Restore" -ForegroundColor DarkGray
} else {
    Write-Warn2 "No USB disk yet.  The interface should now be bound to USBSTOR:"
    Get-CimInstance Win32_PnPEntity -Filter "PNPDeviceID LIKE 'USB%'" -ErrorAction SilentlyContinue |
        Where-Object { (Get-Prop $_.PNPDeviceID 'DEVPKEY_Device_CompatibleIds') -contains 'USB\Class_08&SubClass_06&Prot_50' } |
        Select-Object Name, PNPDeviceID, Service | Format-Table -AutoSize | Out-Host
    Write-Host "Try unplug/replug the phone, or check that the phone still has the image mounted." -ForegroundColor Yellow
    exit 1
}
