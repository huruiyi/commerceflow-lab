import assert from "node:assert/strict";
import { createHash, createHmac, randomUUID } from "node:crypto";
import { writeFile } from "node:fs/promises";
import { join } from "node:path";
import { chromium, expect } from "@playwright/test";

const [
  logisticsUrl,
  warehouseUrl,
  warehouseServiceUrl,
  messagingUrl,
  artifacts,
] = process.argv.slice(2);
const urls = [
  logisticsUrl,
  warehouseUrl,
  warehouseServiceUrl,
  messagingUrl,
].map((value) => new URL(value));
assert.equal(
  new Set(urls.map(({ port }) => port)).size,
  urls.length,
  "Each endpoint needs its own isolated port",
);
for (const url of urls) {
  assert.equal(url.protocol, "http:");
  assert.equal(url.hostname, "127.0.0.1");
  assert.ok(
    url.port && !["18083", "18117", "18118", "18119"].includes(url.port),
    "Use isolated loopback ports",
  );
}

const logisticsToken = "demo-logistics-admin-token";
const messagingToken = "demo-messaging-admin-token";
const warehouseToken = "demo-warehouse-admin-token";
const fixtureCount = 11;
const attempts = 10;

async function request(
  base,
  path,
  { method = "GET", body, token, headers = {}, status = 200, discardBody = false } = {},
) {
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const response = await fetch(base + path, {
    method,
    headers,
    body: typeof body === "string" ? body : JSON.stringify(body),
    signal: AbortSignal.timeout(10000),
  });
  assert.equal(response.status, status, `${method} ${path}`);
  if (discardBody) {
    await response.body?.cancel();
    return;
  }
  return response.status === 204 || status >= 400 ? undefined : response.json();
}

async function signIn(page, base, heading) {
  await page.goto(base);
  await page.getByRole("button", { name: "一键登录", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: heading, exact: true }),
  ).toBeVisible();
}

async function assertLogout(page) {
  await page.getByRole("button", { name: "退出登录", exact: true }).click();
  const login = page.getByRole("button", { name: "一键登录", exact: true });
  await expect(login).toBeVisible();
  await page.reload();
  await expect(login).toBeVisible();
}

