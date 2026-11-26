import { execFileSync } from 'node:child_process';
import { transferLedger } from '../lib/mysql-postgresql-ledger.mjs';

const [project, direction, manifestPath] = process.argv.slice(2);
const release = ['release-forward', 'release-reverse'].includes(direction);
const validScope = release
  ? project === 'fairy-commerceflow' || /^fairy-commerceflow-invoice-release-[a-f0-9]{12}$/.test(project)
  : /^fairy-commerceflow-invoice-pg-[a-f0-9]{12}$/.test(project) && ['ToPostgreSQL', 'ToMySQL'].includes(direction);
if (!validScope || !manifestPath) {
  throw new Error('Invalid invoice transfer scope.');
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
const forward = direction === 'ToPostgreSQL' || direction === 'release-forward';
const sourceVolume = release ? 'invoice-db-data' : 'invoice-mysql-data';
const original = release || forward ? database('invoice-db', sourceVolume, '/var/lib/mysql') : null;
const mysqlContainer = forward ? original : database('invoice-db-rollback', 'invoice-rollback-data', '/var/lib/mysql');
const pgContainer = database('invoice-db-postgresql', 'invoice-postgresql-data', '/var/lib/postgresql/data');
const tableNames = ['invoice_receipts', 'invoice_requests', 'invoice_tickets'];
let action = forward ? 'ToPostgreSQL' : 'ToMySQL';
if (release) {
  function mysql(container, sql) {
    return execFileSync('docker', ['exec', container, 'sh', '-ec',
      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB invoice_platform -e "$1"', 'sh', sql], { encoding: 'utf8' }).trim();
  }
  function pg(sql) {
    return execFileSync('docker', ['exec', pgContainer, 'psql', '-U', 'postgres', '-d', 'invoice_platform',
      '-XAtq', '-v', 'ON_ERROR_STOP=1', '-c', sql], { encoding: 'utf8' }).trim();
  }
  if (mysql(original, `SELECT @@GLOBAL.super_read_only AND EXISTS (
      SELECT 1 FROM performance_schema.persisted_variables WHERE variable_name='super_read_only' AND variable_value='ON')`) !== '1') {
    throw new Error('Original invoice MySQL must be persistently read-only.');
  }
  if (pg("SELECT count(*) FROM pg_stat_activity WHERE usename='invoice_runtime'") !== '0') {
    throw new Error('PostgreSQL invoice writer is connected.');
  }
  if (!forward && pg("SELECT NOT rolcanlogin FROM pg_roles WHERE rolname='invoice_runtime'") !== 't') {
    throw new Error('Disable the PostgreSQL invoice runtime login before rollback.');
  }
  const prefix = forward ? 'invoice_platform.' : '';
  const query = 'SELECT ' + tableNames.map(table => '(SELECT count(*) FROM ' + prefix + table + ')').join('+');
  // A completed transfer can be retried only if the two frozen ledgers still match.
  if ((forward ? pg(query) : mysql(mysqlContainer, query)) !== '0') action = 'Compare';
}
transferLedger({
  direction: action, manifestPath, mysqlDatabase: 'invoice_platform', pgDatabase: 'invoice_platform',
  schema: release ? 'invoice_platform' : 'public', mysqlContainer, pgContainer, tableCount: tableNames.length, tableNames,
});
