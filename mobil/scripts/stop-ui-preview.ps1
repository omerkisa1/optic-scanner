param(
  [int]$MetroPort = 8081
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

$pids = Get-ListeningPids -Port $MetroPort
foreach ($pid in $pids) {
  Stop-Process -Id $pid -Force
}

adb reverse --remove "tcp:$MetroPort" | Out-Null
Write-Host "Metro portu temizlendi: $MetroPort"
