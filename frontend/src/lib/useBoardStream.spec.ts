import { effectScope } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useBoardStream } from './useBoardStream'
import type { BoardSnapshot } from './types'

/**
 * SSE 구독 검증.
 *
 * **여기서 잡으려는 것**: `onmessage` 로 짜면 아무것도 오지 않는다 —
 * 연결은 붙고 에러도 안 나서 원인을 찾기 어렵다. 그래서 이 가짜 `EventSource` 는
 * **`onmessage` 를 실제 브라우저처럼** 다룬다: 이름 붙은 이벤트(`snapshot`)는
 * `onmessage` 로 가지 않는다. 구현이 `onmessage` 를 쓰면 테스트가 빨개진다.
 */
class FakeEventSource {
  static last: FakeEventSource | null = null

  readonly url: string
  closed = false
  private listeners = new Map<string, Set<(event: Event) => void>>()
  /** 이름 없는 이벤트만 여기로 온다 — 실제 EventSource 와 같다. */
  onmessage: ((event: MessageEvent<string>) => void) | null = null

  constructor(url: string) {
    this.url = url
    FakeEventSource.last = this
  }

  addEventListener(type: string, handler: (event: Event) => void) {
    if (!this.listeners.has(type)) {
      this.listeners.set(type, new Set())
    }
    this.listeners.get(type)!.add(handler)
  }

  removeEventListener(type: string, handler: (event: Event) => void) {
    this.listeners.get(type)?.delete(handler)
  }

  close() {
    this.closed = true
  }

  /** 서버가 `event: <name>` 으로 보낸 것을 흉내낸다. */
  emit(name: string, data?: string) {
    const event = data === undefined
      ? new Event(name)
      : Object.assign(new Event(name), { data })
    this.listeners.get(name)?.forEach((handler) => handler(event))
    // 이름 없는 이벤트만 onmessage 로 간다. 'snapshot' 은 여기 해당하지 않는다.
    if (name === 'message') {
      this.onmessage?.(event as MessageEvent<string>)
    }
  }

  /** 15초마다 오는 주석 프레임(`:`). 이벤트가 아니므로 아무 데도 가지 않는다. */
  heartbeat() {
    // 의도적으로 아무것도 하지 않는다 — 실제 브라우저도 주석은 이벤트로 만들지 않는다.
  }
}

function makeSnapshot(projectCount: number, generatedAt: string): BoardSnapshot {
  return {
    generatedAt,
    elapsedMs: 200,
    projects: Array.from({ length: projectCount }, (_, i) => ({
      cwd: `/p/${i}`,
      name: `p${i}`,
      current: {
        sessionId: `s${i}`,
        pid: 1,
        state: 'WAITING' as const,
        title: null,
        lastPrompt: null,
        branch: null,
        permissionMode: null,
        model: null,
        contextTokens: null,
        contextLimit: null,
        contextRatio: null,
        lastActivityAt: null,
        startedAt: null,
        ordinal: 0,
      },
      others: [],
      sessionCount: 1,
    })),
    counts: {},
    errors: [],
  }
}

/** 컴포넌트 없이 composable 을 돌린다 — onScopeDispose 를 검증하려면 scope 가 필요하다. */
function run<T>(fn: () => T): { result: T; stop: () => void } {
  const scope = effectScope()
  const result = scope.run(fn)!
  return { result, stop: () => scope.stop() }
}

beforeEach(() => {
  FakeEventSource.last = null
  vi.stubGlobal('EventSource', FakeEventSource)
})

