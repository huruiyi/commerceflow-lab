import assert from 'node:assert/strict';
import { createHash, createHmac, randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { setTimeout } from 'node:timers/promises';

const phase = process.argv[2];
const first = '337d2e13-9f27-4a12-afc9-d0dfe82f4689';
const second = 'ffc4f8b7-9653-477b-aeb0-fcc709745f5e';
const third = 'bffcd8df-206e-489e-a6c4-a5ebdfc8c735';
const origin = 'http://fulfillment:8080';
const firstCommand = {
  version: 1, commandId: first, operation: 'SHIP', merchantId: 5, orderId: 9100751,
  occurredAt: new Date().toISOString(), carrier: 'Rehearsal Carrier', trackingNumber: 'PG-VERIFY-751',
};
const secondCommand = {
  ...firstCommand, commandId: second, orderId: 9100752, trackingNumber: 'PG-VERIFY-752',
  lines: [{ orderLineId: 721, quantity: 1 }], warehouseId: 2,
};

async function call(path, input, expected = [200]) {
  const target = '/internal/v1/fulfillment' + path;
  const raw = JSON.stringify(input);
  const timestamp = String(Math.floor(Date.now() / 1000));
  const requestId = randomUUID();
  const hash = createHash('sha256').update(raw).digest('hex');
  const signature = createHmac('sha256', 'fulfillment-isolated-api')
    .update(['POST', target, timestamp, requestId, hash].join('\n')).digest('hex');
  const response = await fetch(origin + target, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-CommerceFlow-Timestamp': timestamp,
      'X-CommerceFlow-Request-Id': requestId, 'X-CommerceFlow-Content-SHA256': hash, 'X-CommerceFlow-Signature': signature },
    body: raw,
    signal: AbortSignal.timeout(8000),
  });
  const result = await response.text();
  assert(expected.includes(response.status), path + ': ' + response.status + ' ' + result);
  return result ? JSON.parse(result) : null;
}

function query(commandId) {
  return call('/queries', { commandId, merchantId: 5, executionOnly: true });
}

async function state() {
  const response = await fetch('http://127.0.0.1:8080/state', { signal: AbortSignal.timeout(5000) });
  assert(response.ok);
  return response.json();
}

async function until(read, predicate, seconds, reason) {
  const deadline = Date.now() + seconds * 1000;
  do {
    const value = await read();
    if (predicate(value)) return value;
    await setTimeout(150);
  } while (Date.now() < deadline);
  throw new Error('Timed out: ' + reason);
}

function logisticsAttempts(snapshot, order) {
  return Object.values(snapshot.effects.logistics).find(entry => entry.body.orderId === order);
}

function stepId(commandId, step) {
  const bytes = createHash('md5').update(commandId + ':' + step).digest();
  bytes[6] = (bytes[6] & 0x0f) | 0x30;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString('hex');
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join('-');
}

if (phase === 'hold') {
  const command = await call('/commands', firstCommand, [202]);
  assert.equal(command.commandId, first);
  const snapshot = await until(state, s => logisticsAttempts(s, 9100751)?.heldAfterReceipt, 20, 'first logistics receipt held');
  assert.equal(logisticsAttempts(snapshot, 9100751).attempts, 1);
  console.log(JSON.stringify({ commandId: first, logistics: logisticsAttempts(snapshot, 9100751) }));
} else if (phase === 'fenced') {
  const before = await query(first);
  assert.equal(before.status, 'PENDING');
  assert.equal(before.step, 'LOGISTICS');
  await setTimeout(1500);
  assert.equal(logisticsAttempts(await state(), 9100751).attempts, 1, 'valid MySQL lease must block a new PostgreSQL claim');
} else if (phase === 'finish') {
  const firstDone = await until(() => query(first), q => q.status === 'COMPLETED', 85, 'expired lease recovered');
  assert.equal(firstDone.shipmentVersion, 0);
  assert.equal((await call('/commands', firstCommand)).commandId, first);
  await call('/commands', { ...firstCommand, trackingNumber: 'DIFFERENT' }, [409]);

  const submissions = await Promise.all([call('/commands', secondCommand, [202]), call('/commands', secondCommand, [202])]);
  assert(submissions.every(result => result.commandId === second));
  const secondDone = await until(() => query(second), q => q.status === 'COMPLETED', 45, 'warehouse retries and package completion');
  assert.equal(secondDone.shipmentVersion, 0);
  const snapshot = await state();
  for (const [id, orderId, attempts, inventoryAttempts] of [[first, 9100751, 2, 3], [second, 9100752, 1, 2]]) {
    const logistics = logisticsAttempts(snapshot, orderId);
    assert.equal(logistics.attempts, attempts);
    assert.equal(logistics.receipt.commandId, stepId(id, 'LOGISTICS'));
    assert.equal(snapshot.orders[orderId].status, 'SHIPPED');
    assert.equal(snapshot.effects.inventory[id].attempts, inventoryAttempts, 'outbound replay uses the same business ID');
  }
  assert.equal(snapshot.effects.warehouse[first].attempts, 1);
  assert.equal(snapshot.effects.warehouse[second].attempts, 3);
  assert(snapshot.effects.package[second].actual && snapshot.effects.package[second].finished);
  assert.equal(snapshot.effects.order[stepId(first, 'FINISH_ORDER')].attempts, 1);
  console.log(JSON.stringify(snapshot));
} else if (phase === 'rollback') {
  for (const command of [firstCommand, secondCommand]) {
    assert.equal((await query(command.commandId)).status, 'COMPLETED');
    assert.equal((await call('/commands', command)).commandId, command.commandId);
  }
  const historical = JSON.parse(readFileSync(0, 'utf8'));
  for (const job of historical) {
    const record = await call('/shipment-queries', { orderId: job.orderId, merchantId: 5 });
    for (const key of ['commandId', 'orderId', 'carrier', 'trackingNumber', 'step', 'status']) {
      assert.equal(record[key], job[key], 'historical ' + job.orderId + ' ' + key);
    }
  }
  await call('/commands', { version: 1, commandId: third, operation: 'CONFIRM', merchantId: 5,
    orderId: 9100753, occurredAt: new Date().toISOString() }, [202]);
  await until(() => query(third), q => q.status === 'COMPLETED', 30, 'old MySQL instance executes a new command');
  const snapshot = await state();
  assert.equal(snapshot.effects.order[third].attempts, 1);
  assert.equal(snapshot.effects.order[third].body.type, 'MERCHANT_CONFIRM');
  assert.equal(Object.keys(snapshot.effects.logistics).length, 2);
  console.log(JSON.stringify({ historicalMatched: historical.length, newConfirmation: third }));
} else {
  throw new Error('Unknown fulfillment verification phase');
}
