const filter = document.querySelector('#filters');
const feedback = document.querySelector('#feedback');
const results = document.querySelector('#outbounds');
const token = document.querySelector('#token');
const order = document.querySelector('#order');
const kind = document.querySelector('#kind');

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
  const mode = kind.value;
  const path = '/api/admin/' + mode + (order.value ? '?orderId=' + encodeURIComponent(order.value) : '');
  const entries = await api(path);
  results.setAttribute('aria-label', mode === 'returns' ? '退货单' : '出库单');
  results.replaceChildren(...entries.map(mode === 'returns' ? returnDocument : outbound));
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

function returnDocument(entry) {
  const states = {
    WAITING_RECEIPT: '待实收',
    RECEIVED: '待质检',
    PASSED: '质检合格',
    FAILED: '质检不通过',
    SHORT_RECEIVED: '短收',
    REJECTED: '拒收',
  };
  const article = element('article');
  article.append(
    element('h2', '订单 ' + entry.orderId + ' · 仓库 ' + entry.warehouseId + ' · ' + states[entry.status]),
    element('p', '原包裹 ' + entry.outboundId + ' · 退货运单 ' + entry.trackingNumber),
    element('p', '预期 ' + entry.expectedQuantity + ' 件 · 实收 ' + (entry.receivedQuantity ?? '—')),
  );
  const awaiting = entry.status === 'WAITING_RECEIPT';
  if (!awaiting && entry.status !== 'RECEIVED') {
    if (entry.supplementId) {
      article.append(element('p', '复检合格 ' + entry.qualifiedQuantity + ' 件 · ' + entry.supplementNote));
    } else if (['SHORT_RECEIVED', 'FAILED'].includes(entry.status) && entry.receivedQuantity > 0) {
      article.append(supplementForm(entry));
    }
    return article;
  }

  const form = element('form');
  const actions = element('div');
  actions.className = 'actions';
  let count;
  if (awaiting) {
    count = element('input');
    Object.assign(count, {
      type: 'number', min: '0', max: String(entry.expectedQuantity), value: String(entry.expectedQuantity),
      required: true,
    });
    const label = element('label', '仓库实收件数');
    label.append(count);
    form.append(label);
  }

  const accept = element('button', awaiting ? '登记实收' : '质检合格');
  const reject = element('button', awaiting ? '拒收' : '质检不通过');
  reject.type = 'button';
  reject.className = 'secondary';
  actions.append(accept, reject);
  form.append(actions);
  article.append(form);

  async function save(accepted) {
    accept.disabled = reject.disabled = true;
    const warehouseId = entry.warehouseId;
    const body = awaiting
      ? { warehouseId, quantity: accepted ? count.valueAsNumber : 0, rejected: !accepted }
      : { warehouseId, passed: accepted };
    try {
      await api('/api/admin/returns/' + entry.returnId + (awaiting ? '/receipt' : '/quality'), {
        method: 'PUT',
        body: JSON.stringify(body),
      });
      await load();
      message('仓库结果已登记。');
    } catch (error) {
      message(error.message, true);
    } finally {
      accept.disabled = reject.disabled = false;
    }
  }

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    void save(true);
  });
  reject.addEventListener('click', () => void save(false));
  return article;
}

function supplementForm(entry) {
  const form = element('form');
  const quantity = element('input');
  Object.assign(quantity, { type: 'number', min: '1', max: String(entry.receivedQuantity), required: true });
  quantity.value = String(entry.receivedQuantity);
  const note = element('input');
  Object.assign(note, { required: true, maxLength: 300 });
  const quantityLabel = element('label', '复检合格件数');
  const noteLabel = element('label', '复检说明');
  quantityLabel.append(quantity);
  noteLabel.append(note);
  const submit = element('button', '登记补检凭证');
  form.append(quantityLabel, noteLabel, submit);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    submit.disabled = true;
    try {
      await api('/api/admin/returns/' + entry.returnId + '/supplement', {
        method: 'PUT',
        body: JSON.stringify({
          warehouseId: entry.warehouseId,
          quantity: quantity.valueAsNumber,
          note: note.value.trim(),
        }),
      });
      await load();
      message('补检凭证已登记。');
    } catch (error) {
      message(error.message, true);
    } finally {
      submit.disabled = false;
    }
  });
  return form;
}

filter.addEventListener('submit', async (event) => {
  event.preventDefault();
  try {
    const count = await load();
    message(count ? '显示最近 ' + count + ' 张出库单。' : '没有符合条件的单据。');
  } catch (error) {
    results.replaceChildren();
    message(error.message, true);
  }
});
