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
      label,
    )
    expect(result[0].projects.map((p) => p.name)).toEqual(['오래됨', '중간', '최근'])
  })

  // ⭐ #18 의 회귀 테스트 — 이것이 이 파일의 핵심이다.
  //
  // 작업 중 세션은 lastActivityAt 이 5초마다 갱신된다. 그 값으로 정렬하면
  // "누가 더 오래됐는가"가 매번 뒤집혀 줄이 위아래로 왕복한다.
  //
  // 소스가 아니라 **동작(실제로 나온 순서)** 을 관측한다 — 정렬 함수 이름을
  // 검사하면 배선이 바뀔 때 깨지고, 정작 순서가 틀려도 못 잡는다.
  it('작업 중 그룹은 활동 시각이 갱신돼도 순서가 안 바뀐다', () => {
    // 같은 세 프로젝트인데 활동 시각만 서로 다르게 갱신된 두 스냅샷.
    // 실사용에서 5초 간격으로 실제로 이렇게 온다.
    const first = groupByState(
      snapshot([
        project('bubble-house', 'WORKING', '2026-09-03T09:59:58Z'),
        project('claude-board', 'WORKING', '2026-09-03T09:59:59Z'),
        project('apple-pie', 'WORKING', '2026-09-03T09:59:57Z'),
      ]),
      label,
    )
    // 5초 뒤: 각자 활동해서 시각 순위가 완전히 뒤집혔다
    const second = groupByState(
      snapshot([
        project('bubble-house', 'WORKING', '2026-09-03T10:00:04Z'),
        project('claude-board', 'WORKING', '2026-09-03T10:00:02Z'),
        project('apple-pie', 'WORKING', '2026-09-03T10:00:03Z'),
      ]),
      label,
    )

    const order = (r: ReturnType<typeof groupByState>) => r[0].projects.map((p) => p.name)
    // 시각이 어떻게 갱신되든 순서는 그대로여야 한다
    expect(order(first)).toEqual(['apple-pie', 'bubble-house', 'claude-board'])
    expect(order(second)).toEqual(order(first))
  })

  // 유휴도 같은 이유로 이름순이다 — 활동이 생기면 WORKING 으로 빠지므로
  // 여기서도 활동 시각은 흔들리는 키다.
  it('유휴 그룹도 활동 시각이 갱신돼도 순서가 안 바뀐다', () => {
    const order = (at: [string, string]) =>
      groupByState(
        snapshot([
          project('zebra', 'IDLE', at[0]),
          project('alpha', 'IDLE', at[1]),
        ]),
        label,
      )[0].projects.map((p) => p.name)

    expect(order(['2026-09-03T08:00:00Z', '2026-09-03T09:00:00Z'])).toEqual(['alpha', 'zebra'])
    // 시각 순위를 뒤집어도 순서는 유지된다
    expect(order(['2026-09-03T09:00:00Z', '2026-09-03T08:00:00Z'])).toEqual(['alpha', 'zebra'])
  })

  // 반대 방향도 지킨다 — 방치된 것을 위로 올리는 가치를 잃으면 안 된다.
  // 답변 대기·멈춤 의심에서는 활동 시각 정렬이 그대로 유지되어야 한다.
  it('멈춤 의심은 여전히 오래된 순', () => {
    const result = groupByState(
      snapshot([
        project('aaa-최근', 'STALLED', '2026-09-03T09:59:00Z'),
        project('zzz-오래됨', 'STALLED', '2026-08-07T10:00:00Z'),
      ]),
      label,
    )
    // 이름순이면 'aaa-최근' 이 먼저 오므로, 이 기대는 시각 정렬만 통과시킨다
    expect(result[0].projects.map((p) => p.name)).toEqual(['zzz-오래됨', 'aaa-최근'])
  })

  // 0 으로 취급하면 "가장 오래된 것"이 되어 정보 없는 세션이 맨 위를 차지한다.
  it('활동 시각이 없는 것은 맨 뒤로', () => {
    const result = groupByState(
      snapshot([
        project('시각없음', 'WAITING', null),
        project('오래됨', 'WAITING', '2026-08-07T10:00:00Z'),
      ]),
      label,
    )
    expect(result[0].projects.map((p) => p.name)).toEqual(['오래됨', '시각없음'])
  })

  // 비어 있는 머리글이 남으면 실제 대기 건을 아래로 밀어낸다.
  it('빈 그룹은 내지 않는다', () => {
    const result = groupByState(snapshot([project('a', 'WAITING', null)]), label)
    expect(result).toHaveLength(1)
    expect(result[0].label).toBe('답변 대기')
  })

  // docs/05-검증.md 5번 — 세션 0개일 때 깨지지 않아야 한다.
  it('프로젝트가 0개여도 던지지 않는다', () => {
    expect(groupByState(snapshot([]), label)).toEqual([])
  })

  it('첫 스냅샷 전(null)에도 던지지 않는다', () => {
    expect(groupByState(null, label)).toEqual([])
  })

  // 정렬이 원본 배열을 갈아엎으면, 다음 계산이 이미 섞인 배열을 다시 정렬하게 된다.
  it('입력 스냅샷의 순서를 건드리지 않는다', () => {
    const projects = [
      project('최근', 'WAITING', '2026-09-03T09:59:00Z'),
      project('오래됨', 'WAITING', '2026-08-07T10:00:00Z'),
    ]
    groupByState(snapshot(projects), label)
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
