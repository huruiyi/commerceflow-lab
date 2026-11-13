$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

function Fail([string]$Message) {
  throw "Code quality audit failed: $Message"
}

$javaRoots = @(
  'services\commerce-service\src\main\java',
  'simulators\payment-platform\service\src\main\java',
  'simulators\logistics-platform\service\src\main\java'
) | ForEach-Object { Join-Path $ProjectRoot $_ }

$mapperRoots = @(
  'services\commerce-service\src\main\resources\mapper',
  'simulators\payment-platform\service\src\main\resources\mapper',
  'simulators\logistics-platform\service\src\main\resources\mapper'
) | ForEach-Object { Join-Path $ProjectRoot $_ }

$pomFiles = @(
  'services\commerce-service\pom.xml',
  'simulators\payment-platform\service\pom.xml',
  'simulators\logistics-platform\service\pom.xml'
) | ForEach-Object { Join-Path $ProjectRoot $_ }

$javaFiles = @($javaRoots | ForEach-Object { Get-ChildItem $_ -Recurse -File -Filter '*.java' })
$mapperFiles = @($mapperRoots | ForEach-Object { Get-ChildItem $_ -File -Filter '*.xml' })
$formatFiles = @($javaFiles) + @($mapperFiles) + @($pomFiles | ForEach-Object { Get-Item $_ })

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

foreach ($file in $javaFiles) {
  $relativePath = [IO.Path]::GetRelativePath($ProjectRoot, $file.FullName)
  $source = [IO.File]::ReadAllText($file.FullName)
  if ($source -match '@(?:Select|Insert|Update|Delete)(?:Provider)?\s*\(') {
    Fail "$relativePath contains inline MyBatis SQL"
  }
  if ($file.Name -ne 'SchedulerConfiguration.java' -and
      $source -match 'import\s+org\.springframework\.jdbc\.') {
    Fail "$relativePath introduces direct Spring JDBC access"
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
  "3 Maven POM files; format rules, inline SQL boundary and Mapper contracts are valid."
) -f $javaFiles.Count, $mapperFiles.Count, $statementCount
Write-Host $message -ForegroundColor Green
