import { describe, expect, it } from 'vitest'
import { branchOf, contextLevelOf, contextOf, elapsedOf, promptOf, titleOf } from './format'
import type { Session } from './types'

/**
 * 경계값은 **이 기계의 실제 데이터에서** 가져왔다 (docs/05-검증.md 5번).
 * 실측 2026-09-03: 세션 16개 중 제목 없는 것 5개, 브랜치 없는 것 1개,
 * 가장 긴 브랜치명 `worktree-fix-imax-scan-throttle`, 가장 긴 프롬프트 4,395자.
 */
function session(overrides: Partial<Session> = {}): Session {
  return {
    sessionId: 's1',
    pid: 100,
    state: 'WAITING',
    title: '제목',
    lastPrompt: '프롬프트',
    branch: 'main',
    permissionMode: 'auto',
    model: 'claude-opus-5',
    contextTokens: 544102,
    contextLimit: 1000000,
    contextRatio: 0.544102,
    lastActivityAt: '2026-09-03T08:54:10.145Z',
    startedAt: '2026-08-30T05:58:55.537Z',
    ordinal: 16,
    ...overrides,
  }
}

describe('titleOf', () => {
  it('제목이 있으면 그대로 낸다', () => {
    expect(titleOf(session({ title: '알림봇 상영관 등급 필터링 검증' }))).toBe('알림봇 상영관 등급 필터링 검증')
  })

  // 실측: 16개 중 5개가 이 상태다. 렌더가 죽으면 화면 전체가 날아간다.
  it('제목이 null 이어도 자리를 채운다', () => {
    expect(titleOf(session({ title: null }))).toBe('(제목 없음)')
  })

  it('제목이 공백뿐이어도 자리를 채운다', () => {
    expect(titleOf(session({ title: '   ' }))).toBe('(제목 없음)')
  })
})

describe('branchOf', () => {
  it('긴 브랜치명도 자르지 않는다 — 자르는 것은 CSS 의 일이다', () => {
    expect(branchOf(session({ branch: 'worktree-fix-imax-scan-throttle' }))).toBe('worktree-fix-imax-scan-throttle')
  })

  // 실측: STALLED 세션 하나가 브랜치 없이 왔다.
  it('브랜치가 null 이면 대시', () => {
    expect(branchOf(session({ branch: null }))).toBe('—')
  })
})

describe('contextOf — 절대값·상한·% 셋을 모두 낸다', () => {
  /*
   * docs/00-개요.md 결정사항 3. 기록의 모델명이 claude-opus-5 로만 남아
   * [1m] 변형이 구분되지 않아 **분모가 틀릴 수 있다** — % 만 내면
   * 분모가 틀렸을 때 판단 근거가 통째로 사라진다.
   */
  it('셋이 다 들어간다', () => {
    const text = contextOf(session())
    expect(text).toBe('544K / 1.0M · 54%')
    // 셋이 각각 실제로 들어 있는지 따로 본다 — 형식만 맞고 값이 빠지는 것을 막는다.
    expect(text).toContain('544K') // 절대값
    expect(text).toContain('1.0M') // 상한
    expect(text).toContain('54%') // 비율
  })

  it('상한을 모르면 절대값만이라도 낸다', () => {
    expect(contextOf(session({ contextLimit: null, contextRatio: null }))).toBe('544K')
  })

  it('ratio 가 없으면 직접 계산한다', () => {
    expect(contextOf(session({ contextRatio: null }))).toBe('544K / 1.0M · 54%')
  })

  it('토큰 자체가 없으면 null — 줄에서 통째로 뺀다', () => {
    expect(contextOf(session({ contextTokens: null }))).toBeNull()
  })

  // "아직 안 읽었다(null)"와 "읽었더니 0 이다"는 다른 값이다 — 뭉뚱그리면
  // 컨텍스트 칸이 사라진 것이 어느 쪽 때문인지 화면에서 구별되지 않는다.
  it('0 토큰은 null 이 아니다 — "없음"과 "0"은 다르다', () => {
    expect(contextOf(session({ contextTokens: 0, contextRatio: 0 }))).toBe('0 / 1.0M · 0%')
  })
})

