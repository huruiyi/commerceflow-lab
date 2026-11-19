$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Fail([string]$Message) {
  throw "Code quality audit failed: $Message"
}

$javaRoots = @(
  'services\commerce-service\src\main\java',
  'services\notification-service\src\main\java',
  'services\catalog-service\src\main\java',
  'services\inventory-service\src\main\java',
  'services\order-service\src\main\java',
  'services\payment-orchestration-service\src\main\java',
  'services\fulfillment-service\src\main\java',
  'simulators\payment-platform\service\src\main\java',
  'simulators\logistics-platform\service\src\main\java',
  'simulators\warehouse-platform\service\src\main\java'
) | ForEach-Object { Join-Path $ProjectRoot $_ }

$javaSourceRoots = @(
  'services\commerce-service\src',
  'services\notification-service\src',
  'services\catalog-service\src',
  'services\inventory-service\src',
  'services\order-service\src',
  'services\payment-orchestration-service\src',
  'services\fulfillment-service\src',
  'simulators\payment-platform\service\src',
  'simulators\logistics-platform\service\src',
  'simulators\warehouse-platform\service\src'
) | ForEach-Object { Join-Path $ProjectRoot $_ }

$mapperRoots = @(
  'services\commerce-service\src\main\resources\mapper',
  'services\notification-service\src\main\resources\mapper',
  'services\catalog-service\src\main\resources\mapper',
  'services\inventory-service\src\main\resources\mapper',
  'services\order-service\src\main\resources\mapper',
  'services\payment-orchestration-service\src\main\resources\mapper',
  'services\fulfillment-service\src\main\resources\mapper',
  'simulators\payment-platform\service\src\main\resources\mapper',
  'simulators\logistics-platform\service\src\main\resources\mapper',
  'simulators\warehouse-platform\service\src\main\resources\mapper'
) | ForEach-Object { Join-Path $ProjectRoot $_ }

$pomFiles = @(
  'services\commerce-service\pom.xml',
  'services\notification-service\pom.xml',
  'services\catalog-service\pom.xml',
  'services\inventory-service\pom.xml',
  'services\order-service\pom.xml',
  'services\payment-orchestration-service\pom.xml',
  'services\fulfillment-service\pom.xml',
  'simulators\payment-platform\service\pom.xml',
  'simulators\logistics-platform\service\pom.xml',
  'simulators\warehouse-platform\service\pom.xml'
) | ForEach-Object { Join-Path $ProjectRoot $_ }

$javaFiles = @($javaRoots | ForEach-Object { Get-ChildItem $_ -Recurse -File -Filter '*.java' })
$allJavaFiles = @($javaSourceRoots | ForEach-Object { Get-ChildItem $_ -Recurse -File -Filter '*.java' })
$mapperFiles = @($mapperRoots | ForEach-Object { Get-ChildItem $_ -Recurse -File -Filter '*.xml' })
$formatFiles = @($javaFiles) + @($mapperFiles) + @($pomFiles | ForEach-Object { Get-Item $_ })
$classDeclarationPattern = '^\s*(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed)\s+)*' +
  'class\s+[A-Za-z_$][A-Za-z0-9_$]*[^{]*\{\s*$'

foreach ($file in $formatFiles) {
  $relativePath = [IO.Path]::GetRelativePath($ProjectRoot, $file.FullName)
  $bytes = [IO.File]::ReadAllBytes($file.FullName)
  if ($bytes.Length -eq 0 -or $bytes[-1] -ne 10) {
    Fail "$relativePath must end with a newline"
  }

  $lineNumber = 0
  foreach ($line in [IO.File]::ReadLines($file.FullName)) {
    $lineNumber++
    if ($line.Contains("`t")) {
      Fail "$relativePath`:$lineNumber contains a tab"
    }
    if ($line -match '[ \t]+$') {
      Fail "$relativePath`:$lineNumber contains trailing whitespace"
    }
    if (($file.Extension -eq '.java' -or $mapperFiles.FullName -contains $file.FullName) -and
        $line.Length -gt 120) {
      Fail "$relativePath`:$lineNumber exceeds 120 characters"
    }
    if ($file.Extension -eq '.java' -and $line -match '^\s*import\s+[^;]+\.\*;') {
      Fail "$relativePath`:$lineNumber uses a wildcard import"
    }
    if ($file.Extension -eq '.java' -and
        ([regex]::Matches($line, '\b(?:public|protected|private)\b')).Count -gt 1) {
      Fail "$relativePath`:$lineNumber declares multiple members on one line"
    }
  }
}

