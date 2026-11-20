<script setup lang="ts">
import { computed, ref } from 'vue';
import type { WarehouseDocument } from './documents';
import { documentId } from './documents';

const props = defineProps<{ document: WarehouseDocument; saving: boolean }>();
const emit = defineEmits<{ close: []; save: [path: string, body: object] }>();
const outbound = computed(() => ('planned' in props.document ? props.document : null));
const returned = computed(() => ('returnId' in props.document ? props.document : null));
const amounts = ref(Object.fromEntries(outbound.value?.planned.map((line) => [line.orderLineId, line.quantity]) ?? []));
const quantity = ref(returned.value?.receivedQuantity ?? returned.value?.expectedQuantity ?? 0);
const note = ref('');
const error = ref('');
const canSupplement = computed(
  () =>
    returned.value &&
    !returned.value.supplementId &&
    ['SHORT_RECEIVED', 'FAILED'].includes(returned.value.status) &&
    (returned.value.receivedQuantity ?? 0) > 0,
);

function confirmOutbound(rejected = false) {
  if (!outbound.value) return;
  error.value = '';
  const lines = outbound.value.planned.map(({ orderLineId }) => ({
    orderLineId,
    quantity: rejected ? 0 : amounts.value[orderLineId]!,
  }));
  if (!rejected && !lines.some((line) => line.quantity > 0)) {
    error.value = '实发为零时请选择拒绝整张出库单。';
    return;
  }
  emit('save', 'outbounds/' + outbound.value.outboundId + '/result', {
    warehouseId: outbound.value.warehouseId,
    lines,
  });
}

function saveReturn(accepted: boolean) {
  if (!returned.value) return;
  const { returnId, warehouseId, status } = returned.value;
  if (status === 'WAITING_RECEIPT') {
    emit('save', 'returns/' + returnId + '/receipt', {
      warehouseId,
      quantity: accepted ? quantity.value : 0,
      rejected: !accepted,
    });
  } else {
    emit('save', 'returns/' + returnId + '/quality', {
      warehouseId,
      passed: accepted,
    });
  }
}

function supplement() {
  if (!returned.value || !note.value.trim()) return;
  const { returnId, warehouseId } = returned.value;
  emit('save', 'returns/' + returnId + '/supplement', {
    warehouseId,
    quantity: quantity.value,
    note: note.value.trim(),
  });
}
</script>

<template>
  <section class="warehouse-panel" aria-label="单据详情">
    <header class="warehouse-actions">
      <h2>单据详情</h2>
      <button class="sim-button" :disabled="saving" @click="emit('close')">关闭详情</button>
    </header>
    <p>
      单据编号 {{ documentId(document) }} · 订单 {{ document.orderId }} · 仓库
      {{ document.warehouseId }}
    </p>
    <p v-if="error" role="alert" class="warehouse-error">{{ error }}</p>
    <form v-if="outbound" @submit.prevent="confirmOutbound()">
      <table class="warehouse-lines">
        <thead>
          <tr>
            <th>订单行</th>
            <th>商品</th>
            <th>计划</th>
            <th>实发</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="line in outbound.planned" :key="line.orderLineId">
            <td>{{ line.orderLineId }}</td>
            <td>{{ line.productId }}</td>
            <td>{{ line.quantity }}</td>
            <td>
              <input
                v-if="outbound.status === 'PENDING'"
                v-model.number="amounts[line.orderLineId]"
                type="number"
                min="0"
                :max="line.quantity"
                :aria-label="'订单行 ' + line.orderLineId + ' 实发件数'"
                required
              />
              <template v-else>{{
                outbound.actual.find((value) => value.orderLineId === line.orderLineId)?.quantity ?? '—'
              }}</template>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="outbound.status === 'PENDING'" class="warehouse-actions">
        <button class="sim-button primary" :disabled="saving">确认实发</button>
        <button type="button" class="sim-button" :disabled="saving" @click="confirmOutbound(true)">
          拒绝整张出库单
        </button>
      </div>
    </form>
    <template v-if="returned">
      <p>
        原包裹 {{ returned.outboundId }} · 退货运单
        {{ returned.trackingNumber }}
      </p>
      <p>预期 {{ returned.expectedQuantity }} 件 · 实收 {{ returned.receivedQuantity ?? '—' }} 件</p>
      <form
        v-if="returned.status === 'WAITING_RECEIPT' || returned.status === 'RECEIVED'"
        @submit.prevent="saveReturn(true)"
      >
        <label v-if="returned.status === 'WAITING_RECEIPT'">
          仓库实收件数<input
            v-model.number="quantity"
            type="number"
            min="0"
            :max="returned.expectedQuantity"
            required
          />
        </label>
        <div class="warehouse-actions">
          <button class="sim-button primary" :disabled="saving">
            {{ returned.status === 'RECEIVED' ? '质检合格' : '登记实收' }}
          </button>
          <button type="button" class="sim-button" :disabled="saving" @click="saveReturn(false)">
            {{ returned.status === 'RECEIVED' ? '质检不通过' : '拒收' }}
          </button>
        </div>
      </form>
      <p v-if="returned.supplementId">
        复检合格 {{ returned.qualifiedQuantity }} 件 ·
        {{ returned.supplementNote }}
      </p>
      <form v-else-if="canSupplement" class="warehouse-actions" @submit.prevent="supplement">
        <label
          >复检合格件数<input
            v-model.number="quantity"
            type="number"
            min="1"
            :max="returned.receivedQuantity ?? 0"
            required
        /></label>
        <label>复检说明<input v-model="note" maxlength="300" required /></label>
        <button class="sim-button primary" :disabled="saving">登记补检凭证</button>
      </form>
    </template>
  </section>
</template>
