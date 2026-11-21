const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require(process.argv[3]);
const [portal, , payment, proxy, screenshots] = process.argv.slice(2);
const merchantRoute = '/merchant/invoices';
const adminRoute = '/admin/invoices';
const customerRoute = '/invoices';
let checks = 0;

function equal(actual, expected, message) {
  assert.equal(actual, expected, message);
  checks++;
}

async function login(browser, email, invoiceRoute) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 980 }, locale: 'zh-CN' });
  page.setDefaultTimeout(20000);
  await page.addInitScript(() => localStorage.setItem('commerceflow-locale', 'zh-CN'));
  await page.goto(portal);
  await page.locator('#loginEmail').fill(email);
  await page.locator('#loginPassword').fill('demo-password');
  const response = page.waitForResponse((result) => result.url().endsWith('/api/auth/login'));
  await page.locator('button.login-submit').click();
  equal((await response).status(), 200, email);
  await page.waitForURL('**/dashboard');
  const bar = page.locator('nav.navbar');
  await bar.waitFor();
  const nav = bar.getByRole('link', { name: '电子发票', exact: true });
  if (invoiceRoute) await nav.waitFor();
  equal(await nav.count(), invoiceRoute ? 1 : 0, email + ' navigation visibility');
  if (invoiceRoute) {
    equal(await nav.getAttribute('href'), invoiceRoute, email + ' invoice entry');
    await nav.click();
    await page.waitForURL('**' + invoiceRoute);
    await page.locator('.invoice-center[aria-busy="false"]').waitFor();
  }
  return page;
}

async function apply(page, id, fromOrder = false) {
  if (fromOrder) {
    await page.goto(portal + '/orders/' + id);
    await page.locator('article.order-card').getByRole('link', { name: '电子发票', exact: true }).click();
  } else {
    await page.goto(portal + '/orders/' + id + '/invoices');
  }
  const applyButton = page.getByRole('button', { name: '申请发票', exact: true });
  const response = page.waitForResponse((result) =>
    result.url().endsWith('/api/purchase-orders/' + id + '/invoices') && result.request().method() === 'POST',
  );
  await applyButton.click();
  const issued = await response;
  equal(issued.status(), 202, 'request invoice for order ' + id);
  return (await issued.json()).applicationNo;
}

async function waitForText(page, testId, content, refresh = false) {
  const element = page.getByTestId(testId).filter({ hasText: content });
  if (refresh) {
    const deadline = Date.now() + 75000;
    while (!(await element.isVisible()) && Date.now() < deadline) {
      await page.getByRole('button', { name: '刷新', exact: true }).click();
      await page.locator('.invoice-center[aria-busy="false"]').waitFor();
      if (!(await element.isVisible())) await page.waitForTimeout(1000);
    }
  }
  await element.waitFor({ timeout: refresh ? 1000 : 75000 });
  equal((await element.innerText()).trim(), content, testId);
}

async function ok(page, url, status) {
  const response = await page.request.get(portal + url);
  equal(response.status(), status, url);
  return response;
}

async function postWithCsrf(page, url, data) {
  const csrf = await (await page.request.get(portal + '/api/auth/csrf')).json();
  return page.request.post(portal + url, { data, headers: { [csrf.headerName]: csrf.token } });
}

async function attempts(page, id, status, note) {
  const response = await postWithCsrf(page, '/api/admin/invoices/' + id + '/review-actions', { note });
  equal(response.status(), status, 'review action on ' + id);
}

async function filter(page, q, status = '') {
  await page.getByLabel('订单号或申请号').fill(q);
  await page.getByLabel('发票状态').selectOption(status);
  const response = page.waitForResponse((result) => /\/api\/(?:admin\/|merchant\/)?invoices\?/.test(result.url()));
  await page.getByRole('button', { name: '查询', exact: true }).click();
  equal((await response).status(), 200, 'invoice list query');
}

async function capture(page, file) {
  if (!screenshots) return;
  await fs.mkdir(screenshots, { recursive: true });
  await page.screenshot({ path: path.join(screenshots, file), fullPage: true });
}

