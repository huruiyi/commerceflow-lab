import { execFileSync } from 'node:child_process';
import { transferLedger } from '../lib/mysql-postgresql-ledger.mjs';

const [project, direction, manifestPath] = process.argv.slice(2);
if (!/^fairy-commerceflow-payment-simulator-pg-[a-f0-9]{12}$/.test(project)
    || !['ToPostgreSQL', 'ToMySQL', 'Compare'].includes(direction) || !manifestPath) {
  throw new Error('Expected an isolated payment simulator project, direction and manifest path.');
}

const running = (service) => execFileSync('docker', ['ps', '-q',
  '--filter', 'label=com.docker.compose.project=' + project,
  '--filter', 'label=com.docker.compose.service=' + service], { encoding: 'utf8' }).trim();
if (running('simulator')) throw new Error('Stop the isolated payment simulator before copying.');

function database(service, volume, directory) {
  const id = running(service);
  if (!/^[a-f0-9]{12}$/.test(id)) throw new Error('Expected one isolated ' + service + ' container.');
  const mounts = JSON.parse(execFileSync('docker', ['inspect', '--format', '{{json .Mounts}}', id], { encoding: 'utf8' }));
  if (!mounts.some(mount => mount.Name === project + '_' + volume && mount.Destination === directory)) {
    throw new Error('Unexpected data volume for ' + service);
  }
  return id;
}

const mysqlService = direction === 'ToMySQL' ? 'rollback' : 'original';
transferLedger({
  direction, manifestPath, schema: 'public', tableCount: 17,
  mysqlContainer: database(mysqlService, mysqlService + '-data', '/var/lib/mysql'),
  mysqlDatabase: 'payment_platform', pgContainer: database('postgres', 'postgres-data', '/var/lib/postgresql/data'),
  pgDatabase: 'payment_platform',
});
