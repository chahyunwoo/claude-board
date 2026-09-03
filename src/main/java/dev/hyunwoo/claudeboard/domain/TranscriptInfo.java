package dev.hyunwoo.claudeboard.domain;

import java.time.Instant;

/**
 * 소스 B — 세션 기록에서 역순으로 긁어온 값. docs/01-데이터.md "쓸 필드" 참고.
 *
 * <p>모든 필드가 null 일 수 있다. 역순 리더는 상한(기본 1MB)에 걸리면
 * <b>부분 결과를 반환</b>하기 때문이다 — 제목이 없어도 상태 판별은 되어야 한다.
 *
 * @param lastRecordKind 마지막 대화 레코드의 종류. 상태 판별의 입력이다.
 * @param contextTokens  input + cache_read + cache_creation. output 은 합산하지 않는다.
 * @param truncated      상한에 걸려 부분 결과인지 여부.
 */
public record TranscriptInfo(
        String aiTitle,
        String lastPrompt,
        String branch,
        String permissionMode,
        String model,
        Long contextTokens,
        Instant lastActivityAt,
        Instant startedAt,
        RecordKind lastRecordKind,
        boolean truncated) {

    /** 마지막 대화 레코드가 무엇이었는가. */
    public enum RecordKind {
        /** assistant 레코드가 text 로 끝남 — 사용자 차례다. */
        ASSISTANT,
        /**
         * assistant 레코드가 tool_use 로 끝남 — 도구를 호출하고 결과를 기다리는 중이다.
         *
         * <p>실측(2026-09-03): 살아있는 세션 13개 중 2개가 이 형태였고 둘 다 실제로 작업 중이었다.
         * 이걸 {@link #ASSISTANT} 와 뭉뚱그리면 작업 중인 세션이 "답변 대기"로 오보된다.
         */
        ASSISTANT_TOOL_USE,
        /** user 레코드인데 content 에 tool_result 블록이 있음 — 도구 결과 반환이지 사용자 입력이 아니다. */
        TOOL_RESULT,
        /** user 레코드이고 tool_result 가 아님 — 사용자 입력 직후. */
        USER,
        /** 대화 레코드를 하나도 찾지 못함 (빈 파일 등). */
        NONE
    }

    /** 아무것도 읽지 못했을 때의 값. 예외 대신 이것을 반환한다. */
    public static TranscriptInfo empty() {
        return new TranscriptInfo(null, null, null, null, null, null, null, null, RecordKind.NONE, false);
    }
}