async function verifyLogistics(page) {
  const api = "/api/admin/logistics";
  const admin = (path) =>
    request(logisticsUrl, api + path, { token: logisticsToken });
  await request(logisticsUrl, api + "/session", { status: 401 });
  await signIn(page, logisticsUrl, "运单管理");
  await page.getByRole("button", { name: "新建运单", exact: true }).click();
  const create = page.getByRole("dialog", { name: "新建测试运单" });
  const tracking = `QA-${randomUUID()}`;
  await create.getByLabel("物流单号").fill(tracking);
  await create
    .getByLabel("回调模拟结果")
    .selectOption({ label: "持续失败（用于验证重试）" });
  await create
    .getByRole("button", { name: "创建测试运单", exact: true })
    .click();
  const shipment = page.getByRole("dialog", { name: "运单详情" });
  await expect(shipment).toContainText(tracking);
  await shipment.getByRole("button", { name: "查看此运单回调" }).click();

  const callbackList = page.getByRole("region", { name: /回调投递/ });
  const row = callbackList.getByRole("row").filter({
    has: page.getByRole("button", { name: "详情", exact: true }),
  });
  await expect(row).toHaveCount(1);
  await row.getByRole("button", { name: "详情", exact: true }).click();
  const detail = page.getByRole("dialog", { name: "投递详情" });
  await expect(detail.getByText("下次重试", { exact: true })).toBeVisible({
    timeout: 10000,
  });
  await expect(detail.locator("dl")).toContainText(/\d+ 秒/);
  await detail
    .getByRole("button", { name: "重新投递", exact: true })
    .waitFor({ timeout: 45000 });
  await expect(row).toContainText("已终止");
  await expect(row).toContainText("10 / 10");
  const {
    entries: [summary],
  } = await admin(`/shipments?reference=${tracking}`);
  assert.equal(summary?.trackingNumber, tracking);
  const created = await admin(
    `/shipments/${encodeURIComponent(summary.merchantShipmentNo)}`,
  );
  const [record] = await admin(`/callbacks?shipmentId=${created.id}`);
  assert.equal(record.status, "DEAD");
  assert.equal(record.attempts, attempts);
  const before = await admin(`/callbacks/${record.id}/events`);
  assert.equal(before.length, 2 * attempts + 1);
  assert.equal(before.filter((event) => event.toStatus === "DEAD").length, 1);

  const audit = detail.getByRole("table", { name: "审计记录" });
  const pager = detail.getByRole("navigation", { name: "审计记录分页" });
  await expect(pager).toContainText("共 21 条记录");
  await pager.getByRole("button", { name: "2", exact: true }).click();
  const auditRows = audit.locator("tbody tr[data-audit-entry]");
  await expect(auditRows).toHaveCount(1);
  const olderText = await auditRows.allTextContents();
  await detail
    .getByLabel("回调模拟结果", { exact: true })
    .selectOption({ label: "正常接收" });
  await detail.getByRole("button", { name: "保存模拟结果" }).click();
  await expect(
    detail.getByText("回调模拟结果已保存", { exact: true }),
  ).toBeVisible();
  await detail.getByRole("button", { name: "重新投递", exact: true }).click();
  await detail.getByLabel("操作人").fill("模拟台验收");
  await detail.getByLabel("重投原因").fill("正常接收后验证恢复");
  await detail.getByRole("button", { name: "确认提交" }).click();
  await expect(row).toContainText("已投递", { timeout: 15000 });
  await expect(row).toContainText("1 / 10");
  await expect(detail.getByText("新增 3 条", { exact: true })).toBeVisible();
  assert.deepEqual(
    await auditRows.allTextContents(),
    olderText,
    "Old audit page must stay unchanged",
  );
  await detail.getByRole("button", { name: "回到最新" }).click();
  await expect(auditRows.first()).toContainText("投递成功");
  await expect(pager).toContainText("共 24 条记录");
  await detail.screenshot({ path: join(artifacts, "logistics-replay.png") });

  const filter = detail.getByLabel("审计记录类型");
  for (const [type, count] of [
    ["投递失败", 10],
    ["自动重试", 9],
    ["手动重新投递", 1],
    ["投递成功", 1],
  ]) {
    await filter.selectOption({ label: type });
    await expect(auditRows).toHaveCount(count);
    await expect(pager).toContainText(`共 ${count} 条记录`);
  }
  const replayed = await admin(`/callbacks/${record.id}`);
  assert.equal(replayed.eventId, record.eventId);
  assert.equal(replayed.status, "PUBLISHED");
  const after = await admin(`/callbacks/${record.id}/events`);
  assert.equal(after.length, 24);
  for (const event of before) {
    assert.deepEqual(
      after.find(({ id }) => id === event.id),
      event,
    );
  }
  const previousIds = new Set(before.map(({ id }) => id));
  const newEvents = after
    .filter(({ id }) => !previousIds.has(id))
    .sort((a, b) => a.id - b.id);
  assert.deepEqual(
    newEvents.map(({ eventType }) => eventType),
    ["MANUAL_REPLAY_REQUESTED", "DELIVERY_CLAIMED", "DELIVERY_PUBLISHED"],
  );
  assert.equal(newEvents[0].operatedBy, "模拟台验收");
  assert.equal(newEvents[0].reason, "正常接收后验证恢复");
  return {
    shipmentId: created.id,
    tracking,
    eventId: record.eventId,
    callbackId: record.id,
    failedAttempts: attempts,
    before: before.length,
    after: after.length,
  };
}

