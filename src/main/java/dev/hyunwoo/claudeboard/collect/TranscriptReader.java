package dev.hyunwoo.claudeboard.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hyunwoo.claudeboard.domain.TranscriptInfo;
import dev.hyunwoo.claudeboard.domain.TranscriptInfo.RecordKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * 세션 기록({@code .jsonl})을 <b>끝에서부터</b> 읽어 필요한 필드만 뽑는다.
 *
 * <p>세션 기록은 실측 최대 2.5MB 다(2026-09-03, 109개 파일 기준). 전체 파싱하면
 * 갱신마다 수백 ms 가 날아가므로 역순으로 읽되 <b>필요한 필드를 다 채우면 즉시 중단</b>한다.
 * docs/02-백엔드.md "핵심 — TranscriptReader".
 *
 * <p>상한({@code maxBytes}, 기본 1MB)에 걸리면 <b>부분 결과를 반환</b>한다.
 * 제목이 없어도 상태 판별은 되어야 하기 때문이다.
 *
 * <p>Spring 에 의존하지 않는 순수 자바다.
 */
public final class TranscriptReader {

    /**
     * 역순 읽기 상한. 이걸 넘으면 부분 결과를 반환한다.
     *
     * <p>512KB 는 실측으로 고른 균형점이다 (2026-09-03, 파일 109개 / 총 327MB / 최대 21MB).
     * 상한별 시간과 정보 손실:
     *
     * <pre>
     *   상한    시간    lastPrompt 누락
     *    64K    70ms      49 / 109
     *   256K    65ms      15 / 109
     *   512K    79ms       6 / 109   ← 선택
     *  1024K   104ms       3 / 109
     * </pre>
     *
     * 1MB 대비 시간은 24% 줄고 누락은 3개만 는다.
     *
     * <p>재현: {@code ./gradlew test --tests '*ReaderBenchmark*' -Dbenchmark=true -i}
     */
    public static final long DEFAULT_MAX_BYTES = 512L * 1024L;

    private final ObjectMapper mapper;
    private final long maxBytes;

    public TranscriptReader() {
        this(new ObjectMapper(), DEFAULT_MAX_BYTES);
    }

    public TranscriptReader(ObjectMapper mapper, long maxBytes) {
        this.mapper = mapper;
        this.maxBytes = maxBytes;
    }

    /**
     * 파일을 역순으로 읽어 채울 수 있는 만큼 채운다.
     *
     * <p>깨진 JSON 줄은 <b>그 줄만 건너뛰고 계속</b>한다 — 한 줄 때문에 세션 전체가
     * 안 보이면 안 된다. 파일이 없거나 비어 있으면 {@link TranscriptInfo#empty()} 를 반환한다.
     *
     * @throws IOException 파일을 열 수 없을 때. 호출부가 errors 로 노출한다.
     */
    public TranscriptInfo read(Path path) throws IOException {
        if (!Files.exists(path) || Files.size(path) == 0) {
            return TranscriptInfo.empty();
        }

        Accumulator acc = new Accumulator();

        try (ReverseLineReader reader = new ReverseLineReader(path)) {
            String line;
            while ((line = reader.nextLine()) != null) {
                JsonNode node;
                try {
                    node = mapper.readTree(line);
                } catch (Exception broken) {
                    // 깨진 줄은 건너뛴다. docs/05-검증.md 단위테스트 ⑤.
                    continue;
                }
                if (node == null || !node.isObject()) {
                    continue;
                }

                acc.absorb(node);
                if (acc.isComplete()) {
                    break;
                }

                // 상한은 "더 읽을지"의 기준이지 "이미 읽은 것을 버릴지"의 기준이 아니다.
                // 파싱 앞에서 끊으면 상한이 청크 크기보다 작을 때 첫 줄조차 못 읽어
                // lastRecordKind 가 NONE 이 된다 — 상태 판별이 통째로 죽는다.
                // 반드시 흡수한 뒤에 검사한다. "부분 결과라도 반환한다"의 실제 의미다.
                if (reader.readBytes() > maxBytes) {
                    acc.truncated = true;
                    break;
                }
            }
        }

        // startedAt 은 파일 앞쪽에 있어 역순 읽기로는 대개 닿지 못한다.
        // 파일의 첫 줄에서 따로 읽는다 (한 줄이라 비용이 없다).
        if (acc.startedAt == null) {
            acc.startedAt = readStartedAt(path);
        }

        return acc.toInfo();
    }

