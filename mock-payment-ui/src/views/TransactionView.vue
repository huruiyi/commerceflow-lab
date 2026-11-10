<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'
import type { Transaction } from '../types'

const transactions = ref<Transaction[]>([])
const error = ref('')
const result = ref<Transaction | null>(null)
const form = ref({ appId: '', appSecret: '', merchantRequestNo: `ORDER-${Date.now()}`, channel: 'ALIPAY', subject: 'Spring 教学订单', amount: 99.00, currency: 'CNY', faultScenario: 'NONE' })
const load = async () => transactions.value = await api('/api/admin/transactions')
onMounted(load)
async function create() {
  error.value = ''
  try {
    result.value = await api('/api/channel/transactions', { method: 'POST', headers: { 'X-App-Id': form.value.appId, 'X-Demo-App-Secret': form.value.appSecret }, body: JSON.stringify(form.value) })
    await load()
  } catch (e) { error.value = e instanceof Error ? e.message : '创建失败' }
}
async function close(item: Transaction) {
  try { await api(`/api/admin/transactions/${item.channelTransactionNo}/close`, { method: 'POST' }); await load() }
  catch (e) { error.value = e instanceof Error ? e.message : '关闭失败' }
}
</script>

<template>
  <div class="page-title"><div><span class="eyebrow">CHANNEL TRANSACTIONS</span><h1>三渠道交易实验台</h1><p>创建、查询、收银和关闭渠道交易；重复商户请求号会安全重放。</p></div></div>
  <div v-if="error" class="notice error">{{ error }}</div>
  <div class="content-grid transaction-layout">
    <form class="card" @submit.prevent="create"><div class="card-head"><div><span class="eyebrow">API CONSOLE</span><h3>创建渠道交易</h3></div><span class="badge pending">05A-2</span></div><label>App ID<input v-model="form.appId" required placeholder="从商户页复制" /></label><label>一次性保存的 App Secret<input v-model="form.appSecret" type="password" required /></label><label>商户请求号<input v-model="form.merchantRequestNo" required /></label><label>商品主题<input v-model="form.subject" required /></label><div class="form-row"><label>渠道<select v-model="form.channel"><option>ALIPAY</option><option>WECHAT_PAY</option><option>UNION_PAY</option></select></label><label>金额<input v-model.number="form.amount" type="number" min="0.01" step="0.01" /></label></div><label>故障场景<select v-model="form.faultScenario"><option value="NONE">正常</option><option value="CREATE_FAILURE">创建失败</option><option value="DELAYED_SUCCESS">延迟成功（状态标记）</option><option value="NOTIFY_LOST">通知丢失（状态标记）</option></select></label><button class="primary full">发送创建请求</button><p class="muted small">本章用 App Secret 直接认证以便独立实验；05A-3 将改为 RSA/HMAC 签名、时间戳和 Nonce。</p></form>
    <article class="card span-2"><div class="card-head"><div><span class="eyebrow">LIVE LEDGER</span><h3>渠道交易账本</h3></div><button class="secondary" @click="load">刷新</button></div><div v-if="!transactions.length" class="empty">当前账号没有可见交易，或尚未创建交易。</div><div class="table-scroll" v-else><table><thead><tr><th>渠道交易号</th><th>渠道 / 主题</th><th>金额</th><th>故障</th><th>状态</th><th></th></tr></thead><tbody><tr v-for="item in transactions" :key="item.channelTransactionNo"><td class="mono">{{ item.channelTransactionNo.slice(0, 18) }}…<small>{{ item.merchantRequestNo }}</small></td><td><b>{{ item.channel }}</b><small>{{ item.subject }}</small></td><td>¥{{ item.amount }}</td><td>{{ item.faultScenario }}</td><td><span :class="['badge', item.status.toLowerCase()]">{{ item.status }}</span></td><td><button v-if="item.status === 'PENDING'" class="danger-link" @click="close(item)">关闭</button></td></tr></tbody></table></div></article>
  </div>
  <div v-if="result" class="result-drawer"><div><span class="eyebrow">CHANNEL RESPONSE</span><h3>{{ result.actionType }}</h3><p class="mono">{{ result.channelTransactionNo }}</p></div><a class="primary" :href="result.checkoutUrl" target="_blank">打开模拟收银台 ↗</a><button class="ghost" @click="result = null">关闭</button></div>
</template>
