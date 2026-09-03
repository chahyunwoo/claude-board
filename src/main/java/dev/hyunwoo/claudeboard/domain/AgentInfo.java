package dev.hyunwoo.claudeboard.domain;

import java.time.Instant;

/**
 * 소스 A — {@code claude agents --json} 한 항목. docs/01-데이터.md 참고.
 *
 * @param status {@code "busy"} 일 때만 존재한다. 없다고 유휴로 간주하지 말 것 —
 *               상태 판별은 소스 B(세션 기록)로 한다.
 */
public record AgentInfo(
        long pid,
        String cwd,
        String kind,
        String sessionId,
        String name,
        Instant startedAt,
        String status) {
}
