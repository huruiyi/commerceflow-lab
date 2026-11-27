#requires -Version 7.0
param(
  [Parameter(Mandatory)]
  [string]$BackupRoot
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$project = 'fairy-commerceflow'
$workspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$root = (Resolve-Path -LiteralPath $BackupRoot).Path
$relativeRoot = [IO.Path]::GetRelativePath($workspace, $root)
if ($relativeRoot -eq '.' -or
    (!$relativeRoot.StartsWith('..' + [IO.Path]::DirectorySeparatorChar) -and
      $relativeRoot -ne '..' -and ![IO.Path]::IsPathRooted($relativeRoot))) {
  throw 'Use a backup directory outside the source repository'
}

$dockerPath = (Get-Command docker -CommandType Application | Select-Object -First 1).Source

function Save-DockerOutput([string]$File, [string[]]$Arguments) {
  $startInfo = [Diagnostics.ProcessStartInfo]::new($dockerPath)
  $startInfo.UseShellExecute = $false
  $startInfo.RedirectStandardOutput = $true
  $startInfo.RedirectStandardError = $true
  foreach ($argument in $Arguments) {
    $startInfo.ArgumentList.Add($argument)
  }
  $output = [IO.File]::Open($File, [IO.FileMode]::CreateNew)
  try {
    $process = [Diagnostics.Process]::Start($startInfo)
    try {
      $stderr = $process.StandardError.ReadToEndAsync()
      $process.StandardOutput.BaseStream.CopyTo($output)
      $process.WaitForExit()
      if ($process.ExitCode -ne 0) {
        throw "Docker command failed while writing $([IO.Path]::GetFileName($File)): $($stderr.GetAwaiter().GetResult())"
      }
    } finally {
      $process.Dispose()
    }
  } finally {
    $output.Dispose()
  }
}

function Get-RunningProject {
  $ids = @(docker ps --quiet --no-trunc --filter "label=com.docker.compose.project=$project")
  if ($LASTEXITCODE -ne 0 -or !$ids.Count) { throw "Could not list running containers for $project" }
  $inspected = docker inspect --type container @ids
  if ($LASTEXITCODE -ne 0) { throw "Could not inspect running containers for $project" }
  @($inspected | ConvertFrom-Json)
}

$containers = @(Get-RunningProject)
$databases = @($containers | Where-Object {
  $_.Mounts.Destination -contains '/var/lib/postgresql/data' -and
    @($_.Config.Env | Where-Object { $_ -clike 'POSTGRES_DB=*' }).Count -eq 1
})
$brokers = @($containers | Where-Object { $_.Config.Labels.'com.docker.compose.service' -ceq 'kafka-broker' })
if ($databases.Count -ne 13 -or $brokers.Count -ne 1 -or
    @($databases | Where-Object { $_.State.Health.Status -ne 'healthy' }).Count) {
  throw 'Expected 13 healthy PostgreSQL instances and one Kafka broker in the daily project'
}

$jobName = 'postgresql-recovery-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' +
  [guid]::NewGuid().ToString('N').Substring(0, 8)
$destination = (New-Item -Path (Join-Path $root $jobName) -ItemType Directory).FullName
if ($IsWindows) {
  $acl = Get-Acl -LiteralPath $destination
  $acl.SetAccessRuleProtection($true, $false)
  $principals = @(
    [Security.Principal.WindowsIdentity]::GetCurrent().User
    [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
    [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
  )
  foreach ($principal in $principals) {
    $rule = [Security.AccessControl.FileSystemAccessRule]::new(
      $principal, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'
    )
    $acl.SetAccessRule($rule)
  }
  Set-Acl -LiteralPath $destination -AclObject $acl
} else {
  chmod 700 -- $destination
  if ($LASTEXITCODE -ne 0) { throw 'Could not restrict the backup directory' }
}

$archives = (New-Item -Path (Join-Path $destination 'archives') -ItemType Directory).FullName
$deployment = (New-Item -Path (Join-Path $destination 'deployment') -ItemType Directory).FullName
$historySql = @'
SELECT format(
  'SELECT %L, version, checksum, success FROM %I.flyway_schema_history ORDER BY installed_rank;',
  schemaname, schemaname
)
FROM pg_tables WHERE tablename = 'flyway_schema_history' ORDER BY schemaname
\gexec
'@
$startedAt = [DateTimeOffset]::UtcNow
Write-Host "Backing up daily PostgreSQL and deployment metadata to $destination"

$metadata = foreach ($database in $databases | Sort-Object Name) {
  $name = @($database.Config.Env | Where-Object { $_ -clike 'POSTGRES_DB=*' })
  $mount = @($database.Mounts | Where-Object Destination -ceq '/var/lib/postgresql/data')
  if ($name.Count -ne 1 -or $name[0] -cnotmatch '^POSTGRES_DB=[a-z_]+$' -or
      $mount.Count -ne 1 -or $mount[0].Type -cne 'volume' -or
      !$mount[0].Name.StartsWith("${project}_", [StringComparison]::Ordinal)) {
    throw "Unexpected PostgreSQL settings for $($database.Name)"
  }
  $db = $name[0].Substring('POSTGRES_DB='.Length)
  $snapshotStart = [DateTimeOffset]::UtcNow
  $dump = Join-Path $archives "$db.dump"
  $roles = Join-Path $archives "$db.globals.sql"
  Save-DockerOutput $dump @(
    'exec', $database.Id, 'sh', '-ec',
    'exec pg_dump -U "${POSTGRES_USER:-postgres}" --format=custom --create --dbname="$1"', 'sh', $db
  )
  $snapshotEnd = [DateTimeOffset]::UtcNow
  Save-DockerOutput $roles @(
    'exec', $database.Id, 'sh', '-ec',
    'exec pg_dumpall -U "${POSTGRES_USER:-postgres}" --globals-only --quote-all-identifiers'
  )
  $psql = @(
    'exec', '-i', $database.Id, 'sh', '-ec',
    'exec psql -XqAt -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-postgres}" --dbname="$1"', 'sh', $db
  )
  $history = $historySql | docker @psql
  if ($LASTEXITCODE -ne 0 -or (Get-Item -LiteralPath $dump).Length -lt 1024 -or
      (Get-Item -LiteralPath $roles).Length -lt 128) {
    throw "Incomplete backup for $db"
  }
  Write-Host "Archived $db"
  [ordered]@{
    database = $db
    service = $database.Config.Labels.'com.docker.compose.service'
    image = $database.Image
    volume = $mount[0].Name
    startedAt = $snapshotStart
    completedAt = $snapshotEnd
    flywayHistory = @($history)
  }
}

$broker = $brokers[0]
$kafkaStartedAt = [DateTimeOffset]::UtcNow
Save-DockerOutput (Join-Path $deployment 'kafka-consumer-offsets.txt') @(
  'exec', $broker.Id, '/opt/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server', 'kafka-broker:9092',
  '--all-groups', '--describe'
)
foreach ($entry in @(@('earliest', '-2'), @('latest', '-1'))) {
  Save-DockerOutput (Join-Path $deployment "kafka-partitions-$($entry[0]).txt") @(
    'exec', $broker.Id, '/opt/kafka/bin/kafka-get-offsets.sh', '--bootstrap-server', 'kafka-broker:9092',
    '--time', $entry[1]
  )
}
$kafkaCompletedAt = [DateTimeOffset]::UtcNow

$ids = @($containers.Id)
Save-DockerOutput (Join-Path $deployment 'running-containers.json') (@('inspect', '--type', 'container') + $ids)
$runningIds = @((Get-RunningProject).Id)
if (Compare-Object $ids $runningIds) {
  throw 'Daily containers changed during the backup. The current backup is incomplete.'
}

$gitHead = git -C $workspace rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'Could not identify the source revision' }
$archivePath = Join-Path $deployment 'compose-source.zip'
git -C $workspace archive --format=zip "--output=$archivePath" $gitHead -- compose.yaml .env.example infrastructure
if ($LASTEXITCODE -ne 0) { throw 'Could not archive the deployment source' }
Copy-Item -LiteralPath (Join-Path $workspace '.env') -Destination (Join-Path $deployment 'project.env')
$info = [ordered]@{
  project = $project
  revision = $gitHead
  startedAt = $startedAt
  completedAt = [DateTimeOffset]::UtcNow
  databases = @($metadata)
  kafka = @{
    startedAt = $kafkaStartedAt
    completedAt = $kafkaCompletedAt
    image = $broker.Image
    dataVolumes = @($broker.Mounts | Where-Object Type -ceq 'volume' | ForEach-Object Name)
  }
  services = @($containers | ForEach-Object {
    @{
      service = $_.Config.Labels.'com.docker.compose.service'
      image = $_.Config.Image
      digest = $_.Image
    }
  } | Sort-Object { $_.service })
}
$info | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $destination 'recovery-manifest.json')

$hashes = Get-ChildItem -LiteralPath $destination -Recurse -File | Sort-Object FullName | ForEach-Object {
  $relative = [IO.Path]::GetRelativePath($destination, $_.FullName).Replace('\', '/')
  '{0}  {1}' -f (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $relative
}
$hashes | Set-Content -LiteralPath (Join-Path $destination 'SHA256SUMS.txt')
Write-Host "Backup complete: $($metadata.Count) databases and $($ids.Count) services"
