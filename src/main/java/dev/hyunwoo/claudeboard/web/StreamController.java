package dev.hyunwoo.claudeboard.web;

import dev.hyunwoo.claudeboard.domain.BoardSnapshot;
import dev.hyunwoo.claudeboard.service.BoardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code GET /api/stream} — 수집 주기마다 스냅샷을 push 한다.
 *
 * <p><b>emitter 정리가 이 클래스의 핵심이다.</b> 누수는 예외를 내지 않고 조용히 쌓인다 —
 * 브라우저 탭을 닫거나 새로고침할 때마다 구독이 하나씩 남으면, 수집 스레드가 매 주기
 * 죽은 연결에 쓰기를 시도하게 된다. 끝나는 경로가 셋이므로 <b>셋 모두</b>에 정리를 건다:
 * {@code onCompletion}(정상 종료) · {@code onTimeout}(유휴 만료) · {@code onError}(연결 끊김).
 *
 * <p><b>끊김은 능동적으로 감지해야 한다.</b> 서블릿 컨테이너는 클라이언트가 사라진 것을
 * <b>다음 쓰기를 시도할 때</b> 알게 된다 — 정리를 스냅샷 전송의 실패에만 맡기면, 수집 주기가
 * 길거나 조용한 동안 끊긴 연결이 그대로 남는다. <b>실측</b>: 하트비트가 없을 때 붙였다 끊기를
 * 5회 반복하자 구독 5개가 전부 남았다(누수). 그래서 {@code sse-heartbeat-ms} 마다 주석 프레임을
 * 보내 죽은 연결을 걷어낸다. SSE 주석({@code :})은 이벤트가 아니므로 화면에 영향이 없다.
 *
 * <p><b>중복 렌더 방지</b>: 재연결한 클라이언트에게 과거 이벤트를 되돌려주지 않는다.
 * 연결 즉시 <b>현재 캐시 1건</b>만 보내고 이후는 갱신분만 보낸다. 스냅샷은 누적이 아니라
 * 매번 전체 상태이므로, 같은 것을 두 번 받아도 화면은 같은 상태로 수렴한다.
 * 클라이언트가 {@code Last-Event-ID} 로 재생을 요구하지 않게 이벤트 id 도 쓰지 않는다.
 */
@RestController
public class StreamController {

    private static final Logger log = LoggerFactory.getLogger(StreamController.class);

    /**
     * SSE 연결 유지 시간.
     *
     * <p>{@link Long#MAX_VALUE}(무제한)로 두지 않는 이유: 클라이언트가 정상 종료를 알리지
     * 못하고 사라지는 경우(네트워크 단절, 절전) 서버가 그것을 영원히 붙들고 있게 된다.
     * 만료되면 브라우저 {@code EventSource} 가 알아서 재연결하므로 사용자에겐 티가 안 난다.
     */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    /**
     * 하트비트 주기.
     *
     * <p>수집 주기와 <b>독립</b>이어야 한다 — 수집이 느리거나 실패해도 끊김은 감지돼야 한다.
     * 프록시·브라우저의 유휴 타임아웃(보통 60초 이상)보다 짧게 잡는다.
     */
    private static final long DEFAULT_HEARTBEAT_MS = 15_000L;

    private final BoardService boardService;
    private final long heartbeatMs;