describe('elapsedOf', () => {
  const now = Date.parse('2026-09-03T10:00:00Z')

  it('1분 미만은 "지금"', () => {
    expect(elapsedOf('2026-09-03T09:59:30Z', now)).toBe('지금')
  })

  it('분 단위', () => {
    expect(elapsedOf('2026-09-03T09:54:00Z', now)).toBe('6분')
  })

  it('시간 단위', () => {
    expect(elapsedOf('2026-09-03T07:00:00Z', now)).toBe('3시간')
  })

  // 실측: 27일째 떠 있는 세션이 있었다 (docs/00-개요.md).
  it('일 단위', () => {
    expect(elapsedOf('2026-08-07T10:00:00Z', now)).toBe('27일')
  })

  it('시각이 없으면 대시', () => {
    expect(elapsedOf(null, now)).toBe('—')
  })

  it('깨진 시각이어도 던지지 않는다', () => {
    expect(elapsedOf('not-a-date', now)).toBe('—')
  })

  // 시계가 살짝 어긋나 미래 시각이 와도 "-3분" 같은 것이 나오면 안 된다.
  it('미래 시각은 "지금"으로 접는다', () => {
    expect(elapsedOf('2026-09-03T10:05:00Z', now)).toBe('지금')
  })
})

describe('promptOf', () => {
  it('줄바꿈을 한 줄로 접는다', () => {
    expect(promptOf(session({ lastPrompt: '첫 줄\n\n둘째  줄' }))).toBe('첫 줄 둘째 줄')
  })

  // 실측 최대 4,395자. CSS 말줄임은 보이는 것만 줄이지 노드 크기는 줄이지 않는다.
  it('아주 긴 프롬프트를 잘라낸다', () => {
    const long = 'ㄱ'.repeat(4395)
    const result = promptOf(session({ lastPrompt: long }))
    expect(result).toHaveLength(201) // 200자 + 말줄임표
    expect(result?.endsWith('…')).toBe(true)
  })

  it('프롬프트가 없으면 null — 줄에서 통째로 뺀다', () => {
    expect(promptOf(session({ lastPrompt: null }))).toBeNull()
  })

  it('공백뿐이면 null', () => {
    expect(promptOf(session({ lastPrompt: '  \n ' }))).toBeNull()
  })
})

// #23 — docs/00-개요.md 목표 3 "컨텍스트 사용량과 경고"가 구현돼 있지 않았다.
describe('contextLevelOf', () => {
  const at = (ratio: number | null, tokens = 500_000, limit = 1_000_000) =>
    session({ contextRatio: ratio, contextTokens: tokens, contextLimit: limit })

  it('70% 미만은 normal — 평소엔 조용해야 경고가 보인다', () => {
    expect(contextLevelOf(at(0))).toBe('normal')
    expect(contextLevelOf(at(0.69))).toBe('normal')
  })

  it('70% 이상은 warn — 새 세션을 열 준비를 할 시점', () => {
    expect(contextLevelOf(at(0.7))).toBe('warn')
    expect(contextLevelOf(at(0.84))).toBe('warn')
  })

  it('85% 이상은 danger — 지금 열 시점', () => {
    expect(contextLevelOf(at(0.85))).toBe('danger')
    expect(contextLevelOf(at(0.99))).toBe('danger')
  })

  // 경계에서 갈리는지 본다. 임계 바로 아래/위가 같은 답이면 임계가 죽은 것이다.
  it('임계 경계에서 갈린다', () => {
    expect(contextLevelOf(at(0.6999))).toBe('normal')
    expect(contextLevelOf(at(0.7))).toBe('warn')
    expect(contextLevelOf(at(0.8499))).toBe('warn')
    expect(contextLevelOf(at(0.85))).toBe('danger')
  })

  // ratio 가 없어도 절대값/상한으로 계산한다 — 서버가 ratio 를 빠뜨릴 수 있다.
  it('contextRatio 가 없으면 토큰/상한으로 계산한다', () => {
    expect(contextLevelOf(at(null, 900_000, 1_000_000))).toBe('danger')
    expect(contextLevelOf(at(null, 100_000, 1_000_000))).toBe('normal')
  })

  // 판단 근거가 없으면 경고하지 않는다 — 모르는 것을 위험으로 표시하면 거짓 경보가 된다.
  it('정보가 없으면 normal', () => {
    expect(contextLevelOf(session({ contextRatio: null, contextTokens: null, contextLimit: null }))).toBe('normal')
    expect(contextLevelOf(session({ contextRatio: null, contextTokens: 500_000, contextLimit: 0 }))).toBe('normal')
  })

  // 관측값이 상한을 넘을 수 있다 — 기록의 모델명이 claude-opus-5 로만 남아
  // [1m] 변형이 구분되지 않는다 (docs/00-개요.md 결정사항 3).
  it('상한을 넘으면 danger', () => {
    expect(contextLevelOf(at(1.0))).toBe('danger')
    expect(contextLevelOf(at(1.2))).toBe('danger')
  })

  // 값이 이상하면 경고하지 않는다. 모르는 것을 위험으로 표시하면 거짓 경보다.
  it('음수나 NaN 은 normal', () => {
    expect(contextLevelOf(at(-0.5))).toBe('normal')
    expect(contextLevelOf(at(Number.NaN))).toBe('normal')
  })
})

