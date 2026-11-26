import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';

const [project, direction] = process.argv.slice(2);
const release = ['release-forward', 'release-reverse'].includes(direction);
const isolated = /^fairy-commerceflow-acceptance-warehouse-[a-f0-9]{12}$/.test(project);
if ((!isolated && !(release && project === 'fairy-commerceflow')) ||
    (!release && !['forward', 'reverse'].includes(direction))) throw Error('Invalid warehouse migration scope');

const tables = {
  warehouse_outbounds: ['outbound_id', 'order_id', 'merchant_id', 'warehouse_id', 'whole_order', 'planned',
    'actual', 'status', 'created_at', 'decided_at'],
  warehouse_returns: ['return_id', 'outbound_id', 'order_id', 'order_line_id', 'warehouse_id', 'tracking_number',
    'expected_quantity', 'received_quantity', 'status', 'created_at', 'received_at', 'inspected_at', 'supplement_id',
    'qualified_quantity', 'supplement_note', 'supplement_at'],
};
function docker(args, input) {
  const result = spawnSync('docker', args, {input, encoding: 'utf8', maxBuffer: 128 * 1024 * 1024});
  if (result.error) throw result.error;
  if (result.status !== 0) throw Error('Docker or warehouse SQL failed during ' + direction + ' (exit ' + result.status + ')');
  return result.stdout.trimEnd();
}
function running(service) {
  return docker(['ps', '-q', '--filter', 'label=com.docker.compose.project=' + project,
    '--filter', 'label=com.docker.compose.service=' + service, '--filter', 'label=com.docker.compose.oneoff=False']);
}
function database(service) {
  const id = running(service);
  if (!/^[a-f0-9]{12}$/.test(id)) throw Error('Expected one running ' + service);
  const pg = service.endsWith('-postgresql');
  if (release) {
    const volume = pg ? 'warehouse-postgresql-data' : service.endsWith('-rollback') ?
      'warehouse-rollback-data' : 'warehouse-simulator-db-data';
    const path = pg ? '/var/lib/postgresql/data' : '/var/lib/mysql';
    const mounts = JSON.parse(docker(['inspect', '--format', '{{json .Mounts}}', id]));
    if (!mounts.some(m => m.Destination === path && m.Name === project + '_' + volume)) {
      throw Error('Unexpected volume for ' + service);
    }
  }
  const client = pg ? ['psql','-U','postgres','-d','warehouse_platform','-XAtq','-v','ON_ERROR_STOP=1'] :
    ['sh','-ec','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 -BNr warehouse_platform'];
  return {pg, query: sql => docker(['exec','-i',id,...client],
    (pg && release ? 'SET search_path TO warehouse_platform;\n' : '') +
    (pg ? "SET TIME ZONE 'UTC';\n" : "SET time_zone='+00:00';\n") + sql)};
}
function field(name, pg) {
  if (name.endsWith('_at')) return pg ? "to_char("+name+",'YYYY-MM-DD HH24:MI:SS.US')" :
    "DATE_FORMAT("+name+",'%Y-%m-%d %H:%i:%s.%f')";
  if (pg && name === 'whole_order') return 'CAST(CAST(whole_order AS integer) AS text)';
  return 'CAST(' + name + ' AS ' + (pg ? 'text' : 'CHAR CHARACTER SET utf8mb4') + ')';
}
function normalizeJson(text) {
  // Line and product IDs are signed 64-bit integers. Preserve their original numeric tokens.
  return JSON.stringify(JSON.parse(text, (_, value, context) => {
    if (typeof value === 'number') return JSON.rawJSON(context.source);
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      return Object.fromEntries(Object.keys(value).sort().map(key => [key, value[key]]));
    }
    return value;
  }));
}
function snapshot(db) {
  const schema = db.pg ? 'current_schema()' : 'DATABASE()';
  const layout = db.query("SELECT CONCAT(table_name,'.',column_name) FROM information_schema.columns WHERE table_schema=" +
    schema + " AND table_name<>'flyway_schema_history' ORDER BY table_name,ordinal_position;");
  const expected = Object.entries(tables).flatMap(([table, names]) => names.map(n => table + '.' + n));
  if (layout !== expected.join('\n')) throw Error('Warehouse layout differs from V4');
  const commands = [db.pg ? 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;' : 'START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;'];
  for (const [table, names] of Object.entries(tables)) {
    commands.push('SELECT ' + (db.pg ? 'json_build_array' : 'JSON_ARRAY') + "('" + table + "'," +
      names.map(n => field(n, db.pg)).join(',') + ') FROM ' + table + ' ORDER BY ' + names[0] + ';');
  }
  commands.push('COMMIT;');
  const data = db.query(commands.join('\n'));
  if (!data) return [];
  return data.split('\n').map(line => {
    const [table, ...values] = JSON.parse(line);
    return [table, ...values.map((v,i) => v !== null && ['planned','actual'].includes(tables[table][i]) ? normalizeJson(v) : v)];
  });
}
function literal(pg, name, value) {
  if (value === null) return 'NULL';
  const hex = Buffer.from(value).toString('hex');
  if (!pg) return "CONVERT(X'" + hex + "' USING utf8mb4)";
  const text = "convert_from(decode('" + hex + "','hex'),'UTF8')";
  if (name === 'whole_order') {
    if (value !== '1' && value !== '0') throw Error('Invalid MySQL whole_order flag');
    return value === '1' ? 'TRUE' : 'FALSE';
  }
  if (name.endsWith('_at')) return 'CAST(' + text + ' AS timestamptz)';
  if (['planned','actual'].includes(name)) return 'CAST(' + text + ' AS jsonb)';
  if (['order_id','merchant_id','warehouse_id','order_line_id'].includes(name)) return 'CAST(' + text + ' AS bigint)';
  if (name.endsWith('_quantity')) return 'CAST(' + text + ' AS integer)';
  return text;
}
const digest = rows => createHash('sha256').update(JSON.stringify(rows)).digest('hex');
if (running('warehouse-simulator-service')) throw Error('Stop the warehouse service before copying');
const mysql = database('warehouse-simulator-db');
const postgres = database('warehouse-simulator-db-postgresql');
const forward = direction === 'forward' || direction === 'release-forward';
if (release) {
  if (mysql.query('SELECT @@GLOBAL.super_read_only;') !== '1') throw Error('Original MySQL must be read-only');
  if (!forward && postgres.query("SELECT NOT rolcanlogin AND NOT EXISTS (SELECT FROM pg_stat_activity " +
      "WHERE usename='warehouse_runtime') FROM pg_roles WHERE rolname='warehouse_runtime'") !== 't') {
    throw Error('Disable the PostgreSQL runtime login and close its sessions before rollback');
  }
}
const source = snapshot(forward ? mysql : postgres);
if (!forward) {
  // A receipt can advance in PostgreSQL. The original plan, tracking data and creation time cannot change.
  const immutable = {
    warehouse_outbounds: ['outbound_id','order_id','merchant_id','warehouse_id','whole_order','planned','created_at'],
    warehouse_returns: ['return_id','outbound_id','order_id','order_line_id','warehouse_id','tracking_number','expected_quantity','created_at'],
  };
  function historicalRecord(row) {
    const table = row[0];
    return JSON.stringify(release ? [table, ...immutable[table].map(name => row[1 + tables[table].indexOf(name)])] : row);
  }
  const records = new Set(source.map(historicalRecord));
  if (snapshot(mysql).some(row => !records.has(historicalRecord(row)))) throw Error('Historical warehouse record missing or changed');
}
const target = forward ? postgres : database('warehouse-simulator-db-rollback');
const before = snapshot(target);
if (digest(before) !== digest(source)) {
  if (before.length) throw Error('Preserve the differing destination and use an empty database');
  const statements = ['BEGIN;'];
  for (const [table, ...values] of source) {
    const names = tables[table];
    statements.push('INSERT INTO ' + table + ' (' + names.join(',') + ') VALUES (' +
      values.map((v,i) => literal(target.pg, names[i], v)).join(',') + ');');
  }
  statements.push('COMMIT;');
  target.query(statements.join('\n'));
}
if (digest(source) !== digest(snapshot(target))) throw Error('Restored warehouse rows differ');
console.log(JSON.stringify({direction, outbounds:source.filter(r=>r[0]==='warehouse_outbounds').length,
  returns:source.filter(r=>r[0]==='warehouse_returns').length, sha256:digest(source)}));
