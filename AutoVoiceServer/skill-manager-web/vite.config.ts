import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// 产物由 Spring Boot 托管在 /skill-manager/ 子路径，必须相对引用
export default defineConfig({
  base: './',
  plugins: [react()],
  server: {
    proxy: { '/api': 'http://127.0.0.1:8083' },
  },
  build: {
    outDir: '../skill-manager/src/main/resources/static/skill-manager',
    emptyOutDir: true,
  },
});
