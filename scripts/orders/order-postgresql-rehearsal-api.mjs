import assert from 'node:assert/strict';
import { createHash, createHmac, randomUUID } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';

const phase = process.argv[2];
const traceparent = '00-00000000000000000000000000000001-0000000000000001-01';
const merchantId = 5;
const customerId = 61;
const productId = 928001;
const productName = 'Café Book';
const orderPath = '/internal/v1/orders';
const inventoryPath = '/internal/v1/inventory/commands';
const stock = [{ productId, productName, quantity: 1 }];
const statePath = '/tmp/order-rehearsal.json';

async function post(service, path, input, expected = 200, sender = 'commerce-service', requestId = randomUUID()) {
  const timestamp = String(Math.floor(Date.now() / 1000));
  const body = JSON.stringify(input);
  const contentHash = createHash('sha256').update(body).digest('hex');
  const secret = service === 'order' ? 'order-rehearsal-commerce' : `order-rehearsal-${service}-api`;
  const signature = createHmac('sha256', secret)
    .update(['POST', path, timestamp, requestId, contentHash].join('\n')).digest('hex');
  const response = await fetch(`http://${service}:8080${path}`, { method: 'POST', body,
    signal: AbortSignal.timeout(12000), headers: { 'Content-Type': 'application/json',
      'X-CommerceFlow-Service': sender, 'X-CommerceFlow-Timestamp': timestamp,
      'X-CommerceFlow-Request-Id': requestId, 'X-CommerceFlow-Content-SHA256': contentHash,
      'X-CommerceFlow-Signature': signature, traceparent } });
  const text = await response.text();
  assert.equal(response.status, expected, `${phase} ${path}: ${text}`);
  return text && JSON.parse(text);
}

function uuid(seed) {
  const bytes = createHash('md5').update(seed).digest();
  bytes[6] = (bytes[6] & 0x0f) | 0x30;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString('hex');
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join('-');
}

function envelope(orderId, commandId, causationId = commandId) {
  return { schemaVersion: 1, orderId, commandId, producer: 'commerce-service', occurredAt: new Date().toISOString(),
    reason: 'Order database rehearsal', correlationId: `order-rehearsal-${orderId}`, causationId, traceparent };
}

function orderCall(path, input, expected, requestId) {
  return post('order', orderPath + path, input, expected, 'commerce-service', requestId);
}

async function orderState(id, status, inventoryStatus) {
  const record = await orderCall('/store/query', { type: 'FIND_BY_ID', orderId: id });
  assert.equal(record.order.status, status);
  assert.equal(record.order.inventoryStatus, inventoryStatus);
  return record.order;
}

async function mutate(orderId, type, fields = {}, mutationId = `order-rehearsal-${orderId}:${type}`) {
  const result = await orderCall('/store/mutations', { orderId, type, mutationId, ...fields });
  assert.equal(result.changed, 1);
  return result;
}

async function inventoryCommand(orderId, commandType, items, commandId, causationId) {
  const result = await post('inventory', inventoryPath, { ...envelope(orderId, commandId, causationId), commandType, items });
  assert.equal(result.outcome, 'SUCCEEDED');
  assert.equal(result.commandId, commandId);
  return result;
}

async function createOrder(suffix, parallel = false) {
  const orderNumber = `ORD-PG-REHEARSAL-${suffix}`;
  const input = { orderNumber, customerId, actorId: customerId, totalAmount: 12, currency: 'CNY',
    paymentDueAt: new Date(Date.now() + 30 * 60000).toISOString(), shippingFee: 2, discountAmount: 0,
    items: [{ productId, merchantId, productName, thumbnailUrl: '', quantity: 1, unitPrice: 10,
      subtotal: 10, discountAmount: 0 }],
    address: { recipientName: 'Rehearsal', phone: '13800000000', countryCode: 'CN', stateProvince: 'Shanghai',
      city: 'Shanghai', district: 'Pudong', addressLine1: 'Local rehearsal', addressLine2: '', postalCode: '200120', label: 'Lab' } };
  const pending = orderCall('/store/create', input);
  const rows = await Promise.all(parallel ? [pending, orderCall('/store/create', input)] : [pending]);
  const order = rows[0];
  assert(order.id > 0 && rows.every(row => row.id === order.id));
  assert.equal(order.productSubtotal, 10);
  const reserve = await inventoryCommand(order.id, 'RESERVE', stock, `order-rehearsal:${order.id}:reserve`);
  await mutate(order.id, 'UPDATE_INVENTORY_STATUS', { fromStatus: 'WAITING_RESERVATION', toStatus: 'RESERVED' },
    `order-rehearsal-${order.id}:reserve-status`);
  return { orderId: order.id, orderNumber, reserve };
}

async function paymentClose(order, commandId = uuid(order.command.commandId + ':payment-orchestration')) {
  const request = { ...envelope(order.orderId, commandId, order.command.commandId), occurredAt: order.command.occurredAt };
  const payment = await post('payment', '/internal/v1/payments/close', request);
  assert.equal(payment.outcome, 'CLOSED');
  assert.equal(payment.orderId, order.orderId);
  assert.equal(payment.causationId, commandId);
  assert.deepEqual(await post('payment', '/internal/v1/payments/close', request), payment);
  return payment;
}

async function releaseInventory(order, commandId = uuid(order.command.commandId + ':inventory-service')) {
  return inventoryCommand(order.orderId, 'RELEASE', [], commandId, order.command.commandId);
}

