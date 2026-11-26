#requires -Version 7.4
param(
  [Parameter(Mandatory)][string]$BackupPath,
  [Parameter(Mandatory)][string]$CandidateImage,
  [Parameter(Mandatory)][string]$BaselineImage,
  [Parameter(Mandatory)][string]$EvidenceDirectory
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$project = 'fairy-commerceflow-inventory-pg-' + [guid]::NewGuid().ToString('N').Substring(0,12)
$evidence = New-Item -ItemType Directory (Join-Path $EvidenceDirectory $project)
$backup = (Resolve-Path -LiteralPath $BackupPath).Path
$compose = @('compose','-p',$project,'--project-directory',$root,
  '-f',(Join-Path $root 'infrastructure/compose/compose.inventory-postgresql-verification.yaml'))
$postgres = 'inventory-db-postgresql'
$env:INVENTORY_PG_IMAGE = $CandidateImage
$env:INVENTORY_ROLLBACK_IMAGE = $BaselineImage
foreach ($key in 'INVENTORY_PG_ADMIN_PASSWORD','INVENTORY_PG_MIGRATOR_PASSWORD','INVENTORY_PG_RUNTIME_PASSWORD',
  'INVENTORY_ROLLBACK_PASSWORD','INVENTORY_ROLLBACK_ROOT_PASSWORD') {
  Set-Item "Env:$key" ([Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)))
}

function DatabaseSettings([string]$Service) {
  if ($Service -eq 'postgres') {
    $env:INVENTORY_VERIFICATION_IMAGE = $CandidateImage
    $env:INVENTORY_VERIFICATION_DATABASE = "jdbc:postgresql://${postgres}:5432/inventory"
    $env:INVENTORY_VERIFICATION_USER = 'inventory_runtime'
    $env:INVENTORY_VERIFICATION_PASSWORD = $env:INVENTORY_PG_RUNTIME_PASSWORD
    $env:INVENTORY_VERIFICATION_PROFILE = 'postgresql'
    $env:INVENTORY_VERIFICATION_FLYWAY = 'false'
  } else {
    $env:INVENTORY_VERIFICATION_IMAGE = $BaselineImage
    $env:INVENTORY_VERIFICATION_DATABASE = "jdbc:mysql://${Service}:3306/inventory?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    $env:INVENTORY_VERIFICATION_USER = 'inventory'
    $env:INVENTORY_VERIFICATION_PASSWORD = if ($Service -eq 'original') { 'inventory-isolated-runtime' } else { $env:INVENTORY_ROLLBACK_PASSWORD }
    $env:INVENTORY_VERIFICATION_PROFILE = 'default'
    $env:INVENTORY_VERIFICATION_FLYWAY = 'true'
  }
}

function Query([string]$Service,[string]$Sql) {
  if ($Service -eq 'postgres') {
    docker @compose exec -T $postgres psql -U postgres -d inventory -XAtq -v ON_ERROR_STOP=1 -c $Sql
  } else {
    docker @compose exec -T $Service sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 -NBr inventory -e "$1"' sh $Sql
  }
}

function Restore-MySql([string]$Service,[string]$Path) {
  docker @compose cp $Path "${Service}:/tmp/inventory.sql"
  docker @compose exec -T $Service sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot inventory </tmp/inventory.sql && rm /tmp/inventory.sql'
}

function Start-Inventory([string]$Service) {
  DatabaseSettings $Service
  docker @compose up -d --no-deps --no-build --force-recreate --wait inventory | Out-Host
}

function Call-Scenario([string]$Name) {
  docker @compose exec -T verifier node /verification/inventory-postgresql-rehearsal-api.mjs $Name |
    Set-Content "$evidence/$Name.json"
}

function Balance([string]$Service) {
  Query $Service "SELECT concat_ws('|',stock_available,stock_reserved,stock_sold) FROM (
      SELECT 1 AS position,stock_available,stock_reserved,stock_sold FROM product_inventory WHERE product_id=2
      UNION ALL SELECT 2,stock_available,stock_reserved,stock_sold
      FROM inventory_warehouse_stock WHERE product_id=2 AND warehouse_id=1
    ) AS balances ORDER BY position"
}

