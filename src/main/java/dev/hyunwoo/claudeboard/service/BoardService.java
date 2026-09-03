package dev.hyunwoo.claudeboard.service;

import dev.hyunwoo.claudeboard.domain.BoardSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 백그라운드로 수집해 캐시한다. <b>요청은 이 캐시만 읽는다.</b>
 *
 * <p>왜 캐시인가 — 전체 수집 285ms 중 <b>~200ms 가 {@code claude agents --json}
 * 서브프로세스</b>(Node CLI 부팅)라 이 저장소 밖에 있다. 역순 리더는 이미 전체 파싱 대비
 * 15.9배 빠르므로 더 조여도 소용없다. 수집을 요청 경로에서 빼내야 체감 지연이 사라진다.
 * docs/02-백엔드.md "전체 갱신 200ms — 병목은 이 저장소 밖에 있다".
 *
 * <p>수집 실패는 <b>캐시를 덮지 않는다</b> — 마지막 성공 결과를 계속 내보내는 편이
 * 화면을 비우는 것보다 낫다. 다만 그 사실은 {@code errors} 로 노출한다.
 */
@Service
public class BoardService {

    private static final Logger log = LoggerFactory.getLogger(BoardService.class);

    private final SnapshotSource source;
    private final Clock clock;

    /** 마지막 수집 결과. 요청은 이것만 읽는다. */
    private final AtomicReference<BoardSnapshot> cache = new AtomicReference<>();

    /**
     * 새 스냅샷을 받을 구독자들.
     *
     * <p>{@link CopyOnWriteArrayList} 인 이유: 순회(수집 스레드)와 등록·해제(요청 스레드)가
     * 동시에 일어난다. 순회 중 변경으로 인한 예외를 피하면서 락을 잡지 않는다.
     */
    private final List<Consumer<BoardSnapshot>> listeners = new CopyOnWriteArrayList<>();

    public BoardService(SnapshotSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    /**
     * 캐시를 읽는다.
     *
     * <p>첫 수집 전이면 <b>기다리지 않고</b> 빈 스냅샷을 준다 — 요청 경로에서 수집을 하면
     * 캐시를 둔 의미가 사라진다. {@code errors} 에 그 사실을 적어
     * "세션이 없다"와 구별되게 한다.
     */
    public BoardSnapshot snapshot() {
        BoardSnapshot cached = cache.get();
        return cached != null ? cached : warmingUp();
    }

    /** {@code claude-board.interval} 마다 수집한다. */
    @Scheduled(fixedDelayString = "${claude-board.interval:5s}")
    public void refresh() {
        BoardSnapshot snapshot;
        try {
            snapshot = source.collect(Instant.now(clock));
        } catch (RuntimeException e) {
            // 수집이 통째로 실패해도 스케줄러가 멈추면 안 된다.
            // 캐시는 덮지 않는다 — 마지막 성공 결과가 빈 화면보다 낫다.
            log.warn("수집 실패 — 이전 캐시를 유지한다", e);
            return;
        }
        cache.set(snapshot);
        publish(snapshot);
    }

    /**
     * 구독을 등록하고, 해제하는 함수를 돌려준다.
     *
     * <p>해제 수단을 <b>등록과 같은 자리에서</b> 돌려주는 이유는 해제를 빠뜨리기 어렵게
     * 만들기 위해서다 — emitter 누수는 예외를 내지 않고 조용히 쌓인다.
     */
    public Runnable subscribe(Consumer<BoardSnapshot> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** 현재 구독자 수. 누수 검증이 이 값을 본다. */
    public int listenerCount() {
        return listeners.size();
    }

    /**
     * 구독자에게 돌린다.
     *
     * <p>한 구독자가 던져도 <b>나머지에게는 간다</b> — 끊긴 연결 하나가 전체 통지를
     * 막으면 안 된다. 던진 구독자의 정리는 그쪽 책임이다(등록 시 받은 해제 함수).
     */
    private void publish(BoardSnapshot snapshot) {
        for (Consumer<BoardSnapshot> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException e) {
                log.debug("구독자 통지 실패 — 나머지는 계속한다", e);
            }
        }
    }

    /** 첫 수집이 아직 안 끝났을 때의 응답. */
    private BoardSnapshot warmingUp() {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (var state : dev.hyunwoo.claudeboard.domain.SessionState.values()) {
            counts.put(state.name().toLowerCase(), 0);
        }
        return new BoardSnapshot(Instant.now(clock), 0, List.of(), counts,
                List.of("첫 수집이 아직 끝나지 않았습니다"));
    }
}
