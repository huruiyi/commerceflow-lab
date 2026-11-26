import assert from 'node:assert/strict';
import { createHash, createHmac, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { setTimeout as wait } from 'node:timers/promises';

const simulator = 'http://simulator:8080';
const fixture = 'http://fixture:8080';
const payoutCallbackSecret = 'payment-isolated-payout-callback';
const callbackSecret = 'payment-isolated-callback';
const txPath = '/api/channel/transactions';
const refundPath = '/api/channel/refunds';
const payoutPath = '/api/channel/payouts';

function digest(secret, raw) {
  return createHmac('sha256', secret).update(raw).digest('base64');
}

async function control(path, method = 'GET') {
  const response = await fetch(fixture + path, { method, signal: AbortSignal.timeout(5000) });
  assert.equal(response.status, 200, path);
  return response.json();
}

async function request(method, target, payload, expected = 200, merchant = 'alipay') {
  const raw = payload === undefined ? '' : JSON.stringify(payload);
  const timestamp = String(Math.floor(Date.now() / 1000));
  const nonce = randomUUID();
  const requestId = randomUUID();
  const hash = createHash('sha256').update(raw).digest('hex');
  const secret = merchant === 'payout' ? 'demo-merchant-payout-secret' : 'demo-' + merchant + '-secret';
  const signature = digest(secret, [method, target, timestamp, nonce, requestId, hash].join('\n'));
  const headers = target.startsWith('/api/channel/') ? {
    'X-App-Id': 'commerceflow-' + merchant, 'X-Signature-Algorithm': 'HMAC_SHA256',
    'X-Timestamp': timestamp, 'X-Nonce': nonce, 'X-Request-Id': requestId,
    'X-Body-SHA256': hash, 'X-Signature': signature,
  } : {};
  if (raw) headers['Content-Type'] = 'application/json';
  const response = await fetch(simulator + target, {
    method, headers, body: raw || undefined, signal: AbortSignal.timeout(12000),
  });
  assert.equal(response.status, expected, method + ' ' + target);
  return expected === 200 ? response.json() : null;
}

async function until(check, label) {
  const deadline = Date.now() + 40000;
  do {
    const result = await check();
    if (result) return result;
    await wait(350);
  } while (Date.now() < deadline);
  throw new Error('Timed out waiting for ' + label);
}

function serve() {
  const seen = new Map();
  const acceptedPayouts = new Set();
  createServer(async (req, res) => {
    if (req.url === '/state') {
      res.end(JSON.stringify(Object.fromEntries(seen)));
      return;
    }
    const allow = /^\/allow-payout\/([a-f0-9-]{36})$/.exec(req.url);
    if (allow && req.method === 'POST') {
      acceptedPayouts.add(allow[1]);
      res.end('{}');
      return;
    }
    const kind = req.url?.slice(1);
    if (!['payment', 'refund', 'payout'].includes(kind) || req.method !== 'POST') {
      res.writeHead(404).end();
      return;
    }
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const raw = Buffer.concat(chunks).toString('utf8');
    const payout = kind === 'payout';
    const header = payout ? 'x-payout-signature' : 'x-callback-signature';
    if (req.headers[header] !== digest(payout ? payoutCallbackSecret : callbackSecret, raw)) {
      res.writeHead(401).end();
      return;
    }
    const data = JSON.parse(raw);
    const key = data.channelTransactionNo ?? data.channelRefundNo ?? data.attemptId;
    const history = seen.get(key) ?? [];
    const status = (payout ? acceptedPayouts.has(key) : history.length >= (kind === 'refund' ? 2 : 1)) ? 200 : 503;
    history.push({ status, data });
    seen.set(key, history);
    res.writeHead(status).end();
  }).listen(8080);
}

async function exercise() {
  const suffix = randomUUID();
  const paymentRequest = {
    merchantRequestNo: 'pg-payment-' + suffix, channel: 'ALIPAY', subject: 'PostgreSQL payment rehearsal',
    amount: 30.5, currency: 'CNY',
  };
  const concurrent = await Promise.all(Array.from({length: 4}, () => request('POST', txPath, paymentRequest)));
  const payment = concurrent[0];
  assert.equal(payment.status, 'PENDING');
  for (const replay of concurrent) assert.equal(replay.channelTransactionNo, payment.channelTransactionNo);
  const paymentQuery = txPath + '?merchantRequestNo=' + paymentRequest.merchantRequestNo;
  const token = new URL(payment.checkoutUrl).pathname.split('/').at(-1);
  const checkout = '/api/checkout/' + token + '/complete';
  await request('POST', checkout, undefined, 503);
  assert.equal((await request('GET', paymentQuery)).status, 'PENDING');
  const paid = await request('POST', checkout);
  assert.equal(paid.status, 'SUCCESS');
  assert.equal(paid.channelTransactionNo, payment.channelTransactionNo);
  assert.ok(paid.paidAt);
  assert.equal((await request('POST', checkout)).paidAt, (await request('GET', paymentQuery)).paidAt);
  await request('POST', txPath + '/' + paid.channelTransactionNo + '/close', undefined, 409);

  const closeRequest = {...paymentRequest, merchantRequestNo: 'pg-close-' + suffix, channel: 'WECHAT_PAY'};
  const pendingClose = await request('POST', txPath, closeRequest, 200, 'wechat-pay');
  const closePath = txPath + '/' + pendingClose.channelTransactionNo + '/close';
  const closed = await request('POST', closePath, undefined, 200, 'wechat-pay');
  assert.equal(closed.status, 'CLOSED');
  assert.ok(closed.closedAt);
  const closedQuery = txPath + '?merchantRequestNo=' + closeRequest.merchantRequestNo;
  assert.equal((await request('POST', closePath, undefined, 200, 'wechat-pay')).closedAt,
    (await request('GET', closedQuery, undefined, 200, 'wechat-pay')).closedAt);
  assert.equal(closed.paidAt, null);

  const refundRequest = {
    merchantRefundNo: 'pg-refund-' + suffix, channelTransactionNo: paid.channelTransactionNo,
    amount: 12.75, currency: 'CNY', reasonCode: 'CUSTOMER_REQUEST',
  };
  const refund = await request('POST', refundPath, refundRequest);
  assert.equal(refund.status, 'SUCCEEDED');
  const refundQuery = refundPath + '?merchantRefundNo=' + refundRequest.merchantRefundNo;
  assert.equal((await request('GET', refundQuery)).channelRefundNo, refund.channelRefundNo);
  assert.equal((await request('POST', refundPath, refundRequest)).channelRefundNo, refund.channelRefundNo);
  await request('POST', refundPath, {...refundRequest, merchantRefundNo: 'pg-excess-' + suffix, amount: 20}, 409);
  const refundAttempts = (await control('/state'))[refund.channelRefundNo];
  assert.deepEqual(refundAttempts.map(item => item.status), [503]);
  await request('POST', refundPath + '/' + refundRequest.merchantRefundNo + '/notify', undefined, 503);
  const redelivered = await request('POST', refundPath + '/' + refundRequest.merchantRefundNo + '/notify');
  assert.equal(redelivered.channelRefundNo, refund.channelRefundNo);

  const callbackState = await control('/state');
  const paymentAttempts = callbackState[paid.channelTransactionNo];
  for (const item of paymentAttempts) {
    assert.equal(item.data.channelTransactionNo, paid.channelTransactionNo);
    assert.equal(item.data.status, 'SUCCESS');
    assert.equal(item.data.amount, 30.5);
    assert.equal(item.data.currency, 'CNY');
  }
  assert.deepEqual(paymentAttempts.map(item => item.status), [503, 200]);
  const refundDelivered = callbackState[refund.channelRefundNo];
  for (const item of refundDelivered) {
    assert.equal(item.data.channelRefundNo, refund.channelRefundNo);
    assert.equal(item.data.merchantRefundNo, refundRequest.merchantRefundNo);
    assert.equal(item.data.status, 'SUCCEEDED');
    assert.equal(item.data.amount, 12.75);
    assert.equal(item.data.currency, 'CNY');
  }
  assert.deepEqual(refundDelivered.map(item => item.status), [503, 503, 200]);

  const payout = await submitPayout();
  const completedPayout = await submitPayout();
  const attempts = await until(async () => {
    const history = (await control('/state'))[payout.attemptId];
    return history?.length >= 2 ? history : null;
  }, 'the isolated payout scheduler to retry after a 503');
  assert.ok(attempts.every(attempt => attempt.status === 503));
  assert.ok(attempts.every(attempt => attempt.data.channelPayoutNo === payout.number));
  assert.ok(attempts.every(attempt => attempt.data.status === 'SUCCEEDED'));
  const pgPayout = await acceptPayout(completedPayout);
  console.log(JSON.stringify({
    payment: {request: paymentRequest.merchantRequestNo, number: paid.channelTransactionNo,
      callbacks: paymentAttempts.map(item => item.status)},
    close: {request: closeRequest.merchantRequestNo, number: closed.channelTransactionNo},
    refund: {request: refundRequest.merchantRefundNo, number: refund.channelRefundNo,
      callbacks: refundDelivered.map(item => item.status)},
    payout, pgPayout,
  }));
}

async function submitPayout() {
  const payout = {
    attemptId: randomUUID(), statementId: randomUUID(), beneficiaryId: 1,
    amount: 17.25, simulateFailure: false,
  };
  const submitted = await request('POST', payoutPath, payout, 200, 'payout');
  assert.equal((await request('POST', payoutPath, payout, 200, 'payout')).channelPayoutNo, submitted.channelPayoutNo);
  await request('POST', payoutPath, {...payout, attemptId: randomUUID()}, 409, 'payout');
  return { attemptId: payout.attemptId, statementId: payout.statementId, number: submitted.channelPayoutNo };
}

async function resume(saved) {
  const paid = await request('GET', txPath + '?merchantRequestNo=' + saved.payment.request);
  assert.equal(paid.channelTransactionNo, saved.payment.number);
  assert.equal(paid.status, 'SUCCESS');
  const closed = await request('GET', txPath + '?merchantRequestNo=' + saved.close.request, undefined, 200, 'wechat-pay');
  assert.equal(closed.channelTransactionNo, saved.close.number);
  assert.equal(closed.status, 'CLOSED');
  const refund = await request('GET', refundPath + '?merchantRefundNo=' + saved.refund.request);
  assert.equal(refund.channelRefundNo, saved.refund.number);
  assert.equal(refund.status, 'SUCCEEDED');
  console.log(JSON.stringify(await acceptPayout(saved.payout)));
}

async function acceptPayout(saved) {
  await control('/allow-payout/' + saved.attemptId, 'POST');
  const attempts = await until(async () => {
    const history = (await control('/state'))[saved.attemptId] ?? [];
    return history.some(item => item.status === 200) ? history : null;
  }, 'the original payout callback to succeed');
  assert.ok(attempts.some(item => item.status === 503));
  assert.equal(attempts.filter(item => item.status === 200).length, 1);
  assert.ok(attempts.every(item => item.data.attemptId === saved.attemptId
    && item.data.statementId === saved.statementId && item.data.channelPayoutNo === saved.number));
  const notified = await until(async () => {
    const payout = await request('GET', payoutPath + '/' + saved.attemptId, undefined, 200, 'payout');
    return payout.notifiedAt && payout.status === 'SUCCEEDED' ? payout : null;
  }, 'the payout callback receipt');
  return {attemptId: notified.attemptId, channelPayoutNo: notified.channelPayoutNo,
    notifiedAt: notified.notifiedAt, callbackStatuses: attempts.map(item => item.status)};
}

const [mode, result] = process.argv.slice(2);
if (mode === 'serve') serve();
else if (mode === 'exercise') await exercise();
else if (mode === 'resume') await resume(JSON.parse(result));
else throw new Error('Unknown rehearsal phase.');
