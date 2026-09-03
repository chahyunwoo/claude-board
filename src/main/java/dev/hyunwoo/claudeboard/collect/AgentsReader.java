package dev.hyunwoo.claudeboard.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hyunwoo.claudeboard.domain.AgentInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 소스 A — {@code claude agents --json} 을 실행해 살아있는 세션 목록을 얻는다.
 * docs/01-데이터.md "소스 A".
 *
 * <p><b>반드시 {@code --json} 을 붙인다.</b> TTY 가 없으면 {@code claude agents} 는 거부한다.
 *
 * <p>Spring 에 의존하지 않는 순수 자바다.
 */
public final class AgentsReader {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper mapper;
    private final List<String> command;

    public AgentsReader() {
        this(new ObjectMapper(), List.of("claude", "agents", "--json"));
    }

    public AgentsReader(ObjectMapper mapper, List<String> command) {
        this.mapper = mapper;
        this.command = List.copyOf(command);
    }

    /**
     * 살아있는 세션 목록. 실행에 실패하면 예외를 던진다 —
     * 호출부가 {@code errors} 로 노출해야 한다. 빈 목록으로 삼키면
     * "세션이 없다"와 "읽지 못했다"가 구별되지 않는다.
     */
    public List<AgentInfo> read() throws IOException {
        String json = runCommand();
        return parse(json);
    }

    /** 표준출력을 문자열로. 표준에러는 진단을 위해 예외 메시지에 싣는다. */
    private String runCommand() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        String stdout;
        String stderr;
        try (InputStream out = process.getInputStream();
             InputStream err = process.getErrorStream()) {
            stdout = new String(out.readAllBytes(), StandardCharsets.UTF_8);
            stderr = new String(err.readAllBytes(), StandardCharsets.UTF_8);
        }

        boolean finished;
        try {
            finished = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("agents 실행이 중단됨", e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("agents 실행이 " + TIMEOUT.toSeconds() + "초 안에 끝나지 않음");
        }
        if (process.exitValue() != 0) {
            throw new IOException("agents 실행 실패 (exit=" + process.exitValue() + "): " + stderr.strip());
        }
        return stdout;
    }

    /**
     * JSON 배열을 파싱한다. 항목 하나가 이상해도 나머지는 살린다.
     *
     * <p>{@code status} 는 {@code "busy"} 일 때만 존재한다 — 없다고 유휴로 간주하지 않는다.
     * 상태 판별은 소스 B(세션 기록)로 한다. 실측에서 status 가 없는 항목이 실제로 존재했다.
     */
    List<AgentInfo> parse(String json) throws IOException {
        JsonNode root = mapper.readTree(json);
        if (root == null || !root.isArray()) {
            throw new IOException("agents 출력이 JSON 배열이 아님");
        }

        List<AgentInfo> agents = new ArrayList<>();
        for (JsonNode node : root) {
            if (!node.isObject() || !node.hasNonNull("sessionId")) {
                continue;
            }
            agents.add(new AgentInfo(
                    node.path("pid").asLong(0),
                    node.path("cwd").asText(null),
                    node.path("kind").asText(null),
                    node.get("sessionId").asText(),
                    node.path("name").asText(null),
                    epochMillis(node.path("startedAt")),
                    node.path("status").asText(null)));
        }
        return agents;
    }

    private static Instant epochMillis(JsonNode node) {
        return node.isNumber() ? Instant.ofEpochMilli(node.asLong()) : null;
    }
}
