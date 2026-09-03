package dev.hyunwoo.claudeboard.web;

import dev.hyunwoo.claudeboard.domain.BoardSnapshot;
import dev.hyunwoo.claudeboard.service.BoardService;
import dev.hyunwoo.claudeboard.service.SnapshotSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * SSE 연결 수명. <b>emitter 누수는 예외를 내지 않고 조용히 쌓이므로</b>
 * 구독자 수를 직접 세어서 확인한다. docs/05-검증.md 5번 "SSE 연결이 끊겼다 붙을 때".
 *
 * <p>실제 HTTP 로 붙였다 끊는다 — 컨트롤러 메서드를 직접 부르면 서블릿 컨테이너가
 * {@code onCompletion}/{@code onError} 를 부르는 경로를 타지 않아,
 * <b>정리가 배선되지 않았어도 통과해 버린다.</b>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 이 테스트는 수집을 검증하지 않는다. 스케줄러가 끼어들지 않게 길게 둔다.
                "claude-board.interval=1h",
                // 끊김 감지를 기다리는 시간을 줄인다. 운영 기본값은 15초.
                "claude-board.sse-heartbeat-ms=300"
        })
class StreamControllerTest {

    @TestConfiguration
    static class StubSource {
        /** 실제 {@code claude agents --json} 서브프로세스를 타지 않게 갈아끼운다. */
        @Bean
        @Primary
        SnapshotSource stubSource() {
            return now -> new BoardSnapshot(now, 0, List.of(), Map.of(), List.of());
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    BoardService boardService;

    @Test
    void 연결하면_즉시_현재_상태를_받고_끊으면_구독이_정리된다() throws Exception {
        int before = boardService.listenerCount();

        HttpURLConnection conn = open("/api/stream");
        String firstEvent;
        try (InputStream in = conn.getInputStream();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {

            // 연결 직후 1건이 와야 한다 — 없으면 다음 수집 주기까지 화면이 빈다.
            firstEvent = reader.readLine();
            assertThat(firstEvent).isEqualTo("event:snapshot");

            await().atMost(ofSeconds(5))
                    .untilAsserted(() -> assertThat(boardService.listenerCount()).isEqualTo(before + 1));
        }
        conn.disconnect();

        // 끊긴 뒤 구독이 남아 있으면 누수다. 다음 수집이 죽은 연결에 쓰기를 시도하게 된다.
        await().atMost(ofSeconds(10))
                .untilAsserted(() -> assertThat(boardService.listenerCount()).isEqualTo(before));
    }

    @Test
    void 붙였다_끊기를_반복해도_구독이_쌓이지_않는다() throws Exception {
        int before = boardService.listenerCount();

        // 재연결은 브라우저 EventSource 가 알아서 하는 정상 동작이다.
        // 한 번이라도 정리가 새면 여기서 누적되어 드러난다.
        for (int i = 0; i < 5; i++) {
            HttpURLConnection conn = open("/api/stream");
            try (InputStream in = conn.getInputStream()) {
                assertThat(in.read()).isNotNegative();
            }
            conn.disconnect();
        }

        await().atMost(ofSeconds(30))
                .untilAsserted(() -> assertThat(boardService.listenerCount()).isEqualTo(before));
    }

    @Test
    void 갱신이_연결된_클라이언트에_전달된다() throws Exception {
        HttpURLConnection conn = open("/api/stream");
        try (InputStream in = conn.getInputStream();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {

            drainOneEvent(reader);                       // 연결 직후 1건

            boardService.refresh();                      // 수집 발생

            // 두 번째 이벤트가 와야 한다 — 안 오면 구독 통지가 배선되지 않은 것이다.
            assertThat(reader.readLine()).isEqualTo("event:snapshot");
        } finally {
            conn.disconnect();
        }
    }

    /** {@code event:} / {@code data:} / 빈 줄 한 묶음을 읽어 버린다. */
    private void drainOneEvent(BufferedReader reader) throws Exception {
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            // 이벤트 경계(빈 줄)까지
        }
    }

    private HttpURLConnection open(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(15000);
        return conn;
    }
}
