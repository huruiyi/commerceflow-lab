$ErrorActionPreference='Stop'
$ProjectRoot=(Resolve-Path(Join-Path $PSScriptRoot '..\..')).Path
$WindowsDir=Join-Path $ProjectRoot 'scripts\windows'
$LinuxDir=Join-Path $ProjectRoot 'scripts\linux'
$ContractPath=Join-Path $ProjectRoot 'scripts\script-contracts.tsv'

function Fail([string]$Message){throw "Script audit failed: $Message"}
$contracts=@()
Get-Content $ContractPath|Where-Object{$_-and-not$_.StartsWith('#')}|ForEach-Object{
  $parts=$_-split"`t";if($parts.Count-ne3){Fail "invalid contract row: $_"}
  $contracts+=[pscustomobject]@{Name=$parts[0];Parameters=@($parts[1]-split',');Bindings=@($parts[2]-split',')}
}
$windows=@(Get-ChildItem $WindowsDir -Filter 'verify-*.ps1'|ForEach-Object BaseName|Sort-Object)
$linux=@(Get-ChildItem $LinuxDir -Filter 'verify-*.sh'|ForEach-Object BaseName|Sort-Object)
$declared=@($contracts.Name|Sort-Object)
if(Compare-Object $windows $linux){Fail 'Windows/Bash verify-* filenames differ'}
if(Compare-Object $windows $declared){Fail 'script-contracts.tsv does not cover every verify-* pair'}

foreach($contract in $contracts){
  $psPath=Join-Path $WindowsDir ($contract.Name+'.ps1');$shPath=Join-Path $LinuxDir ($contract.Name+'.sh')
  $tokens=$null;$errors=$null;$ast=[System.Management.Automation.Language.Parser]::ParseFile($psPath,[ref]$tokens,[ref]$errors)
  if($errors.Count){Fail "$($contract.Name).ps1 has parser errors"}
  $actual=@($ast.ParamBlock.Parameters|ForEach-Object{$_.Name.VariablePath.UserPath})
  if(($actual-join',')-ne($contract.Parameters-join',')){Fail "$($contract.Name) PowerShell parameters are [$($actual-join',')]"}
  $source=Get-Content $shPath -Raw
  foreach($binding in $contract.Bindings){
    if($binding.StartsWith('@')){if($source-notmatch[regex]::Escape('--'+$binding.Substring(1))){Fail "$($contract.Name).sh is missing $binding"};continue}
    $name,$position=$binding-split'=';if($name-eq'ARG'){$pattern='\$\{'+$position+':-'}else{$pattern=[regex]::Escape($name)+'\s*=.*\$\{'+$position+':-'}
    if($source-notmatch$pattern){Fail "$($contract.Name).sh is missing positional binding $binding"}
  }
}

$scriptFiles=@(Get-ChildItem $WindowsDir,$LinuxDir -File|Where-Object{($_.Extension-in@('.ps1','.sh'))-and$_.BaseName-ne'audit-script-contracts'})
$allSource=($scriptFiles|ForEach-Object{"$($_.FullName)`n$(Get-Content $_.FullName -Raw)"})-join"`n"
if($allSource-match'(?im)docker\s+volume\s+(rm|prune)|docker\s+compose[^\r\n]*\sdown\s+-v(?:\s|$)'){Fail 'unscoped Docker volume deletion found'}
$volumeDown=@(Select-String -Path ($scriptFiles.FullName) -Pattern 'down.*--volumes')
if($volumeDown.Count-ne2-or@($volumeDown.Path|Where-Object{$_-notmatch'verify-fresh-volume-lifecycle'}).Count-gt0){Fail 'volume cleanup must exist only in the explicit fresh-volume switches'}
$freshPs=Get-Content (Join-Path $WindowsDir 'verify-fresh-volume-lifecycle.ps1') -Raw
$freshSh=Get-Content (Join-Path $LinuxDir 'verify-fresh-volume-lifecycle.sh') -Raw
if($freshPs-notmatch'if\s*\(\$RemoveTemporaryVolumes\).*down.*--volumes' -or $freshSh-notmatch'if\s+\[\[\s+"\$REMOVE_TEMPORARY_VOLUMES"\s+==\s+true\s+\]\].*down\s+--volumes'){Fail 'fresh-volume deletion is not guarded by its explicit switch'}
if($allSource-match'(?im)rm\s+-rf\s+(?:/|~|"?\$HOME|"?\$PROJECT_ROOT)|Remove-Item[^\r\n]*(?:\$HOME|\$ProjectRoot)[^\r\n]*-Recurse'){Fail 'broad recursive deletion target found'}

Write-Host "Script contract audit passed: $($contracts.Count) Windows/Bash verify pairs, ordered parameter bindings aligned, no default named-volume deletion; fresh-volume cleanup is explicit only." -ForegroundColor Green
