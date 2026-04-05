param(
  [int[]]$Ports = @(8081, 8082, 9090)
)

$ErrorActionPreference = 'SilentlyContinue'

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

foreach ($port in $Ports) {
  $pids = Get-ListeningPids -Port $port
  foreach ($targetPid in $pids) {
    Stop-Process -Id $targetPid -Force
  }
}

foreach ($port in $Ports) {
  adb reverse --remove "tcp:$port" | Out-Null
}
adb reverse --remove-all | Out-Null
adb kill-server | Out-Null

foreach ($port in $Ports) {
  $stillListening = Get-ListeningPids -Port $port
  if ($stillListening.Count -gt 0) {
    Write-Host "Port temizlenemedi: $port (PID: $($stillListening -join ', '))"
  } else {
    Write-Host "Temiz: $port"
  }
}
