import { execFileSync } from 'node:child_process';
import { transferLedger } from '../lib/mysql-postgresql-ledger.mjs';

const [project, direction, manifestPath] = process.argv.slice(2);
const daily = project === 'fairy-commerceflow';
if ((!daily && !/^fairy-commerceflow-fulfillment-pg-[a-f0-9]{12}$/.test(project))
  || !['ToPostgreSQL', 'ToMySQL'].includes(direction) || !manifestPath) {
  throw new Error('Invalid isolated fulfillment transfer scope.');
}

function running(service) {
  return execFileSync('docker', ['ps', '-q', '--filter', 'label=com.docker.compose.project=' + project,
    '--filter', 'label=com.docker.compose.service=' + service], { encoding: 'utf8' }).trim();
}

function database(service, directory, volume) {
  const id = running(service);
  if (!/^[a-f0-9]{12}$/.test(id)) throw new Error('Expected one running database for ' + service);
  const mounts = JSON.parse(execFileSync('docker', ['inspect', '--format', '{{json .Mounts}}', id], { encoding: 'utf8' }));
  if (!mounts.some(mount => mount.Name === project + '_' + volume && mount.Destination === directory)) {
    throw new Error('Unexpected data volume for ' + service);
  }
  return id;
}

if (running(daily ? 'fulfillment-service' : 'fulfillment')) throw new Error('Stop the fulfillment writer before copying.');
const tables = ['fulfillment_command_ids', 'fulfillment_merchant_jobs', 'fulfillment_merchant_recoveries',
  'fulfillment_order_locks', 'fulfillment_runtime_mode', 'fulfillment_shadow_quarantine', 'fulfillment_shadow_watermark',
  'fulfillment_shipment_jobs', 'fulfillment_shipment_recoveries'];
const forward = direction === 'ToPostgreSQL';
const original = database(daily ? 'fulfillment-db' : 'original', '/var/lib/mysql', daily ? 'fulfillment-db-data' : 'original-data');
const pgContainer = database('fulfillment-db-postgresql', '/var/lib/postgresql/data', 'fulfillment-postgresql-data');
const mysqlContainer = forward ? original : database(daily ? 'fulfillment-db-rollback' : 'rollback',
  '/var/lib/mysql', 'fulfillment-rollback-data');

function mysql(container, sql) {
  return execFileSync('docker', ['exec', container, 'sh', '-ec',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB fulfillment -e "$1"', 'sh', sql], { encoding: 'utf8' }).trim();
}

function pg(sql) {
  return execFileSync('docker', ['exec', pgContainer, 'psql', '-U', 'postgres', '-d', 'fulfillment',
    '-XAtq', '-v', 'ON_ERROR_STOP=1', '-c', sql], { encoding: 'utf8' }).trim();
}

const frozen = mysql(original, `SELECT @@GLOBAL.super_read_only AND EXISTS (
  SELECT 1 FROM performance_schema.persisted_variables WHERE variable_name='super_read_only' AND variable_value='ON')`);
if (frozen !== '1') throw new Error('Original fulfillment MySQL must be persistently read-only.');
const fenced = pg(`SELECT NOT rolcanlogin AND NOT EXISTS (
  SELECT FROM pg_stat_activity WHERE usename='fulfillment_runtime') FROM pg_roles WHERE rolname='fulfillment_runtime'`);
if (fenced !== 't') throw new Error('Disable the PostgreSQL runtime login and close its sessions before copying.');

const counts = 'SELECT ' + tables.map(table => '(SELECT count(*) FROM ' + table + ')').join('+');
// A populated destination is only eligible for parity verification.
const action = (forward ? pg(counts) : mysql(mysqlContainer, counts)) === '0' ? direction : 'Compare';
transferLedger({
  direction: action, manifestPath, mysqlDatabase: 'fulfillment', pgDatabase: 'fulfillment', schema: 'public',
  mysqlContainer, pgContainer,
  tableCount: tables.length, tableNames: tables,
});
