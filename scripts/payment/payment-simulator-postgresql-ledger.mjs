import { execFileSync } from 'node:child_process';
import { transferLedger } from '../lib/mysql-postgresql-ledger.mjs';

const [project, direction, manifestPath] = process.argv.slice(2);
if (!/^fairy-commerceflow-payment-simulator-pg-[a-f0-9]{12}$/.test(project)
    || !['ToPostgreSQL', 'ToMySQL', 'Compare', 'CompareRollback'].includes(direction) || !manifestPath) {
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

const mysqlService = ['ToMySQL', 'CompareRollback'].includes(direction) ? 'rollback' : 'original';
const mysqlVolume = mysqlService === 'rollback' ? 'payment-simulator-rollback-data' : 'original-data';
const pgContainer = database('postgres', 'payment-simulator-postgresql-data', '/var/lib/postgresql/data');
const fenced = execFileSync('docker', ['exec', pgContainer, 'psql', '-U', 'postgres', '-d', 'payment_platform',
  '-XAtq', '-v', 'ON_ERROR_STOP=1', '-c',
  "SELECT NOT rolcanlogin AND NOT EXISTS (SELECT FROM pg_stat_activity WHERE usename='payment_simulator_runtime') "
  + "FROM pg_roles WHERE rolname='payment_simulator_runtime'"], { encoding: 'utf8' }).trim();
if (fenced !== 't') throw new Error('Fence the isolated PostgreSQL runtime account before copying.');

transferLedger({
  direction: direction === 'CompareRollback' ? 'Compare' : direction, manifestPath, schema: 'public', tableCount: 17,
  mysqlContainer: database(mysqlService, mysqlVolume, '/var/lib/mysql'),
  mysqlDatabase: 'payment_platform', pgContainer, pgDatabase: 'payment_platform',
});
