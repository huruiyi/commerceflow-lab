import assert from 'node:assert/strict';
import { createHash, createHmac, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { setTimeout as wait } from 'node:timers/promises';

const pay = 'http://orchestrator:8080';
const channel = 'http://channel:8080';
const fixture = 'http://fixture:8080';
const base = '/internal/v1/payments';
const traceparent = '00-12345678901234567890123456789012-1234567890123456-01';

async function call(url, options = {}) {
  const response = await fetch(url, {...options, signal: AbortSignal.timeout(15000)});
  if (!response.ok) throw new Error(new URL(url).pathname + ' returned ' + response.status + ': ' + await response.text());
  return response.json();
}

async function contract(path, value) {
  const raw = JSON.stringify(value);
  const target = base + path;
  const hash = createHash('sha256').update(raw).digest('hex');
  const time = String(Math.floor(Date.now() / 1000));
  const nonce = randomUUID();
  const canonical = ['POST', target, time, nonce, hash].join('\n');
  const signature = createHmac('sha256', 'demo-payment-internal-secret').update(canonical).digest('hex');
  return call(pay + target, {method: 'POST', body: raw, headers: {
    'Content-Type': 'application/json', 'X-CommerceFlow-Service': 'commerce-service',
    'X-CommerceFlow-Timestamp': time, 'X-CommerceFlow-Request-Id': nonce,
    'X-CommerceFlow-Content-SHA256': hash, 'X-CommerceFlow-Signature': signature, traceparent,
  }});
}

async function until(check, description) {
  const end = Date.now() + 45000;
  do {
    const result = await check();
    if (result) return result;
    await wait(350);
  } while (Date.now() < end);
  throw new Error('Timed out waiting for ' + description);
}

function serve() {
  const orders = {};
  const receipts = {};
  const offsite = {};
  const mutations = new Map();
  let allowOffsite = false;
  const orderFor = orderId => orders[orderId] ??= {
    id: orderId, orderNumber: 'PG-REHEARSAL-' + orderId, customerId: 1,
    status: 'PENDING_PAYMENT', inventoryStatus: 'RESERVED',
  };
  createServer(async (req, response) => {
    const send = value => {
      response.setHeader('Content-Type', 'application/json');
      response.end(JSON.stringify(value));
    };
    if (req.url === '/state') return send({orders, receipts, offsite});
    if (req.url === '/allow-offsite' && req.method === 'POST') {
      allowOffsite = true;
      return send({});
    }
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    if (!chunks.length) return response.writeHead(404).end();
    const value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (req.url === '/internal/v1/orders/store/query') {
      return send({found: true, order: orderFor(value.orderId), items: [{merchantId: 1}]});
    }
    if (req.url === '/internal/v1/orders/store/mutations') {
      const existing = mutations.get(value.mutationId);
      if (existing) return send(existing);
      const order = orderFor(value.orderId);
      const previousStatus = order.status;
      if (value.type === 'UPDATE_STATUS') order.status = value.toStatus;
      else if (value.type === 'START_REFUND') order.status = 'REFUNDING';
      else if (value.type === 'START_INVENTORY_COMMIT') order.inventoryStatus = 'WAITING_COMMIT';
      else if (value.type === 'UPDATE_INVENTORY_STATUS') order.inventoryStatus = value.toStatus;
      else if (value.type !== 'INSERT_EVENT') return response.writeHead(422).end();
      const result = {changed: 1, previousStatus};
      mutations.set(value.mutationId, result);
      return send(result);
    }
    if (req.url === '/internal/v1/inventory/commands') {
      const sorted = receipts[value.orderId] ??= {};
      sorted[value.commandId] = value.commandType;
      return send({commandId: value.commandId, outcome: 'SUCCEEDED'});
    }
    if (req.url === '/internal/v1/offsite-notifications') {
      const statuses = offsite[value.eventId] ??= [];
      const status = allowOffsite ? 204 : 503;
      statuses.push(status);
      return response.writeHead(status).end();
    }
    response.writeHead(404).end();
  }).listen(8080);
}

const metadata = orderId => ({schemaVersion: 1, correlationId: 'payment-' + orderId,
  causationId: 'checkout-' + orderId, traceparent});
const lookup = orderId => contract('/query', {schemaVersion: 1, orderId});
const fixtureState = () => call(fixture + '/state');

async function createPayment(orderId) {
  const command = {...metadata(orderId), commandId: randomUUID(), occurredAt: new Date().toISOString(),
    producer: 'commerce-service-ordering', customerId: 1, orderId, orderNumber: 'PG-REHEARSAL-' + orderId,
    channel: 'ALIPAY', amount: 12.34, currency: 'CNY', paymentDueAt: new Date(Date.now() + 1800000).toISOString(),
    idempotencyKey: randomUUID(), simulateFailure: false};
  const payment = await contract('/create', command);
  assert.equal(payment.status, 'PENDING');
  assert.equal((await contract('/create', command)).paymentNumber, payment.paymentNumber);
  const token = new URL(payment.checkoutUrl).pathname.split('/').at(-1);
  return {orderId, paymentNumber: payment.paymentNumber, channelTransactionNo: payment.channelTransactionNo, token};
}

async function completePayment(saved) {
  const callback = await call(channel + '/api/checkout/' + saved.token + '/complete?success=true', {method: 'POST'});
  assert.equal(callback.channelTransactionNo, saved.channelTransactionNo);
  assert.equal(callback.status, 'SUCCESS');
  const settled = (await lookup(saved.orderId)).payment;
  assert.equal(settled.paymentNumber, saved.paymentNumber);
  assert.equal(settled.status, 'SUCCESS');
  await until(async () => (await fixtureState()).orders[saved.orderId]?.inventoryStatus === 'COMMITTED',
    'payment inventory commitment');
  return settled;
}

async function exercise(forward) {
  const paid = await completePayment(forward);
  const toClose = await createPayment(900000000409);
  const close = {...metadata(toClose.orderId), commandId: randomUUID(), occurredAt: new Date().toISOString(),
    producer: 'commerce-service-ordering', orderId: toClose.orderId, reason: 'Isolated switch verification'};
  const closeResult = await contract('/close', close);
  assert.equal(closeResult.outcome, 'CLOSED');
  assert.equal(closeResult.closedPayments, 1);
  assert.equal((await contract('/close', close)).resultId, closeResult.resultId);
  assert.equal((await lookup(toClose.orderId)).payment.status, 'CLOSED');

  const refundCommand = {...metadata(forward.orderId), commandId: randomUUID(), orderId: forward.orderId,
    customerId: 1, idempotencyKey: randomUUID(), reasonCode: 'CUSTOMER_CANCELLED',
    reasonDetail: 'Isolated ledger verification'};
  const refund = await contract('/refunds', refundCommand);
  const offsiteId = 'offsite-refund:' + refund.refundNumber;
  const finished = await until(async () => {
    const value = (await lookup(forward.orderId)).refund;
    const state = await fixtureState();
    return value?.status === 'SUCCEEDED' && state.orders[forward.orderId]?.inventoryStatus === 'REFUNDED'
      && state.offsite[offsiteId]?.includes(503) ? value : null;
  }, 'channel refund, inventory compensation and busy offsite callback');
  assert.equal((await contract('/refunds', refundCommand)).refundNumber, refund.refundNumber);
  const funds = await contract('/invoice-funds', {schemaVersion: 1, orderId: forward.orderId});
  assert.equal(funds.paymentNumber, paid.paymentNumber);
  assert.equal(funds.refundedAmount, 12.34);
  const date = new Date().toISOString().slice(0, 10);
  const report = await contract('/ledger-reports', {startDate: date, endDate: date, timeZone: 'UTC',
    channel: 'ALIPAY', currency: 'CNY'});
  assert.ok(report.totals.length);
  const ownership = await contract('/ledger-reports/ownership-backfill',
    {afterOrderId: forward.orderId - 1, limit: 3});
  assert.ok(ownership.inspected >= 1);

  const pending = await createPayment(900000000410);
  console.log(JSON.stringify({forward: {...forward, token: undefined}, pending,
    closed: {paymentNumber: toClose.paymentNumber, resultId: closeResult.resultId},
    refund: {number: finished.refundNumber, channelRefundNo: finished.channelRefundNo, offsiteId}}));
}

async function resume(saved) {
  const paid = await completePayment(saved.pending);
  const completed = await lookup(saved.forward.orderId);
  assert.equal(completed.refund.channelRefundNo, saved.refund.channelRefundNo);
  assert.equal(completed.payment.paymentNumber, saved.forward.paymentNumber);
  await call(fixture + '/allow-offsite', {method: 'POST'});
  const attempts = await until(async () => {
    const history = (await fixtureState()).offsite[saved.refund.offsiteId];
    return history?.includes(204) ? history : null;
  }, 'original offsite event to be acknowledged after rollback');
  assert.equal(attempts.filter(status => status === 204).length, 1);
  console.log(JSON.stringify({orderId: saved.pending.orderId, paymentNumber: paid.paymentNumber,
    refundNumber: completed.refund.refundNumber, eventId: saved.refund.offsiteId, offsiteResponses: attempts}));
}

const [mode, input] = process.argv.slice(2);
if (mode === 'serve') serve();
else if (mode === 'prepare') console.log(JSON.stringify(await createPayment(900000000408)));
else if (mode === 'exercise') await exercise(JSON.parse(input));
else if (mode === 'resume') await resume(JSON.parse(input));
else throw new Error('Unknown payment orchestration rehearsal phase');
