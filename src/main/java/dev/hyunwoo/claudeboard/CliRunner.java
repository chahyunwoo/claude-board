package dev.hyunwoo.claudeboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.hyunwoo.claudeboard.collect.Aggregator;
import dev.hyunwoo.claudeboard.domain.BoardSnapshot;

import java.time.Instant;

/**
 * {@code --json} 모드. 웹 서버 없이 한 번 수집해 표준출력에 찍는다.
 * docs/05-검증.md 의 1~3번 검증이 이 출력을 쓴다.
 *
 * <p>수집 실패로 예외가 나도 {@code errors} 가 실린 JSON 을 내보내는 것이 목표다 —
 * 조용히 죽으면 "세션이 없다"와 "읽지 못했다"가 구별되지 않는다.
 */
public final class CliRunner {

    private CliRunner() {
    }

    /** 종료 코드. 0 = 정상, 1 = JSON 조차 내보내지 못함. */
    public static int run() {
        // ⚠️ **null 필드가 JSON 에 남아야 한다.** 프론트의 types.ts 가
        // title·lastPrompt·branch 의 null 을 성실히 표기하고(docs/03-프론트.md),
        // 역순 리더가 상한에 걸리면 실제로 null 이 온다 —
        // 생략되면 "값이 없다"와 "필드가 없다"가 구별되지 않는다.
        //
        // 그것이 Jackson 의 기본 동작(Include.ALWAYS)이라 명시하지 않는다.
        // serializationInclusion 계열은 deprecated 다. 기본값이 바뀌면
        // CliRunnerTest 의 "null 필드가 살아있는가" 검사가 잡는다.
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        try {
            BoardSnapshot snapshot = Aggregator.withDefaults().collect(Instant.now());
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));
            return 0;
        } catch (Exception e) {
            System.err.println("수집 실패: " + e);
            return 1;
        }
    }
}
