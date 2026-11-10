<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import { authState } from '../main'
import type { Merchant, Transaction } from '../types'

const transactions = ref<Transaction[]>([])
const merchants = ref<Merchant[]>([])
onMounted(async () => {
  if (authState.session?.authorities.includes('TRANSACTION_READ')) transactions.value = await api('/api/admin/transactions')
  if (authState.session?.authorities.includes('MERCHANT_MANAGE')) merchants.value = await api('/api/admin/merchants')
})
const successful = computed(() => transactions.value.filter(t => t.status === 'SUCCESS').length)
const pending = computed(() => transactions.value.filter(t => t.status === 'PENDING').length)
</script>

<template>
  <div class="page-title"><div><span class="eyebrow">PAYMENT CONTROL CENTER</span><h1>早上好，{{ authState.session?.displayName }}</h1><p>从这里观察商户凭据、渠道交易和安全事件。</p></div><span class="status-pill"><i></i>平台运行中</span></div>
  <div class="metric-grid">
    <article class="metric accent"><span>渠道交易</span><b>{{ transactions.length }}</b><small>独立支付数据库</small></article>
    <article class="metric"><span>成功交易</span><b>{{ successful }}</b><small>模拟成功终态</small></article>
    <article class="metric"><span>待支付</span><b>{{ pending }}</b><small>15 分钟有效期</small></article>
    <article class="metric"><span>启用商户</span><b>{{ merchants.filter(m => m.enabled).length }}</b><small>App ID / Secret</small></article>
  </div>
  <div class="content-grid">
    <article class="card span-2"><div class="card-head"><div><span class="eyebrow">RECENT FLOW</span><h3>最近渠道交易</h3></div><router-link to="/transactions">查看全部 →</router-link></div>
      <div v-if="!transactions.length" class="empty">尚无可见交易。管理员可以先创建商户，再到“渠道交易”进行三渠道实验。</div>
      <table v-else><thead><tr><th>交易号</th><th>渠道</th><th>金额</th><th>状态</th></tr></thead><tbody><tr v-for="item in transactions.slice(0, 5)" :key="item.channelTransactionNo"><td class="mono">{{ item.channelTransactionNo.slice(0, 16) }}…</td><td>{{ item.channel }}</td><td>¥{{ item.amount }}</td><td><span :class="['badge', item.status.toLowerCase()]">{{ item.status }}</span></td></tr></tbody></table>
    </article>
    <article class="card"><span class="eyebrow">SECURITY POSTURE</span><h3>当前安全边界</h3><ul class="check-list"><li>后台与商城 Session 隔离</li><li>App Secret 加密且仅展示一次</li><li>敏感操作密码再认证</li><li>商户范围与角色双重授权</li><li class="future">05A-3：签名、防重放与 TOTP</li></ul></article>
  </div>
</template>
