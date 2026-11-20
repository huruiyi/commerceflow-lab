<script setup lang="ts">
import { computed, ref } from 'vue';
import { timezone } from '@commerceflow/simulator-admin/preferences';
import { enumLabel, t } from '../i18n';
import { summarizePayments } from '../payment-statistics';
import type { Transaction } from '../types';
import PaymentChart from './PaymentChart.vue';

const props = defineProps<{ transactions: Transaction[] }>();
type ChartOption = InstanceType<typeof PaymentChart>['$props']['option'];
const amountView = ref(false);
const summary = computed(() => summarizePayments(props.transactions, timezone.value));
const trend = computed<ChartOption>(() => {
  const days = summary.value.days;
  return {
    grid: { top: 36, right: 20, bottom: 16, left: 12, outerBoundsMode: 'same' },
    legend: { top: 0 },
    tooltip: { trigger: 'axis', renderMode: 'richText', confine: true },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: days.map(({ date }) => date),
      axisLabel: { formatter: (date: string) => date.slice(5), hideOverlap: true },
      splitLine: { show: true },
    },
    yAxis: {
      type: 'value',
      minInterval: amountView.value ? 0.01 : 1,
      splitArea: { show: true },
    },
    series: amountView.value
      ? [
          {
            name: t('paymentTrendAmount') + ' (CNY)',
            type: 'line',
            smooth: true,
            areaStyle: { opacity: 0.3 },
            data: days.map(({ cents }) => cents / 100),
          },
        ]
      : [
          {
            name: t('paymentTrendCreated'),
            type: 'line',
            smooth: true,
            areaStyle: { opacity: 0.3 },
            data: days.map(({ created }) => created),
          },
          {
            name: t('paymentTrendPaid'),
            type: 'line',
            smooth: true,
            areaStyle: { opacity: 0.3 },
            data: days.map(({ paid }) => paid),
          },
        ],
  };
});
const distribution = computed(() => {
  const { channels, statuses, scenarios } = summary.value;
  const counts = [...channels.values()];
  const max = Math.max(1, ...counts.map(({ created }) => created));
  const radar: ChartOption = {
    legend: { bottom: 0 },
    tooltip: { renderMode: 'richText', confine: true },
    radar: {
      radius: '57%',
      center: ['50%', '46%'],
      indicator: [...channels.keys()].map((channel) => ({ name: enumLabel(channel), max })),
    },
    series: [
      {
        type: 'radar',
        areaStyle: { opacity: 0.2 },
        data: [
          { name: t('paymentTrendCreated'), value: counts.map(({ created }) => created) },
          { name: t('successfulTransactions'), value: counts.map(({ successful }) => successful) },
        ],
      },
    ],
  };
  function pie(values: Map<string, number>, rose: boolean): ChartOption {
    return {
      legend: { bottom: 0, type: 'scroll' },
      tooltip: { trigger: 'item', renderMode: 'richText', confine: true },
      series: [
        {
          type: 'pie',
          radius: rose ? ['15%', '65%'] : ['44%', '68%'],
          center: ['50%', '43%'],
          roseType: rose ? 'area' : undefined,
          label: { show: false },
          itemStyle: { borderRadius: 5 },
          data: [...values].map(([name, value]) => ({ name: enumLabel(name), value })),
        },
      ],
    };
  }
  return [
    { title: t('paymentByChannel'), option: radar },
    { title: t('paymentByStatus'), option: pie(statuses, false) },
    { title: t('paymentByScenario'), option: pie(scenarios, true) },
  ];
});
</script>

<template>
  <!-- Vben AnalysisChartsTabs and AnalysisChartCard layout (MIT). -->
  <section class="card-box mt-5 p-4" :aria-label="t('paymentStatistics')">
    <div class="payment-chart-toolbar">
      <a-radio-group v-model:value="amountView" button-style="solid" :aria-label="t('paymentStatistics')">
        <a-radio-button :value="false">{{ t('paymentTrendCount') }}</a-radio-button>
        <a-radio-button :value="true">{{ t('paymentTrendAmount') }}</a-radio-button>
      </a-radio-group>
      <span>{{ t('paymentTrendPeriod') }} · {{ timezone }}</span>
    </div>
    <PaymentChart
      class="payment-trend-chart"
      :option="trend"
      :label="amountView ? t('paymentTrendAmount') : t('paymentTrendCount')"
    />
  </section>
  <div class="payment-chart-distribution mt-5">
    <article v-for="chart in distribution" :key="chart.title" class="card-box min-w-0 p-4 shadow-sm">
      <h2 class="m-0 text-base font-semibold md:text-xl">{{ chart.title }}</h2>
      <span class="payment-chart-caption">{{ t('paymentStatisticsScope') }}</span>
      <PaymentChart :option="chart.option" :label="chart.title" />
    </article>
  </div>
</template>

<style scoped>
.payment-chart-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 16px;
  margin-bottom: 12px;
}
.payment-chart-toolbar > span,
.payment-chart-caption {
  color: hsl(var(--muted-foreground));
}
.payment-chart {
  height: 228px;
}
.payment-trend-chart {
  height: 320px;
}
.payment-chart-distribution {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}
@media (width < 1024px) {
  .payment-chart-distribution {
    grid-template-columns: 1fr;
  }
}
</style>
