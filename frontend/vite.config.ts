import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

/**
 * 빌드 결과는 `src/main/resources/static/` 으로 들어간다 — Spring 이 그대로 서빙하고
 * 단일 jar 산출물이 된다 (docs/04-배포.md, docs/06-개발환경.md "저장소 구조 — 왜 하나인가").
 *
 * dev 서버는 포트가 다르므로 **프록시**로 백엔드에 붙인다. CORS 를 여는 것이 아니다 —
 * 여는 순간 "데이터는 이 기계를 떠나지 않는다" 의 경계가 흐려진다 (docs/03-프론트.md).
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    outDir: '../src/main/resources/static',
    // 백엔드 저장소의 디렉터리를 비우므로 명시적으로 허용한다.
    emptyOutDir: true,
  },
  server: {
    proxy: {
      // 백엔드는 127.0.0.1 에만 바인딩한다. localhost 로 적으면 ::1 로 풀려
      // 연결이 거부될 수 있으므로 주소를 그대로 쓴다.
      '/api': {
        target: 'http://127.0.0.1:7777',
        changeOrigin: false,
      },
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.spec.ts'],
  },
})
