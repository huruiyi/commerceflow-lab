const assert = require('node:assert/strict');
const { randomUUID, createHash, createHmac } = require('node:crypto');
const fs = require('node:fs/promises');
const path = require('node:path');

const [portal, playwright, payment, carrier, warehouse, evidence] = process.argv.slice(2);
const { chromium } = require(playwright);
for (const endpoint of [portal, payment, carrier, warehouse]) {
  const url = new URL(endpoint);
  assert.equal(url.hostname, '127.0.0.1');
  assert(!['14200', '14201', '18083', '18118'].includes(url.port), 'Use an isolated Compose stack');
}

let checks = 0;
function equal(actual, expected, label) {
  assert.equal(actual, expected, label);
  checks++;
}

async function until(check, label, timeout = 85000) {
  const deadline = Date.now() + timeout;
  do {
    const value = await check();
    if (value) return value;
    await new Promise((resolve) => setTimeout(resolve, 900));
  } while (Date.now() < deadline);
  throw new Error('Timed out: ' + label);
}

async function call(page, route, method = 'GET', data, status) {
  let headers;
  if (method !== 'GET') {
    const token = await (await page.request.get(portal + '/api/auth/csrf')).json();
    headers = { [token.headerName]: token.token };
  }
  const response = await page.request.fetch(portal + route, { method, data, headers });
  const text = await response.text();
  if (status === undefined) equal(response.ok(), true, method + ' ' + route + ' ' + text);
  else equal(response.status(), status, method + ' ' + route + (response.status() === status ? '' : ' ' + text));
  return text && response.ok() ? JSON.parse(text) : null;
}

async function login(browser, email, password = 'demo-password') {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1024 } });
  page.setDefaultTimeout(20000);
  await page.addInitScript(() => localStorage.setItem('commerceflow-locale', 'zh-CN'));
  await page.goto(portal);
  await page.locator('#loginEmail').fill(email);
  await page.locator('#loginPassword').fill(password);
  await clickApi(page, page.locator('button.login-submit'), '/api/auth/login', 200);
  await page.waitForURL('**/dashboard');
  return page;
}

async function clickApi(page, control, route, status, method = 'POST') {
  const result = page.waitForResponse(
    (response) => new URL(response.url()).pathname === route && response.request().method() === method,
  );
  await control.click();
  const response = await result;
  equal(response.status(), status, method + ' ' + route);
  return response.json();
}

async function merchantFixture(browser, admin, label) {
  const suffix = randomUUID().slice(0, 8);
  const email = `exchange-${label}-${suffix}@example.test`;
  const password = randomUUID();
  const account = await call(admin, '/api/users/merchants', 'POST', { email, password, name: 'Exchange ' + label });
  const page = await login(browser, email, password);
  const name = '换货验收店 ' + label;
  await call(page, '/api/merchant/store', 'PUT', { name: name + ' ' + suffix, description: 'Exchange browser QA' });
  const active = await call(admin, `/api/admin/stores/${account.id}/status`, 'PUT', { status: 'ACTIVE' });
  equal(active.status, 'ACTIVE', label + ' shop activated');
  const productName = '换货验收商品 ' + label;
  const sample = await call(admin, '/api/products/1');
  const draft = await call(page, '/api/merchant/catalog/v1/products', 'POST', {
    requestId: randomUUID(), name: productName, description: 'Exchange browser QA', unitPrice: 42, imageUrl: sample.imageUrl,
  });
  await call(page, `/api/merchant/catalog/v1/products/${draft.productId}/status`, 'PUT', {
    status: 'ACTIVE', expectedVersion: draft.businessVersion,
  });
  await until(async () => {
    const product = await page.request.get(portal + '/api/products/' + draft.productId);
    return product.ok();
  }, label + ' product published');
  await until(async () => {
    const inventory = await call(page, '/api/merchant/inventory');
    return inventory.some((item) => item.productId === draft.productId);
  }, label + ' inventory projection');
  await call(page, `/api/merchant/inventory/${draft.productId}/adjustments`, 'POST', {
    delta: 5, reason: 'Exchange browser QA',
  });
  return { page, id: account.id, productId: draft.productId, productName };
}

