import { execFileSync } from 'node:child_process';
import { transferLedger } from '../lib/mysql-postgresql-ledger.mjs';

const [project, direction, manifestPath] = process.argv.slice(2);
if (!/^fairy-commerceflow-fulfillment-pg-[a-f0-9]{12}$/.test(project)
  || !['ToPostgreSQL', 'ToMySQL'].includes(direction) || !manifestPath) {
  throw new Error('Invalid isolated fulfillment transfer scope.');
}

function running(service) {
  return execFileSync('docker', ['ps', '-q', '--filter', 'label=com.docker.compose.project=' + project,
    '--filter', 'label=com.docker.compose.service=' + service], { encoding: 'utf8' }).trim();
}

function database(service, directory) {
  const id = running(service);
  if (!/^[a-f0-9]{12}$/.test(id)) throw new Error('Expected one running database for ' + service);
  const mounts = JSON.parse(execFileSync('docker', ['inspect', '--format', '{{json .Mounts}}', id], { encoding: 'utf8' }));
  if (!mounts.some(mount => mount.Name === project + '_' + service + '-data' && mount.Destination === directory)) {
    throw new Error('Unexpected data volume for ' + service);
  }
  return id;
}

if (running('fulfillment')) throw new Error('Stop the fulfillment writer before copying.');
const tables = ['fulfillment_command_ids', 'fulfillment_merchant_jobs', 'fulfillment_merchant_recoveries',
  'fulfillment_order_locks', 'fulfillment_runtime_mode', 'fulfillment_shadow_quarantine', 'fulfillment_shadow_watermark',
  'fulfillment_shipment_jobs', 'fulfillment_shipment_recoveries'];
transferLedger({
  direction, manifestPath, mysqlDatabase: 'fulfillment', pgDatabase: 'fulfillment', schema: 'public',
  mysqlContainer: database(direction === 'ToPostgreSQL' ? 'original' : 'rollback', '/var/lib/mysql'),
  pgContainer: database('postgres', '/var/lib/postgresql/data'),
  tableCount: tables.length, tableNames: tables,
});
