import { execFileSync } from 'node:child_process';
import { transferLedger } from '../lib/mysql-postgresql-ledger.mjs';

const [project, direction, manifestPath] = process.argv.slice(2);
const daily = project === 'fairy-commerceflow';
if ((!daily && !/^fairy-commerceflow-payment-simulator-pg-[a-f0-9]{12}$/.test(project))
    || !['ToPostgreSQL', 'ToMySQL', 'Compare', 'CompareRollback'].includes(direction) || !manifestPath) {
  throw new Error('Expected a payment simulator project, direction and manifest path.');
}

const running = (service) => execFileSync('docker', ['ps', '-q',
  '--filter', 'label=com.docker.compose.project=' + project,
  '--filter', 'label=com.docker.compose.service=' + service], { encoding: 'utf8' }).trim();
if (running(daily ? 'payment-simulator-service' : 'simulator')) {
  throw new Error('Stop the payment simulator application before copying.');
}

function database(service, volume, directory) {
  const id = running(service);
  if (!/^[a-f0-9]{12}$/.test(id)) throw new Error('Expected one isolated ' + service + ' container.');
  const mounts = JSON.parse(execFileSync('docker', ['inspect', '--format', '{{json .Mounts}}', id], { encoding: 'utf8' }));
  if (!mounts.some(mount => mount.Name === project + '_' + volume && mount.Destination === directory)) {
    throw new Error('Unexpected data volume for ' + service);
  }
  return id;
}

const sourceService = daily ? 'payment-simulator-db' : 'original';
const sourceVolume = daily ? 'payment-simulator-db-data' : 'original-data';
const source = database(sourceService, sourceVolume, '/var/lib/mysql');
if (daily) {
  const frozen = execFileSync('docker', ['exec', source, 'sh', '-ec',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB -e "$1"', 'sh',
    "SELECT @@GLOBAL.super_read_only AND EXISTS (SELECT 1 FROM performance_schema.persisted_variables "
    + "WHERE variable_name='super_read_only' AND variable_value='ON')"], { encoding: 'utf8' }).trim();
  if (frozen !== '1') throw new Error('Freeze the original daily MySQL persistently before copying.');
}
const rollback = ['ToMySQL', 'CompareRollback'].includes(direction);
const mysqlContainer = rollback
  ? database(daily ? 'payment-simulator-db-rollback' : 'rollback', 'payment-simulator-rollback-data', '/var/lib/mysql')
  : source;
const pgContainer = database(daily ? 'payment-simulator-db-postgresql' : 'postgres',
  'payment-simulator-postgresql-data', '/var/lib/postgresql/data');
const fenced = execFileSync('docker', ['exec', pgContainer, 'psql', '-U', 'postgres', '-d', 'payment_platform',
  '-XAtq', '-v', 'ON_ERROR_STOP=1', '-c',
  "SELECT NOT rolcanlogin AND NOT EXISTS (SELECT FROM pg_stat_activity WHERE usename='payment_simulator_runtime') "
  + "FROM pg_roles WHERE rolname='payment_simulator_runtime'"], { encoding: 'utf8' }).trim();
if (fenced !== 't') throw new Error('Disable the PostgreSQL runtime login and close its sessions before copying.');

transferLedger({
  direction: direction === 'CompareRollback' ? 'Compare' : direction, manifestPath, schema: 'public', tableCount: 17,
  mysqlContainer,
  mysqlDatabase: 'payment_platform', pgContainer, pgDatabase: 'payment_platform',
});
