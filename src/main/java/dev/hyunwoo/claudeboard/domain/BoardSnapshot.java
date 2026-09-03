package dev.hyunwoo.claudeboard.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/sessions} 응답 전체.
 *
 * <p>{@code errors} 를 <b>반드시</b> 노출한다 — 파싱이 조용히 실패하면
 * "세션이 없다"와 "읽지 못했다"가 구별되지 않는다. docs/02-백엔드.md.
 */
public record BoardSnapshot(
        Instant generatedAt,
        long elapsedMs,
        List<Project> projects,
        Map<String, Integer> counts,
        List<String> errors) {
}
