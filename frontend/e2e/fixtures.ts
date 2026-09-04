import type { BoardSnapshot, Session, SessionState } from '../src/lib/types'

/**
 * 경계값은 **이 기계의 실제 데이터**에서 가져왔다 (2026-09-03 실측):
 * 세션 16개 중 제목 없는 것 5개, 브랜치 없는 것 1개,
 * 가장 긴 브랜치명 `worktree-fix-imax-scan-throttle`, 프롬프트 최대 4,395자.
 */
export function session(overrides: Partial<Session> = {}): Session {
  return {
    sessionId: crypto.randomUUID(),
    pid: 57580,
    state: 'WAITING',
    title: '알림봇 상영관 등급 필터링 검증',
    lastPrompt: '회신 확인해봐',
    branch: 'feature/47-poller-redesign',
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

export function snapshot(overrides: Partial<BoardSnapshot> = {}): BoardSnapshot {
  return {
    generatedAt: '2026-09-03T10:26:39.507Z',
    elapsedMs: 233,
    projects: [],
    counts: {},
    errors: [],
    ...overrides,
  }
}

export function project(name: string, state: SessionState, current: Partial<Session> = {}, others: Session[] = []) {
  return {
    cwd: `/Users/me/projects/${name}`,
    name,
    current: session({ state, ...current }),
    others,
    sessionCount: 16,
  }
}