foreach ($file in $allJavaFiles) {
  $relativePath = [IO.Path]::GetRelativePath($ProjectRoot, $file.FullName)
  $lines = [IO.File]::ReadAllLines($file.FullName)
  for ($lineIndex = 0; $lineIndex -lt $lines.Length - 1; $lineIndex++) {
    if ($lines[$lineIndex] -match $classDeclarationPattern -and
        -not [string]::IsNullOrWhiteSpace($lines[$lineIndex + 1])) {
      Fail "$relativePath`:$($lineIndex + 1) must have a blank line after the class declaration"
    }
  }
}

foreach ($file in $javaFiles) {
  $relativePath = [IO.Path]::GetRelativePath($ProjectRoot, $file.FullName)
  $source = [IO.File]::ReadAllText($file.FullName)
  if ($source -match '@(?:Select|Insert|Update|Delete)(?:Provider)?\s*\(') {
    Fail "$relativePath contains inline MyBatis SQL"
  }
  $normalized = $relativePath -replace '\\', '/'
  $shadowSource = $normalized -match '^services/(order-service|payment-orchestration-service)/src/main/java/vip/fairy/commerceflow/(order|payment)/(application/ShadowReplicationService|infrastructure/SourceConnectionFactory)\.java$'
  $rawSql = $source -cmatch '"\s*(?:SELECT|INSERT (?:INTO|IGNORE)|UPDATE|DELETE FROM|REPLACE INTO|TRUNCATE TABLE)\s' -or
    $source -match '\.(?:last|inSql|notInSql|setSql|eqSql|gtSql|geSql|ltSql|leSql)\s*\('
  $nativeJdbc = $source -match 'import\s+java\.sql\.(?:Connection|DriverManager|PreparedStatement|Statement|ResultSet)\s*;'
  if (-not $shadowSource -and ($rawSql -or $nativeJdbc)) {
    Fail "$relativePath contains direct JDBC or inline SQL"
  }
  if ($normalized -ne 'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/scheduling/infrastructure/SchedulerConfiguration.java' -and
      $source -match 'import\s+org\.springframework\.jdbc\.') {
    Fail "$relativePath introduces direct Spring JDBC access"
  }
}

$orderJavaRoot = Join-Path $ProjectRoot (
  'services\order-service\src\main\java\vip\fairy\commerceflow\order'
)
foreach ($layer in @('api', 'application', 'domain', 'infrastructure')) {
  if (-not (Test-Path (Join-Path $orderJavaRoot $layer) -PathType Container)) {
    Fail "order service is missing the $layer layer"
  }
}
$unexpectedOrderRootClasses = @(
  Get-ChildItem $orderJavaRoot -File -Filter '*.java' |
    Where-Object Name -ne 'OrderServiceApplication.java'
)
if ($unexpectedOrderRootClasses.Count -gt 0) {
  Fail "order service root package contains $($unexpectedOrderRootClasses[0].Name)"
}

$logisticsJavaRoot = Join-Path $ProjectRoot (
  'simulators\logistics-platform\service\src\main\java\vip\fairy\commerceflow\simulator\logistics'
)
$expectedLogisticsLayers = @('api', 'application', 'domain', 'infrastructure')
foreach ($layer in $expectedLogisticsLayers) {
  if (-not (Test-Path (Join-Path $logisticsJavaRoot $layer) -PathType Container)) {
    Fail "logistics simulator is missing the $layer layer"
  }
}
$unexpectedLogisticsRootClasses = @(
  Get-ChildItem $logisticsJavaRoot -File -Filter '*.java' |
    Where-Object Name -ne 'LogisticsSimulatorApplication.java'
)
if ($unexpectedLogisticsRootClasses.Count -gt 0) {
  Fail "logistics simulator root package contains $($unexpectedLogisticsRootClasses[0].Name)"
}

