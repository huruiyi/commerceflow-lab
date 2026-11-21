<script setup lang="ts">
import { ref } from 'vue';

const props = defineProps<{ demoToken: string; loading: boolean; error: string }>();
const emit = defineEmits<{ signIn: [token: string] }>();
const token = ref('');
const usedDemo = ref(false);
const local = ['localhost', '127.0.0.1', '[::1]'].includes(window.location.hostname);

function signIn(demo: boolean) {
  const entered = demo ? props.demoToken : token.value.trim();
  if (props.loading || !entered) return;
  usedDemo.value = demo;
  emit('signIn', entered);
}
</script>

<template>
  <form class="sim-auth-form sim-token-login" @submit.prevent="signIn(false)">
    <label>
      管理令牌
      <input v-model="token" type="password" autocomplete="off" required />
    </label>
    <p v-if="error" class="sim-auth-error" role="alert">
      {{ error }}
      <span v-if="usedDemo">如已配置自定义令牌，请手动输入。</span>
    </p>
    <button type="submit" class="sim-button primary" :disabled="loading">
      {{ loading ? '正在连接…' : '进入后台' }}
    </button>
    <button
      v-if="local"
      type="button"
      class="sim-button"
      :disabled="loading"
      title="使用本地演示令牌"
      @click="signIn(true)"
    >
      一键登录
    </button>
  </form>
</template>