// #23 — 시스템 태그가 프롬프트 줄을 통째로 먹던 문제.
// #11·#15 에서 "거르면 뭐가 걸러졌는지 사라진다"고 미뤘던 건이라,
// **내용이 사라지지 않는다**를 명시적으로 검사한다.
describe('promptOf — 태그 처리', () => {
  const p = (lastPrompt: string) => promptOf(session({ lastPrompt }))

  it('시스템 태그의 꺾쇠를 벗기고 내용을 남긴다', () => {
    expect(p('<task-notification><summary>모니터 이벤트</summary>')).toBe('모니터 이벤트')
  })

  // #15 의 우려가 현실이 되지 않는지 — 실측 1위가 <td> 19건이었다.
  it('사용자가 붙여넣은 HTML 의 내용도 살아남는다', () => {
    expect(p('<table><tr><td>이름</td><td>값</td></tr></table> 이 표 좀 봐줘')).toBe('이름 값 이 표 좀 봐줘')
    expect(p('이건 그냥 <strong>강조</strong>가 든 문장이다')).toBe('이건 그냥 강조 가 든 문장이다')
  })

  it('태그 이름만 있고 내용이 없으면 빈 결과가 아니라 null', () => {
    expect(p('<ide_opened_file></ide_opened_file>')).toBeNull()
  })

  it('태그가 없는 평범한 프롬프트는 그대로', () => {
    expect(p('재시도 큐가 계속 쌓이기만 하는 구조인가')).toBe('재시도 큐가 계속 쌓이기만 하는 구조인가')
  })

  // 부등호가 코드나 수식으로 쓰인 경우 — 태그가 아닌데 벗겨지면 내용이 깨진다.
  // 실측: `<[^>]*>` 로 뭉뚱그렸더니 "a < b 이고 c > d" 가 "a d" 가 됐다.
  it('부등호는 태그가 아니므로 안 지운다', () => {
    expect(p('a < b 이고 c > d 인 경우')).toBe('a < b 이고 c > d 인 경우')
    expect(p('조건은 x <= 10 이고 y >= 5 다')).toBe('조건은 x <= 10 이고 y >= 5 다')
  })

  // 실측: 사용자가 붙여넣은 파일 내용에서 HTML 주석이 통째로 사라졌다 (2건).
  it('HTML 주석의 내용은 남긴다', () => {
    expect(p('<!-- 이 파일은 읽기 전용입니다 --> 이거 고쳐줘')).toContain('이 파일은 읽기 전용입니다')
    expect(p('<!-- 이 파일은 읽기 전용입니다 --> 이거 고쳐줘')).toContain('이거 고쳐줘')
  })

  // 속성이 붙은 태그도 지운다 — 안 지우면 <html lang="en"> 같은 게 그대로 보인다.
  it('속성이 있는 태그도 지운다', () => {
    expect(p('<html lang="en"><body class="x">본문</body></html>')).toBe('본문')
  })
})
