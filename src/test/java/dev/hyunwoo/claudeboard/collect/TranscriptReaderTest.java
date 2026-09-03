package dev.hyunwoo.claudeboard.collect;

import dev.hyunwoo.claudeboard.domain.TranscriptInfo;
import dev.hyunwoo.claudeboard.domain.TranscriptInfo.RecordKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/05-검증.md "반드시 넣을 단위 테스트" ①~⑤.
 *
 * <p>테스트는 소스 문자열이 아니라 <b>동작(실제로 판별된 값)</b>을 관측한다.
 * 소스를 매칭하면 배선이 옳아도 리팩터에 깨진다.
 */
class TranscriptReaderTest {

    @TempDir
    Path tmp;

    private final TranscriptReader reader = new TranscriptReader();

    // ── ① 마지막이 assistant → ASSISTANT ────────────────────────────────

    @Test
    void 마지막이_assistant_면_ASSISTANT_로_읽는다() throws IOException {
        Path f = write(
                userText("사용자 발화"),
                assistant("응답입니다", 100, 200, 300));

        TranscriptInfo info = reader.read(f);

        assertThat(info.lastRecordKind()).isEqualTo(RecordKind.ASSISTANT);
    }

    // ── ② 마지막이 user + tool_result → TOOL_RESULT (핵심) ──────────────

    /**
     * <b>이 테스트가 이 클래스에서 가장 중요하다.</b>
     *
     * <p>도구 결과는 {@code type: "user"} 로 기록된다. 순진하게 type 만 보면
     * 사용자 입력으로 오인해 "작업 중"과 "답변 대기"가 정확히 반대로 나온다.
     */
    @Test
    void 마지막이_user_인데_tool_result_블록이면_USER_가_아니라_TOOL_RESULT_다() throws IOException {
        Path f = write(
                userText("파일 좀 읽어줘"),
                assistant("읽을게요", 100, 200, 300),
                userToolResult());

        TranscriptInfo info = reader.read(f);

        assertThat(info.lastRecordKind())
                .as("도구 결과를 사용자 입력으로 오인하면 상태가 뒤집힌다")
                .isEqualTo(RecordKind.TOOL_RESULT)
                .isNotEqualTo(RecordKind.USER);
    }

    @Test
    void 마지막_assistant_가_tool_use_면_ASSISTANT_가_아니라_ASSISTANT_TOOL_USE_다() throws IOException {
        Path f = write(
                userText("파일 좀 읽어줘"),
                assistantToolUse());

        TranscriptInfo info = reader.read(f);

        assertThat(info.lastRecordKind())
                .as("도구 호출 중인 세션을 답변 대기로 오보하면 안 된다")
                .isEqualTo(RecordKind.ASSISTANT_TOOL_USE)
                .isNotEqualTo(RecordKind.ASSISTANT);
    }

    // ── ③ 마지막이 user + 순수 텍스트 → USER ────────────────────────────

    @Test
    void 마지막이_user_이고_순수_텍스트면_USER_다() throws IOException {
        Path f = write(
                assistant("무엇을 도와드릴까요", 100, 200, 300),
                userText("이거 고쳐줘"));

        TranscriptInfo info = reader.read(f);

        assertThat(info.lastRecordKind()).isEqualTo(RecordKind.USER);
    }

    @Test
    void content_가_문자열인_user_레코드도_USER_다() throws IOException {
        // 실측: content 가 배열이 아니라 문자열인 user 레코드가 존재한다.
        Path f = write("{\"type\":\"user\",\"timestamp\":\"2026-09-03T10:00:00Z\","
                + "\"message\":{\"content\":\"문자열 형태 발화\"}}");

        TranscriptInfo info = reader.read(f);

        assertThat(info.lastRecordKind()).isEqualTo(RecordKind.USER);
        assertThat(info.lastPrompt()).isEqualTo("문자열 형태 발화");
    }

    // ── ④ 빈 파일 → 예외 없이 부분 결과 ─────────────────────────────────

    @Test
    void 파일이_비어_있으면_예외_없이_부분_결과를_낸다() throws IOException {
        Path f = tmp.resolve("empty.jsonl");
        Files.writeString(f, "");

        TranscriptInfo info = reader.read(f);

        assertThat(info.lastRecordKind()).isEqualTo(RecordKind.NONE);
        assertThat(info.aiTitle()).isNull();
    }

