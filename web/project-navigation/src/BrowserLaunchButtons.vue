<script setup lang="ts">
import { ref } from 'vue'
import type { ServiceLink } from './navigation'

const props = defineProps<{ serviceId: string; link: ServiceLink; label?: string }>()
const emit = defineEmits<{ feedback: [message: string, error: boolean] }>()

const browsers = [
  { id: 'chrome', name: 'Chrome', icon: new URL('./assets/chrome.png', import.meta.url).href },
  { id: 'edge', name: 'Edge', icon: new URL('./assets/edge.png', import.meta.url).href },
  { id: 'firefox', name: 'Firefox', icon: new URL('./assets/firefox.png', import.meta.url).href },
]
const pending = ref(false)

async function openInBrowser(browser: typeof browsers[number]) {
  if (pending.value) return
  pending.value = true
  emit('feedback', '', false)
  try {
    const target = new URL(props.link.url)
    const response = await fetch('/api/navigation/open-browser', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        browser: browser.id, serviceId: props.serviceId, path: target.pathname, port: Number(target.port || 80),
      }),
      signal: AbortSignal.timeout(4000),
    })
    if (response.ok) {
      emit('feedback', '已请求用 ' + browser.name + ' 打开「' + (props.label || props.link.label) + '」', false)
    } else {
      const error = response.status === 503 ? '未找到 ' + browser.name + ' 的常用安装位置'
        : response.status === 404 ? '此入口暂不可用，请刷新服务列表后重试'
          : '无法通过本地控制台启动 ' + browser.name
      emit('feedback', error, true)
    }
  } catch {
    emit('feedback', '无法连接本地控制台，请重试', true)
  } finally {
    pending.value = false
  }
}
</script>

<template>
  <div class="browser-launch-buttons" role="group" :aria-label="(label || link.label) + '浏览器选项'">
    <button v-for="browser in browsers" :key="browser.id" type="button"
      :disabled="pending"
      :aria-label="'用 ' + browser.name + ' 打开' + (label || link.label)"
      :title="'用 ' + browser.name + ' 打开'"
      @click="openInBrowser(browser)">
      <img :src="browser.icon" alt="" width="18" height="18">
    </button>
  </div>
</template>
