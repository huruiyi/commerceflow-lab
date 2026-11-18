#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$contractPath = Join-Path $PSScriptRoot 'script-contracts.tsv'
$contracts = @{}
foreach ($line in Get-Content -LiteralPath $contractPath) {
  if (!$line -or $line.StartsWith('#')) { continue }
  $fields = $line -split "`t"
  if ($fields.Count -ne 2 -or $fields[0] -notmatch '^verify-[a-z0-9-]+$') {
    throw "Invalid script contract row: $line"
  }
  $contracts.Add($fields[0], $fields[1])
}

$commands = @(Get-ChildItem -LiteralPath $PSScriptRoot -File -Recurse | Where-Object { $_.BaseName -match '^(verify|audit)-' -and $_.Extension -ne '.sql' })
foreach ($file in $commands) {
  if ($file.DirectoryName -ne $PSScriptRoot -or $file.Extension -ne '.ps1') {
    throw "Keep one PowerShell entry in scripts/: $($file.FullName)"
  }
}
$entries = @($commands | Where-Object Name -Like 'verify-*')
if (Compare-Object @($contracts.Keys | Sort-Object) @($entries.BaseName | Sort-Object)) {
  throw 'Verification scripts and script-contracts.tsv differ'
}

$files = @(Get-ChildItem -LiteralPath $PSScriptRoot -File -Filter '*.ps1')
foreach ($file in $files) {
  $tokens = $null
  $errors = $null
  $ast = [System.Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$errors)
  if ($errors.Count) { throw "$($file.Name): $($errors[0].Message)" }
  if ($contracts.ContainsKey($file.BaseName)) {
    $parameters = @($ast.ParamBlock.Parameters | ForEach-Object { $_.Name.VariablePath.UserPath })
    if (($parameters -join ',') -cne $contracts[$file.BaseName]) {
      throw "$($file.Name) parameters differ from its contract: $parameters"
    }
  }
}

$audited = @($files | Where-Object BaseName -ne 'audit-script-contracts')
$source = (Get-Content -LiteralPath $audited.FullName -Raw) -join "`n"
if ($source -match '(?im)docker\s+volume\s+(rm|prune)|docker\s+compose[^\r\n]*\sdown\s+-v(?:\s|$)') {
  throw 'Unscoped Docker volume deletion found'
}
$volumeDown = @(Select-String -LiteralPath $audited.FullName -Pattern 'down.*--volumes')
if ($volumeDown.Count -ne 1 -or $volumeDown[0].Filename -ne 'verify-fresh-volume-lifecycle.ps1' -or
    $volumeDown[0].Line -notmatch 'if\s*\(\$RemoveTemporaryVolumes\)\s*\{\s*TempCompose\s+@\(''down'',''--volumes''') {
  throw 'Temporary volume cleanup must require the explicit -RemoveTemporaryVolumes switch'
}
if ($source -match '(?im)rm\s+-rf\s+(?:/|~|"?\$HOME|"?\$PROJECT_ROOT)|Remove-Item[^\r\n]*(?:\$HOME|\$ProjectRoot)[^\r\n]*-Recurse') {
  throw 'Broad recursive deletion target found'
}
Write-Host "Verified $($entries.Count) script contracts, PowerShell syntax and volume cleanup safeguards."
