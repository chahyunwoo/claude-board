import { STATE_ORDER } from './format'
import type { BoardSnapshot, Project, Session, SessionState } from './types'

/**
 * 프로젝트를 **`current` 의 상태로** 묶는다.
 *
 * 세션이 아니라 프로젝트가 1급 단위다 (docs/01-데이터.md) — 한 프로젝트에 세션이
 * 계속 이어지므로, 화면의 한 줄은 "프로젝트의 현재 세션"이다. 나머지 세션은
 * `others` 로 접혀 있다가 줄을 클릭하면 펼쳐진다.
 */
export interface StateGroup {
  state: SessionState
  label: string
  projects: Project[]
}

/**
 * 방치된 것이 위로 올라오는 정렬 — **오래된 순** (docs/03-프론트.md "정렬").
 *
 * `lastActivityAt` 이 없는 것은 맨 뒤로 보낸다. 0 으로 취급하면 "가장 오래된 것"이 되어
 * 정보가 없는 세션이 화면 맨 위를 차지한다.
 *
 * **답변 대기·멈춤 의심에만 쓴다.** 진행 중인 세션에 쓰면 순서가 계속 뒤집힌다 —
 * 아래 {@link byName} 참고.
 */
function byOldestFirst(a: Project, b: Project): number {
  const at = timeOf(a.current)
  const bt = timeOf(b.current)
  if (at === bt) {
    return a.name.localeCompare(b.name)
  }
  return at - bt
}

/**
 * 이름순 — **활동 중인 그룹에 쓴다.**
 *
 * <b>왜 활동 시각으로 정렬하지 않는가</b>: 작업 중인 세션은 `lastActivityAt` 이
 * 수집 주기(5초)마다 갱신되므로 "누가 더 오래됐는가"가 매번 뒤집힌다.
 * 내용이 하나도 바뀌지 않았는데 줄이 위아래로 왕복해 docs/03-프론트.md 의
 * "상시 표시" 요구(갱신 시 레이아웃이 튀지 않게)를 깬다.
 *
 * **실측 (#18)**: 140분 관찰에서 레이아웃 샘플 264회 중 y 좌표가 안 움직인 것이
 * 27%뿐이었고, 5초 만에 두 줄이 자리를 바꿨다가 그대로 되돌아오는 것이 관측됐다.
 *
 * 이름은 세션이 바뀌어도 변하지 않으므로 순서가 안정적이다.
 * "방치된 것을 찾는" 가치는 답변 대기·멈춤 의심 그룹에서 나오고,
 * 작업 중 세션들 사이의 초 단위 선후는 그 가치를 만들지 않는다.
 */
function byName(a: Project, b: Project): number {
  return a.name.localeCompare(b.name)
}

/**
 * 그룹에 맞는 정렬을 고른다. **판정은 이 한 곳을 통한다** —
 * 호출부가 조건을 풀어 쓰면 상태를 추가할 때 그 호출부만 조용히 빠진다.
 *
 * 진행 중(`WORKING`)과 유휴(`IDLE`)는 이름순, 나머지는 오래된 순이다.
 * `IDLE` 도 이름순인 이유: 2시간 이상 조용한 것들 사이의 선후는 의미가 없고,
 * 활동이 생기면 `WORKING` 으로 빠지므로 여기서도 시각은 흔들리는 키다.
 */
function comparatorFor(state: SessionState): (a: Project, b: Project) => number {
  return state === 'WORKING' || state === 'IDLE' ? byName : byOldestFirst
}

function timeOf(session: Session): number {
  if (!session.lastActivityAt) {
    return Number.POSITIVE_INFINITY
  }
  const parsed = Date.parse(session.lastActivityAt)
  return Number.isNaN(parsed) ? Number.POSITIVE_INFINITY : parsed
}

/**
 * 상태 그룹으로 나눈다. **빈 그룹은 내지 않는다** — 비어 있는 머리글이 남으면
 * "답변 대기 (0)" 이 화면 맨 위를 차지해 실제 대기 건을 밀어낸다.
 *
 * @param showEnded `ENDED` 는 기본 숨김이고 토글로만 보인다 (docs/03-프론트.md).
 */
export function groupByState(
  snapshot: BoardSnapshot | null,
  showEnded: boolean,
  label: (state: SessionState) => string,
): StateGroup[] {
  if (!snapshot) {
    return []
  }
  const groups: StateGroup[] = []
  for (const state of STATE_ORDER) {
    if (state === 'ENDED' && !showEnded) {
      continue
    }
    const projects = snapshot.projects
      .filter((project) => project.current.state === state)
      .sort(comparatorFor(state))
    if (projects.length > 0) {
      groups.push({ state, label: label(state), projects })
    }
  }
  return groups
}

/** 헤더에 내는 요약. 세션 수는 `current` + `others` 를 다 센다. */
export function summarize(snapshot: BoardSnapshot | null): {
  projects: number
  sessions: number
} {
  if (!snapshot) {
    return { projects: 0, sessions: 0 }
  }
  const sessions = snapshot.projects.reduce(
    (total, project) => total + 1 + project.others.length,
    0,
  )
  return { projects: snapshot.projects.length, sessions }
}
