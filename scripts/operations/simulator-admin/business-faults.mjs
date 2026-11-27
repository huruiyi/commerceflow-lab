import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { writeFile } from "node:fs/promises";
import { join } from "node:path";
import { expect } from "@playwright/test";

function literal(value) {
  assert.match(String(value), /^[A-Za-z0-9:_-]+$/);
  return "'" + value + "'";
}

function same(actual, expected, message) {
  assert.deepEqual(actual, expected, message);
}

async function until(check, description, milliseconds = 65000) {
  const deadline = Date.now() + milliseconds;
  do {
    const value = await check();
    if (value) return value;
    await new Promise((resolve) => setTimeout(resolve, 400));
  } while (Date.now() < deadline);
  throw new Error("Timed out: " + description);
}

function postgresRequestId(eventId, channel) {
  const bytes = createHash("md5").update(eventId + ":" + channel).digest();
  bytes[6] = (bytes[6] & 15) | 48;
  bytes[8] = (bytes[8] & 63) | 128;
  const id = bytes.toString("hex");
  return [id.slice(0, 8), id.slice(8, 12), id.slice(12, 16), id.slice(16, 20), id.slice(20)].join("-");
}

class Acceptance {
  constructor(target, artifacts) {
    assert.match(target.Project, /^fairy-commerceflow-acceptance-simulator-[a-f0-9]{12}$/);
    this.project = target.Project;
    this.artifacts = artifacts;
    this.endpoints = target.Endpoints;
    this.databases = new Map();
    this.portal = this.url("commerce-portal-angular");
    this.cashier = this.url("payment-simulator-web");
    this.messaging = this.url("messaging-simulator-web");
    this.faults = this.url("simulator-fault-fixture");
  }

  url(service) {
    const endpoint = this.endpoints[service];
    const url = new URL(endpoint.Url);
    assert.equal(url.origin, endpoint.Url);
    assert.equal(url.hostname, "127.0.0.1");
    assert.equal(url.protocol, "http:");
    const binding = execFileSync("docker", ["port", this.container(service), String(endpoint.ContainerPort) + "/tcp"],
      { encoding: "utf8" }).trim();
    same(binding, "127.0.0.1:" + url.port, "isolated port for " + service);
    return endpoint.Url;
  }

  container(service) {
    const name = this.project + "-" + service + "-1";
    const spec = JSON.parse(execFileSync("docker", ["inspect", name], { encoding: "utf8" }))[0];
    const labels = spec.Config.Labels;
    same(labels["com.docker.compose.project"], this.project);
    same(labels["com.docker.compose.service"], service);
    assert(spec.Mounts.every((mount) => mount.Type !== "volume" || mount.Name.startsWith(this.project + "_")));
    return name;
  }

  database(service, schema, query) {
    if (!this.databases.has(service)) this.databases.set(service, this.container(service));
    return execFileSync("docker", ["exec", "-i", this.databases.get(service), "psql", "-U", "postgres", "-d", schema, "-XqAt", "-v", "ON_ERROR_STOP=1"],
    { input: "SET search_path TO " + schema + ",public;\n" + query, encoding: "utf8" }).trim();
  }

  pg(query) {
    const service = "notification-db-postgresql";
    if (!this.databases.has(service)) this.databases.set(service, this.container(service));
    return execFileSync("docker", ["exec", "-i", this.databases.get(service), "sh", "-ec",
      'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atq'],
    { input: query, encoding: "utf8" }).trim();
  }

  async api(page, path, method = "GET", body, status = 200) {
    let headers;
    if (method !== "GET") {
      const token = await (await page.request.get(this.portal + "/api/auth/csrf")).json();
      headers = { [token.headerName]: token.token };
    }
    const response = await page.request.fetch(this.portal + path, { method, data: body, headers, timeout: 15000 });
    const content = await response.text();
    same(response.status(), status, method + " " + path + " " + content.slice(0, 300));
    return content && JSON.parse(content);
  }