$notificationJavaRoot = Join-Path $ProjectRoot (
  'services\notification-service\src\main\java\vip\fairy\commerceflow\notification'
)
$expectedNotificationLayers = @('api', 'application', 'domain', 'infrastructure')
foreach ($layer in $expectedNotificationLayers) {
  if (-not (Test-Path (Join-Path $notificationJavaRoot $layer) -PathType Container)) {
    Fail "notification service is missing the $layer layer"
  }
}
foreach ($subdirectory in @('messaging', 'persistence')) {
  $path = Join-Path $notificationJavaRoot "infrastructure\$subdirectory"
  if (-not (Test-Path $path -PathType Container)) {
    Fail "notification service infrastructure is missing the $subdirectory subdirectory"
  }
}
$unexpectedNotificationRootClasses = @(
  Get-ChildItem $notificationJavaRoot -File -Filter '*.java' |
    Where-Object Name -ne 'NotificationServiceApplication.java'
)
if ($unexpectedNotificationRootClasses.Count -gt 0) {
  Fail "notification service root package contains $($unexpectedNotificationRootClasses[0].Name)"
}

foreach ($migration in @(
  'V1__notification_service_schema.sql',
  'V2__runtime_cutover_state.sql',
  'V3__orphan_quarantine.sql'
)) {
  $commerceMigration = Join-Path $ProjectRoot (
    "services\commerce-service\src\main\resources\db\notification-schema\$migration"
  )
  $serviceMigration = Join-Path $ProjectRoot (
    "services\notification-service\src\main\resources\db\migration\$migration"
  )
  if ((Get-FileHash $commerceMigration -Algorithm SHA256).Hash -ne
      (Get-FileHash $serviceMigration -Algorithm SHA256).Hash) {
    Fail "notification migration $migration differs between commerce and notification service"
  }
}

$statementCount = 0
foreach ($mapperFile in $mapperFiles) {
  $relativePath = [IO.Path]::GetRelativePath($ProjectRoot, $mapperFile.FullName)
  try {
    [xml]$document = [IO.File]::ReadAllText($mapperFile.FullName)
  } catch {
    Fail "$relativePath is not valid XML: $($_.Exception.Message)"
  }

  $namespace = [string]$document.mapper.namespace
  if ([string]::IsNullOrWhiteSpace($namespace)) {
    Fail "$relativePath has no mapper namespace"
  }

  $javaRelativePath = $namespace.Replace('.', [IO.Path]::DirectorySeparatorChar) + '.java'
  $mapperInterfaces = @($javaRoots | ForEach-Object {
    $candidate = Join-Path $_ $javaRelativePath
    if (Test-Path $candidate) { Get-Item $candidate }
  })
  if ($mapperInterfaces.Count -ne 1) {
    Fail "$relativePath namespace $namespace resolves to $($mapperInterfaces.Count) Java interfaces"
  }

  $interfaceSource = [IO.File]::ReadAllText($mapperInterfaces[0].FullName)
  $statementNodes = @($document.mapper.ChildNodes | Where-Object {
    $_.NodeType -eq [System.Xml.XmlNodeType]::Element -and
    $_.Name -in @('select', 'insert', 'update', 'delete')
  })
  $statementIds = @($statementNodes | ForEach-Object { [string]$_.id })
  $duplicates = @($statementIds | Group-Object | Where-Object Count -gt 1)
  if ($duplicates.Count -gt 0) {
    Fail "$relativePath contains duplicate statement id $($duplicates[0].Name)"
  }

  foreach ($statementId in $statementIds) {
    if ([string]::IsNullOrWhiteSpace($statementId)) {
      Fail "$relativePath contains a statement without id"
    }
    if ($interfaceSource -notmatch ("\b" + [regex]::Escape($statementId) + "\s*\(")) {
      Fail "$relativePath statement $statementId has no method in $namespace"
    }
    $statementCount++
  }
}

$message = (
  "Code quality audit passed: {0} Java files, {1} Mapper XML files, {2} mapped statements, " +
  "{3} Maven POM files; class spacing across {4} Java sources, format rules, service layers, " +
  "notification migration parity, inline SQL boundary and Mapper contracts are valid."
) -f $javaFiles.Count, $mapperFiles.Count, $statementCount, $pomFiles.Count, $allJavaFiles.Count
Write-Host $message -ForegroundColor Green
