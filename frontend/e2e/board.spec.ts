import type { Page } from '@playwright/test'
import { expect, test } from '@playwright/test'
import { project, session, snapshot } from './fixtures'
import {
  connectionCount,
  drop,
  installFakeStream,
  push,
  pushAsMessage,
  reopen,
} from './sse'

/**
 * docs/05-검증.md 5번 — 프론트 검증 항목을 그대로 옮긴 것이다.
 *
 * 백엔드를 띄우지 않는다. CI 러너에는 `~/.claude/projects/` 도 `claude` CLI 도 없어
 * 빈 스냅샷밖에 못 준다. 검증 대상은 "서버가 도는가"가 아니라
 * **"이 응답이 오면 화면이 이렇게 된다"** 이므로 응답을 주입한다.
 */

/**
 * 프로젝트명 칸만 고른다.
 *
 * 이름은 줄의 `.name` 과 아래쪽 `cwd` 경로 버튼 양쪽에 나오므로 `getByText` 로 잡으면
 * 둘 다 걸려 strict mode 위반이 난다 — "두 군데 나온다"는 사실은 정상이지 버그가 아니다.
 */
function projectName(page: Page, name: string) {
  return page.locator('.name', { hasText: name })
}

test.beforeEach(async ({ page }) => {
  await installFakeStream(page)
  await page.goto('/')
})

test('세션이 0개여도 화면이 깨지지 않는다', async ({ page }) => {
  await push(page, snapshot({ projects: [] }))

  await expect(page.getByText('살아있는 세션이 없습니다.')).toBeVisible()
  // 헤더는 살아 있어야 한다 — 빈 화면과 "죽은 화면"은 다르다.
  await expect(page.getByRole('heading', { name: 'CLAUDE SESSIONS' })).toBeVisible()
  await expect(page.getByText('0 프로젝트')).toBeVisible()
  // 빈 그룹 머리글이 남으면 안 된다.
  await expect(page.getByRole('heading', { level: 2 })).toHaveCount(0)
})

test('첫 스냅샷 전과 "세션 0개"는 구별된다', async ({ page }) => {
  // 아직 아무것도 보내지 않았다.
  await expect(page.getByText('연결하는 중…')).toBeVisible()

  await push(page, snapshot({ projects: [] }))
  await expect(page.getByText('살아있는 세션이 없습니다.')).toBeVisible()
  await expect(page.getByText('연결하는 중…')).toHaveCount(0)
})

test('제목이 없는 세션도 렌더된다 (실측: 16개 중 5개)', async ({ page }) => {
  await push(
    page,
    snapshot({
      projects: [
        project('docs-site', 'STALLED', { title: null, branch: null, lastPrompt: null }),
        project('web-client', 'WAITING', { title: '정상 제목' }),
      ],
    }),
  )

  await expect(page.getByText('(제목 없음)')).toBeVisible()
  await expect(projectName(page, 'docs-site')).toBeVisible()
  // 제목 없는 것 때문에 나머지가 죽지 않는다.
  await expect(page.getByText('정상 제목')).toBeVisible()
})

test('긴 브랜치명이 가로 스크롤을 만들지 않는다', async ({ page }) => {
  await push(
    page,
    snapshot({
      projects: [
        project('cgv-ticketing-macro', 'WAITING', {
          branch: 'worktree-fix-imax-scan-throttle',
          lastPrompt: '가'.repeat(4395), // 실측 최대 길이
        }),
      ],
    }),
  )

  await expect(page.getByText('worktree-fix-imax-scan-throttle')).toBeVisible()
  // 문서 폭이 뷰포트를 넘으면 레이아웃이 깨진 것이다.
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
  expect(overflow).toBeLessThanOrEqual(0)
})

test('컨텍스트는 절대값·상한·% 셋을 모두 낸다', async ({ page }) => {
  await push(page, snapshot({ projects: [project('notify-service', 'WAITING')] }))

  // docs/00-개요.md 결정사항 3 — 분모가 틀릴 수 있어 절대값이 필요하다.
  const meta = page.locator('.meta').first()
  await expect(meta).toContainText('544K')
  await expect(meta).toContainText('1.0M')
  await expect(meta).toContainText('54%')
})

test('상태 그룹이 답변 대기 → 멈춤 의심 → 작업 중 순으로 나온다', async ({ page }) => {
  await push(
    page,
    snapshot({
      projects: [
        project('c', 'WORKING'),
        project('a', 'STALLED'),
        project('b', 'WAITING'),
      ],
    }),
  )

  await expect(page.getByRole('heading', { level: 2 })).toHaveText([
    /답변 대기/,
    /멈춤 의심/,
    /작업 중/,
  ])
})

/*
 * ⭐ 이 파일에서 가장 중요한 테스트.
 *
 * 서버는 `event: snapshot` 으로 보낸다. 앱이 `onmessage` 로 짜여 있으면
 * 화면이 영원히 "연결하는 중…" 에 머문다 — 연결은 붙고 에러도 안 난다.
 */
