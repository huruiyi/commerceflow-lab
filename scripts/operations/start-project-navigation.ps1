#requires -Version 7.0
param(
  [switch]$SkipBuild,
  [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$frontend = Join-Path $workspace 'web/project-navigation'
$backend = Join-Path $workspace 'services/project-navigation'
$jar = Join-Path $backend 'target/project-navigation-1.0.0-SNAPSHOT.jar'
if ($JavaHome) {
  $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
  $launcher = if ($IsWindows) { 'java.exe' } else { 'java' }
  $java = Join-Path $env:JAVA_HOME "bin/$launcher"
} else {
  $java = (Get-Command java -CommandType Application | Select-Object -First 1).Source
}
if (!(Test-Path -LiteralPath $java -PathType Leaf)) { throw 'Set JAVA_HOME to JDK 25 or pass -JavaHome.' }

if (!$SkipBuild) {
  Push-Location $frontend
  try {
    & npm ci --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw 'Navigation npm ci failed.' }
    & npm run build
    if ($LASTEXITCODE -ne 0) { throw 'Navigation frontend build failed.' }
  } finally {
    Pop-Location
  }
  & mvn -f (Join-Path $backend 'pom.xml') clean package
  if ($LASTEXITCODE -ne 0) { throw 'Navigation Maven build failed.' }
}

if (!(Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'Build the navigation console before using -SkipBuild.' }
Push-Location $workspace
try {
  Write-Host 'CommerceFlow 运行控制台 http://127.0.0.1:28080'
  & $java -jar $jar
  if ($LASTEXITCODE -ne 0) { throw "Navigation console exited with code $LASTEXITCODE." }
} finally {
  Pop-Location
}
