function Read-MessagingLedger([string]$Project, [string]$Query) {
  $filters = @('--filter', "label=com.docker.compose.project=$Project", '--filter', 'label=com.docker.compose.oneoff=False')
  $app = @(docker ps -q @filters --filter 'label=com.docker.compose.service=messaging-simulator-service')
  if ($LASTEXITCODE -or $app.Count -ne 1) { throw "Cannot identify the messaging provider in $Project" }
  $url = docker inspect $app[0] --format '{{range .Config.Env}}{{println .}}{{end}}' |
    Where-Object { $_ -like 'MESSAGING_DATABASE_URL=*' }
  if ($url -notmatch '^MESSAGING_DATABASE_URL=jdbc:postgresql://(messaging-simulator-db-postgresql):5432/messaging_platform(?:\?|$)') {
    throw 'Unexpected messaging ledger binding'
  }
  $db = @(docker ps -q @filters --filter "label=com.docker.compose.service=$($Matches[1])")
  if ($LASTEXITCODE -or $db.Count -ne 1) { throw "Missing messaging database in $Project" }
  $result = docker exec -e 'PGOPTIONS=-c default_transaction_read_only=on -c search_path=messaging_platform,public' $db[0] sh -ec 'exec psql -U "${POSTGRES_USER:-postgres}" -d messaging_platform -XAtq -v ON_ERROR_STOP=1 -c "$1"' sh $Query
  if ($LASTEXITCODE) { throw "Messaging ledger query failed in $Project" }
  return $result
}