  async click(page, button, path, status) {
    const watched = page.waitForResponse((r) => new URL(r.url()).pathname === path && r.request().method() === "POST");
    await button.click();
    const response = await watched;
    same(response.status(), status, "browser submit " + path + " " + (await response.text()).slice(0, 300));
    return response;
  }

  async control(scenario, operation) {
    const response = await fetch(this.faults + "/control/" + scenario + "/" + operation, {
      method: "POST", signal: AbortSignal.timeout(15000),
    });
    same(response.status, 204, "fixture " + scenario + " " + operation);
  }

  async state() {
    const response = await fetch(this.faults + "/control/state", { signal: AbortSignal.timeout(5000) });
    same(response.status, 200, "fixture state");
    return response.json();
  }

  async login(browser, email) {
    const page = await browser.newPage({ viewport: { width: 1450, height: 1000 }, timezoneId: "Asia/Shanghai" });
    await page.addInitScript(() => localStorage.setItem("commerceflow-locale", "zh-CN"));
    await page.goto(this.portal);
    await page.locator("#loginEmail").fill(email);
    await page.locator("#loginPassword").fill("demo-password");
    await this.click(page, page.locator("button.login-submit"), "/api/auth/login", 200);
    await page.waitForURL("**/dashboard");
    return page;
  }

  orderPath(order) {
    assert(Number.isSafeInteger(order.id) && order.id > 0);
    return "/api/purchase-orders/" + order.id;
  }

  async reserve(page, addressId) {
    const item = await this.api(page, "/api/cart/items", "POST", { productId: 1, quantity: 1 });
    const selection = { addressId, itemIds: [item.id] };
    const quote = await this.api(page, "/api/purchase-orders/checkout/quote", "POST", selection);
    const order = await this.api(page, "/api/purchase-orders/checkout", "POST", { selection, quoteId: quote.quoteId });
    return until(async () => {
      const row = await this.api(page, this.orderPath(order));
      return row.inventoryStatus === "RESERVED" && row;
    }, "order reservation");
  }

