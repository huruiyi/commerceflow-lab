const assert = require('node:assert/strict');
const { chromium } = require(process.argv[3]);
const baseUrl = process.argv[2];
const day = '2025-03-09';
const nextDay = '2025-03-10';
const merchantRoute = '/merchant/payment-ledger-reports';
const adminRoute = '/admin/payment-ledger-reports';

async function signIn(browser, email, path) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1080 }, locale: 'zh-CN' });
  page.setDefaultTimeout(15000);
  await page.addInitScript(() => localStorage.setItem('commerceflow-locale', 'zh-CN'));
  await page.goto(baseUrl);
  await page.locator('#loginEmail').fill(email);
  await page.locator('#loginPassword').fill('demo-password');
  const login = page.waitForResponse((response) => response.url().endsWith('/api/auth/login'));
  await page.locator('button.login-submit').click();
  assert.equal((await login).status(), 200, email);

  const link = page.getByRole('link', { name: '支付运营统计', exact: true });
  await link.waitFor();
  assert.equal(await link.getAttribute('href'), path, email + ' navigation');
  await link.click();
  await page.waitForURL('**' + path);
  await page.getByRole('heading', { name: '支付运营统计' }).waitFor();
  await page.locator('.results[aria-busy="false"]').waitFor();
  return page;
}

async function filters(page, options = {}) {
  const { start = day, end = day, zone = 'Asia/Shanghai', channel = 'ALIPAY', currency = 'CNY', merchant = '' } = options;
  await page.locator('input[name="startDate"]').fill(start);
  await page.locator('input[name="endDate"]').fill(end);
  await page.locator('select[name="timeZone"]').selectOption(zone);
  await page.locator('select[name="channel"]').selectOption(channel);
  await page.locator('select[name="currency"]').selectOption(currency);
  await page.locator('select[name="merchantId"]').selectOption(merchant);
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await page.getByTestId('ledger-period').filter({ hasText: start + ' – ' + end + ' · ' + zone }).waitFor();
}

async function amounts(page, received, refunded, net) {
  for (const [metric, value] of Object.entries({ received, refunded, net })) {
    const cell = page.getByTestId('ledger-' + metric);
    await cell.filter({ hasText: value }).waitFor();
    assert.equal((await cell.innerText()).trim(), value, metric);
  }
}

async function merchantOptions(page) {
  return page.locator('select[name="merchantId"] option').evaluateAll((nodes) =>
    nodes.filter((node) => node.value).map((node) => node.value),
  );
}

async function checkMerchantA(browser) {
  const page = await signIn(browser, 'report-a@example.test', merchantRoute);
  assert.deepEqual(await merchantOptions(page), ['10001']);
  await filters(page, { merchant: '10001' });
  await amounts(page, '100.00 CNY', '10.00 CNY', '90.00 CNY');
  await page.getByTestId('ledger-status').getByText('成功', { exact: true }).waitFor();
  await page.getByTestId('ledger-trend').getByRole('img', { name: /2025-03-09.*创建笔数 1.*成功笔数 1/ }).waitFor();
  await page.getByTestId('ledger-channels').getByRole('row', { name: /支付宝.*100\.00.*10\.00.*90\.00/ }).waitFor();
  assert.equal(await page.getByTestId('ledger-unattributed').count(), 0);
  const denied = await page.request.get(baseUrl + '/api' + merchantRoute + '?startDate=' + day + '&endDate=' + day + '&merchantId=10002');
  assert.equal(denied.status(), 403);

  await filters(page, { end: nextDay });
  await amounts(page, '100.00 CNY', '25.00 CNY', '75.00 CNY');
  await page.getByTestId('ledger-daily').getByRole('row', { name: /2025-03-10.*15\.00.*−15\.00/ }).waitFor();
  if (process.env.CFL_REPORT_SCREENSHOT) {
    await page.screenshot({ path: process.env.CFL_REPORT_SCREENSHOT, fullPage: true });
  }
  await filters(page, { start: nextDay, end: nextDay });
  await amounts(page, '0.00 CNY', '15.00 CNY', '−15.00 CNY');
  await filters(page, { start: nextDay, end: nextDay, zone: 'UTC' });
  await page.getByText('当前筛选范围内暂无支付或退款记录。', { exact: true }).waitFor();
  await amounts(page, '0.00 CNY', '0.00 CNY', '0.00 CNY');

  await page.setViewportSize({ width: 390, height: 844 });
  await filters(page, { end: nextDay });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  await page.close();
}

