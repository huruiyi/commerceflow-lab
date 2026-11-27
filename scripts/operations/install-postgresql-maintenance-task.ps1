#requires -Version 7.0
param(
  [Parameter(Mandatory)]
  [ValidateSet('Backup', 'Restore', 'Monitor')]
  [string]$Task,
  [string]$BackupRoot,
  [string]$StateDirectory = (Join-Path $env:LOCALAPPDATA 'CommerceFlow/postgresql-maintenance')
)

$ErrorActionPreference = 'Stop'
if (!$IsWindows) { throw 'These maintenance tasks run through Windows Task Scheduler' }

if ($Task -ne 'Monitor') {
  if (!$BackupRoot) { throw 'BackupRoot is required for backup and restore tasks' }
  $BackupRoot = (Resolve-Path -LiteralPath $BackupRoot).Path
}
$StateDirectory = [IO.Path]::GetFullPath($StateDirectory)
$pwsh = Join-Path $PSHOME 'pwsh.exe'
$installedPowerShell = Join-Path $env:ProgramFiles 'PowerShell/7/pwsh.exe'
if (Test-Path -LiteralPath $installedPowerShell -PathType Leaf) { $pwsh = $installedPowerShell }
$workspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$runner = Join-Path $PSScriptRoot 'run-postgresql-maintenance.ps1'
$today = (Get-Date).Date
$user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
  -ExecutionTimeLimit (New-TimeSpan -Minutes 15) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries

switch ($Task) {
  'Backup' {
    $name = 'CommerceFlow PostgreSQL Daily Backup'
    $trigger = New-ScheduledTaskTrigger -Daily -At $today.AddHours(21)
    $options = "-Operation Backup -BackupRoot `"$BackupRoot`" -StateDirectory `"$StateDirectory`""
  }
  'Restore' {
    $name = 'CommerceFlow PostgreSQL Weekly Restore'
    $trigger = New-ScheduledTaskTrigger -Weekly -DaysOfWeek Saturday -At $today.AddHours(21).AddMinutes(30)
    $options = "-Operation Restore -BackupRoot `"$BackupRoot`" -StateDirectory `"$StateDirectory`""
  }
  'Monitor' {
    $name = 'CommerceFlow PostgreSQL Recovery Alerts'
    $runner = Join-Path $PSScriptRoot 'alert-postgresql-maintenance.ps1'
    $trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(2) `
      -RepetitionInterval (New-TimeSpan -Minutes 15)
    $options = "-StateDirectory `"$StateDirectory`""
  }
}
if (!(Test-Path -LiteralPath $runner -PathType Leaf)) { throw "Missing maintenance script: $runner" }
$arguments = "-NoLogo -NoProfile -NonInteractive -File `"$runner`" $options"
$action = New-ScheduledTaskAction -Execute $pwsh -Argument $arguments -WorkingDirectory $workspace
$existing = Get-ScheduledTask -TaskPath '\' -TaskName $name -ErrorAction SilentlyContinue
if ($existing -and ($existing.Actions.Execute -ne $pwsh -or $existing.Actions.Arguments -ne $arguments)) {
  throw "An existing task named '$name' has a different action"
}
Register-ScheduledTask -TaskPath '\' -TaskName $name -Action $action -Trigger $trigger `
  -Principal $principal -Settings $settings -Force | Out-Null
Get-ScheduledTaskInfo -TaskPath '\' -TaskName $name | Select-Object TaskName, NextRunTime
