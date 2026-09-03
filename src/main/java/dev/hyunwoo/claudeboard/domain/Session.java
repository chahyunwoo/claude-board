package dev.hyunwoo.claudeboard.domain;

import java.time.Instant;

/**
 * API 에 실려 나가는 세션 한 건. docs/02-백엔드.md 의 {@code GET /api/sessions} 스키마와 대응한다.
 *
 * <p>컨텍스트는 <b>절대값·상한·비율 셋을 모두</b> 낸다. 세션 기록의 모델명이
 * {@code claude-opus-5} 로만 남아 {@code [1m]} 변형이 구분되지 않으므로 분모가 틀릴 수 있고,
 * 그래도 절대값으로는 판단이 가능해야 하기 때문이다. docs/00-개요.md 결정사항 3.
 */
public record Session(
        String sessionId,
        Long pid,
        SessionState state,
        String title,
        String lastPrompt,
        String branch,
        String permissionMode,
        String model,
        Long contextTokens,
        Long contextLimit,
        Double contextRatio,
        Instant lastActivityAt,
        Instant startedAt,
        int ordinal) {
}
