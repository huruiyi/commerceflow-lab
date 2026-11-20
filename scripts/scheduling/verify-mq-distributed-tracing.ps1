param(
  [string]$BaseUrl = 'http://localhost:18081',
  [string]$TempoUrl = 'http://localhost:13200',
  [int]$MaxWaitSeconds = 90,
  [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$externalRuntime = $env:EXTERNAL_RUNTIME -eq 'true'
. (Join-Path $PSScriptRoot '../lib/_verification-db.ps1')
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$variables = @(
  'TRACING_EXPORT_ENABLED', 'SCHEDULING_RABBITMQ_ENABLED', 'SCHEDULING_ROCKETMQ_ENABLED',
  'ORDER_PAYMENT_TIMEOUT', 'SCHEDULING_RABBITMQ_PUBLISH_INITIAL_DELAY',
  'SCHEDULING_ROCKETMQ_PUBLISH_INITIAL_DELAY'
)
$previous = @{}
foreach ($name in $variables) {
  $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$rabbitWasRunning = [bool](docker compose --project-directory $ProjectRoot ps -q rabbitmq-broker 2>$null)
$rocketWasRunning = [bool](docker compose --project-directory $ProjectRoot ps -q rocketmq-broker 2>$null)
$tempoWasRunning = [bool](docker compose --project-directory $ProjectRoot ps -q tempo 2>$null)

function Wait-Healthy([string]$Url) {
  $deadline = (Get-Date).AddSeconds($MaxWaitSeconds)
  do {
    try { if ((Invoke-WebRequest $Url -TimeoutSec 3).StatusCode -eq 200) { return } } catch { }
    Start-Sleep -Seconds 2
  } while ((Get-Date) -lt $deadline)
  throw "Timed out waiting for $Url"
}

function Latest-TaskId([string]$Strategy) {
  $sql = "SELECT COALESCE(MAX(id),0) FROM scheduled_tasks WHERE primary_trigger_snapshot='$Strategy';"
  $value = Invoke-VerificationDb commerce $sql
  return [long](($value | Out-String).Trim())
}

function Find-MqTrace([long]$TaskId, [string]$System) {
  $publish = "$System scheduled task publish"
  $process = "$System scheduled task process"
  $tag = [uri]::EscapeDataString("commerceflow.task.id=$TaskId")
  $deadline = (Get-Date).AddSeconds($MaxWaitSeconds)
  do {
    Start-Sleep -Seconds 2
    try { $search = Invoke-RestMethod "$TempoUrl/api/search?tags=$tag" -TimeoutSec 5 } catch { continue }
    foreach ($summary in @($search.traces)) {
      try { $trace = Invoke-RestMethod "$TempoUrl/api/traces/$($summary.traceID)" -TimeoutSec 5 } catch { continue }
      $names = @($trace.batches.scopeSpans.spans.name)
      if ($names -contains $publish -and $names -contains $process) { return $summary.traceID }
    }
  } while ((Get-Date) -lt $deadline)
  throw "$System task $TaskId did not produce linked publish/process spans"
}

function Restore-Runtime {
  if ($externalRuntime) { return }
  foreach ($name in $variables) {
    if ($null -eq $previous[$name]) {
      Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    } else {
      Set-Item "Env:$name" $previous[$name]
    }
  }
  docker compose --project-directory $ProjectRoot up -d --no-deps --force-recreate commerce-service | Out-Null
  if (-not $rabbitWasRunning) {
    docker compose --project-directory $ProjectRoot stop rabbitmq-broker | Out-Null
  }
  if (-not $rocketWasRunning) {
    docker compose --project-directory $ProjectRoot stop rocketmq-broker rocketmq-nameserver | Out-Null
  }
  if (-not $tempoWasRunning) {
    docker compose --project-directory $ProjectRoot stop tempo | Out-Null
  }
}

try {
  if (-not $externalRuntime) {
    if (-not $SkipBuild) {
      docker compose --project-directory $ProjectRoot build commerce-service
      if ($LASTEXITCODE) { throw 'Tracing image build failed' }
    }
    $env:TRACING_EXPORT_ENABLED = 'true'
    $env:SCHEDULING_RABBITMQ_ENABLED = 'true'
    $env:SCHEDULING_ROCKETMQ_ENABLED = 'true'
    $env:ORDER_PAYMENT_TIMEOUT = '8s'
    $env:SCHEDULING_RABBITMQ_PUBLISH_INITIAL_DELAY = '1s'
    $env:SCHEDULING_ROCKETMQ_PUBLISH_INITIAL_DELAY = '1s'
    docker compose --project-directory $ProjectRoot --profile rabbitmq --profile rocketmq `
      --profile observability up -d rabbitmq-broker rocketmq-topic-init tempo | Out-Null
    docker compose --project-directory $ProjectRoot up -d --no-deps --force-recreate commerce-service | Out-Null
  }
  Wait-Healthy "$TempoUrl/ready"
  Wait-Healthy "$BaseUrl/actuator/health"

  $rabbitBefore = Latest-TaskId 'RABBITMQ'
  & "$PSScriptRoot/verify-rabbitmq-scheduling.ps1" -BaseUrl $BaseUrl `
    -MaxWaitSeconds $MaxWaitSeconds | Out-Host
  $rabbitTask = Latest-TaskId 'RABBITMQ'
  if ($rabbitTask -le $rabbitBefore) { throw 'RabbitMQ trace drill did not create a task' }
  $rabbitTrace = Find-MqTrace $rabbitTask 'rabbitmq'

  $rocketBefore = Latest-TaskId 'ROCKETMQ'
  & "$PSScriptRoot/verify-rocketmq-scheduling.ps1" -BaseUrl $BaseUrl `
    -MaxWaitSeconds $MaxWaitSeconds | Out-Host
  $rocketTask = Latest-TaskId 'ROCKETMQ'
  if ($rocketTask -le $rocketBefore) { throw 'RocketMQ trace drill did not create a task' }
  $rocketTrace = Find-MqTrace $rocketTask 'rocketmq'

  docker compose --project-directory $ProjectRoot stop tempo | Out-Null
  if ($LASTEXITCODE) { throw 'Could not stop Tempo' }
  try {
    Wait-Healthy "$BaseUrl/actuator/health"
  } finally {
    docker compose --project-directory $ProjectRoot start tempo | Out-Null
    if ($LASTEXITCODE) { throw 'Could not restart Tempo' }
  }
  Wait-Healthy "$TempoUrl/ready"
  Write-Host "MQ distributed tracing passed: RabbitMQ task=$rabbitTask trace=$rabbitTrace; RocketMQ task=$rocketTask trace=$rocketTrace; Tempo outage did not stop commerce-service." -ForegroundColor Green
} finally {
  Restore-Runtime
}