function Assert-Balance([string]$Service,[string]$Name,[int]$Available,[int]$Reserved,[int]$Sold) {
  $actual = @(Balance $Service)
  $delta = @($Available,$Reserved,$Sold)
  for ($row = 0; $row -lt $baseline.Count; ++$row) {
    $base = $baseline[$row] -split '\|'
    $expected = (0..2 | ForEach-Object { [int]$base[$_] + $delta[$_] }) -join '|'
    if ($actual.Count -ne 2 -or $actual[$row] -cne $expected) {
      throw "Unexpected stock balance for $Name at $Service."
    }
  }
  @($Name,$Service,$actual[0],$actual[1]) -join "`t" | Add-Content "$evidence/balances.tsv"
}

function Assert-Reservation([string]$Service,[long]$Order,[string]$Status,[int]$Quantity,[int]$Version) {
  $actual = Query $Service "SELECT concat_ws('|',status,quantity,warehouse_id,version) FROM inventory_reservations
    WHERE order_id=$Order AND product_id=2"
  if ($actual -cne "$Status|$Quantity|1|$Version") { throw "Reservation differs for $Order at $Service." }
}

function Copy-Ledger([string]$Direction) {
  $ledger = Join-Path $PSScriptRoot 'inventory-postgresql-ledger.mjs'
  $name = if ($Direction -eq 'ToPostgreSQL') { 'forward' } else { 'reverse' }
  node $ledger $project $Direction "$evidence/$name.json"
  node $ledger $project $Direction "$evidence/$name-repeat.json"
  if ((Get-FileHash "$evidence/$name.json").Hash -cne (Get-FileHash "$evidence/$name-repeat.json").Hash) {
    throw "Ledger parity differs on the $name retry."
  }
}

function Kafka-Offset {
  $offset = docker @compose exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:9092 --topic $topic
  if ($offset -notmatch '^inventory-outbound-events:0:(\d+)$') { throw 'Unexpected inventory topic offsets.' }
  [long]$Matches[1]
}

function Canonical-Event($Event) {
  $fields = [ordered]@{}
  foreach ($key in ($Event.PSObject.Properties.Name | Sort-Object)) { $fields[$key] = $Event.$key }
  ConvertTo-Json -InputObject $fields -Compress
}

function Pending-Event([string]$Service,[string]$Id) {
  $time = if ($Service -eq 'postgres') {
    "to_char(next_attempt_at AT TIME ZONE 'UTC','YYYY-MM-DD HH24:MI:SS.US')"
  } else { "DATE_FORMAT(next_attempt_at,'%Y-%m-%d %H:%i:%s.%f')" }
  $prefix = if ($Service -eq 'postgres') { '' } else { "SET time_zone='+00:00'; " }
  $row = Query $Service "$($prefix)SELECT concat_ws('|',$time,payload) FROM inventory_outbound_event_outbox
    WHERE event_id='$Id' AND published_at IS NULL"
  if (!$row -or $row -is [array]) { throw "Expected one pending event $Id at $Service." }
  $due,$body = $row -split '\|',2
  [ordered]@{ due=$due; payload=(Canonical-Event ($body | ConvertFrom-Json -DateKind String)) }
}