async function seedWarehouse() {
  async function submit(path, command, caller, secret, options = {}) {
    const body = JSON.stringify(command);
    const time = Math.floor(Date.now() / 1000).toString();
    const nonce = randomUUID();
    const digest = createHash("sha256").update(body).digest("hex");
    const signature = createHmac("sha256", secret)
      .update(["POST", path, time, nonce, digest].join("\n"))
      .digest("hex");
    return request(warehouseServiceUrl, path, {
      method: "POST",
      body,
      ...options,
      headers: {
        "X-CommerceFlow-Service": caller,
        "X-CommerceFlow-Timestamp": time,
        "X-CommerceFlow-Request-Id": nonce,
        "X-CommerceFlow-Content-SHA256": digest,
        "X-CommerceFlow-Signature": signature,
      },
    });
  }

  let first;
  for (let orderId = 1; orderId <= fixtureCount; orderId++) {
    const outboundId = randomUUID();
    const outbound = {
      outboundId,
      orderId,
      merchantId: 1,
      warehouseId: 1,
      wholeOrder: true,
      lines: [{ orderLineId: orderId, productId: 1, quantity: 1 }],
    };
    const returnId = randomUUID();
    const returned = {
      returnId,
      outboundId,
      orderId,
      orderLineId: orderId,
      warehouseId: 1,
      trackingNumber: `QA-RETURN-${orderId}`,
      expectedQuantity: 1,
    };
    const submitOutbound = (body, options) =>
      submit("/internal/v1/outbounds", body, "fulfillment-service", "isolated-warehouse-fulfillment-secret", options);
    const submitReturn = (body, options) =>
      submit("/internal/v1/returns", body, "commerce-service", "isolated-warehouse-return-secret", options);
    if (orderId !== 1) {
      await submitOutbound(outbound);
      await submitReturn(returned);
      continue;
    }

    // The caller loses the body after the first command is committed.
    await submitOutbound(outbound, { discardBody: true });
    const replayed = await submitOutbound(outbound);
    assert.equal(replayed.outboundId, outboundId);
    assert.equal(replayed.status, "DISPATCHED");
    assert.deepEqual(replayed.actual, [{ orderLineId: orderId, quantity: 1 }]);
    await submitOutbound({ ...outbound, lines: [{ orderLineId: orderId, productId: 1, quantity: 2 }] }, { status: 409 });
    assert.deepEqual(await submitOutbound(outbound), replayed);

    await submitReturn(returned, { discardBody: true });
    const returnedAgain = await submitReturn(returned);
    assert.equal(returnedAgain.returnId, returnId);
    assert.equal(returnedAgain.status, "WAITING_RECEIPT");
    const adminPath = "/api/admin/returns/" + returnId;
    const write = (path, body, options) =>
      request(warehouseUrl, adminPath + path, { method: "PUT", token: warehouseToken, body, ...options });
    await write("/quality", { warehouseId: 1, passed: true }, { status: 409 });
    const receipt = { warehouseId: 1, quantity: 1, rejected: false };
    await write("/receipt", receipt, { discardBody: true });
    const received = await write("/receipt", receipt);
    assert.equal(received.status, "RECEIVED");
    const checked = await write("/quality", { warehouseId: 1, passed: true });
    assert.equal(checked.status, "PASSED");
    assert.deepEqual(await write("/quality", { warehouseId: 1, passed: true }), checked);
    const jobs = await request(warehouseUrl, "/api/admin/returns?jobId=" + returnId, { token: warehouseToken });
    assert.equal(jobs.total, 1);
    assert.deepEqual(jobs.rows, [checked]);
    first = { outboundId, returnId, dispatchedAt: replayed.decidedAt, receivedAt: received.receivedAt,
      inspectedAt: checked.inspectedAt, status: checked.status };
  }
  return first;
}

async function seedMessaging() {
  for (const channel of ["SMS", "EMAIL"]) {
    for (let index = 1; index <= fixtureCount; index++) {
      const email = channel === "EMAIL";
      await request(messagingUrl, "/api/admin/deliveries", {
        method: "POST",
        token: messagingToken,
        body: {
          requestId: randomUUID(),
          channel,
          reference: `QA-${channel}-${index}`,
          recipient: email ? "qa@commerceflow.test" : "+12025550100",
          message: "模拟台验收",
          ...(email ? { subject: "模拟邮件" } : {}),
        },
      });
    }
  }
}

async function selectPageSize(page, size) {
  await page.getByRole("textbox", { name: "请选择", exact: true }).click();
  await page.getByText(`${size}条/页`, { exact: true }).click();
}

