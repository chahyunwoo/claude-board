package dev.hyunwoo.claudeboard.collect;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hyunwoo.claudeboard.domain.BoardSnapshot;
import dev.hyunwoo.claudeboard.domain.Project;
import dev.hyunwoo.claudeboard.domain.Session;
import dev.hyunwoo.claudeboard.domain.SessionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 집계 테스트. 실제 파일과 가짜 agents 출력을 물려 end-to-end 로 확인한다.
 */
class AggregatorTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    @TempDir
    Path tmp;

    /**
     * {@code claude agents --json} 대신 주어진 JSON 을 뱉는 명령을 물린다.
     *
     * <p>{@code AgentsReader} 를 흉내내지 않고 <b>실제 ProcessBuilder 경로를 그대로 태운다</b> —
     * 그래야 실행·파싱 배선까지 함께 검사된다.
     */
    private static AgentsReader fakeAgents(String json) {
        return new AgentsReader(new ObjectMapper(), List.of("printf", "%s", json));
    }

    private Aggregator aggregator(String agentsJson, long contextLimit) {
        return new Aggregator(
                fakeAgents(agentsJson),
                new TranscriptReader(),
                StateResolver.withDefaults(),
                new SessionFiles(tmp),
                contextLimit);
    }

    /** {@code ~/.claude/projects/<인코딩된경로>/<sessionId>.jsonl} 구조를 흉내낸다. */
    private void writeTranscript(String dirName, String sessionId, String... lines) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve(dirName));
        Files.writeString(dir.resolve(sessionId + ".jsonl"),
                String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private static String assistantWithUsage(long input, long cacheRead, String ts) {
        return """
                {"type":"assistant","timestamp":"%s","gitBranch":"main",\
                "message":{"model":"claude-opus-5","content":[{"type":"text","text":"응답"}],\
                "usage":{"input_tokens":%d,"cache_read_input_tokens":%d,\
                "cache_creation_input_tokens":0,"output_tokens":1}}}"""
                .formatted(ts, input, cacheRead);
    }

    // ── 컨텍스트 상한 자동 상향 ─────────────────────────────────────────

    /**
     * 세션 기록의 모델명이 {@code claude-opus-5} 로만 남아 {@code [1m]} 변형이 구분되지 않는다.
     * 그래서 <b>관측값이 기본 상한을 넘으면 상한을 올린다</b>.
     * docs/00-개요.md 결정사항 3.
     */
    @Test
    void 관측_토큰이_기본_상한을_넘으면_상한을_자동으로_올린다() throws IOException {
        writeTranscript("-Users-me-proj", "s1",
                assistantWithUsage(0, 1_200_000, "2026-09-03T11:59:00Z"));

        BoardSnapshot snap = aggregator("""
                [{"pid":100,"cwd":"/Users/me/proj","sessionId":"s1","name":"proj-a"}]
                """, 1_000_000L).collect(NOW);

        Session s = snap.projects().get(0).current();
        assertThat(s.contextTokens()).isEqualTo(1_200_000);
        assertThat(s.contextLimit())
                .as("관측값이 상한을 넘으면 분모를 올려 비율이 100%를 넘지 않게 한다")
                .isEqualTo(1_200_000);
        assertThat(s.contextRatio()).isEqualTo(1.0);
    }

    @Test
    void 관측_토큰이_상한_미만이면_기본_상한을_유지한다() throws IOException {
        writeTranscript("-Users-me-proj", "s1",
                assistantWithUsage(0, 450_000, "2026-09-03T11:59:00Z"));

        BoardSnapshot snap = aggregator("""
                [{"pid":100,"cwd":"/Users/me/proj","sessionId":"s1","name":"proj-a"}]
                """, 1_000_000L).collect(NOW);

        Session s = snap.projects().get(0).current();
        assertThat(s.contextLimit()).isEqualTo(1_000_000);
        assertThat(s.contextRatio()).isEqualTo(0.45);
    }

    // ── 프로젝트 집계 ───────────────────────────────────────────────────

    /**
     * 한 프로젝트에 세션이 여러 개 동시에 살아있을 수 있다 —
     * 가장 최근 것을 현재로 삼고 나머지는 접는다.
     */
    @Test
    void 한_프로젝트에_세션이_여럿이면_최근_것이_현재고_나머지는_others_다() throws IOException {
        writeTranscript("-Users-me-proj", "old",
                assistantWithUsage(0, 100, "2026-09-03T09:00:00Z"));
        writeTranscript("-Users-me-proj", "recent",
                assistantWithUsage(0, 200, "2026-09-03T11:50:00Z"));

        BoardSnapshot snap = aggregator("""
                [{"pid":1,"cwd":"/Users/me/proj","sessionId":"old","name":"a"},
                 {"pid":2,"cwd":"/Users/me/proj","sessionId":"recent","name":"b"}]
                """, 1_000_000L).collect(NOW);

        assertThat(snap.projects()).hasSize(1);
        Project p = snap.projects().get(0);
        assertThat(p.current().sessionId()).isEqualTo("recent");
        assertThat(p.others()).extracting(Session::sessionId).containsExactly("old");
        assertThat(p.sessionCount()).isEqualTo(2);
    }

    @Test
    void 답변_대기_프로젝트가_최상단으로_정렬된다() throws IOException {
        // working: 도구 결과가 방금 돌아온 세션
        writeTranscript("-Users-me-busy", "w1",
                """
                {"type":"user","timestamp":"2026-09-03T11:59:30Z","gitBranch":"main",\
                "message":{"content":[{"type":"tool_result","tool_use_id":"x","content":"결과"}]}}""");
        // waiting: assistant 가 text 로 끝난 세션
        writeTranscript("-Users-me-idle", "a1",
                assistantWithUsage(0, 100, "2026-09-03T11:00:00Z"));

        BoardSnapshot snap = aggregator("""
                [{"pid":1,"cwd":"/Users/me/busy","sessionId":"w1","name":"busy"},
                 {"pid":2,"cwd":"/Users/me/idle","sessionId":"a1","name":"idle"}]
                """, 1_000_000L).collect(NOW);

        assertThat(snap.projects())
                .as("답변 대기가 가장 높은 가치다 — 최상단이어야 한다")
                .extracting(p -> p.current().state())
                .containsExactly(SessionState.WAITING, SessionState.WORKING);
    }

    // ── errors 노출 ─────────────────────────────────────────────────────

    /**
     * 파싱 실패를 조용히 삼키면 "세션이 없다"와 "읽지 못했다"가 구별되지 않는다.
     * docs/02-백엔드.md.
     */
    @Test
    void 세션_기록을_찾지_못하면_errors_에_남긴다() {
        BoardSnapshot snap = aggregator("""
                [{"pid":1,"cwd":"/Users/me/proj","sessionId":"없는세션","name":"a"}]
                """, 1_000_000L).collect(NOW);

        assertThat(snap.errors())
                .as("조용히 삼키면 안 된다")
                .isNotEmpty()
                .anySatisfy(e -> assertThat(e).contains("없는세션"));
    }

    @Test
    void 기록을_못_찾아도_세션은_목록에서_빠지지_않는다() {
        BoardSnapshot snap = aggregator("""
                [{"pid":1,"cwd":"/Users/me/proj","sessionId":"없는세션","name":"a"}]
                """, 1_000_000L).collect(NOW);

        // 기록이 없어도 pid 는 있다 — 살아있는 세션이므로 보여야 한다.
        assertThat(snap.projects()).hasSize(1);
        assertThat(snap.projects().get(0).current().state()).isEqualTo(SessionState.WORKING);
    }

    @Test
    void 살아있는_세션이_없으면_빈_목록을_낸다() {
        BoardSnapshot snap = aggregator("[]", 1_000_000L).collect(NOW);

        assertThat(snap.projects()).isEmpty();
        assertThat(snap.errors()).isEmpty();
        assertThat(snap.counts()).containsEntry("waiting", 0);
    }

    @Test
    void 상태별_개수를_센다() throws IOException {
        writeTranscript("-Users-me-a", "s1", assistantWithUsage(0, 100, "2026-09-03T11:00:00Z"));
        writeTranscript("-Users-me-b", "s2", assistantWithUsage(0, 100, "2026-09-03T11:00:00Z"));

        BoardSnapshot snap = aggregator("""
                [{"pid":1,"cwd":"/Users/me/a","sessionId":"s1","name":"a"},
                 {"pid":2,"cwd":"/Users/me/b","sessionId":"s2","name":"b"}]
                """, 1_000_000L).collect(NOW);

        assertThat(snap.counts())
                .containsEntry("waiting", 2)
                .containsEntry("working", 0)
                .containsEntry("stalled", 0);
    }

    @Test
    void 프로젝트_이름은_경로의_마지막_조각이다() {
        assertThat(Aggregator.nameOf("/Users/me/projects/notify-service")).isEqualTo("notify-service");
        assertThat(Aggregator.nameOf(null)).isEqualTo("(알 수 없음)");
    }
}
