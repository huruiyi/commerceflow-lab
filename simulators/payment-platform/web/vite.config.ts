import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 14201,
    proxy: { '/api': 'http://localhost:18082' },
  },
});
