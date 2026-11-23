const assert = require('node:assert/strict');
const { chromium } = require(process.argv[3]);
const baseUrl = process.argv[2];
const day = '2025-03-09';
const nextDay = '2025-03-10';
const merchantRoute = '/merchant/payment-ledger-reports';
const adminRoute = '/admin/payment-ledger-reports';
const paginationDates = Array.from({ length: 26 }, (_, index) => `2025-04-${String(index + 1).padStart(2, '0')}`);

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
  await page.waitForURL((url) => url.pathname === path);
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

async function checkDailyPagination(page, route, expected) {
  const [start] = paginationDates;
  const end = paginationDates.at(-1);
  const zone = 'UTC';
  const { currency, received, refunded, net } = expected;
  await filters(page, { start, end, zone, channel: '', currency });
  if (!currency) {
    await page.getByRole('button', { name: 'CNY', exact: true }).click();
  }
  const query = new URLSearchParams({ startDate: start, endDate: end, timeZone: zone, currency: 'CNY' });
  const api = new URL('/api' + route, baseUrl);
  const response = await page.request.get(api + '?' + query);
  assert.equal(response.status(), 200);
  const report = await response.json();
  assert.deepEqual(report.daily.map((row) => row.date), paginationDates);
  assert.equal(report.totals[0].summary.receivedAmount + ' CNY', received);
  assert.equal(report.totals[0].summary.refundedAmount + ' CNY', refunded);
  assert.equal(report.totals[0].summary.netAmount + ' CNY', net);

  const table = page.getByTestId('ledger-daily');
  const dataRows = table.locator('tbody th[scope=row]');
  const bar = page.locator('app-list-pagination nav');
  const previous = bar.getByRole('button', { name: '上一页', exact: true });
  const next = bar.getByRole('button', { name: '下一页', exact: true });
  let calls = 0;
  const countReport = (request) => {
    if (new URL(request.url()).pathname === api.pathname) calls++;
  };
  page.on('request', countReport);

  async function verifyPage(index) {
    const dates = paginationDates.slice(index * 12, index * 12 + 12);
    await page.waitForURL((url) => Number(url.searchParams.get('page') ?? '0') === index);
    await dataRows.getByText(dates[0], { exact: true }).waitFor();
    assert.deepEqual(await dataRows.allTextContents(), dates);
    assert.equal(await previous.isDisabled(), index === 0);
    assert.equal(await next.isDisabled(), index === 2);
    await amounts(page, received, refunded, net);
    const trend = page.getByTestId('ledger-trend');
    assert.equal(await trend.getByRole('img').count(), paginationDates.length);
    assert.equal(await trend.getByRole('img', { name: new RegExp(end + '.*成功笔数') }).count(), 1);
  }

  await verifyPage(0);
  await next.focus();
  await page.keyboard.press('Enter');
  await verifyPage(1);
  await next.click();
  await verifyPage(2);
  await page.goBack();
  await verifyPage(1);
  await page.goForward();
  await verifyPage(2);
  assert.equal(calls, 0, 'Paging aggregated rows does not re-fetch the report');
  page.off('request', countReport);
  if (!currency) {
    await page.getByRole('button', { name: 'USD', exact: true }).click();
    await amounts(page, '7.00 USD', '0.00 USD', '7.00 USD');
    assert.equal(await bar.count(), 0);
    await page.getByRole('button', { name: 'CNY', exact: true }).click();
    await verifyPage(0);
    await next.click();
    await verifyPage(1);
    await next.click();
    await verifyPage(2);
  }

  await page.reload();
  await verifyPage(2);
  assert.equal(await page.locator('input[name=startDate]').inputValue(), start);
  assert.equal(await page.locator('input[name=endDate]').inputValue(), end);
  assert.equal(await page.locator('select[name=timeZone]').inputValue(), zone);
  assert.equal(await page.locator('select[name=currency]').inputValue(), currency);

  await page.setViewportSize({ width: 390, height: 844 });
  await bar.getByText('第 3 页，共 3 页', { exact: true }).waitFor({ state: 'visible' });
  await previous.click();
  await verifyPage(1);
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  if (process.env.CFL_REPORT_PAGINATION_SCREENSHOT && route === adminRoute) {
    await bar.scrollIntoViewIfNeeded();
    await page.screenshot({ path: process.env.CFL_REPORT_PAGINATION_SCREENSHOT });
  }
  await filters(page, { start, end: start, zone, currency: 'CNY' });
  await page.waitForURL((url) => Number(url.searchParams.get('page') ?? '0') === 0);
  await dataRows.getByText(start, { exact: true }).waitFor();
  assert.equal(await dataRows.count(), 1);
  assert.equal(await bar.count(), 0);
  console.log(`${route} validated 26 real rows in pages 12/12/2, refresh, 390px, period totals, trend, and filter reset.`);
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
  await page.setViewportSize({ width: 1440, height: 1080 });
  await checkDailyPagination(page, merchantRoute, {
    currency: 'CNY', received: '325.00 CNY', refunded: '4.00 CNY', net: '321.00 CNY',
  });
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
  await checkDailyPagination(full, adminRoute, {
    currency: '', received: '334.00 CNY', refunded: '4.00 CNY', net: '330.00 CNY',
  });
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
  const browser = await chromium.launch({ channel: process.env.CFL_REPORT_BROWSER_CHANNEL });
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
