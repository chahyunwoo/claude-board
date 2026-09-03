package dev.hyunwoo.claudeboard.collect;

import dev.hyunwoo.claudeboard.domain.SessionState;
import dev.hyunwoo.claudeboard.domain.TranscriptInfo.RecordKind;

import java.time.Duration;
import java.time.Instant;

/**
 * 상태 판별. <b>이 도구의 가치 전부가 여기 걸려 있다.</b>
 * docs/01-데이터.md "상태 판별 규칙", docs/05-검증.md 1번.
 *
 * <p>판정은 반드시 {@link #resolve} 한 곳을 통한다. 호출부가 조건을 풀어 쓰면
 * 규칙이 바뀔 때 그 호출부만 조용히 빠진다 — 로그가 필요하면 결과를 받아 호출부에서 남긴다.
 *
 * <p>시각은 주입받는다({@code now}). 그래야 임계 판정을 테스트에서 결정적으로 검사할 수 있다.
 *
 * <p>Spring 에 의존하지 않는 순수 자바다.
 */
public final class StateResolver {

    /** 마지막이 tool_result 인데 이만큼 조용하면 멈춤 의심. */
    private final Duration stalledAfter;

    /** 이만큼 조용하면 유휴. */
    private final Duration idleAfter;

    public StateResolver(Duration stalledAfter, Duration idleAfter) {
        this.stalledAfter = stalledAfter;
        this.idleAfter = idleAfter;
    }

    /** docs/01-데이터.md 의 임계값 초안 — 검증 후 조정할 것. */
    public static StateResolver withDefaults() {
        return new StateResolver(Duration.ofMinutes(10), Duration.ofHours(2));
    }

    /**
     * 상태를 판정한다.
     *
     * @param lastRecordKind 세션 기록 끝에서 찾은 마지막 대화 레코드의 종류
     * @param lastActivityAt 그 레코드의 시각. null 이면 임계 판정을 하지 않는다
     * @param alive          {@code claude agents --json} 에 pid 가 있는가
     * @param now            현재 시각 (주입)
     */
    public SessionState resolve(RecordKind lastRecordKind, Instant lastActivityAt, boolean alive, Instant now) {
        // pid 가 없으면 기록만 남은 세션이다. 내용과 무관하게 종료.
        if (!alive) {
            return SessionState.ENDED;
        }

        Duration quiet = quietFor(lastActivityAt, now);

        // 마지막이 assistant + text 면 사용자 차례다 — 답변 대기.
        // tool_use 로 끝난 assistant 는 여기 걸리면 안 된다 (아래 진행 중으로 간다).
        // 오래 조용해도 "기다리는 중"인 것은 변하지 않으므로 idle 로 내리지 않는다.
        // 이게 이 도구에서 가장 높은 가치를 갖는 상태다 (docs/00-개요.md 목표 2).
        if (lastRecordKind == RecordKind.ASSISTANT) {
            return SessionState.WAITING;
        }

        // 진행 중인데 오래 조용하다 = 다음 턴이 안 오고 있다 — 멈춤 의심.
        //
        // 두 형태가 "진행 중"이다:
        //   TOOL_RESULT        도구 결과가 돌아왔고 다음 턴을 기다림 (type: "user" 로 기록된다)
        //   ASSISTANT_TOOL_USE 도구를 호출하고 결과를 기다림
        // 앞의 것을 사용자 입력으로, 뒤의 것을 답변 완료로 오인하면
        // "작업 중"과 "답변 대기"가 정확히 뒤바뀐다.
        // docs/05-검증.md 단위테스트 ②가 지키는 지점이다.
        if (isInProgress(lastRecordKind)
                && quiet != null && quiet.compareTo(stalledAfter) > 0) {
            return SessionState.STALLED;
        }

        // 살아있는데 아주 오래 조용하면 유휴.
        if (quiet != null && quiet.compareTo(idleAfter) > 0) {
            return SessionState.IDLE;
        }

        // 대화 레코드를 못 찾았는데 살아있다 = 갓 시작했거나 상한에 걸린 부분 결과.
        // 작업 중으로 본다 — 살아있다는 근거(pid)가 있으므로.
        return SessionState.WORKING;
    }

    /**
     * 도구가 도는 중인가 — 사용자를 기다리는 것이 아니라 진행 중인가.
     *
     * <p>판정은 이 헬퍼 한 곳을 통한다. 호출부가 조건을 풀어 쓰면
     * 새 종류를 추가할 때 그 호출부만 조용히 빠진다.
     */
    private static boolean isInProgress(RecordKind kind) {
        return kind == RecordKind.TOOL_RESULT || kind == RecordKind.ASSISTANT_TOOL_USE;
    }

    /** 마지막 활동 이후 얼마나 조용한가. 시각을 모르면 null (임계 판정을 건너뛴다). */
    private static Duration quietFor(Instant lastActivityAt, Instant now) {
        if (lastActivityAt == null) {
            return null;
        }
        Duration d = Duration.between(lastActivityAt, now);
        // 시계 어긋남으로 음수가 나오면 방금 활동한 것으로 본다.
        return d.isNegative() ? Duration.ZERO : d;
    }
}