  async pay(browser, buyer, order, delayReceipt) {
    const base = this.orderPath(order);
    const storefront = this.portal + "/orders/" + order.id;
    await buyer.goto(storefront);
    const creation = await this.click(buyer, buyer.getByRole("button", { name: "创建支付", exact: true }), base + "/payments", 200);
    const pending = await creation.json();
    const request = creation.request().postDataJSON();
    const payment = pending.payment;
    same(payment.idempotencyKey, request.idempotencyKey, "original browser payment key");
    const address = new URL(payment.checkoutUrl);
    same(address.origin, this.cashier, "cashier is in the current isolated stack");
    const token = address.pathname.split("/").at(-1);
    const completion = this.cashier + "/api/checkout/" + token + "/complete";
    const page = await browser.newPage();
    if (delayReceipt) {
      await this.control("payment", "arm");
      await page.route(completion + "?*", async (route) => {
        if (route.request().method() !== "POST") return route.fallback();
        const receipt = await route.fetch({ maxRetries: 0 });
        same(receipt.status(), 200, "provider accepted before the browser lost the reply");
        await route.abort("failed");
      });
    }
    try {
      await page.goto(address.toString());
      const clickSuccess = page.getByRole("button", { name: /^(支付成功|Payment success)$/ });
      if (delayReceipt) {
        const lost = page.waitForEvent("requestfailed", { predicate: (r) => r.url().startsWith(completion) });
        await clickSuccess.click();
        await lost;
        await page.reload();
        await expect(page.locator(".cashier-details .badge.success")).toBeVisible();
        await buyer.reload();
        await expect(buyer.locator(".payment-receipt")).toContainText(payment.paymentNumber);
        const awaiting = await this.api(buyer, base);
        same([awaiting.status, awaiting.payment.status], ["PENDING_PAYMENT", "PENDING"], "provider settled with business callback held");
        const notifyId = (await this.state()).payment.notificationId;
        assert(notifyId, "captured signature-bearing callback");
        await this.control("payment", "release");
        const results = (await this.state()).payment.results;
        same(results, [
          { status: 200, result: "APPLIED_PAYMENT_SUCCEEDED" },
          { status: 200, result: "ACKNOWLEDGED" },
        ], "late original payment callback and duplicate");
        await expect(buyer.getByText("已支付", { exact: true }).first()).toBeVisible({ timeout: 35000 });
        const provider = this.database("payment-simulator-db-postgresql", "payment_platform",
          "SELECT CONCAT(COUNT(*),'|',MIN(status)) FROM channel_transaction WHERE merchant_request_no=" + literal(payment.paymentNumber));
        same(provider, "1|SUCCESS", "one provider transaction");
        const byKey = this.database("payment-db-postgresql", "payments",
          "SELECT CONCAT(COUNT(*),'|',MIN(status)) FROM payment_records WHERE idempotency_key=" + literal(request.idempotencyKey));
        same(byKey, "1|SUCCESS", "one successful payment for original key");
        const callbacks = this.database("payment-db-postgresql", "payments",
          "SELECT CONCAT(COUNT(*),'|',MIN(processing_status)) FROM payment_notifications WHERE notification_id=" + literal(notifyId));
        same(callbacks, "1|PROCESSED", "duplicate callback has one audit entry");
        const orderEvents = this.database("order-db-postgresql", "orders",
          "SELECT COUNT(*) FROM order_events WHERE order_id=" + order.id + " AND event_type='PAYMENT_SUCCEEDED'");
        same(orderEvents, "1", "one order payment event");
        await buyer.screenshot({ path: join(this.artifacts, "payment-recovered.png"), fullPage: true });
        return { order: order.orderNumber, key: request.idempotencyKey, payment: payment.paymentNumber,
          channel: payment.channelTransactionNo, notification: notifyId, callbacks: results };
      }
      await this.click(page, clickSuccess, new URL(completion).pathname, 200);
      return until(async () => {
        const row = await this.api(buyer, base);
        return row.status === "PAID" && row.inventoryStatus === "COMMITTED" && row;
      }, "invoice order payment");
    } finally {
      await page.close();
    }
  }