async function deliver(page, reference) {
  const app = 'commerceflow-storefront';
  const digest = createHash('sha256').update('').digest('hex');
  for (const phase of ['PICKED_UP', 'IN_TRANSIT', 'AT_STATION', 'OUT_FOR_DELIVERY', 'DELIVERED']) {
    const time = String(Math.floor(Date.now() / 1000));
    const nonce = randomUUID().replaceAll('-', '');
    const signature = createHmac('sha256', 'demo-logistics-request-secret')
      .update([app, time, nonce, digest].join('\n')).digest('base64');
    const response = await page.request.post(carrier + `/api/channel/shipments/${reference}/advance`, {
      headers: { 'X-App-Id': app, 'X-Timestamp': time, 'X-Nonce': nonce, 'X-Signature': signature },
    });
    equal(response.status(), 200, reference + ' carrier advance');
    equal((await response.json()).status, phase, reference + ' phase');
  }
}

function submitFulfillment(page, orderId, operation, fields = {}) {
  return call(page, '/api/merchant/orders/fulfillment-v1/commands', 'POST', {
    version: 1,
    commandId: randomUUID(),
    orderId,
    operation,
    occurredAt: new Date().toISOString(),
    ...fields,
  });
}

async function originalOrder(customer, merchant) {
  const address = await call(customer, '/api/addresses', 'POST', {
    recipientName: 'Exchange Lab', phone: '13800000000', countryCode: 'CN', stateProvince: 'Shanghai',
    city: 'Shanghai', district: 'Pudong', addressLine1: '1 Exchange Road', postalCode: '200120',
    label: 'Exchange browser QA', isDefault: false,
  });
  const item = await call(customer, '/api/cart/items', 'POST', { productId: merchant.productId, quantity: 1 });
  const selection = { addressId: address.id, itemIds: [item.id] };
  const quote = await call(customer, '/api/purchase-orders/checkout/quote', 'POST', selection);
  const order = await call(customer, '/api/purchase-orders/checkout', 'POST', { selection, quoteId: quote.quoteId });
  const route = `/api/purchase-orders/${order.id}`;
  await until(async () => (await call(customer, route)).inventoryStatus === 'RESERVED', 'original reservation');
  const attempt = await call(customer, route + '/payments', 'POST', {
    idempotencyKey: randomUUID(), channel: 'ALIPAY', simulateFailure: false,
  });
  const token = new URL(attempt.payment.checkoutUrl).pathname.split('/').at(-1);
  equal((await customer.request.post(payment + `/api/checkout/${token}/complete?success=true`)).status(), 200, 'payment simulator');
  await until(async () => (await call(customer, route)).inventoryStatus === 'COMMITTED', 'payment committed');
  await submitFulfillment(merchant.page, order.id, 'CONFIRM');
  await until(async () => (await call(customer, route)).status === 'CONFIRMED', 'merchant order confirmation');
  await submitFulfillment(merchant.page, order.id, 'SHIP', {
    carrier: 'CommerceFlow Express', trackingNumber: 'UI-ORIGINAL-' + order.id,
  });
  const shipped = await until(async () => {
    const value = await call(customer, route);
    return value.status === 'SHIPPED' && value.logistics?.merchantShipmentNo && value;
  }, 'original shipment');
  await deliver(customer, shipped.logistics.merchantShipmentNo);
  await until(async () => (await call(customer, route)).logistics.status === 'DELIVERED', 'original delivery');
  return call(customer, route + '/receive', 'POST', {});
}

function salePanel(page) {
  return page.locator('app-line-after-sales');
}

function saleCard(page, id) {
  return salePanel(page).locator(`[data-case-id="${id}"]`);
}

