import type { Session, SessionState } from './types'

/**
 * 화면에 내는 값의 서식. **전부 순수 함수로 두어 단위 테스트가 붙는다** —
 * 컴포넌트 안에 인라인으로 두면 "제목 없는 세션이 렌더되는가"(docs/05-검증.md 5번)를
 * 렌더링 없이 검증할 수 없다.
 */

/** 상태별 화면 라벨. 색이 아니라 **라벨과 위치**로 먼저 구분한다 (docs/03-프론트.md). */
export const STATE_LABEL: Record<SessionState, string> = {
  WAITING: '답변 대기',
  STALLED: '멈춤 의심',
  WORKING: '작업 중',
  IDLE: '유휴',
}

/** 정렬 우선순위. 백엔드 `SessionState.sortOrder` 와 같은 순서다. */
export const STATE_ORDER: SessionState[] = ['WAITING', 'STALLED', 'WORKING', 'IDLE']

/** 값이 없을 때 화면에 내는 것. 빈 문자열로 두면 줄이 무너져 보인다. */
const NONE = '—'

/**
 * 제목. **없는 세션이 실제로 존재한다** (실측: 16개 중 5개) —
 * 그 경우에도 줄은 렌더되어야 하므로 자리를 채운다.
 */
export function titleOf(session: Session): string {
  return session.title?.trim() || '(제목 없음)'
}

/** 브랜치. worktree 세션 등에서 실제로 비어 온다. */
export function branchOf(session: Session): string {
  return session.branch?.trim() || NONE
}

/**
 * 컨텍스트 — **절대값 · 상한 · % 셋을 모두** 낸다 (docs/00-개요.md 결정사항 3).
 *
 * 기록의 모델명이 `claude-opus-5` 로만 남아 `[1m]` 변형이 구분되지 않아
 * **분모가 틀릴 수 있다.** 그래도 절대값으로는 판단이 되므로 셋을 다 낸다 —
 * % 만 내면 분모가 틀렸을 때 판단 근거가 통째로 사라진다.
 */
export function contextOf(session: Session): string | null {
  const { contextTokens, contextLimit, contextRatio } = session
  if (contextTokens == null) {
    return null
  }
  const absolute = compactTokens(contextTokens)
  if (contextLimit == null) {
    // 상한을 모르면 % 도 못 낸다. 절대값만으로도 판단은 된다.
    return absolute
  }
  const percent = contextRatio != null
    ? Math.round(contextRatio * 100)
    : Math.round((contextTokens / contextLimit) * 100)
  return `${absolute} / ${compactTokens(contextLimit)} · ${percent}%`
}

/**
 * 컨텍스트가 얼마나 급한가 (#23).
 *
 * [docs/00-개요.md](../../docs/00-개요.md) 목표 3 이 *"컨텍스트 사용량**과 경고** — 새 세션 시점 판단"*
 * 인데 경고가 구현돼 있지 않았다 — 90% 든 10% 든 같은 회색이라 **새 세션을 열 시점이
 * 화면에서 드러나지 않았다.**
 *
 * 임계값 근거: 이 도구를 쓰는 방식이 *"프로젝트마다 일정 범위가 되면 새 세션을 여는"* 것이므로
 * (00-개요 "문제"), 여유가 있을 때 미리 알아야 한다.
 * - `warn` 70%: 지금 하던 작업을 마치고 새 세션을 열 준비를 할 시점
 * - `danger` 85%: 지금 열어야 하는 시점
 *
 * ⚠️ **분모가 틀릴 수 있다.** 기록의 모델명이 `claude-opus-5` 로만 남아 `[1m]` 변형이
 * 구분되지 않는다(00-개요 결정사항 3). 그래서 경고는 **보조**이고 절대값 표시를 없애지 않는다.
 */
export type ContextLevel = 'normal' | 'warn' | 'danger'

export function contextLevelOf(session: Session): ContextLevel {
  const { contextTokens, contextLimit, contextRatio } = session
  const ratio = contextRatio != null
    ? contextRatio
    : contextTokens != null && contextLimit != null && contextLimit > 0
      ? contextTokens / contextLimit
      : null
  if (ratio == null) {
    return 'normal'
  }
  if (ratio >= 0.85) {
    return 'danger'
  }
  if (ratio >= 0.7) {
    return 'warn'
  }
  return 'normal'
}

