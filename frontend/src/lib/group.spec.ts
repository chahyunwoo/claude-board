import { describe, expect, it } from 'vitest'
import { STATE_LABEL } from './format'
import { groupByState, summarize } from './group'
import type { BoardSnapshot, Project, Session, SessionState } from './types'

const label = (state: SessionState) => STATE_LABEL[state]

function session(state: SessionState, lastActivityAt: string | null): Session {
  return {
    sessionId: `s-${state}-${lastActivityAt}`,
    pid: 1,
    state,
    title: null,
    lastPrompt: null,
    branch: null,
    permissionMode: null,
    model: null,
    contextTokens: null,
    contextLimit: null,
    contextRatio: null,
    lastActivityAt,
    startedAt: null,
    ordinal: 0,
  }
}

function project(name: string, state: SessionState, at: string | null, others: Session[] = []): Project {
  return { cwd: `/p/${name}`, name, current: session(state, at), others, sessionCount: 1 }
}

function snapshot(projects: Project[]): BoardSnapshot {
  return {
    generatedAt: '2026-09-03T10:00:00Z',
    elapsedMs: 233,
    projects,
    counts: {},
    errors: [],
  }
}

describe('groupByState', () => {
  it('답변 대기 → 멈춤 의심 → 작업 중 → 유휴 순서로 낸다', () => {
    const result = groupByState(
      snapshot([
        project('c', 'WORKING', '2026-09-03T09:00:00Z'),
        project('a', 'IDLE', '2026-09-03T09:00:00Z'),
        project('b', 'WAITING', '2026-09-03T09:00:00Z'),
        project('d', 'STALLED', '2026-09-03T09:00:00Z'),
      ]),
      false,
      label,
    )
    expect(result.map((g) => g.state)).toEqual(['WAITING', 'STALLED', 'WORKING', 'IDLE'])
  })

  // 방치된 것이 위로 올라와야 한다 (docs/03-프론트.md "정렬").
  it('그룹 안에서는 오래된 순', () => {
    const result = groupByState(
      snapshot([
        project('최근', 'WAITING', '2026-09-03T09:59:00Z'),
        project('오래됨', 'WAITING', '2026-08-07T10:00:00Z'),
        project('중간', 'WAITING', '2026-09-03T08:00:00Z'),
      ]),
      false,
      label,
    )
    expect(result[0].projects.map((p) => p.name)).toEqual(['오래됨', '중간', '최근'])
  })

  // 0 으로 취급하면 "가장 오래된 것"이 되어 정보 없는 세션이 맨 위를 차지한다.
  it('활동 시각이 없는 것은 맨 뒤로', () => {
    const result = groupByState(
      snapshot([
        project('시각없음', 'WAITING', null),
        project('오래됨', 'WAITING', '2026-08-07T10:00:00Z'),
      ]),
      false,
      label,
    )
    expect(result[0].projects.map((p) => p.name)).toEqual(['오래됨', '시각없음'])
  })

  // 비어 있는 머리글이 남으면 실제 대기 건을 아래로 밀어낸다.
  it('빈 그룹은 내지 않는다', () => {
    const result = groupByState(snapshot([project('a', 'WAITING', null)]), false, label)
    expect(result).toHaveLength(1)
    expect(result[0].label).toBe('답변 대기')
  })

  it('ENDED 는 기본 숨김', () => {
    const result = groupByState(snapshot([project('끝난것', 'ENDED', null)]), false, label)
    expect(result).toHaveLength(0)
  })

  it('ENDED 는 토글하면 보인다', () => {
    const result = groupByState(snapshot([project('끝난것', 'ENDED', null)]), true, label)
    expect(result.map((g) => g.state)).toEqual(['ENDED'])
  })

  // docs/05-검증.md 5번 — 세션 0개일 때 깨지지 않아야 한다.
  it('프로젝트가 0개여도 던지지 않는다', () => {
    expect(groupByState(snapshot([]), false, label)).toEqual([])
  })

  it('첫 스냅샷 전(null)에도 던지지 않는다', () => {
    expect(groupByState(null, false, label)).toEqual([])
  })

  // 정렬이 원본 배열을 갈아엎으면, 다음 계산이 이미 섞인 배열을 다시 정렬하게 된다.
  it('입력 스냅샷의 순서를 건드리지 않는다', () => {
    const projects = [
      project('최근', 'WAITING', '2026-09-03T09:59:00Z'),
      project('오래됨', 'WAITING', '2026-08-07T10:00:00Z'),
    ]
    groupByState(snapshot(projects), false, label)
    expect(projects.map((p) => p.name)).toEqual(['최근', '오래됨'])
  })
})

describe('summarize', () => {
  it('others 까지 세어야 실제 세션 수가 나온다', () => {
    const result = summarize(
      snapshot([
        project('a', 'WAITING', null, [session('WAITING', null), session('IDLE', null)]),
        project('b', 'WORKING', null),
      ]),
    )
    expect(result).toEqual({ projects: 2, sessions: 4 })
  })

  it('0개여도 던지지 않는다', () => {
    expect(summarize(snapshot([]))).toEqual({ projects: 0, sessions: 0 })
  })

  it('첫 스냅샷 전에도 던지지 않는다', () => {
    expect(summarize(null)).toEqual({ projects: 0, sessions: 0 })
  })
})