async function verifyStaffStore(browser, suffix, ownId, otherId, otherCode, amount) {
  const page = await login(browser, 'invoice-store-' + suffix + '@example.test', merchantRoute);
  await page.getByRole('heading', { name: '本店票据', exact: true }).waitFor();
  const mine = page.getByTestId('invoice-row-' + ownId);
  await mine.waitFor();
  equal(await page.getByTestId('invoice-row-' + otherId).count(), 0, 'store ' + suffix + ' list excludes other store');
  await mine.getByRole('link').click();
  await waitForText(page, 'invoice-effective', amount, true);
  equal(await page.getByRole('button', { name: '重新核对' }).count(), 0, 'merchant cannot review');
  const detail = (await (await ok(page, '/api/merchant/invoices/' + ownId, 200)).json());
  assert.ok(!Object.hasOwn(detail, 'customerId') && !Object.hasOwn(detail, 'reviewDetail') &&
    !Object.hasOwn(detail.issue, 'requestId'), 'merchant DTO omits customer and operator data');
  checks++;
  await ok(page, '/api/merchant/invoices/' + otherId, 404);
  await ok(page, '/api/admin/invoices', 403);
  await ok(page, '/api/invoices', 403);
  await attempts(page, 9203, 403, 'cross-role attempt');
  await page.goto(portal + merchantRoute + '/' + otherId);
  await page.getByText('找不到可访问的发票或订单。', { exact: true }).waitFor();
  await page.getByRole('link', { name: '返回发票列表' }).click();
  await filter(page, otherCode);
  await page.getByText('没有匹配的发票。', { exact: true }).waitFor();
  const otherMerchant = suffix === 'a' ? 92002 : 92001;
  const guessed = await (await ok(page, '/api/merchant/invoices?merchantId=' + otherMerchant, 200)).json();
  equal(guessed.rows.some((row) => row.orderId === otherId), false, 'merchant parameter cannot expand scope');
  await page.goto(portal + merchantRoute + '/' + ownId);
  await waitForText(page, 'invoice-effective', amount);
  return page;
}

async function review(page, orderId, note) {
  await page.getByLabel('复核说明').fill(note);
  const response = page.waitForResponse((result) => result.url().endsWith('/' + orderId + '/review-actions'));
  await page.getByRole('button', { name: '重新核对', exact: true }).click();
  equal((await response).status(), 202, 'operator requests review for ' + orderId);
}

async function refundStoreB(page) {
  const body = JSON.stringify({
    notificationId: crypto.randomUUID(),
    merchantRefundNo: 'INVOICE-STORE-B-REFUND',
    channelRefundNo: 'CH-INVOICE-STORE-B-REFUND',
    channel: 'ALIPAY',
    status: 'SUCCEEDED',
    amount: 30,
    currency: 'CNY',
    failureReason: '',
  });
  const signature = crypto.createHmac('sha256', 'invoice-commerce-verification-payment-callback').update(body).digest('base64');
  const response = await page.request.post(payment + '/api/refund-notifications/callback', {
    data: body,
    headers: { 'Content-Type': 'application/json', 'X-Callback-Signature': signature },
  });
  equal(response.status(), 200, 'signed refund callback');
  equal((await response.json()).result, 'APPLIED_REFUND_SUCCEEDED', 'store B refund confirmed');
}