/** `544102` → `544K`. 자릿수가 흔들리면 갱신 때 레이아웃이 튄다. */
function compactTokens(tokens: number): string {
  if (tokens >= 1_000_000) {
    return `${(tokens / 1_000_000).toFixed(1)}M`
  }
  if (tokens >= 1_000) {
    return `${Math.round(tokens / 1_000)}K`
  }
  return String(tokens)
}

/**
 * 경과 시간. `now` 를 **주입받는다** — `Date.now()` 를 안에서 부르면
 * 테스트가 시계에 의존해 재현되지 않는다.
 */
export function elapsedOf(iso: string | null, now: number): string {
  if (!iso) {
    return NONE
  }
  const then = Date.parse(iso)
  if (Number.isNaN(then)) {
    return NONE
  }
  const seconds = Math.max(0, Math.floor((now - then) / 1000))
  if (seconds < 60) {
    return '지금'
  }
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) {
    return `${minutes}분`
  }
  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    return `${hours}시간`
  }
  return `${Math.floor(hours / 24)}일`
}

/**
 * 마지막 프롬프트 한 줄.
 *
 * 줄바꿈을 공백으로 접는다 — 여러 줄이 그대로 오면 한 줄 레이아웃이 무너진다.
 * 길이도 자른다: **실측 최대 4,395자**가 존재해 DOM 에 통째로 넣을 이유가 없다
 * (CSS 말줄임은 보이는 것만 줄이지 노드 크기는 줄이지 않는다).
 *
 * ## 꺾쇠는 벗기고 내용은 남긴다 (#23)
 *
 * 시스템 태그가 프롬프트 줄을 통째로 먹는 세션이 있었다:
 * ```
 * <task-notification> <task-id>bm88crchn</task-id> <summary>Monitor event: …
 * ```
 * 이 줄은 *"내가 뭘 시켰더라"* 를 복구하는 유일한 단서인데(docs/03-프론트.md)
 * 태그 이름만 보여 아무 정보도 주지 못했다.
 *
 * **#11·#15 에서 "거르면 뭐가 걸러졌는지 화면에서 사라진다"는 이유로 미뤘던 건인데,
 * 꺾쇠만 벗기면 그 문제가 없다** — 태그 이름만 빠지고 내용은 전부 남는다:
 * ```
 * <task-notification><summary>모니터 이벤트</summary>  →  모니터 이벤트
 * <table><td>이름</td><td>값</td></table> 이 표 봐줘   →  이름 값 이 표 봐줘
 * 그냥 <strong>강조</strong>가 든 문장                  →  그냥 강조 가 든 문장
 * ```
 * 사용자가 붙여넣은 HTML(실측 1위가 `<td>` 19건)도 **내용이 살아남는다.**
 * 태그 이름 화이트리스트를 두지 않으므로 Claude Code 가 새 태그를 추가해도 어긋나지 않는다.
 *
 * ⚠️ **`<[^>]*>` 로 뭉뚱그리면 안 된다.** 태그가 아닌 부등호까지 먹어 내용이 사라진다:
 * ```
 * "a < b 이고 c > d"  →  "a d"          ← "< b 이고 c >" 를 태그로 오인
 * "<!-- 이 파일은 … -->"  →  ""           ← 주석이 통째로 사라진다 (실측 2건)
 * ```
 * 그래서 **여는/닫는 태그 형태**(`<이름 …>`, `</이름>`)만 지운다.
 * 태그 이름은 글자로 시작해야 하므로 `< b` 같은 부등호는 걸리지 않는다.
 */
export function promptOf(session: Session, limit = 200): string | null {
  const raw = session.lastPrompt
    ?.replace(/<\/?[a-zA-Z][a-zA-Z0-9_-]*(?:\s[^<>]*)?\/?>/gu, ' ')
    .replace(/\s+/gu, ' ')
    .trim()
  if (!raw) {
    return null
  }
  return raw.length > limit ? `${raw.slice(0, limit)}…` : raw
}

/** 헤더의 시각. 초까지 낸다 — 갱신이 살아있는지가 보여야 한다. */
export function clockOf(iso: string): string {
  const at = new Date(iso)
  if (Number.isNaN(at.getTime())) {
    return NONE
  }
  return at.toLocaleTimeString('ko-KR', { hour12: false })
}