    @Test
    void 파일이_없어도_예외_없이_부분_결과를_낸다() throws IOException {
        TranscriptInfo info = reader.read(tmp.resolve("없는파일.jsonl"));

        assertThat(info.lastRecordKind()).isEqualTo(RecordKind.NONE);
    }

    // ── ⑤ 깨진 JSON 줄 → 그 줄만 건너뛰고 계속 ──────────────────────────

    @Test
    void 마지막_줄이_깨진_JSON_이면_그_줄만_건너뛰고_계속_읽는다() throws IOException {
        Path f = write(
                assistant("정상 응답", 100, 200, 300),
                "{\"type\":\"assistant\", 깨진 JSON");

        TranscriptInfo info = reader.read(f);

        assertThat(info.lastRecordKind())
                .as("깨진 줄 하나 때문에 세션 전체가 안 보이면 안 된다")
                .isEqualTo(RecordKind.ASSISTANT);
        assertThat(info.contextTokens()).isEqualTo(600);
    }

    // ── 필드 수집 ───────────────────────────────────────────────────────

    @Test
    void 컨텍스트는_input_과_cache_를_더하고_output_은_빼고_센다() throws IOException {
        Path f = write(assistant("응답", 2, 454492, 728));

        TranscriptInfo info = reader.read(f);

        // 2 + 454492 + 728 = 455222. docs/01-데이터.md 의 계산과 같아야 한다.
        assertThat(info.contextTokens()).isEqualTo(455222);
    }

    @Test
    void 역순이므로_가장_최근_usage_를_쓴다() throws IOException {
        Path f = write(
                assistant("오래된 응답", 1, 1000, 0),
                userToolResult(),
                assistant("최근 응답", 2, 5000, 0));

        TranscriptInfo info = reader.read(f);

        assertThat(info.contextTokens()).isEqualTo(5002);
    }

    @Test
    void lastPrompt_는_tool_result_가_아닌_마지막_user_에서_얻는다() throws IOException {
        // last-prompt 레코드에는 발화 텍스트가 없고 leafUuid 만 있으며 그 uuid 가
        // user 를 가리키지 않는 경우가 많다(실측 24개 중 16개). 그래서 역순 스캔으로 얻는다.
        Path f = write(
                userText("진짜 마지막 발화"),
                assistant("작업할게요", 100, 200, 300),
                userToolResult());

        TranscriptInfo info = reader.read(f);

        assertThat(info.lastPrompt()).isEqualTo("진짜 마지막 발화");
    }

    @Test
    void aiTitle_과_branch_를_수집한다() throws IOException {
        Path f = write(
                "{\"type\":\"ai-title\",\"aiTitle\":\"결제 재시도 점검\"}",
                assistant("응답", 100, 200, 300));

        TranscriptInfo info = reader.read(f);

        assertThat(info.aiTitle()).isEqualTo("결제 재시도 점검");
        assertThat(info.branch()).isEqualTo("feature/1");
        assertThat(info.model()).isEqualTo("claude-opus-5");
    }

    // ── 역순 읽기가 실제로 역순인가 ─────────────────────────────────────

    /**
     * 64KB 청크 경계를 넘겨도 줄이 깨지지 않는지 본다.
     *
     * <p>경계에 걸친 레코드를 이어붙이지 않으면 깨진 JSON 으로 보여 조용히 건너뛰게 된다.
     */
    @Test
    void 청크_경계를_넘는_파일도_줄이_깨지지_않는다() throws IOException {
        List<String> lines = new ArrayList<>();
        // 앞쪽에 200KB 가량 채워 여러 청크가 되게 한다.
        for (int i = 0; i < 400; i++) {
            lines.add(assistant("패딩 " + i + " " + "가".repeat(200), 1, i, 0));
        }
        lines.add("{\"type\":\"ai-title\",\"aiTitle\":\"경계 너머 제목\"}");
        lines.add(assistant("마지막 응답", 7, 11, 13));

        Path f = write(lines.toArray(String[]::new));
        assertThat(Files.size(f)).isGreaterThan(64 * 1024);

        TranscriptInfo info = reader.read(f);

        assertThat(info.lastRecordKind()).isEqualTo(RecordKind.ASSISTANT);
        assertThat(info.contextTokens()).isEqualTo(31);
        assertThat(info.aiTitle()).isEqualTo("경계 너머 제목");
    }

