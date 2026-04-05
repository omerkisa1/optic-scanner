param(
  [switch]$NoInstall,
  [switch]$UseExistingMetro,
  [int]$MetroPort = 8081,
  [string]$AvdName = '',
  [switch]$SkipEmulatorStart
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Resolve-Path (Join-Path $scriptDir '..')
Set-Location $projectRoot.Path

function Get-ListeningPids {
  param([int]$Port)

  try {
    $connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop
    if ($connections) {
      return $connections | Select-Object -ExpandProperty OwningProcess -Unique
    }
  } catch {
  }

  $rows = netstat -ano | Select-String ":$Port"
  if (-not $rows) {
    return @()
  }

  $pids = @()
  foreach ($row in $rows) {
    $line = $row.ToString().Trim()
    if ($line -match 'LISTENING\s+(\d+)$') {
      $pids += [int]$Matches[1]
    }
  }
  return $pids | Select-Object -Unique
}

function Get-ConnectedDeviceSerial {
  $lines = adb devices
  foreach ($line in $lines) {
    $trimmed = $line.Trim()
    if ($trimmed -match '^(\S+)\s+device$') {
      return $Matches[1]
    }
  }
  return $null
}

function Get-EmulatorExe {
  $candidates = @(
    "$env:LOCALAPPDATA\\Android\\Sdk\\emulator\\emulator.exe",
    "$env:ANDROID_HOME\\emulator\\emulator.exe",
    "$env:ANDROID_SDK_ROOT\\emulator\\emulator.exe"
  )

  foreach ($candidate in $candidates) {
    if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path $candidate)) {
      return $candidate
    }
  }

  return $null
}

function Resolve-AvdName {
  param([string]$PreferredAvd)

  if (-not [string]::IsNullOrWhiteSpace($PreferredAvd)) {
    return $PreferredAvd
  }

  $emuExe = Get-EmulatorExe
  if (-not $emuExe) {
    throw 'emulator.exe bulunamadi. Android SDK Emulator kurulumu gerekli.'
  }

  $avds = & $emuExe -list-avds
  $validAvds = @($avds | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
  if ($validAvds.Count -eq 0) {
    throw 'Tanimli AVD bulunamadi. Once Android Studio Device Manager ile bir emulator olustur.'
  }

  return $validAvds[0]
}

function Stop-ProcessOnPort {
  param([int]$Port)

  $pids = Get-ListeningPids -Port $Port
  foreach ($targetPid in $pids) {
    try {
      Stop-Process -Id $targetPid -Force -ErrorAction Stop
    } catch {
    }
  }
}

function Is-MetroRunning {
  param([int]$Port)

  try {
    $resp = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/status" -UseBasicParsing -TimeoutSec 2
    return ($resp.Content -match 'packager-status:running')
  } catch {
    return $false
  }
}

function Wait-Metro {
  param(
    [int]$Port,
    [int]$TimeoutSeconds = 90
  )

  $start = Get-Date
  while ((Get-Date) -lt $start.AddSeconds($TimeoutSeconds)) {
    if (Is-MetroRunning -Port $Port) {
      return $true
    }
    Start-Sleep -Milliseconds 700
  }

  return $false
}

function Wait-ForDevice {
  param([int]$TimeoutSeconds = 120)

  $start = Get-Date
  while ((Get-Date) -lt $start.AddSeconds($TimeoutSeconds)) {
    $serial = Get-ConnectedDeviceSerial
    if ($serial) {
      return $serial
    }
    Start-Sleep -Seconds 2
  }

  return $null
}

function Wait-ForBootCompleted {
  param(
    [string]$Serial,
    [int]$TimeoutSeconds = 180
  )

  $start = Get-Date
  while ((Get-Date) -lt $start.AddSeconds($TimeoutSeconds)) {
    try {
      $boot = (adb -s $Serial shell getprop sys.boot_completed 2>$null).Trim()
      if ($boot -eq '1') {
        return $true
      }
    } catch {
    }
    Start-Sleep -Seconds 2
  }

  return $false
}

function Ensure-DeviceReady {
  if ($SkipEmulatorStart) {
    $existing = Get-ConnectedDeviceSerial
    if (-not $existing) {
      throw 'SkipEmulatorStart secildi ama bagli cihaz yok.'
    }
    return $existing
  }

  $existing = Get-ConnectedDeviceSerial
  if ($existing) {
    return $existing
  }

  $emuExe = Get-EmulatorExe
  if (-not $emuExe) {
    throw 'emulator.exe bulunamadi. Android SDK Emulator kurulumu gerekli.'
  }

  $selectedAvd = Resolve-AvdName -PreferredAvd $AvdName
  Write-Host "Emulator baslatiliyor: $selectedAvd"
  Start-Process -FilePath $emuExe -ArgumentList @('-avd', $selectedAvd, '-netdelay', 'none', '-netspeed', 'full') -WindowStyle Normal | Out-Null

  $serial = Wait-ForDevice -TimeoutSeconds 180
  if (-not $serial) {
    throw 'ADB cihaz bulamadi. Emulator acilmis olsa bile baglanmadi.'
  }

  Write-Host "Emulator baglandi: $serial"
  $bootOk = Wait-ForBootCompleted -Serial $serial -TimeoutSeconds 240
  if (-not $bootOk) {
    Write-Host 'Uyari: Emulator baglandi ama boot tamamlanmadi, devam ediliyor...'
  }

  return $serial
}

if (-not $NoInstall -and -not (Test-Path (Join-Path $projectRoot.Path 'node_modules'))) {
  Write-Host 'node_modules yok, npm install baslatiliyor...'
  npm install
}

Write-Host 'ADB yeniden baslatiliyor...'
adb kill-server | Out-Null
adb start-server | Out-Null

$deviceSerial = Ensure-DeviceReady
Write-Host "Aktif cihaz: $deviceSerial"

if (-not $UseExistingMetro) {
  Write-Host "Port $MetroPort temizleniyor..."
  Stop-ProcessOnPort -Port $MetroPort
  Start-Sleep -Milliseconds 400
  Stop-ProcessOnPort -Port $MetroPort

  if (Is-MetroRunning -Port $MetroPort) {
    throw "Port $MetroPort uzerinde cevap veren Metro kapatilamadi. Elle kapatip tekrar dene."
  }

  Write-Host 'Metro yeni terminalde baslatiliyor...'
  $metroCmd = "Set-Location '$($projectRoot.Path)'; npx react-native start --reset-cache --port $MetroPort"
  Start-Process -FilePath 'powershell' -ArgumentList @('-NoExit', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', $metroCmd) -WindowStyle Normal

  if (-not (Wait-Metro -Port $MetroPort -TimeoutSeconds 120)) {
    $pids = Get-ListeningPids -Port $MetroPort
    if ($pids.Count -gt 0) {
      Write-Host "Port $MetroPort su an su islemler tarafindan kullaniliyor: $($pids -join ', ')"
    }
    throw 'Metro zamaninda ayaga kalkmadi. Yukaridaki PID bilgisini kontrol et.'
  }
} else {
  Write-Host 'Mevcut Metro bekleniyor...'
  if (-not (Wait-Metro -Port $MetroPort -TimeoutSeconds 8)) {
    throw 'UseExistingMetro secildi ama Metro aktif degil.'
  }
}

Write-Host 'ADB hazirlaniyor...'
adb reverse "tcp:$MetroPort" "tcp:$MetroPort" | Out-Null

Write-Host 'Android build ve run baslatiliyor...'
npm run android -- --port $MetroPort
