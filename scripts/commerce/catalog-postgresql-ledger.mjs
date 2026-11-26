import { execFileSync } from 'node:child_process';
import { transferLedger } from '../lib/mysql-postgresql-ledger.mjs';

const [project, direction, manifestPath] = process.argv.slice(2);
const release = ['release-forward', 'release-reverse'].includes(direction);
const expected = release
  ? project === 'fairy-commerceflow' || /^fairy-commerceflow-catalog-release-[a-f0-9]{12}$/.test(project)
  : /^fairy-commerceflow-catalog-pg-[a-f0-9]{12}$/.test(project) && ['ToPostgreSQL', 'ToMySQL'].includes(direction);
if (!expected || !manifestPath) {
  throw new Error('Invalid catalog transfer scope.');
}

function running(service) {
  return execFileSync('docker', ['ps', '-q', '--filter', 'label=com.docker.compose.project=' + project,
    '--filter', 'label=com.docker.compose.service=' + service], { encoding: 'utf8' }).trim();
}

function database(service, directory, volume = service + '-data') {
  const id = running(service);
  if (!/^[a-f0-9]{12}$/.test(id)) throw new Error('Expected one running database for ' + service);
  const mounts = JSON.parse(execFileSync('docker', ['inspect', '--format', '{{json .Mounts}}', id], { encoding: 'utf8' }));
  if (!mounts.some(mount => mount.Name === project + '_' + volume && mount.Destination === directory)) {
    throw new Error('Unexpected data volume for ' + service);
  }
  return id;
}

const tables = ['catalog_cutover_state', 'catalog_elasticsearch_state', 'catalog_event_outbox', 'catalog_product_skus',
  'catalog_search_documents', 'catalog_search_state', 'product_translations', 'products'];
const forward = direction === 'ToPostgreSQL' || direction === 'release-forward';
if (release && running('catalog-service')) throw new Error('Stop the catalog writer before copying.');
const pgContainer = release ? database('catalog-db-postgresql', '/var/lib/postgresql/data', 'catalog-postgresql-data')
  : database('postgres', '/var/lib/postgresql/data');
const original = release ? database('catalog-db', '/var/lib/mysql') : null;
const mysqlContainer = release ? (forward ? original : database('catalog-db-rollback', '/var/lib/mysql', 'catalog-rollback-data'))
  : database(forward ? 'original' : 'rollback', '/var/lib/mysql');
let action = forward ? 'ToPostgreSQL' : 'ToMySQL';
if (release) {
  function mysql(container, query) {
    return execFileSync('docker', ['exec', container, 'sh', '-ec',
      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB catalog -e "$1"', 'sh', query], { encoding: 'utf8' }).trim();
  }
  function pg(query) {
    return execFileSync('docker', ['exec', pgContainer, 'psql', '-U', 'postgres', '-d', 'catalog',
      '-XAtq', '-v', 'ON_ERROR_STOP=1', '-c', query], { encoding: 'utf8' }).trim();
  }
  const frozen = mysql(original, `SELECT @@GLOBAL.super_read_only AND EXISTS (
    SELECT 1 FROM performance_schema.persisted_variables WHERE variable_name='super_read_only' AND variable_value='ON')`);
  if (frozen !== '1') throw new Error('Original catalog MySQL must be persistently read-only.');
  if (pg("SELECT count(*) FROM pg_stat_activity WHERE usename='catalog_runtime'") !== '0') {
    throw new Error('PostgreSQL catalog writer is connected.');
  }
  if (!forward && pg("SELECT NOT rolcanlogin FROM pg_roles WHERE rolname='catalog_runtime'") !== 't') {
    throw new Error('Disable the PostgreSQL catalog runtime login before rollback.');
  }
  const prefix = forward ? 'catalog.' : '';
  const counts = 'SELECT ' + tables.map(table => '(SELECT count(*) FROM ' + prefix + table + ')').join('+');
  // An already populated destination is eligible only for parity verification.
  if ((forward ? pg(counts) : mysql(mysqlContainer, counts)) !== '0') action = 'Compare';
}
transferLedger({
  direction: action, manifestPath, mysqlDatabase: 'catalog', pgDatabase: 'catalog',
  schema: release ? 'catalog' : 'public', mysqlContainer, pgContainer, tableCount: tables.length, tableNames: tables,
});