function Assert-EventDelivered([string]$Service,[string]$Id,[long]$Offset,$Pending) {
  $deadline = [DateTimeOffset]::UtcNow.AddSeconds(35)
  do {
    $count = Query $Service "SELECT count(*) FROM inventory_outbound_event_outbox WHERE event_id='$Id' AND published_at IS NOT NULL"
    if ($count -ceq '1') { break }
    Start-Sleep -Milliseconds 500
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  if ($count -cne '1') { throw "Pending event $Id did not publish at $Service." }
  if ((Kafka-Offset) -ne ($Offset + 1)) { throw "Expected one Kafka record for $Id at $Service." }
  $message = docker @compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 `
    --topic $topic --partition 0 --offset $Offset --max-messages 1 --timeout-ms 7000 | ConvertFrom-Json -DateKind String
  if ($message.eventId -cne $Id -or (Canonical-Event $message) -cne $Pending.payload) {
    throw "Kafka receipt differs for $Id at $Service."
  }
  $message
}

$referenceSql = "SELECT concat_ws('|',d.order_id,d.outbound_id,d.warehouse_id,l.order_line_id,l.product_id,l.quantity)
  FROM inventory_outbound_documents d JOIN inventory_outbound_lines l ON d.outbound_id=l.outbound_id
  WHERE d.order_id IN (751,752,753,755) ORDER BY d.order_id,d.outbound_id,l.order_line_id;
  SELECT concat_ws('|',order_id,case_id,order_line_id,product_id,quantity,kind,warehouse_id,outbound_id)
  FROM inventory_line_credits WHERE order_id IN (751,752,753,755) ORDER BY order_id,case_id"
$totalsSql = "SELECT concat_ws('|',p.product_id,p.stock_available,p.stock_reserved,p.stock_sold,
    COALESCE(SUM(w.stock_available),0),COALESCE(SUM(w.stock_reserved),0),COALESCE(SUM(w.stock_sold),0))
  FROM product_inventory p LEFT JOIN inventory_warehouse_stock w ON w.product_id=p.product_id
  GROUP BY p.product_id,p.stock_available,p.stock_reserved,p.stock_sold ORDER BY p.product_id"
$eventId = 'outbound:5050266c-9138-46af-844d-89fb4f4bea08:9201752'
$originalEventId = 'outbound:b2e11d49-7316-4180-9992-0f32e7e571b1:9201755'
$reservationSql = "SELECT concat_ws('|',id,order_id,product_id,warehouse_id,quantity,version) FROM inventory_reservations
  WHERE status='RESERVED' AND order_id < 9200751 ORDER BY id"
$topic = 'inventory-outbound-events'
DatabaseSettings original
try {
  docker @compose up -d --wait original $postgres kafka verifier | Out-Host
  Restore-MySql original $backup
  $historySql = 'SELECT concat_ws(''|'',version,checksum,success) FROM flyway_schema_history ORDER BY installed_rank'
  $history = @(Query original $historySql)
  if ($history.Count -ne 10 -or @($history | Where-Object { $_ -notmatch '^(?:[1-9]|10)\|[-0-9]+\|1$' }).Count) {
    throw 'Expected a MySQL V10 backup with successful migrations.'
  }
  $history | Set-Content "$evidence/mysql-history.tsv"
  $schema = Join-Path $evidence 'rollback-schema.sql'
  docker @compose exec -T original sh -ec 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-data --skip-add-drop-table inventory && mysqldump -uroot --no-tablespaces --set-gtid-purged=OFF --no-create-info --skip-add-locks inventory flyway_schema_history' > $schema
  docker @compose up -d --wait rollback | Out-Host
  Restore-MySql rollback $schema
  if (Compare-Object $history @(Query rollback $historySql) -SyncWindow 0) { throw 'MySQL rollback migration history differs.' }
  $baseline = @(Balance original)
  if ($baseline.Count -ne 2 -or [int]($baseline[1] -split '\|')[0] -lt 3) { throw 'Expected stock in warehouse 1 for product 2.' }
  $baseline | Set-Content "$evidence/original-stock.tsv"
  $originalReferences = @(Query original $referenceSql)
  if (!$originalReferences.Count) { throw 'No historical outbound or return evidence was found.' }
  $originalReferences | Set-Content "$evidence/original-references.tsv"
  $nativeReservations = @(Query original $reservationSql)
  $nativeReservations | Set-Content "$evidence/original-reservations.tsv"
  $nativePending = Query original 'SELECT count(*) FROM inventory_outbound_event_outbox WHERE published_at IS NULL'
  $nativePending | Set-Content "$evidence/original-pending-count.txt"

  docker @compose run --rm --no-deps inventory-postgresql-roles | Out-Host
  docker @compose run --rm --no-deps inventory-postgresql-migrator -target=1 migrate | Out-Host
  docker @compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists `
    --partitions 1 --replication-factor 1 --topic $topic | Out-Host
  Start-Inventory original
  Call-Scenario hold-mysql
  Assert-Reservation original 9200751 RESERVED 2 0
  Assert-Balance original hold-mysql -2 2 0
  $deadline = [DateTimeOffset]::UtcNow.AddSeconds(45)
  do {
    $pendingCount = Query original 'SELECT count(*) FROM inventory_outbound_event_outbox WHERE published_at IS NULL'
    if ($pendingCount -ceq '0') { break }
    Start-Sleep -Milliseconds 500
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  if ($pendingCount -cne '0') { throw 'The isolated source still has pending history.' }
  $forwardOffset = Kafka-Offset
  docker @compose stop kafka | Out-Host
  Call-Scenario outbound-mysql
  Assert-Balance original outbound-mysql -3 2 1
  docker @compose stop inventory | Out-Host
  $forwardPending = Pending-Event original $originalEventId
  $forwardPending | ConvertTo-Json | Set-Content "$evidence/pending-forward-original.json"
  $beforeTotals = @(Query original $totalsSql)
  $beforeTotals | Set-Content "$evidence/original-warehouse-totals.tsv"
  Query original 'SET PERSIST super_read_only=ON'
  Copy-Ledger ToPostgreSQL
  if (Compare-Object $beforeTotals @(Query postgres $totalsSql) -SyncWindow 0) { throw 'PostgreSQL warehouse totals differ.' }
  if (($nativeReservations -join "`n") -cne ((Query postgres $reservationSql) -join "`n")) {
    throw 'PostgreSQL historical pre-reservations differ.'
  }
  $forwardCopied = Pending-Event postgres $originalEventId
  if ($forwardCopied.due -cne $forwardPending.due -or $forwardCopied.payload -cne $forwardPending.payload) {
    throw 'PostgreSQL pending event or retry time differs.'
  }
  Assert-Reservation postgres 9200751 RESERVED 2 0
  Assert-Balance postgres copied-reserve -3 2 1

  docker @compose run --rm --no-deps inventory-postgresql-migrator | Out-Host
  if ((Query postgres (Get-Content "$root/infrastructure/inventory/verify-runtime.sql" -Raw)) -cne 't') { throw 'Unexpected runtime privileges.' }
  Query postgres 'ALTER ROLE inventory_runtime LOGIN'
  docker @compose up -d --wait kafka | Out-Host
  Start-Inventory postgres
  if ((Query postgres "SELECT EXISTS (SELECT FROM pg_stat_activity WHERE usename='inventory_runtime' AND datname='inventory')") -cne 't') {
    throw 'Inventory did not connect with the restricted runtime role.'
  }
  if ((Query postgres "SELECT string_agg(version,',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success") -cne '1,2') {
    throw 'Expected PostgreSQL V2.'
  }
  Call-Scenario release-postgresql
  Assert-Reservation postgres 9200751 RELEASED 2 1
  Assert-Balance postgres release-postgresql -1 0 1
  $forwardEvent = Assert-EventDelivered postgres $originalEventId $forwardOffset $forwardPending
  ConvertTo-Json -InputObject $forwardEvent | Set-Content "$evidence/kafka-forward.json"
  Call-Scenario reserve-postgresql
  Assert-Reservation postgres 9200752 RESERVED 3 0
  Assert-Balance postgres reserve-postgresql -4 3 1
  Call-Scenario commit-postgresql
  Assert-Reservation postgres 9200752 COMMITTED 3 1
  Assert-Balance postgres commit-postgresql -4 0 4

  $reverseOffset = Kafka-Offset
  docker @compose stop kafka | Out-Host
  Call-Scenario outbound-postgresql
  Assert-Balance postgres outbound-postgresql -4 0 4

  Call-Scenario return-postgresql
  Assert-Balance postgres return-postgresql -3 0 3
  $credit = Query postgres "SELECT concat_ws('|',case_id,order_id,order_line_id,quantity,kind,warehouse_id,outbound_id)
    FROM inventory_line_credits WHERE order_id=9200752"
  if ($credit -cne '20581e3d-8a0a-4994-9e48-7f89c18525f9|9200752|9201752|1|RETURN|1|5050266c-9138-46af-844d-89fb4f4bea08') {
    throw 'Unexpected return credit ledger.'
  }
  $credit | Set-Content "$evidence/return-credit.tsv"
  $movements = @(Query postgres "SELECT concat_ws('|',movement_type,quantity,warehouse_id)
    FROM inventory_movements WHERE order_id=9200752 ORDER BY id")
  if ($movements -join ',' -cne 'RESERVED|3|1,COMMITTED|3|1,OUTBOUND_POSTED|2|1,RETURN_RESTOCKED|1|1') {
    throw "Unexpected shipment and return movements: $($movements -join ',')"
  }
  $movements | Set-Content "$evidence/ship-return-movements.tsv"

  Call-Scenario hold-postgresql
  Assert-Balance postgres hold-postgresql -4 1 3
  Assert-Reservation postgres 9200753 RESERVED 1 0
  docker @compose stop inventory | Out-Host
  Query postgres 'ALTER ROLE inventory_runtime NOLOGIN'
  Query postgres "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='inventory_runtime'" | Out-Null
  $reversePending = Pending-Event postgres $eventId
  $reversePending | ConvertTo-Json | Set-Content "$evidence/pending-reverse-original.json"
  $reverseTotals = @(Query postgres $totalsSql)
  docker @compose exec -T $postgres pg_dump -U postgres -d inventory -Fc > "$evidence/inventory-postgresql.dump"
  Copy-Ledger ToMySQL
  $reverseCopied = Pending-Event rollback $eventId
  if ($reverseCopied.due -cne $reversePending.due -or $reverseCopied.payload -cne $reversePending.payload) {
    throw 'MySQL rollback pending event or retry time differs.'
  }
  if ((Compare-Object $reverseTotals @(Query rollback $totalsSql) -SyncWindow 0) -or
      ($nativeReservations -join "`n") -cne ((Query rollback $reservationSql) -join "`n")) {
    throw 'MySQL rollback warehouse balances or historical pre-reservations differ.'
  }
  Assert-Reservation rollback 9200753 RESERVED 1 0
  Assert-Balance rollback copied-rollback-reserve -4 1 3
  if ((Compare-Object $originalReferences @(Query postgres $referenceSql) -SyncWindow 0) -or
      (Compare-Object $originalReferences @(Query rollback $referenceSql) -SyncWindow 0)) {
    throw 'Historical outbound and return receipts changed.'
  }
  docker @compose up -d --wait kafka | Out-Host
  Start-Inventory rollback
  Call-Scenario rollback-mysql
  Assert-Reservation rollback 9200753 RELEASED 1 1
  Assert-Balance rollback rollback-mysql -3 0 3
  $message = Assert-EventDelivered rollback $eventId $reverseOffset $reversePending
  if ($message.orderId -ne 9200752 -or $message.orderLineId -ne 9201752 -or $message.quantity -ne 2 -or $message.warehouseId -ne 1) {
    throw 'Kafka outbound receipt differs from inventory.'
  }
  ConvertTo-Json -InputObject $message | Set-Content "$evidence/kafka-outbound.json"
  [ordered]@{
    project = $project
    sourceBackupSha256 = (Get-FileHash $backup).Hash
    postgresFlyway = '2'
    forward = Get-Content "$evidence/forward.json" -Raw | ConvertFrom-Json
    reverse = Get-Content "$evidence/reverse.json" -Raw | ConvertFrom-Json
    historicalReceipts = $originalReferences.Count
    historicalReservations = $nativeReservations.Count
    originalPendingEvents = [int]$nativePending
    kafkaForwardEvent = $forwardEvent
    kafkaOutboundEvent = $message
    inventoryMovements = $movements
  } | ConvertTo-Json -Depth 6 | Tee-Object "$evidence/result.json"
} catch {
  docker @compose logs --no-color inventory kafka original rollback $postgres > "$evidence/failure.log"
  throw
} finally {
  docker @compose down | Out-Host
}