  async verifyMessaging(browser, buyer, order) {
    await this.control("messaging", "arm");
    const base = this.orderPath(order);
    await buyer.getByRole("button", { name: "取消并退款", exact: true }).click();
    const response = await this.click(buyer, buyer.getByRole("button", { name: "确认退款", exact: true }), base + "/refunds", 202);
    const refund = await response.json();
    const refundKey = response.request().postDataJSON().idempotencyKey;
    const eventId = "offsite-refund:" + refund.refundNumber;
    const finalOrder = await until(async () => {
      const value = await this.api(buyer, base);
      return value.status === "REFUNDED" && value.refund?.refundNumber === refund.refundNumber && value;
    }, "settled refund and source notification");
    await expect(buyer.getByText(refund.refundNumber, { exact: true }).first()).toBeVisible();
    const channels = ["SMS", "EMAIL"];
    const identities = Object.fromEntries(channels.map((channel) => [channel, postgresRequestId(eventId, channel)]));
    await until(async () => {
      const { requests } = (await this.state()).messaging;
      return channels.every((channel) => requests[identities[channel]]?.replyLost && requests[identities[channel]].queries > 0);
    }, "both provider acceptances survived loss while recovery reads are delayed");
    const intakeCount = this.pg("SELECT COUNT(*) FROM offsite_notification_inbox WHERE event_id=" + literal(eventId));
    same(intakeCount, "1", "one business notification intake");
    for (const channel of channels) {
      const requestId = identities[channel];
      same(this.pg("SELECT status FROM offsite_notification_deliveries WHERE request_id=" + literal(requestId)), "RETRY",
        channel + " waits for its original provider receipt");
    }
    await this.control("messaging", "release");
    const page = await browser.newPage();
    try {
      await page.goto(this.messaging);
      await page.getByRole("button", { name: "一键登录", exact: true }).click();
      const deliveries = {};
      for (const channel of channels) {
        const requestId = identities[channel];
        const parts = await until(() => {
          const values = this.pg("SELECT status, provider_id, attempts FROM offsite_notification_deliveries WHERE request_id="
            + literal(requestId)).split("|");
          return values[0] === "DELIVERED" && values;
        }, "original " + channel + " receipt acknowledged");
        const [, providerId, attempts] = parts;
        assert(Number(attempts) >= 2, channel + " expected failed acknowledgement and retry");
        const trace = (await this.state()).messaging.requests[requestId];
        same([trace.channel, trace.reference, trace.submissions, trace.replyLost], [channel, eventId, 1, true]);
        assert(trace.queries >= 2, "status retried after recovery");
        same(this.database("messaging-simulator-db-postgresql", "messaging_platform",
          "SELECT CONCAT(COUNT(*),'|',MIN(provider_id),'|',MIN(status)) FROM messaging_deliveries WHERE request_id="
          + literal(requestId)), "1|" + providerId + "|DELIVERED", "one original provider row");
        const menu = page.getByRole("navigation", { name: "后台菜单" });
        await menu.getByRole("button", { name: channel === "SMS" ? "短信投递" : "邮件投递", exact: true }).click();
        await page.getByPlaceholder("输入完整关联号").fill(eventId);
        await page.getByRole("button", { name: "搜索", exact: true }).last().click();
        const row = page.getByRole("row").filter({ hasText: eventId });
        await expect(row).toHaveCount(1);
        await row.getByRole("button", { name: "详情", exact: true }).click();
        const panel = page.getByRole("region", { name: "投递详情", exact: true });
        await expect(panel).toContainText(requestId);
        await expect(panel).toContainText(providerId);
        await expect(panel).toContainText("已送达");
        await panel.screenshot({ path: join(this.artifacts, channel.toLowerCase() + "-receipt.png") });
        await panel.getByRole("button", { name: "关闭详情" }).click();
        deliveries[channel] = { requestId, providerId, attempts: Number(attempts), ...trace };
      }
      same(this.database("payment-db-postgresql", "payments", "SELECT CONCAT(COUNT(*),'|',MIN(status)) FROM refund_records WHERE refund_number="
        + literal(refund.refundNumber)), "1|SUCCEEDED", "one source refund");
      return { order: order.orderNumber, refund: finalOrder.refund.refundNumber, refundKey, eventId, deliveries };
    } finally {
      await page.close();
    }
  }

