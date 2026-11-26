import { execFileSync } from 'node:child_process';
import { transferLedger } from '../lib/mysql-postgresql-ledger.mjs';

const [project, direction, manifestPath] = process.argv.slice(2);
const daily = project === 'fairy-commerceflow';
if ((!daily && !/^fairy-commerceflow-inventory-pg-[a-f0-9]{12}$/.test(project))
    || !['ToPostgreSQL', 'ToMySQL'].includes(direction) || !manifestPath) {
  throw new Error('Invalid isolated inventory transfer scope.');
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

if (running(daily ? 'inventory-service' : 'inventory')) throw new Error('Stop the inventory writer before copying.');

const tables = ['exchange_replacement_reservations', 'inventory_catalog_event_inbox', 'inventory_command_inbox',
  'inventory_delivered_reconciliation', 'inventory_delivered_reconciliation_lines', 'inventory_line_credits',
  'inventory_migration_state', 'inventory_movements', 'inventory_outbound_documents', 'inventory_outbound_event_outbox',
  'inventory_outbound_lines', 'inventory_reservations', 'inventory_stocktake_orders', 'inventory_stocktake_reconciliation',
  'inventory_warehouse_stock', 'inventory_warehouses', 'product_inventory'];
const forward = direction === 'ToPostgreSQL';
const original = database(daily ? 'inventory-db' : 'original', '/var/lib/mysql', daily ? 'inventory-db-data' : 'original-data');
const pgContainer = database('inventory-db-postgresql', '/var/lib/postgresql/data', 'inventory-postgresql-data');
const mysqlContainer = forward ? original : database(daily ? 'inventory-db-rollback' : 'rollback',
  '/var/lib/mysql', 'inventory-rollback-data');

function mysql(container, sql) {
  return execFileSync('docker', ['exec', container, 'sh', '-ec',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB inventory -e "$1"', 'sh', sql], { encoding: 'utf8' }).trim();
}

function pg(sql) {
  return execFileSync('docker', ['exec', pgContainer, 'psql', '-U', 'postgres', '-d', 'inventory',
    '-XAtq', '-v', 'ON_ERROR_STOP=1', '-c', sql], { encoding: 'utf8' }).trim();
}

const frozen = mysql(original, `SELECT @@GLOBAL.super_read_only AND EXISTS (
  SELECT 1 FROM performance_schema.persisted_variables WHERE variable_name='super_read_only' AND variable_value='ON')`);
if (frozen !== '1') throw new Error('Original inventory MySQL must be persistently read-only.');
const fenced = pg(`SELECT NOT rolcanlogin AND NOT EXISTS (
  SELECT FROM pg_stat_activity WHERE usename='inventory_runtime') FROM pg_roles WHERE rolname='inventory_runtime'`);
if (fenced !== 't') throw new Error('Disable the PostgreSQL inventory runtime login and close its sessions before copying.');

const counts = 'SELECT ' + tables.map(table => '(SELECT count(*) FROM ' + table + ')').join('+');
// Retry only compares a populated destination, preserving the committed ledger.
const action = (forward ? pg(counts) : mysql(mysqlContainer, counts)) === '0' ? direction : 'Compare';
transferLedger({
  direction: action, manifestPath, mysqlDatabase: 'inventory', pgDatabase: 'inventory', schema: 'public',
  mysqlContainer, pgContainer,
  tableCount: tables.length, tableNames: tables,
});
