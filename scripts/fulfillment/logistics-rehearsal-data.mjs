import { execFileSync } from 'node:child_process';
import { transferLedger } from '../lib/mysql-postgresql-ledger.mjs';

const [project, direction, manifestPath] = process.argv.slice(2);
const release = ['release-forward','release-reverse'].includes(direction);
const validScope = release
  ? project === 'fairy-commerceflow' || /^fairy-commerceflow-logistics-release-[a-f0-9]{12}$/.test(project)
  : /^fairy-commerceflow-acceptance-logistics-[a-f0-9]{12}$/.test(project) && ['ToPostgreSQL','ToMySQL'].includes(direction);
if (!validScope || !manifestPath) throw Error('Invalid logistics transfer scope');
const forward = direction === 'ToPostgreSQL' || direction === 'release-forward';
function running(service) {
  return execFileSync('docker', ['ps', '-q', '--filter', 'label=com.docker.compose.project=' + project,
    '--filter', 'label=com.docker.compose.service=' + service], {encoding: 'utf8'}).trim();
}
function db(service, volume, destination) {
  const id = running(service);
  if (!/^[a-f0-9]{12}$/.test(id)) throw Error('Missing database: ' + service);
  if (release) {
    const mounts = JSON.parse(execFileSync('docker',['inspect','--format','{{json .Mounts}}',id],{encoding:'utf8'}));
    if (!mounts.some(m => m.Name === project + '_' + volume && m.Destination === destination)) throw Error('Unexpected volume: ' + service);
  }
  return id;
}
if (running(release ? 'logistics-simulator-service' : 'logistics')) throw Error('Stop the logistics writer before copying');
const original = release ? db('logistics-simulator-db','logistics-simulator-db-data','/var/lib/mysql') : null;
const mysqlContainer = release ? (forward ? original : db('logistics-simulator-db-rollback','logistics-rollback-data','/var/lib/mysql'))
  : db(forward ? 'original' : 'rollback');
const pgContainer = release ? db('logistics-simulator-db-postgresql','logistics-postgresql-data','/var/lib/postgresql/data') : db('postgres');
const tableNames = ['callback_outbox','callback_outbox_events','shipment_events','shipment_records','signed_request_nonces'];
let action = forward ? 'ToPostgreSQL' : 'ToMySQL';
if (release) {
  function mysql(id, sql) {
    return execFileSync('docker',['exec',id,'sh','-ec','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB logistics_platform -e "$1"','sh',sql],{encoding:'utf8'}).trim();
  }
  function pg(sql) {
    return execFileSync('docker',['exec',pgContainer,'psql','-U','postgres','-d','logistics_platform','-XAtq','-v','ON_ERROR_STOP=1','-c',sql],{encoding:'utf8'}).trim();
  }
  if (mysql(original,'SELECT @@GLOBAL.super_read_only') !== '1') throw Error('Original MySQL is writable');
  if (pg("SELECT count(*) FROM pg_stat_activity WHERE usename='logistics_runtime'") !== '0') throw Error('PostgreSQL writer is connected');
  if (!forward && pg("SELECT NOT rolcanlogin FROM pg_roles WHERE rolname='logistics_runtime'") !== 't') throw Error('Disable PostgreSQL runtime login before rollback');
  const prefix = forward ? 'logistics_platform.' : '';
  const query = 'SELECT ' + tableNames.map(t => '(SELECT count(*) FROM ' + prefix + t + ')').join('+');
  // An identical completed transfer can be retried. Divergent nonempty targets are only compared.
  if ((forward ? pg(query) : mysql(mysqlContainer,query)) !== '0') action = 'Compare';
}
transferLedger({direction:action, manifestPath, mysqlContainer, pgContainer, schema:release ? 'logistics_platform' : 'public', tableCount:tableNames.length,
  tableNames, mysqlDatabase: 'logistics_platform', pgDatabase: 'logistics_platform'});
