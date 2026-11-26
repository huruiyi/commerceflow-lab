import { execFileSync } from 'node:child_process';
import { transferLedger } from '../lib/mysql-postgresql-ledger.mjs';

const [project, direction, manifestPath] = process.argv.slice(2);
if (!/^fairy-commerceflow-invoice-pg-[a-f0-9]{12}$/.test(project)
    || !['ToPostgreSQL', 'ToMySQL'].includes(direction) || !manifestPath) {
  throw new Error('Invalid isolated invoice transfer scope.');
}

function running(service) {
  return execFileSync('docker', ['ps', '-q', '--filter', 'label=com.docker.compose.project=' + project,
    '--filter', 'label=com.docker.compose.service=' + service], { encoding: 'utf8' }).trim();
}

function database(service, volume, path) {
  const id = running(service);
  if (!/^[a-f0-9]{12}$/.test(id)) throw new Error('Expected a single database for ' + service);
  const mounts = JSON.parse(execFileSync('docker', ['inspect', '--format', '{{json .Mounts}}', id], { encoding: 'utf8' }));
  if (!mounts.some(mount => mount.Name === project + '_' + volume && mount.Destination === path)) {
    throw new Error('Unexpected data volume for ' + service);
  }
  return id;
}

if (running('invoice-simulator-service')) throw new Error('Stop the invoice simulator before copying.');
const forward = direction === 'ToPostgreSQL';
transferLedger({
  direction, manifestPath, mysqlDatabase: 'invoice_platform', pgDatabase: 'invoice_platform', schema: 'public',
  mysqlContainer: database(forward ? 'invoice-db' : 'invoice-db-rollback',
    forward ? 'invoice-mysql-data' : 'invoice-rollback-data', '/var/lib/mysql'),
  pgContainer: database('invoice-db-postgresql', 'invoice-postgresql-data', '/var/lib/postgresql/data'),
  tableCount: 3, tableNames: ['invoice_receipts', 'invoice_requests', 'invoice_tickets'],
});