async function checkMerchantB(browser) {
  const page = await signIn(browser, 'report-b@example.test', merchantRoute);
  assert.deepEqual(await merchantOptions(page), ['10002']);
  await filters(page, { merchant: '10002' });
  await amounts(page, '200.00 CNY', '25.00 CNY', '175.00 CNY');
  await filters(page, { currency: 'JPY' });
  await page.getByText('当前筛选范围内暂无支付或退款记录。', { exact: true }).waitFor();
  await amounts(page, '0.00 JPY', '0.00 JPY', '0.00 JPY');
  await filters(page, { channel: 'WECHAT_PAY' });
  await page.getByText('当前筛选范围内暂无支付或退款记录。', { exact: true }).waitFor();
  await filters(page, { channel: 'UNION_PAY', currency: 'USD' });
  await amounts(page, '12.34 USD', '0.00 USD', '12.34 USD');
  await page.getByTestId('ledger-channels').getByRole('row', { name: /银联.*12\.34/ }).waitFor();
  await filters(page, { channel: '', currency: '' });
  const currencies = page.getByRole('group', { name: '币种', exact: true });
  await currencies.getByRole('button', { name: 'CNY', exact: true }).click();
  await amounts(page, '200.00 CNY', '25.00 CNY', '175.00 CNY');
  await currencies.getByRole('button', { name: 'USD', exact: true }).click();
  await amounts(page, '12.34 USD', '0.00 USD', '12.34 USD');
  await filters(page);
  await amounts(page, '200.00 CNY', '25.00 CNY', '175.00 CNY');

  const reportRequest = (url) => url.pathname === '/api' + merchantRoute;
  await page.route(reportRequest, (route) => route.fulfill({ status: 503 }));
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await page.getByText('支付报表加载失败，请重试。', { exact: true }).waitFor();
  assert.equal(await page.getByTestId('ledger-net').count(), 0);
  await page.unroute(reportRequest);
  await page.getByRole('button', { name: '重试', exact: true }).click();
  await amounts(page, '200.00 CNY', '25.00 CNY', '175.00 CNY');
  await page.close();
}

async function checkPlatform(browser) {
  const full = await signIn(browser, 'admin@example.com', adminRoute);
  const allowed = await merchantOptions(full);
  assert.ok(allowed.includes('10001') && allowed.includes('10002'));
  await filters(full, { merchant: '10002' });
  await amounts(full, '200.00 CNY', '25.00 CNY', '175.00 CNY');
  await filters(full, { merchant: '10001' });
  await amounts(full, '100.00 CNY', '10.00 CNY', '90.00 CNY');
  await filters(full);
  await full.getByTestId('ledger-unattributed').getByText(/1,700\.00 CNY/).waitFor();
  await full.close();

  const limited = await signIn(browser, 'report-limited@example.test', adminRoute);
  assert.deepEqual(await merchantOptions(limited), ['10002']);
  await filters(limited);
  await amounts(limited, '200.00 CNY', '25.00 CNY', '175.00 CNY');
  assert.equal(await limited.getByTestId('ledger-unattributed').count(), 0);
  await limited.close();

  const denied = await signIn(browser, 'report-denied@example.test', adminRoute);
  await denied.getByRole('alert').getByText('当前账号无权查看该支付报表。').waitFor();
  assert.deepEqual(await merchantOptions(denied), []);
  await denied.close();
}

(async () => {
  const browser = await chromium.launch();
  try {
    await checkMerchantA(browser);
    await checkMerchantB(browser);
    await checkPlatform(browser);
    console.log('Browser verified both merchants, all five filters, cross-day balances, scoped choices, empty/error/retry, and mobile layout.');
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