  async verifyInvoice(buyer, order) {
    await this.control("invoice", "arm");
    const path = this.orderPath(order) + "/invoices";
    const location = this.portal + "/orders/" + order.id + "/invoices";
    await buyer.goto(location);
    const response = await this.click(buyer, buyer.getByRole("button", { name: "申请发票", exact: true }), path, 202);
    const application = await response.json();
    const requestId = await until(async () => Object.keys((await this.state()).invoice.requests)[0], "provider admitted the lost invoice submission");
    assert((await this.state()).invoice.requests[requestId].replyLost);
    const held = await until(async () => {
      const data = (await this.state()).invoice.receipts;
      return Object.entries(data).find(([, item]) => item.requestId === requestId && item.arrivals > 0);
    }, "simulator holds a late signed invoice receipt");
    same(held[1].forwarded, 0, "signed invoice receipt not forwarded yet");
    await buyer.reload();
    await expect(buyer.getByTestId("invoice-application")).toHaveText(application.applicationNo);
    await expect(buyer.locator('[data-state="PENDING"]')).toBeVisible();
    same(this.database("invoice-db-postgresql", "invoice_platform",
      "SELECT COUNT(*) FROM invoice_tickets WHERE request_id=" + literal(requestId)), "1", "channel issued before business got receipt");
    await this.control("invoice", "release");
    await expect(buyer.locator('[data-state="ISSUED"]')).toBeVisible({ timeout: 45000 });
    const business = await this.api(buyer, path);
    same(business.applicationNo, application.applicationNo, "original invoice application");
    const ticket = business.issue.invoiceNo;
    const stored = await until(async () => {
      const record = (await this.state()).invoice.receipts[held[0]];
      return record.forwarded >= 2 && record;
    }, "invoice sender retried original signed receipt after lost acknowledgement");
    same(this.database("commerce-db-postgresql", "commerceflow", "SELECT CONCAT(COUNT(*),'|',MIN(status),'|',MIN(invoice_no)) FROM invoice_attempts "
      + "WHERE kind='ISSUE' AND order_id=" + order.id), "1|SUCCEEDED|" + ticket, "one original commerce issue attempt");
    same(this.database("invoice-db-postgresql", "invoice_platform", "SELECT CONCAT(COUNT(*),'|',MIN(invoice_no)) FROM invoice_tickets WHERE request_id="
      + literal(requestId)), "1|" + ticket, "one simulated ticket on original request");
    const receipt = await until(() => {
      const evidence = this.database("invoice-db-postgresql", "invoice_platform", "SELECT CONCAT(COUNT(*),'|',MIN(attempts)) FROM invoice_receipts "
        + "WHERE event_id=" + literal(held[0]) + " AND delivered_at IS NOT NULL");
      return evidence.startsWith("1|") && evidence;
    }, "single delivered audit receipt");
    same(await this.api(buyer, path, "POST", {}, 202).then((row) => row.applicationNo), application.applicationNo);
    await buyer.screenshot({ path: join(this.artifacts, "invoice-recovered.png"), fullPage: true });
    return { order: order.orderNumber, application: application.applicationNo, requestId, ticket, eventId: held[0],
      forwarded: stored.forwarded, receiptAttempts: Number(receipt.split("|")[1]) };
  }
}

export async function verifyBusinessFaults(browser, target, artifacts) {
  const acceptance = new Acceptance(target, artifacts);
  const buyer = await acceptance.login(browser, "demo@example.com");
  const evidence = { project: target.Project, browser: browser.version() };
  const save = () => writeFile(join(artifacts, "business-faults.json"), JSON.stringify(evidence, null, 2));
  try {
    const address = await acceptance.api(buyer, "/api/addresses", "POST", {
      recipientName: "Simulator verification",
      phone: "13800000000",
      countryCode: "CN",
      stateProvince: "Shanghai",
      city: "Shanghai",
      district: "Pudong",
      addressLine1: "12 Recovery Road",
      postalCode: "200120",
      label: "Simulator verification",
      isDefault: false,
    });
    const first = await acceptance.reserve(buyer, address.id);
    evidence.payment = await acceptance.pay(browser, buyer, first, true);
    await acceptance.control("payment", "reset");
    await save();
    console.log("PASS payment: lost cashier reply, late callback, duplicate acknowledgement, one debit");
    evidence.messaging = await acceptance.verifyMessaging(browser, buyer, first);
    await acceptance.control("messaging", "reset");
    await save();
    console.log("PASS SMS and email: accepted reply lost, both original provider receipts recovered");
    const second = await acceptance.reserve(buyer, address.id);
    await acceptance.pay(browser, buyer, second, false);
    evidence.invoice = await acceptance.verifyInvoice(buyer, second);
    console.log("PASS invoice: lost acceptance, late and repeated receipt, one ticket");
  } catch (error) {
    evidence.error = String(error.stack ?? error);
    evidence.failureState = await acceptance.state().catch(() => null);
    throw error;
  } finally {
    for (const scenario of ["payment", "messaging", "invoice"]) {
      await acceptance.control(scenario, "reset");
    }
    evidence.faultsAfterReset = await acceptance.state();
    for (const scenario of Object.values(evidence.faultsAfterReset)) same(scenario.mode, "off");
    await save();
    await buyer.close();
  }
}
