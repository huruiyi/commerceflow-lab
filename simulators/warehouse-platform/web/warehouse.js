const filter = document.querySelector('#filters');
const feedback = document.querySelector('#feedback');
const results = document.querySelector('#outbounds');
const token = document.querySelector('#token');
const order = document.querySelector('#order');

const labels = { PENDING: '待实发', DISPATCHED: '已确认实发', VOIDED: '已拒绝出库' };

function element(tag, value) {
  const node = document.createElement(tag);
  if (value !== undefined) node.textContent = value;
  return node;
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    cache: 'no-store',
    headers: { Authorization: 'Bearer ' + token.value, 'Content-Type': 'application/json' },
  });
  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new Error(error?.detail || '请求失败（' + response.status + '）');
  }
  return response.json();
}

function message(text, error = false) {
  feedback.textContent = text;
  feedback.dataset.error = error;
}

async function load() {
  const path = '/api/admin/outbounds' + (order.value ? '?orderId=' + encodeURIComponent(order.value) : '');
  const entries = await api(path);
  results.replaceChildren(...entries.map(outbound));
  return entries.length;
}

function outbound(entry) {
  const article = element('article');
  const form = element('form');
  const pending = entry.status === 'PENDING';
  article.append(
    element('h2', '订单 ' + entry.orderId + ' · 仓库 ' + entry.warehouseId + ' · ' + labels[entry.status]),
    element('code', entry.outboundId),
    form,
  );
  const table = element('table');
  const header = table.createTHead().insertRow();
  for (const column of ['订单行', '商品', '计划', '实发']) header.append(element('th', column));
  const body = table.createTBody();
  const counts = new Map();
  for (const line of entry.planned) {
    const row = body.insertRow();
    for (const value of [line.orderLineId, line.productId, line.quantity]) row.insertCell().textContent = value;
    const cell = row.insertCell();
    if (pending) {
      const input = element('input');
      Object.assign(input, { type: 'number', min: '0', max: String(line.quantity), value: String(line.quantity) });
      input.required = true;
      input.setAttribute('aria-label', '订单行 ' + line.orderLineId + ' 实发件数');
      counts.set(line.orderLineId, input);
      cell.append(input);
    } else {
      cell.textContent = entry.actual.find((value) => value.orderLineId === line.orderLineId)?.quantity ?? '—';
    }
  }
  form.append(table);
  if (!pending) return article;

  const actions = element('div');
  actions.className = 'actions';
  const confirm = element('button', '确认实发');
  const reject = element('button', '拒绝整张出库单');
  reject.type = 'button';
  reject.className = 'secondary';
  actions.append(confirm, reject);
  form.append(actions);

  async function decide(voided) {
    confirm.disabled = reject.disabled = true;
    try {
      const lines = Array.from(counts, ([orderLineId, input]) => ({
        orderLineId,
        quantity: voided ? 0 : input.valueAsNumber,
      }));
      if (!voided && !lines.some((line) => line.quantity > 0)) {
        message('实发为零时请选择拒绝整张出库单。', true);
        return;
      }
      await api('/api/admin/outbounds/' + entry.outboundId + '/result', {
        method: 'PUT',
        body: JSON.stringify({ warehouseId: entry.warehouseId, lines }),
      });
      await load();
      message(voided ? '出库单已拒绝。' : '实发件数已确认。');
    } catch (error) {
      message(error.message, true);
    } finally {
      confirm.disabled = reject.disabled = false;
    }
  }

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    void decide(false);
  });
  reject.addEventListener('click', () => void decide(true));
  return article;
}

filter.addEventListener('submit', async (event) => {
  event.preventDefault();
  try {
    const count = await load();
    message(count ? '显示最近 ' + count + ' 张出库单。' : '没有符合条件的出库单。');
  } catch (error) {
    results.replaceChildren();
    message(error.message, true);
  }
});
