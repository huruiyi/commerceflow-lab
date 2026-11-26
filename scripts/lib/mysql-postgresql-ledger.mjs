import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { writeFileSync } from 'node:fs';

export function transferLedger({direction, mysqlContainer, mysqlDatabase, pgContainer, pgDatabase, manifestPath,
                                schema, tableCount, tableNames}) {
  if (!['ToPostgreSQL', 'ToMySQL', 'Compare'].includes(direction) || !manifestPath) {
    throw new Error('Expected a transfer direction and a manifest path.');
  }
  const identifier = (s) => {
    if (!/^[a-z][a-z_0-9]*$/.test(s)) throw new Error('Invalid database identifier.');
    return s;
  };
  identifier(schema);
  identifier(mysqlDatabase);
  identifier(pgDatabase);
  for (const name of [mysqlContainer, pgContainer]) {
    if (!/^[a-z0-9][a-z0-9-]+$/.test(name)) throw new Error('Invalid container name.');
  }

  function query(args, sql) {
    const result = spawnSync('docker', ['exec', '-i', ...args], {
      input: sql, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024,
    });
    if (result.error) throw result.error;
    if (result.status !== 0) {
      // Server diagnostics for a failed COPY or INSERT may contain complete customer records.
      throw new Error(`SQL batch failed on ${args[0]} (exit ${result.status}).`);
    }
    return result.stdout.trimEnd();
  }
  const mysql = (sql) => query([mysqlContainer, 'sh', '-c',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 -BNr "$1"',
    'sh', mysqlDatabase], "SET time_zone='+00:00'; SET SESSION information_schema_stats_expiry=0;\n" + sql);
  const pg = (sql) => query([pgContainer, 'psql', '-U', 'postgres', '-d', pgDatabase, '-XAtq', '-v', 'ON_ERROR_STOP=1'],
    "SET search_path TO " + schema + "; SET TIME ZONE 'UTC';\n" + sql);
  const records = (s) => s.length ? s.split('\n').map(line => JSON.parse(line)) : [];
  const tables = pg(`SELECT tablename FROM pg_tables WHERE schemaname='${schema}' AND tablename <> 'flyway_schema_history' ORDER BY tablename`).split('\n').map(identifier);
  if (tables.length !== tableCount || (tableNames && tables.join(',') !== tableNames.join(','))) {
    throw new Error('Unexpected business tables in ' + schema);
  }
  const columns = records(mysql(`SELECT JSON_ARRAY(table_name,column_name,column_type,extra)
    FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY table_name,ordinal_position`));
  const foreignKeys = records(mysql(`SELECT JSON_ARRAY(table_name,referenced_table_name)
    FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND referenced_table_name IS NOT NULL`));
  if (direction === 'ToPostgreSQL') {
    const sourceTables = mysql("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND table_name<>'flyway_schema_history' ORDER BY table_name").split('\n');
    if (JSON.stringify(tables) !== JSON.stringify(sourceTables)) throw new Error('Source and destination business table sets differ.');
  }
  if (direction === 'ToMySQL' && mysql('SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE();') !== '0') {
    throw new Error('Restore MySQL triggers and routines after the reverse copy to avoid generating additional ledger records.');
  }
  const ordered = [];
  while (ordered.length < tables.length) {
    const ready = tables.filter(t => !ordered.includes(t) && !foreignKeys.some(([child, parent]) =>
      child === t && parent !== t && !ordered.includes(parent)));
    if (!ready.length) throw new Error('A foreign key is cyclic or references a missing parent.');
    ordered.push(...ready);
  }
  const meta = new Map();
  for (const t of ordered) {
    const cols = columns.filter(([name]) => name === t).map(([, name, type, extra]) =>
      ({ name: identifier(name), type, generated: extra.includes('STORED GENERATED'), identity: extra.includes('auto_increment') }));
    if (!cols.length) throw new Error('Missing MySQL table ' + t);
    meta.set(t, cols);
  }
  const pgCols = records(pg(`SELECT json_build_array(table_name,column_name,is_generated='ALWAYS',is_identity='YES')
    FROM information_schema.columns WHERE table_schema='${schema}' AND table_name<>'flyway_schema_history'
    ORDER BY table_name,ordinal_position`));
  for (const [table, cols] of meta) {
    const expected = cols.map(c => [table, c.name, c.generated, c.identity]);
    if (JSON.stringify(expected) !== JSON.stringify(pgCols.filter(([t]) => t === table))) {
      throw new Error('Columns, generation or identity differ for ' + table);
    }
  }

  function snapshot(engine) {
    const isPg = engine === 'pg';
    const sql = [isPg ? 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;' : 'START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;'];
    for (const [table, cols] of meta) {
      const fields = cols.map(c => isPg ? `"${c.name}"::text` : 'CAST(' + String.fromCharCode(96) + c.name + String.fromCharCode(96) + ' AS CHAR CHARACTER SET utf8mb4)');
      sql.push(`SELECT ${isPg ? 'json_build_array' : 'JSON_ARRAY'}('${table}', ${fields.join(', ')}) FROM ${table};`);
      const key = cols.find(c => c.identity);
      if (key && isPg) {
        const seq = pg(`SELECT pg_get_serial_sequence('${schema}.${table}','${key.name}')`);
        const parts = seq.split('.').map(identifier);
        if (parts.length !== 2 || parts[0] !== schema) throw new Error('Unexpected sequence for ' + table);
        sql.push(`SELECT json_build_array('@sequence','${table}',(last_value + CASE WHEN is_called THEN 1 ELSE 0 END)::text) FROM ${seq};`);
      } else if (key) {
        sql.push(`SELECT JSON_ARRAY('@sequence','${table}',CAST(auto_increment AS CHAR)) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='${table}';`);
      }
    }
    sql.push('COMMIT;');
    const rows = new Map(ordered.map(t => [t, []]));
    const sequences = new Map();
    for (const [tag, ...values] of records((isPg ? pg : mysql)(sql.join('\n')))) {
      if (tag === '@sequence') sequences.set(values[0], values[1]);
      else rows.get(tag).push(values);
    }
    for (const [t, cols] of meta) {
      const i = cols.findIndex(c => c.identity);
      if (i === -1) continue;
      const next = sequences.get(t);
      if (!/^\d+$/.test(next) || rows.get(t).some(row => BigInt(row[i]) >= BigInt(next))) {
        throw new Error('Identity counter would reuse an existing key in ' + t);
      }
    }
    return { rows, sequences };
  }

  function canonicalJson(value, context) {
    if (typeof value === 'number') {
      // Compare mathematical values without rounding long identifiers through a JavaScript double.
      const [, sign, whole, fraction = '', exponent = '0'] =
        /^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(context.source);
      const digits = (whole + fraction).replace(/^0+/, '');
      const significant = digits.replace(/0+$/, '');
      const scale = BigInt(exponent) + BigInt(digits.length - significant.length - fraction.length);
      return ['number', significant ? sign + significant + 'e' + scale : '0'];
    }
    if (Array.isArray(value)) return ['array', value];
    if (value !== null && typeof value === 'object') return ['object', Object.keys(value).sort().map(k => [k, value[k]])];
    return value;
  }
  function normalized(c, value) {
    if (value === null) return null;
    if (c.type === 'json') return JSON.parse(value, (_key, v, context) => canonicalJson(v, context));
    if (c.type === 'tinyint(1)') {
      if (['1', 'true'].includes(value)) return '1';
      if (['0', 'false'].includes(value)) return '0';
      throw new Error('Invalid boolean value in ' + c.name);
    }
    if (/^(datetime|timestamp)/.test(c.type)) return value.replace(/\+00(:00)?$/, '').replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '');
    return value;
  }
  function digest(snapshot, t) {
    const cols = meta.get(t);
    const lines = snapshot.rows.get(t).map(row => JSON.stringify(row.map((v, i) => normalized(cols[i], v)))).sort();
    return { table: t, rows: lines.length, sha256: createHash('sha256').update(lines.join('\n')).digest('hex'),
      nextIdentity: snapshot.sequences.get(t) ?? null };
  }

  const reverse = direction === 'ToMySQL';
  const source = snapshot(reverse ? 'pg' : 'mysql');
  if (direction !== 'Compare') {
    const target = reverse ? mysql : pg;
    const counts = target(ordered.map(t => 'SELECT COUNT(*) FROM ' + t + ';').join('\n')).split('\n');
    if (counts.length !== ordered.length || counts.some(n => n !== '0')) throw new Error('Destination business tables must be empty.');
    const sql = ['BEGIN;'];
    for (const [table, cols] of meta) {
      const included = cols.map((c, i) => ({ ...c, i })).filter(c => !c.generated);
      const quote = reverse ? String.fromCharCode(96) : '"';
      const fields = included.map(c => quote + c.name + quote).join(', ');
      const rows = source.rows.get(table);
      if (!reverse && rows.length) sql.push(`COPY ${table} (${fields}) FROM STDIN;`);
      for (const row of rows) {
        const values = included.map(c => {
          const v = row[c.i];
          if (v === null) return reverse ? 'NULL' : '\\N';
          if (reverse) {
            const str = c.type === 'tinyint(1)' || /^(datetime|timestamp)/.test(c.type) ? normalized(c, v) : v;
            return "CONVERT(UNHEX('" + Buffer.from(str).toString('hex') + "') USING utf8mb4)";
          }
          return v.replaceAll('\\', '\\\\').replaceAll('\t', '\\t').replaceAll('\r', '\\r').replaceAll('\n', '\\n');
        });
        sql.push(reverse ? `INSERT INTO ${table} (${fields}) VALUES (${values.join(', ')});` : values.join('\t'));
      }
      if (!reverse && rows.length) sql.push('\\.');
      const key = cols.find(c => c.identity);
      if (!reverse && key) sql.push(`ALTER TABLE ${table} ALTER COLUMN ${key.name} RESTART WITH ${source.sequences.get(table)};`);
    }
    sql.push('COMMIT;');
    target(sql.join('\n') + '\n');
    if (reverse) {
      // MySQL ALTER TABLE commits implicitly. Advance counters only after the entire row transaction commits.
      mysql([...source.sequences].map(([t, n]) => `ALTER TABLE ${t} AUTO_INCREMENT = ${n};`).join('\n'));
    }
  }
  const actual = snapshot(reverse ? 'mysql' : 'pg');
  const manifest = ordered.map(t => digest(source, t));
  for (const entry of manifest) {
    if (JSON.stringify(entry) !== JSON.stringify(digest(actual, entry.table))) throw new Error('Parity check failed for ' + entry.table);
  }
  writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
  console.log(`Verified ${manifest.length} tables, ${manifest.reduce((n, m) => n + m.rows, 0)} rows and ${source.sequences.size} identity counters.`);
}