describe('useBoardStream', () => {
  it('/api/stream 에 붙는다 — 절대 URL 이 아니다 (같은 오리진, CORS 없음)', () => {
    const { stop } = run(() => useBoardStream())
    expect(FakeEventSource.last!.url).toBe('/api/stream')
    // 오리진을 박으면 프록시를 우회해 CORS 가 필요해진다 — 그 순간 경계가 깨진다.
    expect(FakeEventSource.last!.url).not.toMatch(/^https?:/)
    stop()
  })

  /*
   * ⭐ 이 테스트가 이 파일의 존재 이유다.
   *
   * 서버는 `event: snapshot` 으로 보낸다. `onmessage` 는 이름 없는 이벤트만 받으므로
   * 구현이 `onmessage` 를 쓰면 **아무것도 오지 않는다**. 이 가짜는 그 동작을 그대로
   * 흉내내므로, `addEventListener('snapshot', …)` 이 아니면 여기서 갈린다.
   */
  it("event: snapshot 을 받는다 (onmessage 로 짜면 여기서 빨개진다)", () => {
    const { result, stop } = run(() => useBoardStream())
    expect(result.snapshot.value).toBeNull()

    FakeEventSource.last!.emit('snapshot', JSON.stringify(makeSnapshot(3, '2026-09-03T10:00:00Z')))

    expect(result.snapshot.value).not.toBeNull()
    expect(result.snapshot.value!.projects).toHaveLength(3)
    stop()
  })

  it('이름 없는 message 이벤트는 무시한다 — 서버가 그렇게 보내지 않는다', () => {
    const { result, stop } = run(() => useBoardStream())
    FakeEventSource.last!.emit('message', JSON.stringify(makeSnapshot(9, '2026-09-03T10:00:00Z')))
    expect(result.snapshot.value).toBeNull()
    stop()
  })

  /*
   * ⭐ 중복 렌더 — 클라이언트가 dedup 을 하지 않는 것이 **의도**다.
   * 서버가 과거 이벤트를 재생하지 않고 이벤트 id 도 쓰지 않으므로,
   * 재연결해도 같은 상태로 수렴한다. 통째로 교체하는지 본다.
   */
  it('스냅샷은 통째로 교체된다 — 병합하면 사라진 세션이 화면에 남는다', () => {
    const { result, stop } = run(() => useBoardStream())
    const es = FakeEventSource.last!

    es.emit('snapshot', JSON.stringify(makeSnapshot(5, '2026-09-03T10:00:00Z')))
    expect(result.snapshot.value!.projects).toHaveLength(5)

    // 세션이 줄어든 스냅샷이 왔다. 병합하면 5개가 남는다.
    es.emit('snapshot', JSON.stringify(makeSnapshot(2, '2026-09-03T10:00:05Z')))
    expect(result.snapshot.value!.projects).toHaveLength(2)
    expect(result.snapshot.value!.generatedAt).toBe('2026-09-03T10:00:05Z')

    stop()
  })

  it('같은 스냅샷을 두 번 받아도 같은 상태로 수렴한다 (재연결 직후의 1건)', () => {
    const { result, stop } = run(() => useBoardStream())
    const es = FakeEventSource.last!
    const payload = JSON.stringify(makeSnapshot(4, '2026-09-03T10:00:00Z'))

    es.emit('snapshot', payload)
    const first = result.snapshot.value!
    es.emit('snapshot', payload)  // 재연결 직후 서버가 캐시 1건을 다시 보낸다

    expect(result.snapshot.value!.projects).toHaveLength(4)
    expect(result.snapshot.value!.generatedAt).toBe(first.generatedAt)
    stop()
  })

  it('주석 프레임(하트비트)은 아무 일도 일으키지 않는다', () => {
    const { result, stop } = run(() => useBoardStream())
    FakeEventSource.last!.emit('snapshot', JSON.stringify(makeSnapshot(3, '2026-09-03T10:00:00Z')))
    FakeEventSource.last!.heartbeat()
    expect(result.snapshot.value!.projects).toHaveLength(3)
    stop()
  })

  it('open 으로 연결 표시가 켜지고 error 로 꺼진다', () => {
    const { result, stop } = run(() => useBoardStream())
    expect(result.connected.value).toBe(false)

    FakeEventSource.last!.emit('open')
    expect(result.connected.value).toBe(true)

    FakeEventSource.last!.emit('error')
    expect(result.connected.value).toBe(false)
    stop()
  })

  // 여기서 close() 하면 브라우저의 자동 재연결까지 막힌다.
  it('error 에서 연결을 닫지 않는다 — 재연결은 브라우저가 한다', () => {
    const { stop } = run(() => useBoardStream())
    FakeEventSource.last!.emit('error')
    expect(FakeEventSource.last!.closed).toBe(false)
    stop()
  })

  it('끊겼다 붙어도 마지막 스냅샷을 유지한다 — 화면이 비지 않는다', () => {
    const { result, stop } = run(() => useBoardStream())
    const es = FakeEventSource.last!

    es.emit('snapshot', JSON.stringify(makeSnapshot(3, '2026-09-03T10:00:00Z')))
    es.emit('error')
    expect(result.snapshot.value!.projects).toHaveLength(3)

    es.emit('open')
    es.emit('snapshot', JSON.stringify(makeSnapshot(3, '2026-09-03T10:00:10Z')))
    expect(result.snapshot.value!.projects).toHaveLength(3)
    expect(result.connected.value).toBe(true)
    stop()
  })

  it('깨진 JSON 이 와도 던지지 않고 화면에 알린다', () => {
    const { result, stop } = run(() => useBoardStream())
    const es = FakeEventSource.last!

    es.emit('snapshot', JSON.stringify(makeSnapshot(3, '2026-09-03T10:00:00Z')))
    es.emit('snapshot', '{ 깨진 JSON')

    expect(result.clientError.value).toContain('스냅샷')
    // 마지막으로 성공한 것을 유지한다 — 빈 화면보다 낫다.
    expect(result.snapshot.value!.projects).toHaveLength(3)
    stop()
  })

  it('다음 성공에서 에러 표시가 걷힌다', () => {
    const { result, stop } = run(() => useBoardStream())
    const es = FakeEventSource.last!
    es.emit('snapshot', '{ 깨진 JSON')
    expect(result.clientError.value).not.toBeNull()

    es.emit('snapshot', JSON.stringify(makeSnapshot(1, '2026-09-03T10:00:00Z')))
    expect(result.clientError.value).toBeNull()
    stop()
  })

  // 안 닫으면 서버 emitter 가 다음 쓰기 시도까지 남는다.
  it('스코프가 끝나면 연결을 닫는다', () => {
    const { result, stop } = run(() => useBoardStream())
    const es = FakeEventSource.last!
    es.emit('open')
    expect(es.closed).toBe(false)

    stop()

    expect(es.closed).toBe(true)
    expect(result.connected.value).toBe(false)
  })
})
