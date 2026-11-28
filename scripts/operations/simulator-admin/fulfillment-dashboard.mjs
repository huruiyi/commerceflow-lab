import assert from "node:assert/strict";
import { chromium, expect } from "@playwright/test";

const [origin, output] = process.argv.slice(2);
const url = new URL(origin);
assert.equal(url.protocol, "http:");
assert.equal(url.hostname, "127.0.0.1");
assert.ok(Number(url.port) >= 20000, "Expected an isolated Grafana port");
const dashboardId = "commerceflow-fulfillment-operations";
const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: 1460, height: 1350 } });
  const user = { user: "admin", password: "commerceflow-grafana" };
  const login = await page.request.post(`${origin}/login`, { data: user });
  assert.ok(login.ok(), `Grafana sign-in returned ${login.status()}`);
  const reply = await page.request.get(`${origin}/api/dashboards/uid/${dashboardId}`);
  assert.ok(reply.ok(), `Grafana dashboard returned ${reply.status()}`);
  const { dashboard, meta } = await reply.json();
  assert.ok(meta.provisioned && dashboard.panels.length > 0);
  const expected = new Set(dashboard.panels.flatMap((panel) => panel.targets.map((target) => target.expr)));
  const rendered = new Set();
  page.on("response", async (response) => {
    if (new URL(response.url()).pathname !== "/api/ds/query" || !response.ok()) return;
    const { queries } = response.request().postDataJSON();
    const { results } = await response.json();
    for (const { expr, refId } of queries) {
      const result = results[refId];
      if (expected.has(expr) && result?.status === 200 &&
          result.frames?.some((frame) => frame.data.values[0]?.length > 0)) {
        rendered.add(expr);
      }
    }
  });
  await page.goto(`${origin}/d/${dashboardId}?from=now-20m&to=now`);
  for (const { title } of dashboard.panels) {
    const panel = page.getByText(title, { exact: true }).first();
    await expect(panel).toBeVisible({ timeout: 15000 });
    await panel.scrollIntoViewIfNeeded();
  }
  await expect.poll(() => [...expected].filter((expression) => !rendered.has(expression)), {
    timeout: 25000,
    message: "Expected Grafana samples for every fulfillment query",
  }).toEqual([]);
  await page.screenshot({ path: output, fullPage: true });
} finally {
  await browser.close();
}
