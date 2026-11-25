const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { DatabaseSync } = require('node:sqlite');

const [source, output] = process.argv.slice(2);
assert.ok(source && output, 'Usage: node export-china-divisions.cjs <source snapshot> <new output directory>');
const dist = path.resolve(source, 'dist');
const names = ['province', 'city', 'area', 'street', 'village'];
const parentKeys = [null, 'provinceCode', 'cityCode', 'areaCode', 'streetCode'];
const digest = (data) => createHash('sha256').update(data).digest('hex');
const sha256File = (file) => {
  const descriptor = fs.openSync(file, 'r');
  const hash = createHash('sha256');
  try {
    const buffer = Buffer.allocUnsafe(64 * 1024);
    let length;
    while ((length = fs.readSync(descriptor, buffer)) !== 0) hash.update(buffer.subarray(0, length));
    return hash.digest('hex');
  } finally {
    fs.closeSync(descriptor);
  }
};
const quote = (value) => '"' + value.replaceAll('"', '""') + '"';
const header = 'code,parent_code,level,name\n';
const read = (file) => JSON.parse(fs.readFileSync(path.join(dist, file), 'utf8'));

function exportCoded(db, target) {
  const counts = {};
  let parents = new Set();
  const file = fs.openSync(target, 'wx');
  try {
    fs.writeSync(file, header);
    for (const [index, name] of names.entries()) {
      const filename = name === 'city' ? 'cities' : name + 's';
      const rows = read(filename + '.json');
      const sourceCsv = fs.readFileSync(path.join(dist, filename + '.csv'), 'utf8').trimEnd().split(/\r?\n/);
      const columns = sourceCsv[0].split(',');
      assert.equal(sourceCsv.length - 1, rows.length, name + ' CSV row count');
      for (const [offset, row] of rows.entries()) {
        const fields = columns.map((column) => column === 'name' ? quote(row[column]) : row[column]);
        const plainName = !/[,"\r\n]/.test(row.name) &&
          columns.map((column) => row[column]).join(',') === sourceCsv[offset + 1];
        assert.ok(plainName || fields.join(',') === sourceCsv[offset + 1], name + ' CSV differs at ' + row.code);
      }
      assert.equal(db.prepare('SELECT COUNT(*) AS total FROM ' + name).get().total, rows.length);
      const sqlite = db.prepare('SELECT * FROM ' + name + ' ORDER BY code').iterate();
      rows.sort((left, right) => left.code.localeCompare(right.code, 'en'));
      const keys = new Set();
      const lines = [];
      for (const row of rows) {
        assert.deepEqual({ ...sqlite.next().value }, row);
        const parent = index === 0 ? '' : row[parentKeys[index]];
        assert.equal(keys.has(row.code), false, row.code);
        assert.ok(index === 0 || parents.has(parent), row.code + ' has no parent ' + parent);
        keys.add(row.code);
        lines.push([row.code, parent, index + 1, quote(row.name)].join(','));
        if (lines.length === 2000) fs.writeSync(file, lines.splice(0).join('\n') + '\n');
      }
      assert.equal(sqlite.next().done, true);
      if (lines.length) fs.writeSync(file, lines.join('\n') + '\n');
      counts[name] = keys.size;
      parents = keys;
    }
  } finally {
    fs.closeSync(file);
  }
  return counts;
}

function exportNamed(data, target) {
  const counts = [0, 0, 0];
  const ids = new Set();
  const lines = [];
  function add(names, parent) {
    const code = 'hmt-' + digest(JSON.stringify(names)).slice(0, 20);
    assert.ok(!ids.has(code), names.join('/'));
    ids.add(code);
    counts[names.length - 1]++;
    lines.push([code, parent, names.length, quote(names.at(-1))].join(','));
    return code;
  }
  for (const [province, cities] of Object.entries(data)) {
    const provinceCode = add([province], '');
    for (const [city, districts] of Object.entries(cities)) {
      const cityCode = add([province, city], provinceCode);
      for (const district of districts) add([province, city, district], cityCode);
    }
  }
  fs.writeFileSync(target, header + lines.join('\n') + '\n', { flag: 'wx' });
  return counts;
}

fs.mkdirSync(output);
const coded = path.join(output, 'statistical-divisions.csv');
const named = path.join(output, 'hong-kong-macao-taiwan-names.csv');
const db = new DatabaseSync(path.join(dist, 'data.sqlite'), { readOnly: true });
let counts;
try {
  counts = exportCoded(db, coded);
} finally {
  db.close();
}
const supplementaryCounts = exportNamed(read('HK-MO-TW.json'), named);
const manifest = {
  counts,
  total: Object.values(counts).reduce((sum, count) => sum + count, 0),
  supplementaryCounts,
  inputs: fs.readdirSync(dist).sort().map((name) => {
    const file = path.join(dist, name);
    return { name, bytes: fs.statSync(file).size, sha256: sha256File(file) };
  }),
  outputs: [coded, named].map((file) => ({ name: path.basename(file), sha256: sha256File(file) })),
  validation: 'All coded CSV, JSON and SQLite rows match. Every coded row has a unique code at its level and an existing parent.',
};
fs.writeFileSync(path.join(output, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n', { flag: 'wx' });
process.stdout.write(JSON.stringify({ counts, total: manifest.total, supplementaryCounts }) + '\n');
