#requires -Version 7.0
param(
  [ValidateRange(45, 300)][int]$MaxWaitSeconds = 120,
  [string]$NotificationImage
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$project = 'fairy-commerceflow-notification-alert-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$evidence = (New-Item -Path (Join-Path ([IO.Path]::GetTempPath()) $project) -ItemType Directory).FullName
$ingestToken = [guid]::NewGuid().ToString('N')
$primaryToken = [guid]::NewGuid().ToString('N')
$backupToken = [guid]::NewGuid().ToString('N')
$alertName = 'CommerceFlowNotificationConsumerDeadLetter'
$topic = 'customer-notification-commands'
$dlt = "$topic-dlt"
$services = @('prometheus', 'alertmanager', 'alert-webhook', 'notification-service-postgresql', 'notification-db-postgresql', 'kafka-broker')
$escalationSeconds = 12

$fixturePath = $evidence.Replace('\', '/')
$image = if ($NotificationImage) { $NotificationImage } else { "$project-notification:local" }
$envFile = Join-Path $evidence 'compose.env'
@(
  "NOTIFICATION_ALERT_FIXTURE='$fixturePath'"
  "NOTIFICATION_ALERT_IMAGE='$image'"
  "NOTIFICATION_ALERT_INGEST_TOKEN=$ingestToken"
  "NOTIFICATION_ALERT_PRIMARY_TOKEN=$primaryToken"
  "NOTIFICATION_ALERT_ESCALATION_TOKEN=$backupToken"
) | Set-Content -LiteralPath $envFile
Set-Content -LiteralPath (Join-Path $evidence 'ingest.token') -Value $ingestToken -NoNewline
$compose = @('--project-name', $project, '--project-directory', $root, '--profile', 'observability',
  '--env-file', $envFile, '-f', (Join-Path $root 'compose.yaml'),
  '-f', (Join-Path $root 'infrastructure/compose/compose.notification-alert-verification.yaml'))

@'
global:
  scrape_interval: 1s
  evaluation_interval: 1s
rule_files: [/workflow/notification-rules.yml]
alerting:
  alertmanagers:
    - static_configs:
        - targets: [alertmanager:9093]
scrape_configs:
  - job_name: notification-service
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: [notification-service:8080]
'@ | Set-Content -LiteralPath (Join-Path $evidence 'prometheus.yml')
@'
route:
  receiver: notification-on-call
  group_by: [alertname, job, severity]
  group_wait: 1s
  group_interval: 2s
  repeat_interval: 6s
receivers:
  - name: notification-on-call
    webhook_configs:
      - url: http://alert-webhook:8080/api/alerts
        send_resolved: true
        http_config:
          authorization:
            credentials_file: /workflow/ingest.token
'@ | Set-Content -LiteralPath (Join-Path $evidence 'alertmanager.yml')

$rules = Get-Content -LiteralPath (Join-Path $root 'infrastructure/monitoring/rules/commerceflow-alerts.yml') -Raw
$pattern = '(?ms)^      - alert: ' + $alertName + '\r?\n.*?(?=^      - alert:|^  - name:|\z)'
$rule = [regex]::Match($rules, $pattern).Value.TrimEnd()
if (!$rule.Contains('[2m]') -or !$rule.Contains("lookup: Read $dlt")) {
  throw 'The notification DLT rule or its lookup instructions have changed.'
}
@('groups:', '  - name: notification-isolated', '    rules:', $rule.Replace('[2m]', '[32s]')) |
  Set-Content -LiteralPath (Join-Path $evidence 'notification-rules.yml')

function Request([string]$Url, [string]$Method = 'GET', [int]$Status = 200, [string]$Token, $Body) {
  $options = @{ Method = $Method; SkipHttpErrorCheck = $true; TimeoutSec = 8 }
  if ($Token) { $options.Headers = @{ Authorization = "Bearer $Token" } }
  if ($null -ne $Body) {
    $options.ContentType = 'application/json'
    $options.Body = ConvertTo-Json -InputObject $Body -Depth 15 -Compress
  }
  $response = Invoke-WebRequest $Url @options
  if ($response.StatusCode -ne $Status) { throw "$Method $Url returned $($response.StatusCode), expected $Status." }
  if ($response.Content) { $response.Content | ConvertFrom-Json }
}

function Service-Url([string]$Service, [int]$Port) {
  $binding = docker compose @compose port $Service $Port
  if ($binding -cnotmatch '^127\.0\.0\.1:[0-9]+$') { throw "Unexpected host binding for $Service." }
  "http://$binding"
}

function Daily-Snapshot {
  $ids = @(docker ps -aq --filter 'label=com.docker.compose.project=fairy-commerceflow')
  if ($ids.Count) {
    docker inspect --format '{{.Id}} {{.State.StartedAt}} {{.State.Status}} {{json .Mounts}}' @ids |
      Sort-Object
  }
}

function Wait-For([string]$Description, [scriptblock]$Check) {
  $deadline = [DateTimeOffset]::UtcNow.AddSeconds($MaxWaitSeconds)
  do {
    $value = & $Check
    if ($value) { return $value }
    Start-Sleep -Milliseconds 500
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  throw "Timed out waiting for $Description."
}

function Inbox([string]$Token, [string]$Kind, [string]$IncidentId) {
  (Request "$webhook/api/on-call/inbox" -Token $Token).deliveries |
    Where-Object { $_.kind -ceq $Kind -and (!$IncidentId -or $_.incidentId -ceq $IncidentId) }
}

function Dlt-Tail {
  $result = @{}
  $lines = @(docker compose @compose exec -T kafka-broker /opt/kafka/bin/kafka-get-offsets.sh `
    --bootstrap-server localhost:9092 --topic $dlt)
  foreach ($line in $lines) {
    if ($line -cmatch ('^' + [regex]::Escape($dlt) + ':(\d+):(\d+)$')) {
      $result[[int]$Matches[1]] = [long]$Matches[2]
    }
  }
  if ($result.Count -ne 3) { throw 'Expected all three notification dead-letter partitions.' }
  $result
}

function Send-Command([string]$EventId, [int]$SchemaVersion) {
  $command = Get-Content -LiteralPath (Join-Path $root 'services/notification-service/src/test/resources/contracts/customer-notification-command-v1.json') -Raw |
    ConvertFrom-Json
  $command.schemaVersion = $SchemaVersion
  $command.eventId = $EventId
  $command.correlationId = $EventId
  $command.causationId = "$EventId-acceptance"
  $message = $command | ConvertTo-Json -Compress
  $headers = "commerceflow-correlation-id:$($command.correlationId),commerceflow-causation-id:$($command.causationId),traceparent:$($command.traceparent)"
  ($headers + [char]9 + $message) | docker compose @compose exec -T kafka-broker `
    /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic $topic --property parse.headers=true
}

function Read-IncidentRecord($TailBefore, [string]$EventId) {
  $tailAfter = Dlt-Tail
  $changed = @($TailBefore.Keys | Where-Object { $tailAfter[$_] -ne $TailBefore[$_] })
  if ($changed.Count -ne 1 -or $tailAfter[$changed[0]] -ne $TailBefore[$changed[0]] + 1) {
    throw 'Expected one new DLT record in this isolated alert window.'
  }
  $partition = $changed[0]
  $offset = $TailBefore[$partition]
  $output = @(docker compose @compose exec -T kafka-broker /opt/kafka/bin/kafka-console-consumer.sh `
    --bootstrap-server localhost:9092 --topic $dlt --partition $partition --offset $offset --max-messages 1 `
    --timeout-ms 10000 --consumer-property enable.auto.commit=false --property print.partition=true --property print.offset=true)
  $record = $output | Where-Object { $_ -cmatch "^Partition:$partition[\t ]+Offset:$offset[\t ]+" } | Select-Object -First 1
  if (!$record) { throw 'Could not read the DLT partition and offset in the alert evidence window.' }
  $payload = ($record -split "\t", 3)[-1] | ConvertFrom-Json
  if ($payload.eventId -cne $EventId -or $payload.schemaVersion -ne 99) {
    throw 'The alert lookup did not identify the fault command.'
  }
  [ordered]@{ topic = $dlt; partition = $partition; offset = $offset; eventId = $payload.eventId }
}

function Invoke-FaultEpisode([string]$Name, [switch]$Escalate) {
  $known = @(Inbox $primaryToken 'firing' | ForEach-Object incidentId)
  $tailBefore = Dlt-Tail
  $eventId = "notification-alert-$Name-$($project.Split('-')[-1])"
  Send-Command $eventId 99
  $firing = Wait-For "$Name delivery to primary inbox" {
    Inbox $primaryToken 'firing' | Where-Object { $_.incidentId -cnotin $known } | Select-Object -First 1
  }
  if ($firing.alert.labels.alertname -cne $alertName -or $firing.alert.labels.job -cne 'notification-service' -or
      $firing.alert.annotations.lookup -notmatch [regex]::Escape($dlt)) {
    throw 'Unexpected alert or missing lookup context in the on-call inbox.'
  }
  $null = Wait-For "$Name Prometheus firing state" {
    (Request "$prometheus/api/v1/alerts").data.alerts |
      Where-Object { $_.labels.alertname -ceq $alertName -and $_.state -ceq 'firing' }
  }
  $incidentId = $firing.incidentId
  $ackUrl = "$webhook/api/on-call/incidents/$incidentId/ack"
  $null = Request $ackUrl POST 401
  $null = Request $ackUrl POST 404 $backupToken
  $escalationDelay = $null
  if ($Escalate) {
    $escalated = Wait-For "$Name delivery to backup inbox after the acknowledgement deadline" {
      Inbox $backupToken 'escalated' $incidentId | Select-Object -First 1
    }
    $escalationDelay = ([DateTimeOffset]$escalated.deliveredAt - [DateTimeOffset]$firing.deliveredAt).TotalSeconds
    if ($escalationDelay -lt $escalationSeconds) { throw 'Escalation was sent ahead of the acknowledgement deadline.' }
  }
  $token = if ($Escalate) { $backupToken } else { $primaryToken }
  $expectedActor = if ($Escalate) { 'escalation' } else { 'primary' }
  $ack = Request $ackUrl POST 200 $token
  $repeat = Request $ackUrl POST 200 $token
  if ($ack.acknowledgedBy -cne $expectedActor -or $ack.acknowledgedAt -cne $repeat.acknowledgedAt) {
    throw 'Acknowledgement actor or replay differs.'
  }
  $record = Read-IncidentRecord $tailBefore $eventId
  Send-Command $eventId 1
  $null = Wait-For "$Name corrected event in the notification inbox" {
    $row = docker compose @compose exec -T notification-db-postgresql psql -U postgres -d notification -XAtq `
      -v ON_ERROR_STOP=1 -c "SELECT COUNT(*) FROM notification_inbox WHERE event_id='$eventId';"
    $row -ceq '1'
  }
  $primaryRecovery = Wait-For "$Name recovery notification to primary" {
    Inbox $primaryToken 'resolved' $incidentId | Select-Object -First 1
  }
  if ($Escalate) {
    $null = Wait-For "$Name recovery notification to backup" {
      Inbox $backupToken 'resolved' $incidentId | Select-Object -First 1
    }
  }
  $expectedBackupDeliveries = if ($Escalate) { 2 } else { 0 }
  $primaryRows = @((Request "$webhook/api/on-call/inbox" -Token $primaryToken).deliveries |
    Where-Object incidentId -CEQ $incidentId)
  $backupRows = @((Request "$webhook/api/on-call/inbox" -Token $backupToken).deliveries |
    Where-Object incidentId -CEQ $incidentId)
  if ($primaryRows.Count -ne 2 -or $backupRows.Count -ne $expectedBackupDeliveries) {
    throw 'The workflow duplicated a delivery or sent it to an unexpected recipient.'
  }
  Write-Host "PASS $Name - $incidentId, $eventId, DLT $($record.partition):$($record.offset), acknowledged by $expectedActor."
  [ordered]@{
    stage = $Name; incidentId = $incidentId; deliveredAt = $firing.deliveredAt
    acknowledgedAt = $ack.acknowledgedAt; acknowledgedBy = $ack.acknowledgedBy
    escalationSeconds = $escalationDelay; resolvedAt = $primaryRecovery.resolvedAt
    dlt = $record; correctedInboxRows = 1; alert = $firing.alert
  }
}

$dailyBefore = @(Daily-Snapshot)
$volumesBefore = @(docker volume ls --format '{{.Name}}')
$dailyMounts = @($dailyBefore | ForEach-Object { ($_ -split ' ', 4)[3] | ConvertFrom-Json } |
  Where-Object Type -CEQ 'volume' | ForEach-Object Name)
$commerceVolumes = @($volumesBefore | Where-Object { $_ -match '^fairy-commerceflow[-_]' -or $_ -cin $dailyMounts })
$dailyBefore | Set-Content (Join-Path $evidence 'daily-before.txt')
Write-Host "Isolated project: $project. Evidence: $evidence"
try {
  docker compose @compose config --quiet
  $startMode = if ($NotificationImage) { '--no-build' } else { '--build' }
  docker compose @compose up $startMode -d --wait --wait-timeout $MaxWaitSeconds prometheus
  docker compose @compose exec -T alertmanager amtool check-config /etc/alertmanager/alertmanager.yml
  docker compose @compose exec -T prometheus promtool check config /workflow/prometheus.yml
  docker compose @compose exec -T prometheus promtool test rules /workflow-test/commerceflow-alerts.test.yml
  $webhook = Service-Url alert-webhook 8080
  $prometheus = Service-Url prometheus 9090
  $null = Request "$webhook/api/on-call/inbox" -Status 401
  $metric = [uri]::EscapeDataString('commerceflow_notification_consumer_dead_letters_total{job="notification-service"}')
  $null = Wait-For 'notification dead-letter metric scraped at zero' {
    $series = @((Request "$prometheus/api/v1/query?query=$metric").data.result)
    $series.Count -eq 1 -and [double]$series[0].value[1] -eq 0
  }
  $first = Invoke-FaultEpisode 'primary'
  $second = Invoke-FaultEpisode 'escalation' -Escalate
  $primaryBefore = Request "$webhook/api/on-call/inbox" -Token $primaryToken | ConvertTo-Json -Depth 15 -Compress
  $backupBefore = Request "$webhook/api/on-call/inbox" -Token $backupToken | ConvertTo-Json -Depth 15 -Compress
  docker compose @compose restart alert-webhook
  $webhook = Service-Url alert-webhook 8080
  $null = Wait-For 'webhook restart' {
    try { (Request "$webhook/healthz").status -ceq 'UP' } catch { $false }
  }
  $null = Request "$webhook/api/alerts" POST 202 $ingestToken @{ alerts = @($first.alert, $second.alert) }
  $primaryAfter = Request "$webhook/api/on-call/inbox" -Token $primaryToken | ConvertTo-Json -Depth 15 -Compress
  $backupAfter = Request "$webhook/api/on-call/inbox" -Token $backupToken | ConvertTo-Json -Depth 15 -Compress
  if ($primaryBefore -cne $primaryAfter -or $backupBefore -cne $backupAfter) {
    throw 'A webhook restart or late firing replay changed the acknowledgement and delivery records.'
  }
  [ordered]@{ project = $project; image = $image; escalationSeconds = $escalationSeconds
    episodes = @($first, $second); restartAndLateReplayPreserved = $true } |
    ConvertTo-Json -Depth 15 | Set-Content (Join-Path $evidence 'workflow-result.json')
} finally {
  $isolatedIds = @(docker ps -aq --filter "label=com.docker.compose.project=$project")
  $isolatedVolumes = @()
  if ($isolatedIds.Count) {
    $mounts = docker inspect --format '{{json .Mounts}}' @isolatedIds
    $isolatedVolumes = @($mounts | ForEach-Object { $_ | ConvertFrom-Json } |
      Where-Object Type -CEQ 'volume' | ForEach-Object Name | Sort-Object -Unique)
  }
  try {
    docker compose @compose logs --no-color --tail 70 | Set-Content (Join-Path $evidence 'compose.log')
  } finally {
    docker compose @compose stop @services
  }
  $dailyAfter = @(Daily-Snapshot)
  $dailyAfter | Set-Content (Join-Path $evidence 'daily-after.txt')
  $volumeNames = @(docker volume ls --format '{{.Name}}')
  $missing = @($volumesBefore | Where-Object { $_ -cnotin $volumeNames })
  $missingCommerce = @($commerceVolumes | Where-Object { $_ -cnotin $volumeNames })
  $missingIsolated = @($isolatedVolumes | Where-Object { $_ -cnotin $volumeNames })
  $preserved = [ordered]@{
    dailyContainers = $dailyBefore.Count
    dailyContainerStateUnchanged = ($dailyBefore -join [char]10) -ceq ($dailyAfter -join [char]10)
    preexistingCommerceFlowVolumeCount = $commerceVolumes.Count
    missingCommerceFlowVolumes = $missingCommerce
    missingGlobalVolumes = $missing
    missingIsolatedVolumes = $missingIsolated
    retainedIsolatedVolumes = @($isolatedVolumes | Where-Object { $_ -cin $volumeNames })
  }
  $preserved | ConvertTo-Json | Set-Content (Join-Path $evidence 'preservation.json')
  if (!$preserved.dailyContainerStateUnchanged -or $missingCommerce.Count -or $missingIsolated.Count) {
    throw "Daily container state or CommerceFlow volume inventory changed. See $evidence/preservation.json."
  }
  if ($missing.Count) {
    Write-Warning "Volumes outside the CommerceFlow check disappeared during the run. See $evidence/preservation.json."
  }
}

Write-Host "Notification alert delivery, acknowledgement, escalation and recovery passed. Evidence: $evidence"
