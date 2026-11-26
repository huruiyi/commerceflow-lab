import assert from 'node:assert/strict';
import { createHash, createHmac, randomUUID } from 'node:crypto';

const phase = process.argv[2];
const prefix = '/internal/v1/inventory';
const releaseOrder = 9200751;
const shippedOrder = 9200752;
const rollbackOrder = 9200753;
const originalOutboundOrder = 9200755;
const orderLine = 9201752;
const productId = 2;
const merchantId = 5;
const outboundId = '5050266c-9138-46af-844d-89fb4f4bea08';
const caseId = '20581e3d-8a0a-4994-9e48-7f89c18525f9';
const document = { outboundId, orderId: shippedOrder, merchantId, warehouseId: 1, partial: true,
  lines: [{ orderLineId: orderLine, productId, quantity: 2 }] };
const credit = { caseId, orderId: shippedOrder, orderLineId: orderLine, productId, merchantId,
  quantity: 1, kind: 'RETURN', warehouseId: 1, outboundId };

async function call(path, body, role = 'commerce', expected = 200) {
  const target = prefix + path;
  const timestamp = String(Math.floor(Date.now() / 1000));
  const requestId = randomUUID();
  const payload = JSON.stringify(body);
  const hash = createHash('sha256').update(payload).digest('hex');
  const signature = createHmac('sha256', 'inventory-isolated-' + role)
    .update(['POST', target, timestamp, requestId, hash].join('\n')).digest('hex');
  const response = await fetch('http://inventory:8080' + target, {
    method: 'POST', body: payload, signal: AbortSignal.timeout(12000),
    headers: { 'Content-Type': 'application/json', 'X-CommerceFlow-Service': role + '-service',
      'X-CommerceFlow-Request-Id': requestId, 'X-CommerceFlow-Timestamp': timestamp,
      'X-CommerceFlow-Content-SHA256': hash, 'X-CommerceFlow-Signature': signature },
  });
  const result = await response.text();
  assert.equal(response.status, expected, `${phase} ${path}: ${result}`);
  return result && JSON.parse(result);
}

function command(orderId, commandType, quantity) {
  const commandId = `pg-rehearsal:${orderId}:${commandType}`;
  return { schemaVersion: 1, commandId, commandType, orderId, occurredAt: '2026-11-26T00:00:00Z',
    producer: 'commerce-service', correlationId: `pg-rehearsal:${orderId}`, causationId: commandId,
    traceparent: '00-00000000000000000000000000000001-0000000000000001-01', reason: 'Inventory database rehearsal',
    items: commandType === 'RESERVE' ? [{ productId, productName: 'Rehearsal product', quantity }] : [] };
}

async function succeeded(request) {
  const result = await call('/commands', request);
  assert.equal(result.outcome, 'SUCCEEDED');
  assert.equal(result.commandId, request.commandId);
  return result;
}

async function lookupOutbound() {
  const rows = await call('/outbound-documents/query', { merchantId, orderIds: [shippedOrder] });
  assert.equal(rows.length, 1);
  for (const [key, value] of Object.entries({ ...document.lines[0], warehouseId: 1, outboundId, orderId: shippedOrder })) {
    assert.equal(rows[0][key], value);
  }
  return rows[0];
}

if (phase === 'hold-mysql') {
  console.log(JSON.stringify(await succeeded(command(releaseOrder, 'RESERVE', 2))));
} else if (phase === 'outbound-mysql') {
  await succeeded(command(originalOutboundOrder, 'RESERVE', 1));
  await succeeded(command(originalOutboundOrder, 'COMMIT'));
  const prior = { outboundId: 'b2e11d49-7316-4180-9992-0f32e7e571b1', orderId: originalOutboundOrder,
    merchantId, warehouseId: 1, lines: [{ orderLineId: 9201755, productId, quantity: 1 }] };
  const receipt = await call('/outbound-documents', prior, 'fulfillment');
  assert.deepEqual(receipt, { outboundId: prior.outboundId, orderId: originalOutboundOrder, lineCount: 1 });
  console.log(JSON.stringify(receipt));
} else if (phase === 'release-postgresql') {
  await succeeded(command(releaseOrder, 'RESERVE', 2));
  const release = command(releaseOrder, 'RELEASE');
  const receipts = await Promise.all([succeeded(release), succeeded(release)]);
  console.log(JSON.stringify(receipts));
} else if (phase === 'reserve-postgresql') {
  const rejected = command(9200754, 'RESERVE', 1000000);
  for (const result of [await call('/commands', rejected), await call('/commands', rejected)]) {
    assert.equal(result.outcome, 'REJECTED');
    assert.equal(result.commandId, rejected.commandId);
  }
  const request = command(shippedOrder, 'RESERVE', 3);
  const receipts = await Promise.all([succeeded(request), succeeded(request)]);
  await call('/commands', { ...request, items: [{ ...request.items[0], quantity: 4 }] }, 'commerce', 400);
  console.log(JSON.stringify(receipts));
} else if (phase === 'commit-postgresql') {
  const request = command(shippedOrder, 'COMMIT');
  console.log(JSON.stringify([await succeeded(request), await succeeded(request)]));
} else if (phase === 'outbound-postgresql') {
  const receipts = await Promise.all([call('/outbound-documents', document, 'fulfillment'),
    call('/outbound-documents', document, 'fulfillment')]);
  assert.deepEqual(receipts, [{ outboundId, orderId: shippedOrder, lineCount: 1 },
    { outboundId, orderId: shippedOrder, lineCount: 1 }]);
  console.log(JSON.stringify(await lookupOutbound()));
} else if (phase === 'return-postgresql') {
  const origin = { orderId: shippedOrder, orderLineId: orderLine, merchantId, productId, warehouseId: 1, outboundId };
  const sources = await call('/line-credits/sources', origin);
  assert.deepEqual(sources, [{ warehouseId: 1, outboundId, quantity: 2 }]);
  assert.equal(await call('/line-credits', { credit: { ...credit, outboundId: randomUUID() }, apply: true }), false);
  assert.equal(await call('/line-credits', { credit, apply: false }), true);
  const receipts = await Promise.all([call('/line-credits', { credit, apply: true }),
    call('/line-credits', { credit, apply: true })]);
  assert.deepEqual(receipts, [true, true]);
  assert.equal(await call('/line-credits', { credit: { ...credit, caseId: randomUUID(), quantity: 2 }, apply: true }), false);
  console.log(JSON.stringify({ sources, credit, receipts }));
} else if (phase === 'hold-postgresql') {
  console.log(JSON.stringify(await succeeded(command(rollbackOrder, 'RESERVE', 1))));
} else if (phase === 'rollback-mysql') {
  await succeeded(command(rollbackOrder, 'RESERVE', 1));
  const release = await succeeded(command(rollbackOrder, 'RELEASE'));
  const outbound = await lookupOutbound();
  assert.equal((await call('/outbound-documents', document, 'fulfillment')).outboundId, outboundId);
  assert.equal(await call('/line-credits', { credit, apply: true }), true);
  console.log(JSON.stringify({ release, outbound, credit }));
} else {
  throw new Error('Unknown inventory rehearsal phase');
}
