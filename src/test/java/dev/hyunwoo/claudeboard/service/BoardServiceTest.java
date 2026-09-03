package dev.hyunwoo.claudeboard.service;

import dev.hyunwoo.claudeboard.domain.BoardSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 캐시와 구독 통지. docs/02-백엔드.md "전체 갱신 200ms — 병목은 이 저장소 밖에 있다".
 *
 * <p>여기서 검증하는 것은 <b>수집이 요청 경로에 없다</b>는 것이다 —
 * 그게 캐시를 둔 유일한 이유이므로, 이게 깨지면 이 구조 전체가 의미를 잃는다.
 */
class BoardServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    /** 호출 횟수를 세는 수집원. 실제 수집(서브프로세스)을 타지 않는다. */
    private static class CountingSource implements SnapshotSource {
        final AtomicInteger calls = new AtomicInteger();
        RuntimeException toThrow;
        String marker = "첫번째";

        @Override
        public BoardSnapshot collect(Instant now) {
            calls.incrementAndGet();
            if (toThrow != null) {
                throw toThrow;
            }
            return new BoardSnapshot(now, 1, List.of(), Map.of(), List.of(marker));
        }
    }

    @Test
    void 요청은_수집을_유발하지_않는다() {
        CountingSource aggregator = new CountingSource();
        BoardService service = new BoardService(aggregator, FIXED);

        service.refresh();
        int afterRefresh = aggregator.calls.get();

        for (int i = 0; i < 50; i++) {
            service.snapshot();
        }

        // 요청 50번이 수집을 한 번도 더 유발하지 않아야 한다.
        assertThat(aggregator.calls.get()).isEqualTo(afterRefresh).isEqualTo(1);
    }

    @Test
    void 첫_수집_전에는_기다리지_않고_사유를_알린다() {
        CountingSource aggregator = new CountingSource();
        BoardService service = new BoardService(aggregator, FIXED);

        BoardSnapshot snapshot = service.snapshot();

        // 수집을 시도하지 않았고("기다리지 않는다"),
        assertThat(aggregator.calls.get()).isZero();
        // "세션이 없다"와 구별되게 사유가 실린다.
        assertThat(snapshot.errors()).isNotEmpty();
        assertThat(snapshot.projects()).isEmpty();
    }

    @Test
    void 수집이_실패해도_이전_캐시를_유지한다() {
        CountingSource aggregator = new CountingSource();
        BoardService service = new BoardService(aggregator, FIXED);

        service.refresh();
        aggregator.toThrow = new IllegalStateException("서브프로세스 실패");

        // 스케줄러가 멈추면 안 되므로 예외가 밖으로 나오지 않는다.
        service.refresh();

        // 빈 화면 대신 마지막 성공 결과가 남는다.
        assertThat(service.snapshot().errors()).containsExactly("첫번째");
    }

    @Test
    void 구독자는_갱신을_받고_해제하면_끊긴다() {
        CountingSource aggregator = new CountingSource();
        BoardService service = new BoardService(aggregator, FIXED);
        List<BoardSnapshot> received = new ArrayList<>();

        Runnable unsubscribe = service.subscribe(received::add);
        assertThat(service.listenerCount()).isEqualTo(1);

        service.refresh();
        assertThat(received).hasSize(1);

        unsubscribe.run();
        assertThat(service.listenerCount()).isZero();

        service.refresh();
        assertThat(received).hasSize(1);   // 해제 뒤에는 오지 않는다
    }

    @Test
    void 한_구독자가_던져도_나머지는_받는다() {
        CountingSource aggregator = new CountingSource();
        BoardService service = new BoardService(aggregator, FIXED);
        List<BoardSnapshot> healthy = new ArrayList<>();

        service.subscribe(s -> {
            throw new IllegalStateException("끊긴 연결");
        });
        service.subscribe(healthy::add);

        service.refresh();

        // 끊긴 연결 하나가 전체 통지를 막으면 안 된다.
        assertThat(healthy).hasSize(1);
    }
}