    /** 파일 첫 줄의 timestamp. 세션 시작 시각의 근사값이다. */
    private Instant readStartedAt(Path path) {
        try (var lines = Files.lines(path)) {
            return lines.limit(50)
                    .map(this::parseQuietly)
                    .filter(n -> n != null && n.hasNonNull("timestamp"))
                    .map(n -> parseInstant(n.get("timestamp").asText()))
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode parseQuietly(String line) {
        try {
            return mapper.readTree(line);
        } catch (Exception e) {
            return null;
        }
    }

    private static Instant parseInstant(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 역순으로 훑으며 값을 채운다. <b>먼저 만난 값이 이긴다</b> — 역순이므로 그것이 최신이다.
     */
    private static final class Accumulator {
        String aiTitle;
        String lastPrompt;
        String branch;
        String permissionMode;
        String model;
        Long contextTokens;
        Instant lastActivityAt;
        Instant startedAt;
        RecordKind lastRecordKind = RecordKind.NONE;
        boolean truncated;

        void absorb(JsonNode node) {
            String type = node.path("type").asText(null);
            if (type == null) {
                return;
            }

            if (branch == null && node.hasNonNull("gitBranch")) {
                branch = node.get("gitBranch").asText();
            }
            if (permissionMode == null && node.hasNonNull("permissionMode")) {
                permissionMode = node.get("permissionMode").asText();
            }

            switch (type) {
                case "ai-title" -> {
                    if (aiTitle == null && node.hasNonNull("aiTitle")) {
                        aiTitle = node.get("aiTitle").asText();
                    }
                }
                case "assistant" -> absorbAssistant(node);
                case "user" -> absorbUser(node);
                default -> {
                    // last-prompt·attachment 등은 상태 판별에 쓰지 않는다.
                    // last-prompt 에는 발화 텍스트가 없고 leafUuid 만 있으며,
                    // 그 uuid 가 user 레코드를 가리키지 않는 경우가 많다
                    // (실측: last-prompt 있는 파일 24개 중 user 를 가리킨 것은 16개).
                }
            }
        }

        private void absorbAssistant(JsonNode node) {
            if (lastRecordKind == RecordKind.NONE) {
                // assistant 는 두 갈래다. text 로 끝나면 사용자 차례(답변 대기)지만
                // tool_use 로 끝나면 도구 결과를 기다리는 중(작업 중)이다.
                // 뭉뚱그리면 작업 중인 세션이 "답변 대기"로 오보된다.
                lastRecordKind = hasBlock(node.path("message").path("content"), "tool_use")
                        ? RecordKind.ASSISTANT_TOOL_USE
                        : RecordKind.ASSISTANT;
                lastActivityAt = parseInstant(node.path("timestamp").asText(null));
            }
            if (model == null && node.path("message").hasNonNull("model")) {
                model = node.get("message").get("model").asText();
            }
            if (contextTokens == null) {
                contextTokens = contextFrom(node.path("message").path("usage"));
            }
        }

        private void absorbUser(JsonNode node) {
            JsonNode content = node.path("message").path("content");
            boolean toolResult = hasToolResult(content);

            if (lastRecordKind == RecordKind.NONE) {
                lastRecordKind = toolResult ? RecordKind.TOOL_RESULT : RecordKind.USER;
                lastActivityAt = parseInstant(node.path("timestamp").asText(null));
            }

            // 마지막 발화는 tool_result 가 아닌 user 레코드에서만 얻는다.
            if (lastPrompt == null && !toolResult) {
                lastPrompt = textOf(content);
            }
        }

        /**
         * {@code message.content} 배열에 {@code type: "tool_result"} 블록이 있는가.
         *
         * <p><b>도구 결과는 {@code type: "user"} 로 기록된다.</b> 이걸 사용자 입력으로 오인하면
         * "작업 중"과 "답변 대기"가 정확히 뒤바뀐다. docs/01-데이터.md 의 경고이자
         * docs/05-검증.md 단위테스트 ②가 지키는 지점이다.
         */
        private static boolean hasToolResult(JsonNode content) {
            return hasBlock(content, "tool_result");
        }

        /** {@code content} 배열에 주어진 type 의 블록이 있는가. 블록 판정은 여기 한 곳을 통한다. */
        private static boolean hasBlock(JsonNode content, String blockType) {
            if (!content.isArray()) {
                return false;
            }
            for (JsonNode block : content) {
                if (block.isObject() && blockType.equals(block.path("type").asText(null))) {
                    return true;
                }
            }
            return false;
        }

        /** content 가 문자열이면 그대로, 배열이면 text 블록을 이어붙인다. */
        private static String textOf(JsonNode content) {
            if (content.isTextual()) {
                return blankToNull(content.asText());
            }
            if (!content.isArray()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if (block.isObject() && "text".equals(block.path("type").asText(null))) {
                    if (!sb.isEmpty()) {
                        sb.append(' ');
                    }
                    sb.append(block.path("text").asText(""));
                }
            }
            return blankToNull(sb.toString());
        }

        private static String blankToNull(String s) {
            return (s == null || s.isBlank()) ? null : s.trim();
        }

        /**
         * 컨텍스트 = input + cache_read + cache_creation.
         *
         * <p>{@code output_tokens} 는 합산하지 않는다 — 다음 요청의 입력이 되긴 하나
         * 이미 cache_read 에 반영된다. docs/01-데이터.md "컨텍스트 사용량".
         */
        private static Long contextFrom(JsonNode usage) {
            if (!usage.isObject()) {
                return null;
            }
            long sum = usage.path("input_tokens").asLong(0)
                    + usage.path("cache_read_input_tokens").asLong(0)
                    + usage.path("cache_creation_input_tokens").asLong(0);
            return sum > 0 ? sum : null;
        }

        /**
         * 더 읽을 필요가 없는가.
         *
         * <p>{@code permissionMode} 는 일부 레코드에만 있어(실측: user 158개 중 14개)
         * 완료 조건에 넣으면 매번 상한까지 읽게 된다. 그래서 제외한다 —
         * 없으면 없는 대로 부분 결과로 낸다.
         */
        boolean isComplete() {
            return lastRecordKind != RecordKind.NONE
                    && aiTitle != null
                    && lastPrompt != null
                    && branch != null
                    && model != null
                    && contextTokens != null;
        }

        TranscriptInfo toInfo() {
            return new TranscriptInfo(
                    aiTitle, lastPrompt, branch, permissionMode, model,
                    contextTokens, lastActivityAt, startedAt, lastRecordKind, truncated);
        }
    }
}
