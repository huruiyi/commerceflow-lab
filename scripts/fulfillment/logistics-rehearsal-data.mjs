import { execFileSync } from 'node:child_process';
import { transferLedger } from '../lib/mysql-postgresql-ledger.mjs';

const [project, direction, manifestPath] = process.argv.slice(2);
if (!/^fairy-commerceflow-acceptance-logistics-[a-f0-9]{12}$/.test(project) ||
    !['ToPostgreSQL', 'ToMySQL'].includes(direction)) throw Error('Expected an isolated logistics rehearsal');
function running(service) {
  return execFileSync('docker', ['ps', '-q', '--filter', 'label=com.docker.compose.project=' + project,
    '--filter', 'label=com.docker.compose.service=' + service], {encoding: 'utf8'}).trim();
}
if (running('logistics')) throw Error('Stop the isolated logistics writer before copying');
const mysqlContainer = running(direction === 'ToPostgreSQL' ? 'original' : 'rollback');
const pgContainer = running('postgres');
if (![mysqlContainer, pgContainer].every(id => /^[a-f0-9]{12}$/.test(id))) throw Error('Missing isolated database');
const tableNames = ['callback_outbox','callback_outbox_events','shipment_events','shipment_records','signed_request_nonces'];
transferLedger({direction, manifestPath, mysqlContainer, pgContainer, schema: 'public', tableCount: tableNames.length,
  tableNames, mysqlDatabase: 'logistics_platform', pgDatabase: 'logistics_platform'});
