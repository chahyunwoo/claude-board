/**
 * 백엔드 `domain/` 레코드와 1:1 대응한다. docs/02-백엔드.md 의
 * `GET /api/sessions` 스키마가 출처다.
 *
 * **null 을 성실하게 표기한다.** 역순 리더는 상한(512KB)에 걸리면 부분 결과를 반환하므로
 * `title`·`lastPrompt`·`branch`·`contextTokens` 가 실제로 비어서 온다
 * (실측: 세션 16개 중 제목 없는 것 5개, 브랜치 없는 것 1개). 낙관적으로 `string` 이라
 * 적으면 렌더가 `undefined` 를 그대로 뿌린다.
 */

/** 상태 5종. docs/01-데이터.md "최종 상태 5종". */
export type SessionState = 'WAITING' | 'STALLED' | 'WORKING' | 'IDLE' | 'ENDED'

export interface Session {
  sessionId: string
  pid: number | null
  state: SessionState
  title: string | null
  lastPrompt: string | null
  branch: string | null
  permissionMode: string | null
  model: string | null
  /** 절대값. 상한(분모)이 틀릴 수 있어 이것이 1차 판단 근거다. */
  contextTokens: number | null
  contextLimit: number | null
  contextRatio: number | null
  lastActivityAt: string | null
  startedAt: string | null
  /** 그 프로젝트에서 몇 번째 세션인가. */
  ordinal: number
}

export interface Project {
  cwd: string
  name: string
  current: Session
  others: Session[]
  /** 기록 파일 총 개수 (종료된 것 포함). */
  sessionCount: number
}

export interface BoardSnapshot {
  generatedAt: string
  elapsedMs: number
  projects: Project[]
  counts: Record<string, number>
  /**
   * 반드시 화면에 낸다 — 파싱이 조용히 실패하면 "세션이 없다"와
   * "읽지 못했다"가 구별되지 않는다. docs/02-백엔드.md.
   */
  errors: string[]
}
