import type { Page } from '@playwright/test'
import type { BoardSnapshot } from '../src/lib/types'

/**
 * SSE 스트림을 브라우저 안에서 흉내낸다.
 *
 * **왜 `page.route` 가 아닌가**: Playwright 의 `route.fulfill` 은 응답을 **한 번에**
 * 준다 — 열어둔 채 몇 초 뒤 다음 이벤트를 밀어넣을 수 없다. 재연결·중복 렌더를
 * 검증하려면 그 제어가 필요하다.
 *
 * 그래서 `EventSource` 자체를 페이지에 주입한 가짜로 바꾼다. **`onmessage` 를
 * 실제 브라우저와 똑같이** 다룬다 — 이름 붙은 이벤트(`snapshot`)는 `onmessage` 로
 * 가지 않으므로, 앱이 `onmessage` 로 짜여 있으면 화면이 영원히 비어 e2e 가 빨개진다.
 *
 * `addInitScript` 라서 **페이지 스크립트보다 먼저** 돌고, 앱이 `new EventSource` 를
 * 부르는 시점엔 이미 가짜가 자리잡고 있다.
 */
export async function installFakeStream(page: Page) {
  await page.addInitScript(() => {
    type Handler = (event: Event) => void

    class FakeEventSource extends EventTarget {
      static instances: FakeEventSource[] = []
      static readonly CONNECTING = 0
      static readonly OPEN = 1
      static readonly CLOSED = 2

      readonly url: string
      readyState = FakeEventSource.CONNECTING
      onmessage: Handler | null = null
      onopen: Handler | null = null
      onerror: Handler | null = null

      constructor(url: string) {
        super()
        this.url = String(url)
        FakeEventSource.instances.push(this)
        // 실제 EventSource 처럼 다음 틱에 열린다.
        setTimeout(() => this.fireOpen(), 0)
      }

      fireOpen() {
        if (this.readyState === FakeEventSource.CLOSED) return
        this.readyState = FakeEventSource.OPEN
        const event = new Event('open')
        this.dispatchEvent(event)
        this.onopen?.(event)
      }

      close() {
        this.readyState = FakeEventSource.CLOSED
      }
    }

    // 테스트가 조종할 손잡이. 페이지 컨텍스트에 노출한다.
    const control = {
      /** 이름 붙은 이벤트를 보낸다. 기본은 서버와 같은 `snapshot`. */
      emit(data: unknown, name = 'snapshot') {
        const es = FakeEventSource.instances.at(-1)
        if (!es || es.readyState === FakeEventSource.CLOSED) return
        const event = new MessageEvent(name, { data: JSON.stringify(data) })
        es.dispatchEvent(event)
        // 이름 없는 이벤트만 onmessage 로 간다 — 실제 브라우저와 같다.
        if (name === 'message') es.onmessage?.(event)
      },
      /** 연결이 끊긴 것처럼 만든다. 브라우저의 자동 재연결은 흉내내지 않는다. */
      drop() {
        const es = FakeEventSource.instances.at(-1)
        if (!es) return
        es.readyState = FakeEventSource.CONNECTING
        const event = new Event('error')
        es.dispatchEvent(event)
        es.onerror?.(event)
      },
      /** 재연결된 것처럼 만든다. */
      reopen() {
        FakeEventSource.instances.at(-1)?.fireOpen()
      },
      /** 만들어진 연결 수. 앱이 중복 연결을 만들지 않는지 본다. */
      connectionCount() {
        return FakeEventSource.instances.length
      },
      /** 15초마다 오는 주석 프레임. 이벤트가 아니므로 아무 일도 일어나지 않아야 한다. */
      heartbeat() {
        // 실제 브라우저도 주석(`:`)은 이벤트로 만들지 않는다. 의도적으로 비어 있다.
      },
    }

    Object.defineProperty(window, 'EventSource', { value: FakeEventSource, writable: true })
    Object.defineProperty(window, '__stream', { value: control, writable: false })
  })
}

/** 스냅샷 한 건을 화면에 밀어넣는다. */
export async function push(page: Page, data: BoardSnapshot) {
  await page.evaluate(payload => {
    ;(window as unknown as { __stream: { emit(d: unknown, n?: string): void } }).__stream.emit(payload)
  }, data as unknown)
}

/** 이름 없는 `message` 로 보낸다 — 서버는 이렇게 보내지 않는다. */
export async function pushAsMessage(page: Page, data: BoardSnapshot) {
  await page.evaluate(payload => {
    ;(window as unknown as { __stream: { emit(d: unknown, n?: string): void } }).__stream.emit(payload, 'message')
  }, data as unknown)
}

export async function drop(page: Page) {
  await page.evaluate(() => (window as unknown as { __stream: { drop(): void } }).__stream.drop())
}

export async function reopen(page: Page) {
  await page.evaluate(() => (window as unknown as { __stream: { reopen(): void } }).__stream.reopen())
}

export async function connectionCount(page: Page): Promise<number> {
  return page.evaluate(() =>
    (window as unknown as { __stream: { connectionCount(): number } }).__stream.connectionCount(),
  )
}
