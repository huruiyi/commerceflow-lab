<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue';
import { init, use, type ComposeOption } from 'echarts/core';
import {
  LineChart,
  PieChart,
  RadarChart,
  type LineSeriesOption,
  type PieSeriesOption,
  type RadarSeriesOption,
} from 'echarts/charts';
import {
  GridComponent,
  LegendComponent,
  RadarComponent,
  TooltipComponent,
  type GridComponentOption,
  type LegendComponentOption,
  type RadarComponentOption,
  type TooltipComponentOption,
} from 'echarts/components';
import { SVGRenderer } from 'echarts/renderers';

type ChartOptions = ComposeOption<
  | LineSeriesOption
  | PieSeriesOption
  | RadarSeriesOption
  | GridComponentOption
  | LegendComponentOption
  | RadarComponentOption
  | TooltipComponentOption
>;
const props = defineProps<{ option: ChartOptions; label: string }>();
use([LineChart, PieChart, RadarChart, GridComponent, LegendComponent, RadarComponent, TooltipComponent, SVGRenderer]);

const host = ref<HTMLElement>();
let chart: ReturnType<typeof init> | undefined;
let resize: ResizeObserver | undefined;
let theme: MutationObserver | undefined;

function render() {
  if (!host.value || !chart) return;
  const ink = getComputedStyle(host.value).color;
  chart.setOption(
    {
      color: ['#58aee6', '#24bfc2', '#ad97d4', '#efb05c', '#db8096'],
      animation: false,
      darkMode: Boolean(host.value.closest('.sim-dark')),
      textStyle: { color: ink },
      ...props.option,
    },
    { notMerge: true },
  );
}

onMounted(() => {
  if (!host.value) return;
  chart = init(host.value, undefined, { renderer: 'svg' });
  render();
  resize = new ResizeObserver(() => chart?.resize());
  resize.observe(host.value);
  const shell = host.value.closest('.sim-admin');
  if (shell) {
    theme = new MutationObserver(render);
    theme.observe(shell, { attributeFilter: ['class'] });
  }
});

watch(() => props.option, render);
onUnmounted(() => {
  resize?.disconnect();
  theme?.disconnect();
  chart?.dispose();
});
</script>

<template>
  <div ref="host" class="payment-chart" role="img" :aria-label="label" />
</template>