    /**
     * 상한을 넘으면 부분 결과를 낸다 — 예외를 던지지 않는다.
     *
     * <p>상한을 아주 작게 줘서 파일 끝 한 줌만 읽게 만든다. 그래도 상태 판별에 필요한
     * {@code lastRecordKind} 는 채워져야 한다. "제목이 없어도 상태 판별은 되어야 한다."
     */
    @Test
    void 상한을_넘으면_예외_대신_부분_결과를_낸다() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("{\"type\":\"ai-title\",\"aiTitle\":\"닿지 못할 제목\"}");
        for (int i = 0; i < 400; i++) {
            lines.add(assistant("패딩 " + i + " " + "가".repeat(200), 1, i, 0));
        }
        Path f = write(lines.toArray(String[]::new));

        TranscriptReader capped = new TranscriptReader(new com.fasterxml.jackson.databind.ObjectMapper(), 4096);
        TranscriptInfo info = capped.read(f);

        assertThat(info.truncated()).isTrue();
        assertThat(info.lastRecordKind())
                .as("상한에 걸려도 상태 판별은 되어야 한다")
                .isEqualTo(RecordKind.ASSISTANT);
        assertThat(info.aiTitle())
                .as("앞쪽 제목까지는 못 닿는다 — 부분 결과")
                .isNull();
    }

    @Test
    void 역순_리더는_전체_파일을_읽지_않는다() throws IOException {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            lines.add(assistant("패딩 " + i + " " + "가".repeat(300), 1, i, 0));
        }
        lines.add("{\"type\":\"ai-title\",\"aiTitle\":\"끝쪽 제목\"}");
        lines.add(userText("끝쪽 발화"));
        lines.add(assistant("끝쪽 응답", 5, 5, 5));
        Path f = write(lines.toArray(String[]::new));

        long size = Files.size(f);
        assertThat(size).isGreaterThan(1_000_000);

        long start = System.nanoTime();
        TranscriptInfo info = reader.read(f);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 필요한 필드를 다 채웠으므로 즉시 중단되어야 한다.
        assertThat(info.aiTitle()).isEqualTo("끝쪽 제목");
        assertThat(elapsedMs)
                .as("전체 파싱이면 이보다 훨씬 오래 걸린다")
                .isLessThan(200);
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────

    private Path write(String... lines) throws IOException {
        Path f = tmp.resolve("session-" + System.nanoTime() + ".jsonl");
        Files.writeString(f, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return f;
    }

    private static String assistant(String text, int input, int cacheRead, int cacheCreate) {
        return """
                {"type":"assistant","timestamp":"2026-09-03T10:00:00Z","gitBranch":"feature/1",\
                "message":{"model":"claude-opus-5","content":[{"type":"text","text":"%s"}],\
                "usage":{"input_tokens":%d,"cache_read_input_tokens":%d,\
                "cache_creation_input_tokens":%d,"output_tokens":999}}}"""
                .formatted(text, input, cacheRead, cacheCreate);
    }

    /** 도구를 호출하고 결과를 기다리는 assistant 레코드. */
    private static String assistantToolUse() {
        return """
                {"type":"assistant","timestamp":"2026-09-03T10:02:00Z","gitBranch":"feature/1",\
                "message":{"model":"claude-opus-5","content":[{"type":"tool_use","id":"t1",\
                "name":"Bash","input":{}}],\
                "usage":{"input_tokens":1,"cache_read_input_tokens":2,\
                "cache_creation_input_tokens":3,"output_tokens":9}}}""";
    }

    private static String userText(String text) {
        return """
                {"type":"user","timestamp":"2026-09-03T09:59:00Z","gitBranch":"feature/1",\
                "message":{"content":[{"type":"text","text":"%s"}]}}"""
                .formatted(text);
    }

    private static String userToolResult() {
        return """
                {"type":"user","timestamp":"2026-09-03T10:01:00Z","gitBranch":"feature/1",\
                "message":{"content":[{"type":"tool_result","tool_use_id":"x","content":"결과"}]}}""";
    }

    static Instant at(String iso) {
        return Instant.parse(iso);
    }
}
