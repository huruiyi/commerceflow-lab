// Copy the provider tables, keeping each engine's Flyway history and the original MySQL volume.
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';

const [project, phase] = process.argv.slice(2);
const daily = 'fairy-commerceflow';
const release = ['release-forward','release-reverse'].includes(phase);
if (!/^fairy-commerceflow-messaging-isolated-[a-f0-9]{12}$/.test(project) && !(release && project === daily)) {
  throw Error('Invalid messaging migration scope');
}
const fields = {
  messaging_deliveries: ['request_id','provider_id','channel','recipient_mask','reference','fingerprint','outcome',
    'status','accepted_at','ready_at','finished_at'],
  messaging_faults: ['request_id','behavior'],
};
function docker(args, input) {
  const result = spawnSync('docker', args, {input, encoding:'utf8', maxBuffer:128*1024*1024});
  if (result.error) throw result.error;
  if (result.status !== 0) throw Error('Docker or SQL failed during ' + phase + ' (exit ' + result.status + ')');
  return result.stdout.trimEnd();
}
function db(service, scope = project) {
  const id = docker(['ps','-q','--filter','label=com.docker.compose.project='+scope,
    '--filter','label=com.docker.compose.service='+service, '--filter','label=com.docker.compose.oneoff=False']);
  if (!/^[a-f0-9]{12}$/.test(id)) throw Error('Expected one running '+scope+'/'+service);
  return {id, pg:service.endsWith('-postgresql'), scope};
}
function sql(conn, statement) {
  const dbName = 'messaging_platform';
  const args = conn.pg ? ['psql','-U',release ? 'postgres' : 'messaging','-d',dbName,'-XAtq','-v','ON_ERROR_STOP=1'] :
    ['sh','-ec','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 -BNr "$1"','sh',dbName];
  const path = release && conn.pg ? 'SET search_path TO messaging_platform;\n' : '';
  return docker(['exec','-i',conn.id,...args], path + (conn.pg ? "SET TIME ZONE 'UTC';\n" : "SET time_zone='+00:00';\n")+statement);
}
function snapshot(conn) {
  const schema = conn.pg ? 'current_schema()' : 'DATABASE()';
  const layout = sql(conn, "SELECT CONCAT(table_name,'.',column_name) FROM information_schema.columns " +
    "WHERE table_schema=" + schema + " AND table_name <> 'flyway_schema_history' ORDER BY table_name,ordinal_position;");
  const expectedLayout = Object.entries(fields).flatMap(([table, columns]) => columns.map(column => table + '.' + column));
  if (layout !== expectedLayout.join('\n')) throw Error('Unexpected messaging table layout');
  const commands = [conn.pg ? 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;' : 'START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;'];
  for (const [table, columns] of Object.entries(fields)) {
    const select = columns.map(c => !c.endsWith('_at') ? c : conn.pg ?
      "to_char("+c+",'YYYY-MM-DD HH24:MI:SS.US')" : "DATE_FORMAT("+c+",'%Y-%m-%d %H:%i:%s.%f')");
    commands.push("SELECT "+(conn.pg?'json_build_array':'JSON_ARRAY')+"('"+table+"',"+select.join(',')+
      ') FROM '+table+' ORDER BY request_id;');
  }
  commands.push('COMMIT;');
  const output = sql(conn,commands.join('\n'));
  return output ? output.split('\n').map(row=>JSON.parse(row)) : [];
}
function value(conn, val) {
  if (val === null) return 'NULL';
  const hex = Buffer.from(val,'utf8').toString('hex');
  return conn.pg ? "convert_from(decode('"+hex+"','hex'),'UTF8')" : "CONVERT(X'"+hex+"' USING utf8mb4)";
}
const links = {
  seed:['messaging-simulator-db','messaging-simulator-db'],
  forward:['messaging-simulator-db','messaging-simulator-db-postgresql'],
  reverse:['messaging-simulator-db-postgresql','rollback-db'],
  'release-forward':['messaging-simulator-db','messaging-simulator-db-postgresql'],
  'release-reverse':['messaging-simulator-db-postgresql','messaging-simulator-db-rollback'],
};
if (!Object.hasOwn(links,phase)) throw Error('Unknown migration phase');
const [source,target] = links[phase];
const from = db(source,phase === 'seed' ? daily : project);
const to = db(target);
if (release) {
  const provider = docker(['ps','-q','--filter','label=com.docker.compose.project='+project,
    '--filter','label=com.docker.compose.service=messaging-simulator-service']);
  if (provider) throw Error('Stop the provider before the final copy');
  const frozen = from.pg ? sql(from, "SELECT NOT rolcanlogin AND NOT EXISTS (SELECT FROM pg_stat_activity WHERE usename='messaging_runtime') FROM pg_roles WHERE rolname='messaging_runtime'") :
    sql(from, 'SELECT @@GLOBAL.super_read_only');
  if (frozen !== (from.pg ? 't' : '1')) throw Error('Source ledger is still writable by the provider');
}
const sourceRows = snapshot(from);
const digest = rows => createHash('sha256').update(JSON.stringify(rows)).digest('hex');
const priorRows = snapshot(to);
const identical = digest(priorRows) === digest(sourceRows);
if (priorRows.length && !identical) throw Error('Destination contains different rows. Preserve it and use an empty destination.');
const statements = ['BEGIN;'];
for (const [table,...row] of identical ? [] : sourceRows) {
  const values = fields[table].map((c,i) => {
    const literal = value(to,row[i]);
    return to.pg && c.endsWith('_at') ? 'CAST('+literal+' AS timestamptz)' : literal;
  });
  statements.push('INSERT INTO '+table+' ('+fields[table].join(',')+') VALUES ('+values.join(',')+');');
}
statements.push('COMMIT;');
sql(to,statements.join('\n'));
if (digest(snapshot(to)) !== digest(sourceRows)) throw Error('Copied rows differ');
console.log(JSON.stringify({phase, deliveries:sourceRows.filter(r=>r[0]==='messaging_deliveries').length,
  faults:sourceRows.filter(r=>r[0]==='messaging_faults').length,sha256:digest(sourceRows)}));
