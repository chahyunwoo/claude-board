package dev.hyunwoo.claudeboard.collect;

import dev.hyunwoo.claudeboard.domain.SessionState;
import dev.hyunwoo.claudeboard.domain.TranscriptInfo.RecordKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상태 판별 테스트. docs/05-검증.md 1번 "반드시 넣을 단위 테스트".
 *
 * <p>시각을 주입하므로 임계 판정이 결정적이다 — {@code Instant.now()} 에 의존하지 않는다.
 */
class StateResolverTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    private final StateResolver resolver = StateResolver.withDefaults();

    private static Instant minutesAgo(long m) {
        return NOW.minus(Duration.ofMinutes(m));
    }

    // ── ① 마지막이 assistant → WAITING ─────────────────────────────────

    @Test
    void 마지막이_assistant_면_답변_대기다() {
        SessionState state = resolver.resolve(RecordKind.ASSISTANT, minutesAgo(1), NOW);

        assertThat(state).isEqualTo(SessionState.WAITING);
    }

    @Test
    void 답변_대기는_오래_조용해도_유휴로_내려가지_않는다() {
        // 기다리는 중인 것은 시간이 지나도 변하지 않는다. 이게 이 도구의 최고 가치 상태다.
        SessionState state = resolver.resolve(RecordKind.ASSISTANT, minutesAgo(60 * 24 * 27), NOW);

        assertThat(state).isEqualTo(SessionState.WAITING);
    }

    // ── ② 마지막이 tool_result → WORKING (뒤집히면 안 된다) ────────────

    /**
     * <b>이 테스트가 이 클래스에서 가장 중요하다.</b>
     *
     * <p>도구 결과는 {@code type: "user"} 로 기록된다. 이걸 사용자 입력으로 오인하면
     * "작업 중"과 "답변 대기"가 정확히 반대로 나온다.
     */
    @Test
    void 마지막이_tool_result_이고_최근이면_작업_중이지_답변_대기가_아니다() {
        SessionState state = resolver.resolve(RecordKind.TOOL_RESULT, minutesAgo(1), NOW);

        assertThat(state)
                .as("도구 결과를 사용자 입력으로 오인하면 상태가 정확히 뒤집힌다")
                .isEqualTo(SessionState.WORKING)
                .isNotEqualTo(SessionState.WAITING);
    }

    /**
     * 실측(2026-09-03)으로 드러난 케이스. 살아있는 세션 13개 중 2개가 이 형태였고
     * 둘 다 실제로 작업 중이었는데, 초안 규칙(마지막=assistant → 답변 대기)은
     * 이 둘을 "답변 대기"로 오보했다.
     */
    @Test
    void 마지막이_assistant_인데_tool_use_로_끝나면_작업_중이지_답변_대기가_아니다() {
        SessionState state = resolver.resolve(RecordKind.ASSISTANT_TOOL_USE, minutesAgo(1), NOW);

        assertThat(state)
                .as("도구 호출 후 결과를 기다리는 중이다 — 사용자를 기다리는 게 아니다")
                .isEqualTo(SessionState.WORKING)
                .isNotEqualTo(SessionState.WAITING);
    }

    @Test
    void 도구_호출_뒤_오래_조용하면_멈춤_의심이다() {
        SessionState state = resolver.resolve(RecordKind.ASSISTANT_TOOL_USE, minutesAgo(11), NOW);

        assertThat(state).isEqualTo(SessionState.STALLED);
    }

    @Test
    void 마지막이_tool_result_인데_임계를_넘으면_멈춤_의심이다() {
        SessionState state = resolver.resolve(RecordKind.TOOL_RESULT, minutesAgo(11), NOW);

        assertThat(state).isEqualTo(SessionState.STALLED);
    }

    @Test
    void 멈춤_임계_직전에는_아직_작업_중이다() {
        // 경계: 10분 임계, 9분 경과 → 아직 WORKING
        SessionState state = resolver.resolve(RecordKind.TOOL_RESULT, minutesAgo(9), NOW);

        assertThat(state).isEqualTo(SessionState.WORKING);
    }

    // ── ③ 마지막이 user 순수 텍스트 → WORKING ──────────────────────────

    @Test
    void 마지막이_사용자_입력이면_작업_중이다() {
        SessionState state = resolver.resolve(RecordKind.USER, minutesAgo(1), NOW);

        assertThat(state).isEqualTo(SessionState.WORKING);
    }

    /**
     * 사용자 입력 뒤 오래 조용한 것과 도구 결과 뒤 오래 조용한 것은 다르다.
     *
     * <p>도구 결과 뒤 정적은 "다음 턴이 안 온다"(멈춤 의심)이지만,
     * 사용자 입력 뒤 오래 조용한 것은 그냥 유휴다.
     */
    @Test
    void 사용자_입력_뒤_오래_조용하면_멈춤이_아니라_유휴다() {
        SessionState state = resolver.resolve(RecordKind.USER, minutesAgo(60 * 3), NOW);

        assertThat(state)
                .isEqualTo(SessionState.IDLE)
                .isNotEqualTo(SessionState.STALLED);
    }

    // ── 경계·이상값 ────────────────────────────────────────────────────

    @Test
    void 대화_레코드를_못_찾았는데_살아있으면_작업_중으로_본다() {
        // 갓 시작했거나 상한에 걸린 부분 결과. pid 라는 근거가 있다.
        SessionState state = resolver.resolve(RecordKind.NONE, null, NOW);

        assertThat(state).isEqualTo(SessionState.WORKING);
    }

    @Test
    void 활동_시각을_모르면_임계_판정을_건너뛴다() {
        // 시각이 없다고 STALLED/IDLE 로 넘기면 안 된다 — 모르는 것과 오래된 것은 다르다.
        assertThat(resolver.resolve(RecordKind.TOOL_RESULT, null, NOW))
                .isEqualTo(SessionState.WORKING);
    }

    @Test
    void 시계가_어긋나_미래_시각이어도_방금_활동한_것으로_본다() {
        Instant future = NOW.plus(Duration.ofMinutes(30));

        assertThat(resolver.resolve(RecordKind.TOOL_RESULT, future, NOW))
                .isEqualTo(SessionState.WORKING);
    }

    @Test
    void 정렬_순서는_답변_대기가_가장_앞이다() {
        assertThat(SessionState.WAITING.sortOrder()).isEqualTo(1);
        assertThat(SessionState.WAITING.sortOrder())
                .isLessThan(SessionState.STALLED.sortOrder())
                .isLessThan(SessionState.WORKING.sortOrder())
                .isLessThan(SessionState.IDLE.sortOrder());
    }
}
