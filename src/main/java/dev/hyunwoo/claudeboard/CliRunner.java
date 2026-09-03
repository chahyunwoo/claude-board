package dev.hyunwoo.claudeboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.ALWAYS);

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