    /**
     * 하트비트 전용 스레드. 연결 수와 무관하게 하나면 된다 —
     * 하는 일이 주석 한 줄 쓰기뿐이다.
     */
    private final ScheduledExecutorService heartbeats =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);   // 종료를 막지 않는다
                return t;
            });

    public StreamController(
            BoardService boardService,
            @Value("${claude-board.sse-heartbeat-ms:" + DEFAULT_HEARTBEAT_MS + "}") long heartbeatMs) {
        this.boardService = boardService;
        this.heartbeatMs = heartbeatMs;
    }

    /** 컨텍스트가 내려갈 때 스레드를 접는다. 남기면 테스트 사이에 쌓인다. */
    @PreDestroy
    void shutdown() {
        heartbeats.shutdownNow();
    }

    @GetMapping(path = "/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);

        // 정리가 두 번 돌지 않게 한다 — onError 뒤에 onCompletion 이 이어서 오는 경우가 있고,
        // 그때 complete() 를 다시 부르면 이미 끝난 응답에 손대는 셈이 된다.
        AtomicBoolean closed = new AtomicBoolean(false);

        // 구독 해제 함수는 등록 후에야 생기고, 정리는 그 전에 불릴 수 있다.
        // 둘 다 한 칸짜리 상자에 담아 순서 의존을 없앤다.
        List<Runnable> unsubscribes = new CopyOnWriteArrayList<>();
        List<ScheduledFuture<?>> beats = new CopyOnWriteArrayList<>();

        // 끝나는 경로 모두에서 구독을 뗀다. 하나라도 빠지면 그 경로로 끝난 연결이 누수된다.
        // compareAndSet 으로 멱등을 보장한다 — onError 뒤에 onCompletion 이 이어서 오거나,
        // 하트비트와 스냅샷 전송이 동시에 실패할 수 있다.
        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                unsubscribes.forEach(Runnable::run);
                beats.forEach(b -> b.cancel(false));
            }
        };

        unsubscribes.add(boardService.subscribe(
                snapshot -> send(emitter, snapshot, closed, cleanup)));
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(e -> cleanup.run());

        // 끊김 감지용 하트비트. 쓰기가 실패하면 send() 가 정리까지 이어준다.
        ScheduledFuture<?> beat = heartbeats.scheduleWithFixedDelay(
                () -> heartbeat(emitter, closed, cleanup),
                heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
        // 정리될 때 하트비트도 같이 멈춘다 — 안 그러면 죽은 연결에 계속 쓰기를 시도한다.
        beats.add(beat);

        // 연결 직후 현재 상태 1건. 이게 없으면 다음 수집 주기까지 화면이 비어 있다.
        send(emitter, boardService.snapshot(), closed, cleanup);
        return emitter;
    }

    /**
     * 한 건 보낸다.
     *
     * <p>쓰기 실패는 <b>연결이 끊겼다는 뜻</b>이다(브라우저 탭 닫힘 등). 정상적인 일이므로
     * 에러로 시끄럽게 남기지 않되, 반드시 {@code completeWithError} 로 넘겨
     * {@code onError} → 정리까지 이어지게 한다. 여기서 삼키면 구독이 그대로 남는다.
     */
    /**
     * 살아있는지 찔러본다.
     *
     * <p>주석 프레임({@code :})을 쓴다 — 이벤트가 아니므로 클라이언트가 렌더하지 않는다.
     * 실패하면 연결이 끊긴 것이고, {@code completeWithError} 가 정리로 이어진다.
     */
    private void heartbeat(SseEmitter emitter, AtomicBoolean closed, Runnable cleanup) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment("ping"));
        } catch (IOException | IllegalStateException e) {
            log.debug("하트비트 실패 — 연결이 끊긴 것으로 보고 정리한다", e);
            abort(emitter, cleanup, e);
        }
    }

    /**
     * 끊긴 연결을 걷어낸다.
     *
     * <p><b>{@code completeWithError} 에 정리를 맡기지 않는다.</b> 그것은
     * {@code onError} 콜백을 <b>부르지 못하는 경우가 있다</b> — 이미 실패한 응답에 대해
     * 요청 스레드 밖에서 부르면 그렇다. <b>실측</b>: 하트비트가 끊김을 정확히 감지하고
     * {@code completeWithError} 를 불렀는데도 구독 5개가 남아, 같은 연결에 대해 실패 로그가
     * 무한히 반복됐다. 그래서 <b>감지한 쪽에서 직접</b> 정리하고, {@code completeWithError} 는
     * 컨테이너에 알리는 용도로만 부른다(그쪽이 콜백을 부르면 {@code cleanup} 이
     * 멱등이므로 두 번 돌지 않는다).
     */
    private void abort(SseEmitter emitter, Runnable cleanup, Exception cause) {
        cleanup.run();
        try {
            emitter.completeWithError(cause);
        } catch (RuntimeException ignored) {
            // 이미 끝난 응답이면 컨테이너가 거부한다. 정리는 위에서 끝났으므로 문제가 없다.
        }
    }

    private void send(SseEmitter emitter, BoardSnapshot snapshot,
                      AtomicBoolean closed, Runnable cleanup) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("snapshot").data(snapshot));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 전송 실패 — 연결이 끊긴 것으로 보고 정리한다", e);
            abort(emitter, cleanup, e);
        }
    }
}
