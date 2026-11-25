const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const [input, output] = process.argv.slice(2);
assert.ok(input && output, 'Usage: node export-mca-divisions.cjs <regions.sql> <new output directory>');
const fields = [
  'shortCode', 'code', 'name', 'level', 'type', 'typeSource', 'dataYear', 'parentShortCode',
  'parentCode', 'parentName', 'namePath', 'namePathFull', 'childrenCount', 'synthesized',
];
const hash = (value) => createHash('sha256').update(value).digest('hex');
const quote = (value) => '"' + value.replaceAll('"', '""') + '"';

function parseValues(input) {
  const token = /(?:'((?:[^']|'')*)'|(NULL|\d+))(?:,|$)/gy;
  const values = [];
  let consumed = 0;
  for (const match of input.matchAll(token)) {
    consumed += match[0].length;
    values.push(match[1] != null ? match[1].replaceAll("''", "'") : match[2] === 'NULL' ? null : Number(match[2]));
  }
  assert.equal(consumed, input.length);
  assert.equal(values.length, fields.length);
  return values;
}

function catalogCode(row) {
  if (row.level === 1 && row.shortCode === '资料暂缺' && row.name === '台湾省') return 'unassigned-tw';
  assert.match(row.shortCode, /^\d+$/u);
  assert.equal(row.shortCode.length, [0, 2, 4, 6, 9][row.level]);
  assert.equal(row.shortCode.padEnd(9, '0'), row.code);
  return row.shortCode.padEnd(6, '0');
}

const sql = fs.readFileSync(input);
const decoded = new TextDecoder('utf-8', { fatal: true }).decode(sql);
const header = decoded.match(/共 (\d+) 条：省 (\d+) \/ 地级 (\d+) \/ 县级 (\d+) \/ 乡镇街道 (\d+)/u);
assert.ok(header, 'The source header must declare the total and per-level counts');
const byShortCode = new Map();
for (const line of decoded.split(/\r?\n/)) {
  if (!line.startsWith('INSERT')) continue;
  const match = line.match(/^INSERT INTO regions VALUES \((.*)\);$/u);
  assert.ok(match, 'Unexpected INSERT format');
  const values = parseValues(match[1]);
  const row = Object.fromEntries(fields.map((field, index) => [field, values[index]]));
  assert.ok(!byShortCode.has(row.shortCode), 'Repeated source row ' + row.shortCode);
  byShortCode.set(row.shortCode, row);
}
assert.equal(byShortCode.size, Number(header[1]));
const codes = new Set();
const counts = [0, 0, 0, 0];
const childCounts = new Map();
const years = new Set();
let skippedLevel = 0;
const csv = ['code,parent_code,level,name,source_metadata'];
for (const row of byShortCode.values()) {
  const code = catalogCode(row);
  assert.ok(!codes.has(code), code);
  codes.add(code);
  counts[row.level - 1]++;
  years.add(row.dataYear);
  const parent = byShortCode.get(row.parentShortCode);
  assert.ok(row.level === 1 ? row.parentShortCode === null : parent && parent.level < row.level, code);
  if (parent) {
    assert.equal(row.parentName, parent.name, code);
    childCounts.set(parent.shortCode, (childCounts.get(parent.shortCode) ?? 0) + 1);
    if (parent.level !== row.level - 1) skippedLevel++;
  }
  csv.push([code, parent ? catalogCode(parent) : '', row.level, quote(row.name), quote(JSON.stringify(row))].join(','));
}
for (const row of byShortCode.values()) {
  assert.equal(childCounts.get(row.shortCode) ?? 0, row.childrenCount, row.shortCode);
}
assert.deepEqual(counts, header.slice(2).map(Number));
assert.equal(years.size, 1);

const normalized = Buffer.from(csv.join('\n') + '\n');
const manifest = {
  inputFile: path.basename(input), year: [...years][0], rows: byShortCode.size, counts, skippedLevel,
  missingCode: [...byShortCode.values()].filter((row) => row.shortCode === '资料暂缺').map((row) => row.name),
  sqlSha256: hash(sql), csvSha256: hash(normalized),
};
fs.mkdirSync(output);
fs.writeFileSync(path.join(output, 'regions.csv'), normalized, { flag: 'wx' });
fs.writeFileSync(path.join(output, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify(manifest));