async function verifyLists(page, { url, first, second, detail, image }) {
  const menu = page.getByRole("navigation", { name: "后台菜单" });
  const dismiss = (panel) =>
    panel.getByRole("button", { name: "关闭详情", exact: true }).click();
  const dataRows = page
    .getByRole("row")
    .filter({ has: page.getByRole("button", { name: "详情", exact: true }) });
  const pagerSize = page.getByRole("textbox", { name: "请选择", exact: true });
  await signIn(page, url, first.heading);

  const counts = {};
  for (const [index, settings] of [first, second].entries()) {
    await menu
      .getByRole("button", { name: settings.name, exact: true })
      .click();
    await expect(
      page.getByRole("heading", { name: settings.heading, exact: true }),
    ).toBeVisible();
    await expect(dataRows).toHaveCount(fixtureCount);
    await selectPageSize(page, 10);
    await expect(dataRows).toHaveCount(10);
    const firstIds = await dataRows.locator("td:first-child").allTextContents();
    await page.getByRole("button", { name: "2", exact: true }).click();
    await expect(dataRows).toHaveCount(1);
    const reference = (
      await dataRows.first().getByRole("cell").first().textContent()
    ).trim();
    assert.ok(
      reference && !firstIds.map((id) => id.trim()).includes(reference),
      "Second page must contain a different record",
    );
    await dataRows
      .first()
      .getByRole("button", { name: "详情", exact: true })
      .click();
    const panel = detail(settings);
    await expect(panel).toContainText(reference);
    await expect(panel).toContainText(settings.field);
    if (index === 1) {
      await panel.screenshot({ path: join(artifacts, image) });
    }
    await dismiss(panel);
    await expect(panel).toBeHidden();
    if (index === 1) {
      await selectPageSize(page, 30);
    }
    counts[settings.name] = [10, 1];
  }

  await menu.getByRole("button", { name: first.name, exact: true }).click();
  await expect(pagerSize).toHaveValue("10条/页");
  await page.reload();
  await expect(
    page.getByRole("heading", { name: first.heading, exact: true }),
  ).toBeVisible();
  await expect(pagerSize).toHaveValue("10条/页");
  await menu.getByRole("button", { name: second.name, exact: true }).click();
  await expect(pagerSize).toHaveValue("30条/页");
  await assertLogout(page);
  return counts;
}

const browser = await chromium.launch();
try {
  const context = await browser.newContext({
    viewport: { width: 1450, height: 1000 },
  });
  const logistics = await verifyLogistics(await context.newPage());
  console.log(
    "PASS logistics: 10 failures, terminal state, manual replay, 24 audit records",
  );

  const warehouseRecovery = await seedWarehouse();
  console.log("PASS warehouse recovery: lost results, request replay, and out-of-order inspection");
  const warehousePage = await context.newPage();
  const warehouse = await verifyLists(warehousePage, {
    url: warehouseUrl,
    first: { name: "出库单", heading: "出库单列表", field: "商品明细" },
    second: { name: "退货单", heading: "退货单列表", field: "收货与质检" },
    detail: ({ name }) =>
      warehousePage.getByRole("dialog", { name: name + "详情" }),
    image: "warehouse-return-detail.png",
  });
  console.log(
    "PASS warehouse: two pages, document drawers, per-list size, reload login, logout",
  );

  await seedMessaging();
  const messagingPage = await context.newPage();
  const messaging = await verifyLists(messagingPage, {
    url: messagingUrl,
    first: { name: "短信投递", heading: "短信投递列表", field: "供应商单号" },
    second: { name: "邮件投递", heading: "邮件投递列表", field: "供应商单号" },
    detail: () =>
      messagingPage.getByRole("region", { name: "投递详情", exact: true }),
    image: "messaging-email-detail.png",
  });
  console.log(
    "PASS messaging: two pages, delivery details, per-channel size, reload login, logout",
  );

  await writeFile(
    join(artifacts, "result.json"),
    JSON.stringify({ logistics, warehouse, warehouseRecovery, messaging }, null, 2),
  );
} finally {
  await browser.close();
}
