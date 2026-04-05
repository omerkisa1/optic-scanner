param(
  [switch]$NoInstall,
  [switch]$UseExistingMetro,
  [int]$MetroPort = 8081
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

function Wait-Metro {
  param(
    [int]$Port,
    [int]$TimeoutSeconds = 90
  )

  $start = Get-Date
  while ((Get-Date) -lt $start.AddSeconds($TimeoutSeconds)) {
    try {
      $resp = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/status" -UseBasicParsing -TimeoutSec 2
      if ($resp.Content -match 'packager-status:running') {
        return $true
      }
    } catch {
    }
    Start-Sleep -Milliseconds 700
  }

  return $false
}

if (-not $NoInstall -and -not (Test-Path (Join-Path $projectRoot.Path 'node_modules'))) {
  Write-Host 'node_modules yok, npm install baslatiliyor...'
  npm install
}

if (-not $UseExistingMetro) {
  Write-Host "Port $MetroPort temizleniyor..."
  Stop-ProcessOnPort -Port $MetroPort
  Start-Sleep -Milliseconds 400
  Stop-ProcessOnPort -Port $MetroPort

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
adb start-server | Out-Null
adb reverse "tcp:$MetroPort" "tcp:$MetroPort" | Out-Null

Write-Host 'Android build ve run baslatiliyor...'
npm run android
