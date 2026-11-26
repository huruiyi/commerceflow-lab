import { transferLedger } from '../lib/mysql-postgresql-ledger.mjs';

const [direction, mysqlContainer, mysqlDatabase, pgContainer, pgDatabase, manifestPath] = process.argv.slice(2);
transferLedger({direction, mysqlContainer, mysqlDatabase, pgContainer, pgDatabase, manifestPath,
  schema: 'commerceflow', tableCount: 78});
