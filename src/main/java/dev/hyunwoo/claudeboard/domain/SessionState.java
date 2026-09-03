package dev.hyunwoo.claudeboard.domain;

/**
 * 세션 상태 4종. docs/01-데이터.md "최종 상태" 참고.
 *
 * <p>{@link #sortOrder} 는 보드 정렬 우선순위다. 답변 대기가 가장 높은 가치이므로 1.
 *
 * <p><b>종료된 세션은 상태로 두지 않는다</b> (#17). 이 보드는 살아있는 세션만 다룬다 —
 * {@code claude agents --json} 이 주는 목록이 곧 대상이고, 종료된 기록은
 * {@code Project.sessionCount} 로 개수만 센다. docs/00-개요.md "범위 밖".
 */
public enum SessionState {

    /** 답변 대기 — 마지막 레코드가 assistant. */
    WAITING(1),

    /** 멈춤 의심 — 마지막이 tool_result 인데 임계를 넘도록 조용함. */
    STALLED(2),

    /** 작업 중 — 마지막이 tool_result 또는 user 이고 최근 갱신됨. */
    WORKING(3),

    /** 유휴 — 살아있으나 오래 조용함. */
    IDLE(4);

    private final int sortOrder;

    SessionState(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public int sortOrder() {
        return sortOrder;
    }
}
