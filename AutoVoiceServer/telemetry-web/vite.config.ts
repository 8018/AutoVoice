import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  // base "./"：产物由 Spring Boot 托管在 /telemetry/ 子路径，资源引用须相对，
  // 否则 index.html 里的 /assets/* 会解析到站点根，404 白屏
  base: "./",
  server: { proxy: { "/api": "http://127.0.0.1:8080" } },
  build: {
    outDir: "../telemetry/src/main/resources/static/telemetry",
    emptyOutDir: true,
  },
});
