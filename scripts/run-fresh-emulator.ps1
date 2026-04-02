param(
    [string]$AvdName = "Medium_Phone",
    [string]$AppId = "com.omrreader",
    [switch]$SkipBuild,
    [switch]$SkipUninstall,
    [switch]$WipeData
)

$ErrorActionPreference = "Stop"

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "=== $Message ===" -ForegroundColor Cyan
}

function Invoke-Checked {
    param(
        [string]$Label,
        [scriptblock]$Command
    )

    Write-Host "-> $Label"
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Label failed with exit code $LASTEXITCODE"
    }
}

function Get-EmulatorSerials {
    $lines = & adb devices
    if ($LASTEXITCODE -ne 0) {
        throw "adb devices failed with exit code $LASTEXITCODE"
    }

    $serials = @()
    foreach ($line in $lines) {
        if ($line -match "^(emulator-[0-9]+)\s+(device|offline|unauthorized)$") {
            $serials += $matches[1]
        }
    }

    return $serials | Select-Object -Unique
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $repoRoot

Write-Step "Checking required tools"
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb command not found. Install Android platform-tools and add to PATH."
}
if (-not (Get-Command emulator -ErrorAction SilentlyContinue)) {
    throw "emulator command not found. Install Android emulator and add to PATH."
}

$avdList = & emulator -list-avds
if ($LASTEXITCODE -ne 0) {
    throw "emulator -list-avds failed with exit code $LASTEXITCODE"
}
if (-not ($avdList -contains $AvdName)) {
    throw "AVD '$AvdName' not found. Available: $($avdList -join ', ')"
}

Write-Step "Stopping stale emulator instances"
$runningSerials = Get-EmulatorSerials
foreach ($serial in $runningSerials) {
    Write-Host "-> Requesting shutdown for $serial"
    & adb -s $serial emu kill | Out-Host
}

Get-Process -Name emulator -ErrorAction SilentlyContinue | Stop-Process -Force
Get-Process -Name qemu-system-x86_64 -ErrorAction SilentlyContinue | Stop-Process -Force

Invoke-Checked "adb kill-server" { adb kill-server }
Invoke-Checked "adb start-server" { adb start-server }

Write-Step "Starting emulator"
$emuArgs = @(
    "-avd", $AvdName,
    "-no-snapshot-load",
    "-no-boot-anim",
    "-gpu", "swiftshader_indirect",
    "-netdelay", "none",
    "-netspeed", "full"
)
if ($WipeData) {
    $emuArgs += "-wipe-data"
}

Start-Process -FilePath emulator -ArgumentList $emuArgs | Out-Null

Write-Step "Waiting for emulator serial"
$serial = $null
$serialDeadline = (Get-Date).AddMinutes(2)
while ((Get-Date) -lt $serialDeadline -and -not $serial) {
    $serial = Get-EmulatorSerials | Select-Object -First 1
    if (-not $serial) {
        Write-Host "Waiting for emulator to appear in adb..."
        Start-Sleep -Seconds 2
    }
}

if (-not $serial) {
    throw "Emulator did not appear in adb within timeout."
}

Write-Host "Using emulator serial: $serial"

Write-Step "Waiting for adb device state"
$deviceReady = $false
$deviceDeadline = (Get-Date).AddMinutes(2)
while ((Get-Date) -lt $deviceDeadline) {
    $stateOutput = ""
    try {
        $stateOutput = ((& adb -s $serial get-state 2>$null) -join "").Trim()
    } catch {
        $stateOutput = ""
    }

    $state = if ([string]::IsNullOrWhiteSpace($stateOutput)) { "offline" } else { $stateOutput }
    if ($state -eq "device") {
        $deviceReady = $true
        break
    }

    Write-Host "ADB state: $state"
    Start-Sleep -Seconds 2
}

if (-not $deviceReady) {
    throw "Emulator adb state did not become 'device' in time."
}

Write-Step "Waiting for Android boot completion"
$bootReady = $false
$bootDeadline = (Get-Date).AddMinutes(5)
while ((Get-Date) -lt $bootDeadline) {
    $bootValue = (& adb -s $serial shell getprop sys.boot_completed 2>$null).Trim()
    if ($bootValue -eq "1") {
        $bootReady = $true
        break
    }

    Write-Host "Booting..."
    Start-Sleep -Seconds 2
}

if (-not $bootReady) {
    throw "Emulator boot did not complete in time."
}

& adb -s $serial shell input keyevent 82 | Out-Null

if (-not $SkipBuild) {
    Write-Step "Building app (clean + assembleDebug)"
    Invoke-Checked "gradlew :app:clean :app:assembleDebug" {
        .\gradlew.bat --no-daemon :app:clean :app:assembleDebug
    }
} else {
    Write-Step "Skipping build as requested"
}

$apkPath = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apkPath)) {
    throw "APK not found at $apkPath"
}

Write-Step "Installing app"
if (-not $SkipUninstall) {
    Write-Host "-> adb -s $serial uninstall $AppId (failure is ignored if not installed)"
    & adb -s $serial uninstall $AppId | Out-Host
}

Invoke-Checked "adb -s $serial install -r -d" {
    adb -s $serial install -r -d "$apkPath"
}

Write-Step "Launching app"
Invoke-Checked "adb -s $serial shell am start" {
    adb -s $serial shell am start -n "$AppId/.MainActivity"
}

Write-Step "Done"
Write-Host "Emulator started, fresh app installed and launched."
Write-Host "If you want logs: adb -s $serial logcat | findstr /i \"com.omrreader FATAL EXCEPTION\""
