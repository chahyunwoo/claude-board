import { defineConfig, devices } from '@playwright/test'

/**
 * E2E — **백엔드를 띄우지 않는다.**
 *
 * CI 러너에는 `~/.claude/projects/` 도 `claude` CLI 도 없어서 백엔드를 띄워도
 * 빈 스냅샷밖에 못 준다. 검증하려는 것은 "서버가 도는가"가 아니라
 * **"이 응답이 오면 화면이 이렇게 된다"** 이므로, 라우트를 가로채 응답을 주입한다.
 * 그래야 세션 0개·제목 없음·긴 브랜치명 같은 경계를 **결정적으로** 재현할 수 있다
 * (docs/05-검증.md 5번).
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    // 실제 빌드 결과를 서빙한다 — dev 서버가 아니라 배포되는 것과 같은 산출물을 본다.
    //
    // `--outDir dist` 를 **빌드와 preview 양쪽에** 준다. 기본 outDir 은
    // `../src/main/resources/static` (백엔드 저장소 안)이라, 한쪽만 주면 빌드한 곳과
    // 서빙하는 곳이 어긋나 옛 산출물을 검사하게 된다. e2e 는 백엔드 디렉터리를 건드리지 않는다.
    command:
      'npx vite build --outDir dist --emptyOutDir && npx vite preview --port 4173 --host 127.0.0.1 --outDir dist',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
})
