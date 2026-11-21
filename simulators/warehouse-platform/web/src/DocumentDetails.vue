<script setup lang="ts">
import { computed, ref } from 'vue';
import { formatAdminDate } from '@commerceflow/simulator-admin/preferences';
import type { WarehouseDocument } from './documents';
import { documentId, outboundLabels, returnLabels } from './documents';

const props = defineProps<{ document: WarehouseDocument; saving: boolean }>();
const emit = defineEmits<{ save: [path: string, body: object] }>();
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
  <section class="warehouse-detail-card" aria-labelledby="warehouse-document-summary">
    <div class="warehouse-section-heading">
      <h3 id="warehouse-document-summary">单据信息</h3>
      <span class="warehouse-status" :data-status="document.status">
        {{ outbound ? outboundLabels[outbound.status] : returned && returnLabels[returned.status] }}
      </span>
    </div>
    <dl class="warehouse-detail-fields">
      <div class="warehouse-detail-wide">
        <dt>{{ outbound ? '出库单号' : '退货单号' }}</dt>
        <dd class="warehouse-reference">{{ documentId(document) }}</dd>
      </div>
      <div>
        <dt>订单编号</dt>
        <dd>{{ document.orderId }}</dd>
      </div>
      <div>
        <dt>仓库编号</dt>
        <dd>{{ document.warehouseId }}</dd>
      </div>
      <div v-if="outbound" class="warehouse-detail-wide">
        <dt>创建时间</dt>
        <dd>
          <time :datetime="outbound.createdAt">{{ formatAdminDate(outbound.createdAt) }}</time>
        </dd>
      </div>
      <template v-if="returned">
        <div class="warehouse-detail-wide">
          <dt>原包裹</dt>
          <dd class="warehouse-reference">{{ returned.outboundId }}</dd>
        </div>
        <div class="warehouse-detail-wide">
          <dt>退货运单</dt>
          <dd class="warehouse-reference">{{ returned.trackingNumber }}</dd>
        </div>
      </template>
    </dl>
  </section>
  <form v-if="outbound" class="warehouse-detail-card" aria-label="商品明细" @submit.prevent="confirmOutbound()">
    <div class="warehouse-section-heading">
      <h3>商品明细</h3>
      <span class="warehouse-muted">{{ outbound.planned.length }} 个订单行</span>
    </div>
    <p v-if="error" role="alert" class="warehouse-error">{{ error }}</p>
    <div class="warehouse-table-wrap">
      <table class="warehouse-lines">
        <thead>
          <tr>
            <th scope="col">订单行</th>
            <th scope="col">商品编号</th>
            <th scope="col">计划件数</th>
            <th scope="col">实发件数</th>
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
    </div>
    <div v-if="outbound.status === 'PENDING'" class="warehouse-actions">
      <button class="sim-button primary" :disabled="saving">确认实发</button>
      <button type="button" class="sim-button" :disabled="saving" @click="confirmOutbound(true)">拒绝整张出库单</button>
    </div>
  </form>
  <template v-if="returned">
    <section class="warehouse-detail-card" aria-labelledby="warehouse-receipt-summary">
      <h3 id="warehouse-receipt-summary">收货与质检</h3>
      <dl class="warehouse-quantities">
        <div>
          <dt>预期件数</dt>
          <dd>{{ returned.expectedQuantity }}</dd>
        </div>
        <div>
          <dt>实收件数</dt>
          <dd>{{ returned.receivedQuantity ?? '—' }}</dd>
        </div>
        <div v-if="returned.supplementId">
          <dt>复检合格件数</dt>
          <dd>{{ returned.qualifiedQuantity ?? '—' }}</dd>
        </div>
      </dl>
      <dl v-if="returned.supplementId" class="warehouse-detail-fields">
        <div class="warehouse-detail-wide">
          <dt>补检凭证编号</dt>
          <dd class="warehouse-reference">{{ returned.supplementId }}</dd>
        </div>
        <div v-if="returned.supplementNote" class="warehouse-detail-wide">
          <dt>复检说明</dt>
          <dd>{{ returned.supplementNote }}</dd>
        </div>
      </dl>
    </section>
    <form
      v-if="returned.status === 'WAITING_RECEIPT' || returned.status === 'RECEIVED'"
      class="warehouse-detail-card"
      @submit.prevent="saveReturn(true)"
    >
      <h3>{{ returned.status === 'RECEIVED' ? '登记质检结果' : '登记实收结果' }}</h3>
      <div v-if="returned.status === 'WAITING_RECEIPT'" class="warehouse-form-fields">
        <label>
          仓库实收件数<input
            v-model.number="quantity"
            type="number"
            min="0"
            :max="returned.expectedQuantity"
            required
          />
        </label>
      </div>
      <div class="warehouse-actions">
        <button class="sim-button primary" :disabled="saving">
          {{ returned.status === 'RECEIVED' ? '质检合格' : '登记实收' }}
        </button>
        <button type="button" class="sim-button" :disabled="saving" @click="saveReturn(false)">
          {{ returned.status === 'RECEIVED' ? '质检不通过' : '拒收' }}
        </button>
      </div>
    </form>
    <form v-if="canSupplement" class="warehouse-detail-card" @submit.prevent="supplement">
      <h3>登记补检</h3>
      <div class="warehouse-form-fields">
        <label
          >复检合格件数<input
            v-model.number="quantity"
            type="number"
            min="1"
            :max="returned.receivedQuantity ?? 0"
            required
        /></label>
        <label>复检说明<input v-model="note" maxlength="300" required /></label>
      </div>
      <div class="warehouse-actions">
        <button class="sim-button primary" :disabled="saving">登记补检凭证</button>
      </div>
    </form>
  </template>
</template>
