import { execFileSync } from 'node:child_process';
import { transferLedger } from '../lib/mysql-postgresql-ledger.mjs';

const [project, direction, manifestPath] = process.argv.slice(2);
const daily = project === 'fairy-commerceflow';
if ((!daily && !/^fairy-commerceflow-order-pg-[a-f0-9]{12}$/.test(project))
    || !['ToPostgreSQL', 'ToMySQL'].includes(direction) || !manifestPath) {
  throw new Error('Invalid isolated order transfer scope.');
}

function running(service) {
  return execFileSync('docker', ['ps', '-q', '--filter', 'label=com.docker.compose.project=' + project,
    '--filter', 'label=com.docker.compose.service=' + service], { encoding: 'utf8' }).trim();
}

function database(service, directory, volume) {
  const id = running(service);
  if (!/^[a-f0-9]{12}$/.test(id)) throw new Error('Expected one isolated database for ' + service);
  const mounts = JSON.parse(execFileSync('docker', ['inspect', '--format', '{{json .Mounts}}', id], { encoding: 'utf8' }));
  if (!mounts.some(mount => mount.Name === project + '_' + volume && mount.Destination === directory)) {
    throw new Error('Unexpected data volume for ' + service);
  }
  return id;
}

if (running(daily ? 'order-service' : 'order')) throw new Error('Stop the order application before copying.');
const tables = ['order_command_outbox', 'order_events', 'order_internal_request_replay', 'order_items',
  'order_line_refund_admissions', 'order_package_allocations', 'order_package_lines', 'order_process_managers',
  'order_remote_message_inbox', 'order_result_inbox', 'order_shadow_audit', 'order_shadow_orphan_quarantine',
  'order_shadow_watermark', 'order_shipping_addresses', 'order_warehouse_amounts', 'shop_orders'];
const forward = direction === 'ToPostgreSQL';
const original = database(daily ? 'order-db' : 'original', '/var/lib/mysql', daily ? 'order-db-data' : 'original-data');
const pgContainer = database('order-db-postgresql', '/var/lib/postgresql/data', 'order-postgresql-data');
const mysqlContainer = forward ? original : database(daily ? 'order-db-rollback' : 'rollback',
  '/var/lib/mysql', 'order-rollback-data');

function mysql(container, sql) {
  return execFileSync('docker', ['exec', container, 'sh', '-ec',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB orders -e "$1"', 'sh', sql], { encoding: 'utf8' }).trim();
}

function pg(sql) {
  return execFileSync('docker', ['exec', pgContainer, 'psql', '-U', 'postgres', '-d', 'orders',
    '-XAtq', '-v', 'ON_ERROR_STOP=1', '-c', sql], { encoding: 'utf8' }).trim();
}

const frozen = mysql(original, `SELECT @@GLOBAL.super_read_only AND EXISTS (
  SELECT 1 FROM performance_schema.persisted_variables WHERE variable_name='super_read_only' AND variable_value='ON')`);
if (frozen !== '1') throw new Error('Original order MySQL must be persistently read-only.');
const fenced = pg(`SELECT NOT rolcanlogin AND NOT EXISTS (
  SELECT FROM pg_stat_activity WHERE usename='order_runtime') FROM pg_roles WHERE rolname='order_runtime'`);
if (fenced !== 't') throw new Error('Disable the PostgreSQL order runtime login and close its sessions before copying.');

const countRows = 'SELECT ' + tables.map(table => '(SELECT count(*) FROM ' + table + ')').join('+');
// A populated destination is only eligible for parity verification.
const action = (forward ? pg(countRows) : mysql(mysqlContainer, countRows)) === '0' ? direction : 'Compare';
transferLedger({
  direction: action, manifestPath, mysqlDatabase: 'orders', pgDatabase: 'orders', schema: 'public',
  mysqlContainer, pgContainer, tableCount: tables.length, tableNames: tables,
});
