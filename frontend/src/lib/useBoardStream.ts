import { onScopeDispose, ref, shallowRef } from 'vue'
import type { BoardSnapshot } from './types'

/**
 * `GET /api/stream` 구독.
 *
 * <h3>⚠️ `onmessage` 가 아니라 `addEventListener('snapshot', …)` 이다</h3>
 *
 * 서버는 `event: snapshot` 이라는 **이름 붙은 이벤트**로 보낸다
 * (`web/StreamController.java` 의 `SseEmitter.event().name("snapshot")`).
 * `onmessage` 는 이름 없는 이벤트(`message`)만 받으므로 **아무것도 오지 않는다** —
 * 연결은 멀쩡히 붙고 에러도 안 나서 원인을 찾기 어렵다. docs/03-프론트.md.
 *
 * <h3>중복 렌더 방지를 여기서 만들지 않는다</h3>
 *
 * 서버가 이미 막았다 (docs/02-백엔드.md "중복 렌더 방지") — 재연결한 클라이언트에게
 * 과거 이벤트를 재생하지 않고, 이벤트 id 도 쓰지 않아 브라우저가 `Last-Event-ID` 로
 * 재생을 요구하지 않는다. 스냅샷은 누적이 아니라 **매번 전체 상태**이므로 같은 것을
 * 두 번 받아도 같은 상태로 수렴한다. **클라이언트는 통째로 교체한다.**
 * 여기에 dedup 을 얹으면 얻는 것 없이 "갱신이 안 되는" 버그만 생긴다.
 *
 * 15초마다 오는 주석 프레임(`:`)은 끊김 감지용이고 이벤트가 아니라 자동으로 무시된다.
 *
 * <h3>재연결</h3>
 *
 * `EventSource` 가 알아서 한다. 직접 재연결을 짜지 않는다 — 브라우저의 백오프와
 * 겹치면 연결이 두 개가 된다.
 */
export function useBoardStream(url = '/api/stream') {
  /**
   * `shallowRef` 인 이유: 스냅샷은 통째로 교체되지 매번 깊은 곳이 바뀌지 않는다.
   * 세션 16개 × 필드 14개를 매 5초 깊은 반응형으로 감싸면 그만큼이 순수 낭비다.
   */
  const snapshot = shallowRef<BoardSnapshot | null>(null)
  /** 연결이 붙어 있는가. 화면 오른쪽 위의 점이 이 값을 본다. */
  const connected = ref(false)
  /** 파싱 실패 등 클라이언트 쪽 문제. 서버의 `errors` 와 구별해서 낸다. */
  const clientError = ref<string | null>(null)

  const source = new EventSource(url)

  source.addEventListener('open', () => {
    connected.value = true
  })

  source.addEventListener('snapshot', event => {
    try {
      // 통째로 교체한다. 병합하지 않는다 — 사라진 세션이 화면에 남는다.
      snapshot.value = JSON.parse((event as MessageEvent<string>).data) as BoardSnapshot
      connected.value = true
      clientError.value = null
    } catch (cause) {
      clientError.value = `스냅샷을 읽지 못했습니다: ${String(cause)}`
    }
  })

  source.addEventListener('error', () => {
    // 브라우저가 자동으로 재연결한다. 여기서 close() 하면 그 재연결까지 막힌다.
    connected.value = false
  })

  /**
   * 컴포넌트가 사라지면 연결도 닫는다.
   *
   * 안 닫으면 서버 쪽 emitter 가 다음 쓰기 시도까지 남는다 — 서버는 하트비트로
   * 이것을 걷어내지만(docs/02-백엔드.md), 그건 서버의 방어선이지 여기서
   * 흘려도 된다는 뜻이 아니다.
   */
  onScopeDispose(() => {
    source.close()
    connected.value = false
  })

  // source 는 내보내지 않는다 — 아무도 안 쓰는데 내보내면
  // "밖에서 조작해도 되는 것"으로 읽힌다. 정리는 onScopeDispose 가 한다.
  return { snapshot, connected, clientError }
}