async function customerApply(customer, order, merchant) {
  const bar = customer.locator('nav.navbar');
  const entry = bar.locator('a[href="/orders"]');
  equal((await entry.innerText()).trim(), '我的订单', 'customer order entry');
  await entry.click();
  await customer.getByText(order.orderNumber, { exact: true }).click();
  await customer.waitForURL('**/orders/' + order.id);
  const panel = salePanel(customer);
  await panel.getByRole('button', { name: '查看售后' }).click();
  await panel.getByLabel('售后类型').selectOption({ label: '换货' });
  await panel.getByLabel('订单商品').selectOption({ label: merchant.productName });
  await panel.getByLabel('售后原因').fill('双店换货浏览器验收');
  const route = `/api/purchase-orders/${order.id}/after-sales`;
  const quote = await clickApi(customer, panel.getByRole('button', { name: '核对可换数量' }), route + '/quote', 200);
  equal(quote.kind, 'EXCHANGE', 'exchange quote type');
  equal(quote.merchandiseAmount + quote.shippingAmount, 0, 'no price difference');
  const accepted = await clickApi(customer, panel.getByRole('button', { name: '提交换货申请' }), route, 202);
  equal(accepted.merchantId, merchant.id, 'case belongs to original shop');
  equal(accepted.productId, merchant.productId, 'case belongs to original SKU');
  return accepted;
}

async function searchOrders(page, number) {
  const filter = page.locator('app-order-list-filter');
  await filter.locator('input[name=query]').fill(number);
  const response = page.waitForResponse((item) => item.url().includes('/api/merchant/orders') && item.url().includes('q='));
  await filter.getByRole('button', { name: '搜索', exact: true }).click();
  equal((await response).status(), 200, 'merchant order filter');
}

async function merchantOpen(page, order) {
  await page.locator('nav.navbar').getByRole('link', { name: '商家履约' }).click();
  await page.waitForURL('**/merchant/orders');
  await searchOrders(page, order.orderNumber);
  const card = page.locator('.fulfillment-card').filter({ hasText: order.orderNumber });
  await card.waitFor();
  if (!(await card.locator('app-line-after-sales').isVisible())) {
    await card.getByRole('button', { name: '查看详情', exact: true }).click();
  }
  await card.getByRole('button', { name: /^(查看|刷新)售后$/ }).click();
}

async function refreshUntil(page, id, label) {
  await until(async () => {
    if (await saleCard(page, id).getByText(label, { exact: true }).isVisible()) return true;
    await salePanel(page).getByRole('button', { name: '刷新售后' }).click();
    return false;
  }, label);
}

async function advanceExchange(customer, merchant, item) {
  const actor = merchant.page;
  const card = saleCard(actor, item.id);
  const customerCard = saleCard(customer, item.id);
  const merchantPath = `/api/merchant/after-sales/${item.id}`;
  const customerPath = `/api/purchase-orders/after-sales/${item.id}`;
  await card.getByLabel('处理说明').fill('同意原商品换货');
  await clickApi(actor, card.getByRole('button', { name: '同意申请' }), merchantPath + '/review', 200);
  await refreshUntil(customer, item.id, '待退货');
  const returnNumber = 'UI-RETURN-' + item.orderId;
  await customerCard.getByLabel('承运商', { exact: true }).fill('CommerceFlow Express');
  await customerCard.getByLabel('退货运单号', { exact: true }).fill(returnNumber);
  await clickApi(customer, customerCard.getByRole('button', { name: '登记退货' }), customerPath + '/return', 200);
  const inbound = await until(async () => {
    const value = await call(customer, customerPath + '/return-tracking');
    return value?.platformShipmentNo && value;
  }, 'return registered');
  await deliver(customer, inbound.merchantShipmentNo);
  const headers = { Authorization: 'Bearer demo-warehouse-admin-token' };
  const wmsRoute = warehouse + '/api/admin/returns';
  await until(async () => {
    const result = await customer.request.get(wmsRoute + '?orderId=' + item.orderId, { headers });
    return result.ok() && (await result.json()).some((entry) => entry.returnId === item.id);
  }, 'warehouse received return instruction');
  equal((await customer.request.put(wmsRoute + '/' + item.id + '/receipt', {
    headers, data: { warehouseId: item.warehouseId, quantity: item.quantity, rejected: false },
  })).status(), 200, 'warehouse receipt');
  equal((await customer.request.put(wmsRoute + '/' + item.id + '/quality', {
    headers, data: { warehouseId: item.warehouseId, passed: true },
  })).status(), 200, 'warehouse quality');
  await refreshUntil(actor, item.id, '待商家验收');
  await card.getByLabel('处理说明').fill('验收通过');
  await clickApi(actor, card.getByRole('button', { name: '验收并准备补发' }), merchantPath + '/inspection', 200);
  await refreshUntil(actor, item.id, '待补发');
  const replacementNumber = 'UI-REPLACEMENT-' + item.orderId;
  await card.getByLabel('补发承运商').fill('CommerceFlow Express');
  await card.getByLabel('补发运单号').fill(replacementNumber);
  await clickApi(actor, card.getByRole('button', { name: '登记补发' }), merchantPath + '/replacement', 200);
  const outbound = await until(async () => {
    const value = await call(actor, merchantPath + '/replacement-tracking');
    return value?.platformShipmentNo && value;
  }, 'replacement registered');
  assert.notEqual(inbound.platformShipmentNo, outbound.platformShipmentNo, 'distinct shipment references');
  checks++;
  await deliver(actor, outbound.merchantShipmentNo);
  await refreshUntil(actor, item.id, '已完成');
  await refreshUntil(customer, item.id, '已完成');
  return { returnNumber, replacementNumber, inbound: inbound.platformShipmentNo, outbound: outbound.platformShipmentNo };
}

