import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 14209,
    proxy: {
      '/api/auth': 'http://127.0.0.1:14200',
      '/api': 'http://127.0.0.1:18101',
    },
  },
});