test('event: snapshot 으로 렌더된다 — onmessage 로 짜면 여기서 갈린다', async ({ page }) => {
  await pushAsMessage(page, snapshot({ projects: [project('무시될것', 'WAITING')] }))
  // 이름 없는 이벤트는 서버가 보내지 않는 형태다. 반응하면 안 된다.
  await expect(page.getByText('연결하는 중…')).toBeVisible()

  await push(page, snapshot({ projects: [project('렌더될것', 'WAITING')] }))
  await expect(projectName(page, '렌더될것')).toBeVisible()
  await expect(projectName(page, '무시될것')).toHaveCount(0)
})

/*
 * ⭐ 중복 렌더 — 서버가 이미 막았고 클라이언트는 통째로 교체한다.
 * "실제로 끊어볼 것" (이슈 #11 검증 항목).
 */
test('SSE 를 끊었다 붙여도 중복 렌더가 없다', async ({ page }) => {
  const payload = snapshot({
    projects: [project('notify-service', 'WAITING'), project('web-client', 'WORKING')],
  })

  await push(page, payload)
  await expect(page.locator('.project')).toHaveCount(2)

  // 실제로 끊는다.
  await drop(page)
  await expect(page.getByText('끊김')).toBeVisible()
  // 끊겨도 마지막 화면은 유지된다 — 비면 "언제부터 안 오는지"를 알 수 없다.
  await expect(page.locator('.project')).toHaveCount(2)

  // 붙으면서 서버가 캐시 1건을 다시 보낸다. 그대로 같은 스냅샷이다.
  await reopen(page)
  await push(page, payload)
  await expect(page.getByText('라이브')).toBeVisible()

  // 누적됐다면 4개가 된다. 통째로 교체하므로 2개여야 한다.
  await expect(page.locator('.project')).toHaveCount(2)
  await expect(projectName(page, 'notify-service')).toHaveCount(1)

  // 같은 것을 한 번 더 받아도 마찬가지다.
  await push(page, payload)
  await expect(page.locator('.project')).toHaveCount(2)

  // 재연결을 직접 짜면 연결이 두 개가 된다. 브라우저에 맡겼는지 본다.
  expect(await connectionCount(page)).toBe(1)
})

test('사라진 세션은 화면에서도 사라진다 — 병합하지 않는다', async ({ page }) => {
  await push(
    page,
    snapshot({
      projects: [project('남을것', 'WAITING'), project('사라질것', 'WAITING')],
    }),
  )
  await expect(page.locator('.project')).toHaveCount(2)

  await push(page, snapshot({ projects: [project('남을것', 'WAITING')] }))
  await expect(page.locator('.project')).toHaveCount(1)
  await expect(projectName(page, '사라질것')).toHaveCount(0)
})

test('errors 가 화면에 나온다 — "없다"와 "못 읽었다"는 다르다', async ({ page }) => {
  await push(
    page,
    snapshot({ projects: [], errors: ['첫 수집이 아직 끝나지 않았습니다'] }),
  )
  await expect(page.getByText('첫 수집이 아직 끝나지 않았습니다')).toBeVisible()
})

test('ended 는 기본 숨김이고 토글로 보인다', async ({ page }) => {
  await push(
    page,
    snapshot({
      projects: [project('살아있음', 'WAITING'), project('끝남', 'ENDED', { pid: null })],
    }),
  )

  await expect(projectName(page, '끝남')).toHaveCount(0)
  await expect(page.locator('.project')).toHaveCount(1)

  await page.getByLabel('종료 표시').check()
  await expect(projectName(page, '끝남')).toBeVisible()
  await expect(page.locator('.project')).toHaveCount(2)
})

test('프로젝트 줄을 누르면 다른 세션이 펼쳐진다', async ({ page }) => {
  await push(
    page,
    snapshot({
      projects: [
        project('cgv-ticketing-macro', 'WAITING', { title: '현재 세션' }, [
          session({ title: '접힌 세션', ordinal: 3 }),
        ]),
      ],
    }),
  )

  await expect(page.getByText('접힌 세션')).toHaveCount(0)
  await expect(page.getByText('세션 1개 더')).toBeVisible()

  await page.getByRole('button', { expanded: false }).click()
  await expect(page.getByText('접힌 세션')).toBeVisible()

  await page.getByRole('button', { expanded: true }).click()
  await expect(page.getByText('접힌 세션')).toHaveCount(0)
})

test('세션 조작 버튼이 없다 — 조회 전용', async ({ page }) => {
  await push(page, snapshot({ projects: [project('notify-service', 'WAITING')] }))

  // docs/00-개요.md "조회 전용". 잘못 눌러 작업이 날아가는 위험을 아예 없앤다.
  for (const forbidden of ['종료', '재개', '중지', '삭제', '전송']) {
    await expect(page.getByRole('button', { name: forbidden })).toHaveCount(0)
  }
})