async function verifyParcels(page, item, parcels, merchant = false) {
  const card = saleCard(page, item.id);
  const root = merchant ? '/api/merchant/after-sales/' : '/api/purchase-orders/after-sales/';
  await clickApi(page, card.getByRole('button', { name: '查看退货物流' }), root + item.id + '/return-tracking', 200, 'GET');
  await clickApi(page, card.getByRole('button', { name: '查看补发物流' }), root + item.id + '/replacement-tracking', 200, 'GET');
  await verifyParcelPanels(card, parcels);
}

async function verifyParcelPanels(scope, parcels) {
  for (const [title, number] of [['退回物流', parcels.returnNumber], ['补发物流', parcels.replacementNumber]]) {
    const panel = scope.getByRole('region', { name: title });
    await panel.getByText(number, { exact: false }).waitFor();
    equal((await panel.innerText()).includes('已签收'), true, title + ' delivered');
  }
}

async function main() {
  await fs.mkdir(evidence, { recursive: true });
  const browser = await chromium.launch({ channel: 'msedge' });
  try {
    const admin = await login(browser, 'admin@example.com');
    const customers = [
      await login(browser, 'demo@example.com'),
      await login(browser, 'exchange-buyer-b@example.test'),
    ];
    const merchants = [await merchantFixture(browser, admin, 'A'), await merchantFixture(browser, admin, 'B')];
    const orders = [];
    const cases = [];
    const parcels = [];
    for (let index = 0; index < 2; index++) {
      orders.push(await originalOrder(customers[index], merchants[index]));
      cases.push(await customerApply(customers[index], orders[index], merchants[index]));
      await merchantOpen(merchants[index].page, orders[index]);
    }
    for (let index = 0; index < 2; index++) {
      const other = 1 - index;
      const customer = customers[index];
      const merchant = merchants[index].page;
      const foreign = cases[other].id;
      for (const page of [customer, merchant]) {
        equal(await page.locator('nav.navbar').getByRole('link', { name: '换货管理' }).count(), 0, 'platform entry hidden');
        await call(page, '/api/admin/exchanges', 'GET', undefined, 403);
        await call(page, '/api/admin/exchanges/' + foreign, 'GET', undefined, 403);
      }
      await call(customer, `/api/purchase-orders/${orders[other].id}/after-sales`, 'GET', undefined, 404);
      await call(merchant, `/api/merchant/orders/${orders[other].id}/after-sales`, 'GET', undefined, 404);
      await call(merchant, `/api/merchant/after-sales/${foreign}/review`, 'POST', { accepted: true, note: 'foreign' }, 404);
      await call(merchant, `/api/merchant/after-sales/${foreign}/replacement`, 'POST', {
        carrier: 'CommerceFlow Express', trackingNumber: 'NOT-OWNED',
      }, 404);
      await call(customer, `/api/merchant/after-sales/${cases[index].id}/replacement`, 'POST', {
        carrier: 'CommerceFlow Express', trackingNumber: 'CUSTOMER-DENIED',
      }, 403);
      await searchOrders(merchant, orders[other].orderNumber);
      await merchant.getByText('没有符合这些条件的订单。', { exact: true }).waitFor();
      equal(await merchant.locator('.fulfillment-card').filter({ hasText: orders[other].orderNumber }).count(), 0, 'foreign order hidden');
      await merchantOpen(merchant, orders[index]);
      parcels.push(await advanceExchange(customer, merchants[index], cases[index]));
    }
    for (let index = 0; index < 2; index++) {
      const other = 1 - index;
      for (const [page, root] of [
        [customers[index], '/api/purchase-orders/after-sales/'],
        [merchants[index].page, '/api/merchant/after-sales/'],
      ]) {
        for (const kind of ['return-tracking', 'replacement-tracking']) {
          await call(page, root + cases[other].id + '/' + kind, 'GET', undefined, 404);
        }
      }
      await verifyParcels(customers[index], cases[index], parcels[index]);
      await verifyParcels(merchants[index].page, cases[index], parcels[index], true);
    }
    await customers[0].screenshot({ path: path.join(evidence, 'customer-exchange.png'), fullPage: true });
    await merchants[1].page.screenshot({ path: path.join(evidence, 'merchant-exchange.png'), fullPage: true });
    await admin.locator('nav.navbar').getByRole('link', { name: '换货管理' }).click();
    await admin.waitForURL('**/admin/exchanges');
    for (let index = 0; index < 2; index++) {
      const row = admin.locator(`tr[data-case-id="${cases[index].id}"]`);
      await row.waitFor();
      await clickApi(admin, row.getByRole('button', { name: '查看详情' }), '/api/admin/exchanges/' + cases[index].id, 200, 'GET');
      const detail = admin.locator(`[data-exchange-detail="${cases[index].id}"]`);
      await verifyParcelPanels(detail, parcels[index]);
      equal(await detail.getByRole('button').count(), 0, 'platform exchange details are read-only');
    }
    await admin.screenshot({ path: path.join(evidence, 'platform-exchanges.png'), fullPage: true });
    await admin.getByLabel('订单 ID').fill(String(orders[0].id));
    await clickApi(admin, admin.getByRole('button', { name: '搜索', exact: true }), '/api/admin/exchanges', 200, 'GET');
    await admin.locator(`tr[data-case-id="${cases[0].id}"]`).waitFor();
    equal(await admin.locator(`tr[data-case-id="${cases[1].id}"]`).count(), 0, 'platform order filter');
    for (const page of [customers[0], merchants[0].page]) {
      await page.goto(portal + '/admin/exchanges');
      await page.waitForURL('**/dashboard');
      equal(await page.locator('app-platform-exchanges').count(), 0, 'platform route denied');
    }
    await call(admin, `/api/merchant/after-sales/${cases[0].id}/replacement`, 'POST', {
      carrier: 'CommerceFlow Express', trackingNumber: 'PLATFORM-DENIED',
    }, 403);
    const report = {
      checks,
      samples: orders.map((order, index) => ({
        orderId: order.id, merchantId: merchants[index].id, caseId: cases[index].id, ...parcels[index],
      })),
    };
    await fs.writeFile(path.join(evidence, 'result.json'), JSON.stringify(report, null, 2));
    process.stdout.write(JSON.stringify(report) + '\n');
  } finally {
    await browser.close();
  }
}

main().catch((failure) => {
  process.stderr.write(failure.stack + '\n');
  process.exitCode = 1;
});
