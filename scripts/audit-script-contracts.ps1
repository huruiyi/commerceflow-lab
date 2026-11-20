#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$contractPath = Join-Path $PSScriptRoot 'script-contracts.tsv'
$contracts = @{}
foreach ($line in Get-Content -LiteralPath $contractPath) {
  if (!$line -or $line.StartsWith('#')) { continue }
  $fields = $line -split "`t"
  if ($fields.Count -ne 2 -or $fields[0] -cnotmatch '^(?:[a-z0-9-]+/)?verify-[a-z0-9-]+\.ps1$') {
    throw "Invalid script contract row: $line"
  }
  $contracts.Add($fields[0], $fields[1])
}

$files = @(Get-ChildItem -LiteralPath $PSScriptRoot -File -Recurse)
$entryPaths = @{}
$entryNames = @{}
foreach ($file in $files) {
  if ($file.BaseName -notmatch '^(verify|audit)-' -or $file.Extension -eq '.sql') { continue }
  $relative = [IO.Path]::GetRelativePath($PSScriptRoot, $file.FullName).Replace('\', '/')
  if ($relative -cnotmatch '^(?:[a-z0-9-]+/)?(?:verify|audit)-[a-z0-9-]+\.ps1$' -or
      $entryNames.ContainsKey($file.BaseName)) {
    throw "Keep one PowerShell entry per name in scripts/ or its category: $relative"
  }
  $entryNames.Add($file.BaseName, $relative)
  if ($file.BaseName -like 'verify-*') { $entryPaths.Add($file.FullName, $relative) }
}
if (Compare-Object @($contracts.Keys | Sort-Object) @($entryPaths.Values | Sort-Object) -CaseSensitive) {
  throw 'Verification scripts and script-contracts.tsv differ'
}

$files = @($files | Where-Object Extension -eq '.ps1')
foreach ($file in $files) {
  $tokens = $null
  $errors = $null
  $ast = [System.Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$errors)
  if ($errors.Count) { throw "$($file.Name): $($errors[0].Message)" }
  if ($entryPaths.ContainsKey($file.FullName)) {
    $parameters = @($ast.ParamBlock.Parameters | ForEach-Object { $_.Name.VariablePath.UserPath })
    if (($parameters -join ',') -cne $contracts[$entryPaths[$file.FullName]]) {
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
Write-Host "Verified $($entryPaths.Count) script contracts, PowerShell syntax and volume cleanup safeguards."
