import { execFileSync } from 'node:child_process';
import { transferLedger } from '../lib/mysql-postgresql-ledger.mjs';

const [project, direction, manifestPath] = process.argv.slice(2);
if (!/^fairy-commerceflow-order-pg-[a-f0-9]{12}$/.test(project)
    || !['ToPostgreSQL', 'ToMySQL'].includes(direction) || !manifestPath) {
  throw new Error('Invalid isolated order transfer scope.');
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

if (running('order')) throw new Error('Stop the isolated order application before copying.');
const tables = ['order_command_outbox', 'order_events', 'order_internal_request_replay', 'order_items',
  'order_line_refund_admissions', 'order_package_allocations', 'order_package_lines', 'order_process_managers',
  'order_remote_message_inbox', 'order_result_inbox', 'order_shadow_audit', 'order_shadow_orphan_quarantine',
  'order_shadow_watermark', 'order_shipping_addresses', 'order_warehouse_amounts', 'shop_orders'];
transferLedger({
  direction, manifestPath, mysqlDatabase: 'orders', pgDatabase: 'orders', schema: 'public',
  mysqlContainer: database(direction === 'ToPostgreSQL' ? 'original' : 'rollback', '/var/lib/mysql'),
  pgContainer: database('postgres', '/var/lib/postgresql/data'), tableCount: tables.length, tableNames: tables,
});
