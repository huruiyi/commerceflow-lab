import assert from 'node:assert/strict';
import { createHash, createHmac, timingSafeEqual } from 'node:crypto';
import { createServer } from 'node:http';

const orders = {
  9100751: { status: 'CONFIRMED', version: 0, line: { orderLineId: 711, productId: 11, quantity: 1 } },
  9100752: { status: 'CONFIRMED', version: 0, line: { orderLineId: 721, productId: 12, quantity: 2 } },
  9100753: { status: 'PAID', version: 0, line: { orderLineId: 731, productId: 13, quantity: 1 } },
};
const effects = { order: {}, warehouse: {}, inventory: {}, logistics: {}, package: {} };

function perRequest(req, role, raw) {
  const timestamp = req.headers['x-commerceflow-timestamp'];
  const id = req.headers['x-commerceflow-request-id'];
  const hash = createHash('sha256').update(raw).digest('hex');
  const expected = createHmac('sha256', 'fulfillment-isolated-' + role)
    .update(['POST', req.url, timestamp, id, hash].join('\n')).digest('hex');
  const actual = req.headers['x-commerceflow-signature'] ?? '';
  assert.equal(req.headers['x-commerceflow-service'], 'fulfillment-service');
  assert(/^\d+$/.test(timestamp) && Math.abs(Date.now() / 1000 - Number(timestamp)) <= 300);
  assert.equal(hash, req.headers['x-commerceflow-content-sha256']);
  assert.equal(actual.length, expected.length);
  assert(timingSafeEqual(Buffer.from(actual), Buffer.from(expected)));
  return id;
}

function record(group, id, body) {
  assert.match(id, /^[a-f0-9-]{36}$/);
  let result = effects[group][id];
  if (!result) result = effects[group][id] = { body, attempts: 0 };
  assert.deepEqual(body, result.body, group + ' request replay must preserve payload');
  ++result.attempts;
  return result;
}

function respond(res, value, requestId, status = 200) {
  const payload = JSON.stringify(value);
  res.setHeader('Content-Type', 'application/json');
  if (requestId) {
    const signature = createHmac('sha256', 'fulfillment-isolated-warehouse')
      .update(requestId + '\n' + payload).digest('hex');
    res.setHeader('X-CommerceFlow-Receipt-Signature', signature);
  }
  res.statusCode = status;
  res.end(payload);
}

const orderPath = '/internal/v1/orders/store';
const logisticsPath = '/internal/v1/logistics/fulfillment';
createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/state') return respond(res, { orders, effects });
  try {
    assert.equal(req.method, 'POST');
    const role = req.url.startsWith(orderPath) ? 'order' : req.url.startsWith(logisticsPath)
      ? 'logistics' : req.url.startsWith('/internal/v1/inventory/') ? 'inventory' : 'warehouse';
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const raw = Buffer.concat(chunks);
    const id = perRequest(req, role, raw);
    const body = JSON.parse(raw);
    const order = orders[body.orderId];
    assert(order, 'unknown fixture order');
    switch (req.url) {
      case orderPath + '/query':
        assert.equal(body.merchantId, 5);
        if (body.type === 'SHIPMENT_LINES') {
          const line = order.line;
          return respond(res, { found: true, order: { id: body.orderId },
            items: [{ id: line.orderLineId, productId: line.productId, quantity: line.quantity, merchantId: 5 }] });
        }
        assert.equal(body.type, 'FIND_MERCHANT');
        return respond(res, { found: true, order: { id: body.orderId, orderNumber: 'ORD-REHEARSAL-' + body.orderId,
          status: order.status, inventoryStatus: 'COMMITTED', shipmentVersion: order.version } });
      case orderPath + '/mutations': {
        assert.equal(body.actorId, 5);
        const entry = record('order', body.mutationId, body);
        if (entry.attempts === 1) {
          if (body.type === 'MERCHANT_SHIP') order.status = 'SHIPPED';
          else if (body.type === 'MERCHANT_CONFIRM') order.status = 'CONFIRMED';
          else assert.equal(body.type, 'RESERVE_SHIPMENT');
        }
        return respond(res, { changed: 1 });
      }
      case orderPath + '/packages/reserve': {
        assert.equal(body.merchantId, 5);
        assert.equal(body.warehouseId, 2);
        assert.deepEqual(body.lines, [{ orderLineId: order.line.orderLineId, quantity: 1 }]);
        const entry = record('package', body.packageId, body);
        const lines = [{ ...order.line, quantity: 1 }];
        return respond(res, { accepted: true, lines, actualLines: entry.actual ? lines : null });
      }
      case orderPath + '/packages/actual': {
        const entry = effects.package[body.packageId];
        assert(entry);
        assert.equal(body.warehouseId, 2);
        assert.deepEqual(body.lines, [{ orderLineId: order.line.orderLineId, quantity: 1 }]);
        entry.actual = true;
        return respond(res, { recorded: true });
      }
      case orderPath + '/packages/finish': {
        const entry = effects.package[body.packageId];
        assert(entry?.actual);
        entry.finished = true;
        order.status = 'SHIPPED';
        return respond(res, { finished: true });
      }
      case '/internal/v1/outbounds': {
        assert.equal(body.merchantId, 5);
        const first = body.orderId === 9100751;
        assert.equal(body.warehouseId, first ? 1 : 2);
        assert.equal(body.wholeOrder, first);
        assert.deepEqual(body.lines, [{ ...order.line, quantity: 1 }]);
        const entry = record('warehouse', body.outboundId, body);
        if (!first && entry.attempts === 1) return respond(res, { unavailable: true }, id, 503);
        if (!first && entry.attempts === 2) return respond(res, { ...body, status: 'PENDING' }, id);
        return respond(res, { ...body, status: 'DISPATCHED',
          actual: [{ orderLineId: order.line.orderLineId, quantity: 1 }] }, id);
      }
      case '/internal/v1/inventory/outbound-documents': {
        assert.equal(body.merchantId, 5);
        assert.deepEqual(body.lines, [{ ...order.line, quantity: 1 }]);
        record('inventory', body.outboundId, body);
        return respond(res, { outboundId: body.outboundId, orderId: body.orderId, lineCount: body.lines.length });
      }
      case logisticsPath + '/commands': {
        assert.equal(body.operation, 'ENSURE_SHIPMENT');
        assert.equal(body.orderNumber, 'ORD-REHEARSAL-' + body.orderId);
        const entry = record('logistics', body.commandId, body);
        entry.receipt = { commandId: body.commandId, platformShipmentNo: 'LGS-REHEARSAL-' + body.orderId,
          shipmentVersion: 0 };
        if (body.orderId === 9100751 && entry.attempts === 1) {
          entry.heldAfterReceipt = true;
          return;
        }
        return respond(res, entry.receipt);
      }
      case logisticsPath + '/shipment-checks':
        return respond(res, { present: Object.values(effects.logistics).some(row => row.body.orderId === body.orderId) });
      default:
        throw new Error('Unrecognized fixture endpoint ' + req.url);
    }
  } catch (error) {
    console.error(error.message);
    respond(res, { error: error.message }, undefined, 400);
  }
}).listen(8080, '0.0.0.0');