async function acceptResult(order, type, downstream, expected) {
  const result = { schemaVersion: 1, resultId: downstream.resultId, resultType: type, outcome: 'SUCCEEDED',
    orderId: order.orderId, occurredAt: downstream.occurredAt, producer: type === 'PAYMENT'
      ? 'payment-orchestration-service' : 'inventory-service', externalReference: downstream.causationId,
    sourceStatus: downstream.outcome, message: downstream.message, correlationId: downstream.correlationId,
    causationId: downstream.causationId, traceparent };
  assert.equal((await orderCall('/results', result)).status, expected);
  assert.equal((await orderCall('/results', result)).status, 'DUPLICATE_PROCESSED');
}

async function beginProtocol(suffix) {
  const order = await createOrder(suffix);
  const command = { ...envelope(order.orderId, uuid(order.orderNumber)), commandType: 'CANCEL', actorId: customerId,
    expectedStatus: 'PENDING_PAYMENT' };
  assert.equal((await orderCall('/commands', command)).status, 'WAITING_RESULT');
  return { ...order, command };
}

async function historical() {
  const expected = JSON.parse(readFileSync(0, 'utf8'));
  for (const order of expected) {
    const current = await orderState(order.id, order.status, order.inventoryStatus);
    assert.equal(current.orderNumber, order.orderNumber);
    assert.equal(current.totalAmount, order.totalAmount);
  }
  return expected.length;
}

if (phase === 'forward-hold') {
  const order = await beginProtocol('FORWARD');
  order.payment = await paymentClose(order);
  await acceptResult(order, 'PAYMENT', order.payment, 'WAITING_RESULT');
  await orderState(order.orderId, 'CANCELLING', 'RESERVED');
  writeFileSync(statePath, JSON.stringify({ forward: order }));
  console.log(JSON.stringify(order));
} else if (phase === 'forward-resume') {
  const { forward } = JSON.parse(readFileSync(statePath, 'utf8'));
  const matched = await historical();
  await orderState(forward.orderId, 'CANCELLING', 'RESERVED');
  assert.equal((await orderCall('/commands', forward.command)).status, 'DUPLICATE_WAITING_RESULT');
  await orderCall('/commands', { ...forward.command, reason: 'Changed command' }, 409);
  assert.deepEqual(await paymentClose(forward), forward.payment);
  const release = await releaseInventory(forward);
  await acceptResult(forward, 'INVENTORY', release, 'COMPLETED');
  await mutate(forward.orderId, 'UPDATE_INVENTORY_STATUS', { fromStatus: 'RESERVED', toStatus: 'RELEASED' });
  await orderState(forward.orderId, 'CANCELLED', 'RELEASED');
  console.log(JSON.stringify({ forward, release, matched }));
} else if (phase === 'store-cancellation') {
  const order = await createOrder('STORE', true);
  const criteria = { keyword: 'CAFE BOOK', status: 'PENDING_PAYMENT', size: 10, page: 0, productIds: [] };
  const found = await orderCall('/store/search', { customerId, criteria });
  assert(found.orders.some(row => row.id === order.orderId));
  const id = order.orderId;
  const marker = uuid(order.orderNumber);
  const command = { commandId: marker, occurredAt: new Date().toISOString() };
  const reason = { reason: 'Order store cancellation rehearsal' };
  await Promise.all([mutate(id, 'START_CANCELLATION', reason), mutate(id, 'START_CANCELLATION', reason)]);
  await orderState(id, 'CANCELLING', 'RESERVED');
  const payment = await paymentClose({ ...order, command });
  const release = await releaseInventory({ ...order, command });
  const requestId = randomUUID();
  const finish = { mutationId: `order-rehearsal-${id}:FINISH_CANCELLATION`, orderId: id, type: 'FINISH_CANCELLATION' };
  assert.equal((await orderCall('/store/mutations', finish, 200, requestId)).changed, 1);
  await orderCall('/store/mutations', finish, 409, requestId);
  await mutate(id, 'UPDATE_INVENTORY_STATUS', { fromStatus: 'RESERVED', toStatus: 'RELEASED' });
  await orderState(id, 'CANCELLED', 'RELEASED');
  console.log(JSON.stringify({ order, payment, release }));
} else if (phase === 'reverse-hold') {
  const state = JSON.parse(readFileSync(statePath, 'utf8'));
  const order = await beginProtocol('REVERSE');
  order.release = await releaseInventory(order);
  await acceptResult(order, 'INVENTORY', order.release, 'WAITING_RESULT');
  await mutate(order.orderId, 'UPDATE_INVENTORY_STATUS', { fromStatus: 'RESERVED', toStatus: 'RELEASED' });
  await orderState(order.orderId, 'CANCELLING', 'RELEASED');
  writeFileSync(statePath, JSON.stringify({ ...state, reverse: order }));
  console.log(JSON.stringify(order));
} else if (phase === 'rollback') {
  const { forward, reverse } = JSON.parse(readFileSync(statePath, 'utf8'));
  const matched = await historical();
  await orderState(forward.orderId, 'CANCELLED', 'RELEASED');
  await orderState(reverse.orderId, 'CANCELLING', 'RELEASED');
  const payment = await paymentClose(reverse);
  await acceptResult(reverse, 'PAYMENT', payment, 'COMPLETED');
  await orderState(reverse.orderId, 'CANCELLED', 'RELEASED');
  console.log(JSON.stringify({ reverse, payment, matched }));
} else {
  throw new Error('Unknown order rehearsal phase');
}
