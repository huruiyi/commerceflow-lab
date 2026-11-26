import { execFileSync } from 'node:child_process';
import { transferLedger } from '../lib/mysql-postgresql-ledger.mjs';

const [project, direction, manifestPath] = process.argv.slice(2);
if (!/^fairy-commerceflow-catalog-pg-[a-f0-9]{12}$/.test(project)
  || !['ToPostgreSQL', 'ToMySQL'].includes(direction)) {
  throw new Error('Invalid isolated catalog transfer scope.');
}

function database(service, directory) {
  const id = execFileSync('docker', ['ps', '-q', '--filter', 'label=com.docker.compose.project=' + project,
    '--filter', 'label=com.docker.compose.service=' + service], { encoding: 'utf8' }).trim();
  if (!/^[a-f0-9]{12}$/.test(id)) throw new Error('Expected one running database for ' + service);
  const mounts = JSON.parse(execFileSync('docker', ['inspect', '--format', '{{json .Mounts}}', id], { encoding: 'utf8' }));
  if (!mounts.some(mount => mount.Name === project + '_' + service + '-data' && mount.Destination === directory)) {
    throw new Error('Unexpected data volume for ' + service);
  }
  return id;
}

const mysqlService = direction === 'ToPostgreSQL' ? 'original' : 'rollback';
const tables = ['catalog_cutover_state', 'catalog_elasticsearch_state', 'catalog_event_outbox', 'catalog_product_skus',
  'catalog_search_documents', 'catalog_search_state', 'product_translations', 'products'];
transferLedger({
  direction, manifestPath, mysqlDatabase: 'catalog', pgDatabase: 'catalog', schema: 'public',
  mysqlContainer: database(mysqlService, '/var/lib/mysql'),
  pgContainer: database('postgres', '/var/lib/postgresql/data'),
  tableCount: tables.length, tableNames: tables,
});
