import assert from 'node:assert/strict';
import { createHmac, randomUUID } from 'node:crypto';
import { setTimeout } from 'node:timers/promises';

const [phase, productIdText, versionText, priceText] = process.argv.slice(2);
const path = '/internal/v1';
const merchant = 5;
const searchPath = path + '/product-search';

async function api(method, target, body, expected = 200) {
  const ts = Math.floor(Date.now() / 1000).toString();
  const id = randomUUID();
  const signature = createHmac('sha256', 'catalog-runtime-verification')
    .update([method, target, ts, id].join('\n')).digest('hex');
  const response = await fetch('http://catalog:8080' + target, {
    method,
    headers: {
      'X-CommerceFlow-Service': 'commerce-service', 'X-CommerceFlow-Timestamp': ts,
      'X-CommerceFlow-Request-Id': id, 'X-CommerceFlow-Signature': signature,
      'X-Merchant-Id': String(merchant), 'Content-Type': 'application/json',
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(10_000),
  });
  const result = await response.text();
  assert.equal(response.status, expected, method + ' ' + target + ': ' + result);
  return result ? JSON.parse(result) : null;
}

async function until(read, predicate, reason) {
  const deadline = Date.now() + 75_000;
  do {
    const state = await read();
    if (predicate(state)) return state;
    await setTimeout(500);
  } while (Date.now() < deadline);
  throw new Error('Timed out: ' + reason);
}

async function search(options) {
  return api('POST', searchPath, {
    locale: 'en', sort: 'default', merchantIds: [merchant], ratedIds: [],
    page: 0, size: 48, includeSkuFacets: true, ...options,
  });
}

async function assertSearch(productId) {
  const result = await search({query: 'pgverify', minPrice: 80, maxPrice: 90,
    sku: 'finish=matte', attributes: 'brand=verifybrand'});
  assert.deepEqual(result.products.map(p => p.productId), [productId], 'JSON SKU and monitor filter');
  assert.equal(result.products[0].name, 'PgVerify translated monitor');
  assert.deepEqual(result.skuRanges[productId], { min: 89, max: 139 });
  assert(result.skuFacets.some(f => f.name === 'finish' && f.value === 'matte' && f.productCount === 1));
  assert(result.attributeFacets.some(f => f.name === 'brand' && f.value === 'verifybrand' && f.productCount === 1));

  const phrase = await search({query: 'translated monitor', match: 'phrase', exclude: 'catalogphantom'});
  assert(phrase.products.some(p => p.productId === productId));
  const excluded = await search({query: 'pgverify', exclude: 'translated'});
  assert(!excluded.products.some(p => p.productId === productId));
  const rating = await search({sort: 'rating', ratedIds: [productId, 1]});
  assert.deepEqual(rating.products.slice(0, 2).map(p => p.productId), [productId, 1]);
  for (const [sort, sign] of [['price-asc', 1], ['price-desc', -1]]) {
    const page = await search({sort});
    const prices = page.products.map(p => page.skuRanges[p.productId]?.min ?? p.unitPrice);
    assert(prices.every((price, i) => i === 0 || sign * (price - prices[i - 1]) >= 0), sort);
  }
}

async function readyIndex() {
  return until(() => api('GET', searchPath + '/index'),
    s => s.pending === 0 && !s.rebuildRequested && s.elasticsearch.phase === 'READY',
    'SQL and Elasticsearch projection readiness');
}

async function compareIndex() {
  const comparison = await api('GET', searchPath + '/index/comparison');
  assert.equal(comparison.status, 'CHECKED');
  assert.deepEqual(comparison.differences, []);
  assert.equal(comparison.nextAfterId, null, 'comparison must span all products in this snapshot');
  return comparison.checked;
}

if (phase === 'sql') {
  const listing = path + '/merchant/products';
  assert.equal((await api('GET', path + '/products/1?lang=en')).name, 'Learning Laptop');
  const chinese = await search({query: '学习笔记本', locale: 'zh-CN', match: 'phrase'});
  assert.equal(chinese.products[0]?.productId, 1, 'Chinese phrase ranking');
  const registration = {
    requestId: randomUUID(), name: 'PgVerify 原始显示器', description: 'isolated migration fixture',
    unitPrice: 129, imageUrl: '/assets/catalog/runtime-verification.webp',
  };
  const [first, replay] = await Promise.all([api('POST', listing, registration), api('POST', listing, registration)]);
  assert.equal(first.productId, replay.productId, 'concurrent registration replay');
  await api('POST', listing, { ...registration, name: 'Conflict' }, 409);
  const productId = first.productId;
  const current = listing + '/' + productId;
  let product = await api('PUT', current + '/monitor-profile', {
    expectedVersion: first.businessVersion, profile: {
      brand: 'VerifyBrand', screenSizeInches: 27, widthPixels: 2560, heightPixels: 1440,
      panel: 'IPS', refreshRateHz: 144, ports: ['HDMI', 'USB-C'],
    },
  });
  assert(Date.parse(product.updatedAt) > Date.parse(first.updatedAt), 'product updated_at advances');
  product = await api('PUT', current + '/price', { expectedVersion: product.businessVersion, unitPrice: 139 });
  await api('PUT', current + '/price', { expectedVersion: first.businessVersion, unitPrice: 1 }, 409);
  product = await api('PUT', current + '/status', { expectedVersion: product.businessVersion, status: 'ACTIVE' });
  assert.equal((await api('GET', path + '/products/' + productId + '?lang=en')).name, registration.name);

  const translation = { expectedVersion: product.businessVersion, name: 'Temporary translation',
    brief: 'Localized monitor', description: 'Café translation upsert verification' };
  product = await api('PUT', current + '/translations/en', translation);
  product = await api('PUT', current + '/translations/en', {
    ...translation, expectedVersion: product.businessVersion, name: 'PgVerify translated monitor',
  });

  const skuPath = current + '/skus';
  const defaults = await api('GET', skuPath);
  const base = defaults.find(row => row.defaultSku);
  await api('PUT', skuPath + '/' + base.skuId, { expectedVersion: base.businessVersion, code: base.code,
    specifications: { Package: 'Standard' }, unitPrice: 139, status: 'ACTIVE' });
  const variant = { requestId: randomUUID(), code: 'PGVERIFY-MATTE', specifications: { Finish: 'Matte' },
    unitPrice: 109, status: 'ACTIVE' };
  const sku = await api('POST', skuPath, variant);
  assert.equal((await api('POST', skuPath, variant)).skuId, sku.skuId);
  await api('POST', skuPath, { ...variant, requestId: randomUUID(), code: 'pgverify-matte' }, 409);
  const changed = await api('PUT', skuPath + '/' + sku.skuId, {
    expectedVersion: sku.businessVersion, code: sku.code, unitPrice: 89, status: 'ACTIVE',
  });
  assert.equal(changed.stockProductId, sku.stockProductId);
  await until(() => api('GET', searchPath + '/index'), s => s.pending === 0, 'SQL index catches up');
  await assertSearch(productId);
  const matchingAccent = await search({ query: 'CAFÉ', locale: 'en' });
  assert(matchingAccent.products.some(row => row.productId === productId), 'case-insensitive SQL search');
  const differentAccent = await search({ query: 'CAFE', locale: 'en' });
  assert(!differentAccent.products.some(row => row.productId === productId), 'SQL search preserves accent distinctions');
  console.log(JSON.stringify({productId, stockProductId: sku.stockProductId,
    businessVersion: (await api('GET', current)).businessVersion, sql: true}));
} else if (phase === 'elastic') {
  const productId = Number(productIdText);
  const state = await until(() => api('GET', searchPath + '/index'),
    s => s.elasticsearch.phase === 'READY', 'Elasticsearch initial rebuild');
  await assertSearch(productId);
  await api('POST', searchPath + '/index/rebuild');
  const rebuilt = await until(() => api('GET', searchPath + '/index'), s =>
    s.generation > state.generation && s.elasticsearch.generation > state.elasticsearch.generation &&
    s.elasticsearch.phase === 'READY', 'both index generations advance');
  const comparison = await api('GET', searchPath + '/index/comparison');
  assert.equal(comparison.status, 'CHECKED');
  assert(comparison.checked >= 48);
  assert.deepEqual(comparison.differences, []);
  console.log(JSON.stringify({ sqlGeneration: rebuilt.generation, elasticGeneration: rebuilt.elasticsearch.generation,
    checked: comparison.checked }));
} else if (phase === 'fallback') {
  await assertSearch(Number(productIdText));
  const state = await api('GET', searchPath + '/index');
  assert.equal(state.elasticsearch.phase, 'UNAVAILABLE');
  console.log(JSON.stringify({ fallback: true, sqlGeneration: state.generation }));
} else if (phase === 'window-edit') {
  const productId = 1;
  const current = path + '/merchant/products/' + productId;
  const old = await api('GET', current);
  const price = Number(productIdText);
  assert.equal(old.lifecycleStatus, 'ACTIVE');
  assert(Number.isFinite(price) && price > 0 && price !== old.unitPrice);
  const priced = await api('PUT', current + '/price', { expectedVersion: old.businessVersion, unitPrice: price });
  const translated = await api('PUT', current + '/translations/en', {
    expectedVersion: priced.businessVersion, name: 'CatalogWindow laptop ' + priced.businessVersion,
    brief: 'Catalog window verification', description: 'Isolated catalog PostgreSQL publisher verification',
  });
  assert.equal(translated.businessVersion, old.businessVersion + 2);
  console.log(JSON.stringify({ productId, fromVersion: old.businessVersion, version: translated.businessVersion,
    price, name: 'CatalogWindow laptop ' + priced.businessVersion }));
} else if (phase === 'window-index' || phase === 'window-rebuild') {
  let state = await readyIndex();
  if (phase === 'window-rebuild') {
    const previous = state;
    await api('POST', searchPath + '/index/rebuild');
    state = await until(() => api('GET', searchPath + '/index'),
      s => s.generation > previous.generation && s.elasticsearch.generation > previous.elasticsearch.generation &&
        s.pending === 0 && s.elasticsearch.phase === 'READY',
      'SQL and Elasticsearch both advance to the next generation');
  }
  if (productIdText) {
    const productId = Number(productIdText);
    const result = await search({ query: 'CatalogWindow' });
    const product = result.products.find(item => item.productId === productId);
    assert.equal(product?.businessVersion, Number(versionText));
    assert.equal(product?.unitPrice, Number(priceText));
    assert.equal(product?.name, 'CatalogWindow laptop ' + (Number(versionText) - 1));
  }
  console.log(JSON.stringify({ sqlGeneration: state.generation, elasticGeneration: state.elasticsearch.generation,
    compared: await compareIndex() }));
} else {
  throw new Error('Unknown catalog verification phase');
}
