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
 * 그룹 안 정렬은 **오래된 순** — 방치된 것이 위로 올라온다 (docs/03-프론트.md "정렬").
 *
 * `lastActivityAt` 이 없는 것은 맨 뒤로 보낸다. 0 으로 취급하면 "가장 오래된 것"이 되어
 * 정보가 없는 세션이 화면 맨 위를 차지한다.
 */
function byOldestFirst(a: Project, b: Project): number {
  const at = timeOf(a.current)
  const bt = timeOf(b.current)
  if (at === bt) {
    return a.name.localeCompare(b.name)
  }
  return at - bt
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
      .sort(byOldestFirst)
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
