<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, signedApi } from '../api'
import type { Transaction } from '../types'

const transactions = ref<Transaction[]>([])
const error = ref('')
const result = ref<Transaction | null>(null)
const form = ref({ appId: '', algorithm: 'HMAC_SHA256' as 'HMAC_SHA256' | 'RSA_SHA256', appSecret: '', privateKey: '', merchantRequestNo: `ORDER-${Date.now()}`, channel: 'ALIPAY', subject: 'Spring 教学订单', amount: 99.00, currency: 'CNY', faultScenario: 'NONE' })
const load = async () => transactions.value = await api('/api/admin/transactions')
onMounted(load)

async function create() {
  error.value = ''
  const payload = { merchantRequestNo: form.value.merchantRequestNo, channel: form.value.channel,
    subject: form.value.subject, amount: form.value.amount, currency: form.value.currency,
    faultScenario: form.value.faultScenario }
  const body = JSON.stringify(payload)
  try {
    result.value = await signedApi('/api/channel/transactions', 'POST', body, {
      appId: form.value.appId, algorithm: form.value.algorithm,
      secret: form.value.appSecret, privateKey: form.value.privateKey
    })
    await load()
  } catch (e) { error.value = e instanceof Error ? e.message : '创建失败' }
}
async function close(item: Transaction) {
  try { await api(`/api/admin/transactions/${item.channelTransactionNo}/close`, { method: 'POST' }); await load() }
  catch (e) { error.value = e instanceof Error ? e.message : '关闭失败' }
}
</script>

<template>
  <div class="page-title"><div><span class="eyebrow">SIGNED API LAB · 05A-3</span><h1>商户请求签名实验台</h1><p>Web Crypto 在浏览器端完成 HMAC/RSA 签名，服务端验证时间窗口、摘要和防重放记录。</p></div><span class="badge success">SIGNED REQUESTS</span></div>
  <div v-if="error" class="notice error">{{ error }}</div>
  <div class="content-grid transaction-layout">
    <form class="card" @submit.prevent="create"><div class="card-head"><div><span class="eyebrow">MERCHANT SDK SIMULATOR</span><h3>创建签名交易</h3></div><span class="badge pending">05A-3</span></div><label>App ID<input v-model="form.appId" required placeholder="从商户页复制" /></label><label>签名算法<select v-model="form.algorithm"><option value="HMAC_SHA256">HMAC_SHA256</option><option value="RSA_SHA256">RSA_SHA256</option></select></label><label v-if="form.algorithm === 'HMAC_SHA256'">App Secret<input v-model="form.appSecret" type="password" required autocomplete="off" /></label><label v-else>RSA 私钥（PKCS#8 PEM，仅保留在当前页内存）<textarea v-model="form.privateKey" rows="6" required placeholder="-----BEGIN PRIVATE KEY-----"></textarea></label><label>商户请求号<input v-model="form.merchantRequestNo" required /></label><label>商品主题<input v-model="form.subject" required /></label><div class="form-row"><label>渠道<select v-model="form.channel"><option>ALIPAY</option><option>WECHAT_PAY</option><option>UNION_PAY</option></select></label><label>金额<input v-model.number="form.amount" type="number" min="0.01" step="0.01" /></label></div><label>故障场景<select v-model="form.faultScenario"><option value="NONE">正常</option><option value="CREATE_FAILURE">创建失败</option><option value="DELAYED_SUCCESS">延迟成功</option><option value="NOTIFY_LOST">通知丢失</option></select></label><button class="primary full">签名并发送</button><p class="muted small">签名串：METHOD + PATH + TIMESTAMP + NONCE + REQUEST_ID + BODY_SHA256。密钥不会保存到 localStorage。</p></form>
    <article class="card span-2"><div class="card-head"><div><span class="eyebrow">LIVE LEDGER</span><h3>渠道交易账本</h3></div><button class="secondary" @click="load">刷新</button></div><div v-if="!transactions.length" class="empty">当前账号没有可见交易，或尚未创建交易。</div><div class="table-scroll" v-else><table><thead><tr><th>渠道交易号</th><th>渠道 / 主题</th><th>金额</th><th>故障</th><th>状态</th><th></th></tr></thead><tbody><tr v-for="item in transactions" :key="item.channelTransactionNo"><td class="mono">{{ item.channelTransactionNo.slice(0, 18) }}…<small>{{ item.merchantRequestNo }}</small></td><td><b>{{ item.channel }}</b><small>{{ item.subject }}</small></td><td>¥{{ item.amount }}</td><td>{{ item.faultScenario }}</td><td><span :class="['badge', item.status.toLowerCase()]">{{ item.status }}</span></td><td><button v-if="item.status === 'PENDING'" class="danger-link" @click="close(item)">关闭</button></td></tr></tbody></table></div></article>
  </div>
  <section class="card" style="margin-top:18px"><span class="eyebrow">SECURITY PIPELINE</span><h3>服务端验签顺序</h3><div class="stat-grid"><div class="stat"><span>01</span><strong>时间窗口</strong><small>默认 ±5 分钟</small></div><div class="stat"><span>02</span><strong>SHA-256 摘要</strong><small>先发现正文篡改</small></div><div class="stat"><span>03</span><strong>HMAC / RSA</strong><small>常量时间比较</small></div><div class="stat"><span>04</span><strong>Redis Nonce</strong><small>原子 SET NX + TTL</small></div><div class="stat"><span>05</span><strong>MySQL 约束</strong><small>最终防重放记录</small></div></div></section>
  <div v-if="result" class="result-drawer"><div><span class="eyebrow">VERIFIED RESPONSE</span><h3>{{ result.actionType }}</h3><p class="mono">{{ result.channelTransactionNo }}</p></div><a class="primary" :href="result.checkoutUrl" target="_blank">打开模拟收银台 ↗</a><button class="ghost" @click="result = null">关闭</button></div>
</template>
