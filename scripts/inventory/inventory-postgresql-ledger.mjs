import { execFileSync } from 'node:child_process';
import { transferLedger } from '../lib/mysql-postgresql-ledger.mjs';

const [project, direction, manifestPath] = process.argv.slice(2);
if (!/^fairy-commerceflow-inventory-pg-[a-f0-9]{12}$/.test(project)
    || !['ToPostgreSQL', 'ToMySQL'].includes(direction) || !manifestPath) {
  throw new Error('Invalid isolated inventory transfer scope.');
}

function running(service) {
  return execFileSync('docker', ['ps', '-q', '--filter', 'label=com.docker.compose.project=' + project,
    '--filter', 'label=com.docker.compose.service=' + service], { encoding: 'utf8' }).trim();
}

function database(service, directory) {
  const id = running(service);
  if (!/^[a-f0-9]{12}$/.test(id)) throw new Error('Expected one isolated database for ' + service);
  const mounts = JSON.parse(execFileSync('docker', ['inspect', '--format', '{{json .Mounts}}', id], { encoding: 'utf8' }));
  if (!mounts.some(mount => mount.Name === project + '_' + service + '-data' && mount.Destination === directory)) {
    throw new Error('Unexpected data volume for ' + service);
  }
  return id;
}

if (running('inventory')) throw new Error('Stop the isolated inventory writer before copying.');

const tables = ['exchange_replacement_reservations', 'inventory_catalog_event_inbox', 'inventory_command_inbox',
  'inventory_delivered_reconciliation', 'inventory_delivered_reconciliation_lines', 'inventory_line_credits',
  'inventory_migration_state', 'inventory_movements', 'inventory_outbound_documents', 'inventory_outbound_event_outbox',
  'inventory_outbound_lines', 'inventory_reservations', 'inventory_stocktake_orders', 'inventory_stocktake_reconciliation',
  'inventory_warehouse_stock', 'inventory_warehouses', 'product_inventory'];
transferLedger({
  direction, manifestPath, mysqlDatabase: 'inventory', pgDatabase: 'inventory', schema: 'public',
  mysqlContainer: database(direction === 'ToPostgreSQL' ? 'original' : 'rollback', '/var/lib/mysql'),
  pgContainer: database('postgres', '/var/lib/postgresql/data'),
  tableCount: tables.length, tableNames: tables,
});