(async () => {
  const browser = await chromium.launch();
  try {
    const guest = await browser.newPage();
    await guest.goto(portal + customerRoute);
    await guest.waitForURL((url) => url.searchParams.get('returnUrl') === customerRoute);
    await ok(guest, '/api/invoices', 401);
    await guest.close();

    const customerA = await login(browser, 'demo@example.com', customerRoute);
    await customerA.getByRole('heading', { name: '我的发票', exact: true }).waitFor();
    const appA = await apply(customerA, 9201, true);
    equal((await (await ok(customerA, '/api/purchase-orders/9201/invoices', 200)).json()).applicationNo,
      appA, 'customer application stable');
    const customerB = await login(browser, 'invoice-other@example.test', customerRoute);
    await apply(customerB, 9202);
    await waitForText(customerA, 'invoice-effective', '120.00 CNY');
    await waitForText(customerB, 'invoice-effective', '180.00 CNY');
    await capture(customerA, 'customer-invoice.png');
    console.log('Customer invoices issued for both stores (120.00 and 180.00 CNY).');

    await ok(customerA, '/api/purchase-orders/9202/invoices', 404);
    const wrongOrder = await postWithCsrf(customerA, '/api/purchase-orders/9202/invoices', {});
    equal(wrongOrder.status(), 404, 'customer cannot submit on an order owned by another user');
    await ok(customerB, '/api/purchase-orders/9201/invoices/eligibility', 404);
    await ok(customerA, '/api/merchant/invoices', 403);
    await ok(customerA, '/api/admin/invoices', 403);
    await customerA.goto(portal + '/orders/9202/invoices');
    await customerA.getByText('找不到可访问的发票或订单。', { exact: true }).waitFor();
    equal(await customerA.getByRole('button', { name: '申请发票' }).count(), 0, 'another order cannot be invoiced');
    await customerA.goto(portal + customerRoute);
    await customerA.getByTestId('invoice-row-9201').waitFor();
    equal(await customerA.getByTestId('invoice-row-9202').count(), 0, 'customer list scoped to owner');

    await refundStoreB(customerB);
    const armed = await customerB.request.post(proxy + '/fixture/fail-three-issues');
    equal(armed.status(), 204, 'three deterministic terminal channel failures armed');
    await apply(customerB, 9204);
    await apply(customerA, 9203);

    const storeA = await verifyStaffStore(browser, 'a', 9201, 9202, 'INVOICE-STORE-B', '120.00 CNY');
    const storeB = await verifyStaffStore(browser, 'b', 9202, 9201, 'INVOICE-STORE-A', '150.00 CNY');
    await storeB.getByText('INVOICE-STORE-B-REFUND', { exact: true }).waitFor();
    await storeB.getByText('30.00 CNY', { exact: true }).waitFor();
    await capture(storeB, 'merchant-invoice.png');
    await storeB.setViewportSize({ width: 390, height: 844 });
    equal(await storeB.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true, '390px layout');
    await capture(storeB, 'merchant-invoice-mobile.png');
    console.log('Both merchants isolated; store B red invoice shown with balance 150.00 CNY.');

    const denied = await login(browser, 'invoice-denied@example.test', null);
    await denied.goto(portal + adminRoute);
    await denied.waitForURL('**/dashboard');
    await ok(denied, '/api/admin/invoices', 403);
    await ok(denied, '/api/admin/invoices/9203', 403);
    await attempts(denied, 9203, 403, 'not authorized');

    const operator = await login(browser, 'invoice-operator@example.test', adminRoute);
    await operator.getByRole('heading', { name: '电子发票管理', exact: true }).waitFor();
    await operator.getByTestId('invoice-row-9201').waitFor();
    await operator.getByTestId('invoice-row-9202').waitFor();
    await attempts(operator, 9201, 409, 'completed invoices cannot be reopened');
    await filter(operator, 'INVOICE-REVIEW', 'REVIEW_REQUIRED');
    await operator.getByTestId('invoice-row-9203').getByRole('link').click();
    await operator.getByText('ORDER_PAYMENT_AMOUNT_MISMATCH', { exact: true }).waitFor();
    const note = '核对支付账本与订单后复查';
    await review(operator, 9203, note);
    await operator.getByText(note, { exact: true }).waitFor();
    await operator.getByRole('button', { name: '重新核对', exact: true }).waitFor();
    await capture(operator, 'operator-review.png');

    await operator.goto(portal + adminRoute + '/9204');
    await operator.getByTestId('invoice-detail').getByText('待核对', { exact: true }).waitFor({ timeout: 75000 });
    await operator.getByText('ISSUE_RETRIES_EXHAUSTED', { exact: true }).waitFor();
    equal(await operator.getByText('SIMULATED_CHANNEL_FAILURE', { exact: true }).count(), 3, 'all failed attempts retained');
    await review(operator, 9204, '模拟渠道恢复，重新发起开票');
    await waitForText(operator, 'invoice-effective', '90.00 CNY');
    await operator.getByText('模拟渠道恢复，重新发起开票', { exact: true }).waitFor();
    equal(await operator.getByText('SIMULATED_CHANNEL_FAILURE', { exact: true }).count(), 3, 'retry preserves failed history');
    await operator.goto(portal + adminRoute);
    await filter(operator, 'INVOICE-FAILED', 'ISSUED');
    await operator.getByTestId('invoice-row-9204').waitFor();
    await storeA.close();
    await storeB.close();
    console.log('PASS: Chromium ' + checks + ' assertions, two customers, two stores, authorized and unauthorized administrators.');
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
