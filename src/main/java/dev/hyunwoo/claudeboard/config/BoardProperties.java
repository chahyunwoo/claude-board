package dev.hyunwoo.claudeboard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DataSizeUnit;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.util.unit.DataSize;
import org.springframework.util.unit.DataUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * {@code claude-board.*} 설정. application.yml 과 1:1 대응한다.
 *
 * <p>기본값을 여기에 둔 이유는 <b>설정이 죽어 있는 것을 막기 위해서</b>다 —
 * 코드에 상수를 따로 두고 {@code max(상수, 설정)} 처럼 쓰면 상수보다 작은 설정이
 * 조용히 무시된다. 값은 이 한 곳에서만 온다.
 *
 * @param interval           수집 주기
 * @param stalledAfter       멈춤 의심 임계
 * @param idleAfter          유휴 임계
 * @param contextLimit       컨텍스트 상한 기본값. 관측값이 넘으면 자동 상향된다
 * @param transcriptMaxBytes 역순 읽기 상한
 */
@ConfigurationProperties(prefix = "claude-board")
public record BoardProperties(
        @DurationUnit(ChronoUnit.SECONDS) Duration interval,
        @DurationUnit(ChronoUnit.MINUTES) Duration stalledAfter,
        @DurationUnit(ChronoUnit.MINUTES) Duration idleAfter,
        long contextLimit,
        @DataSizeUnit(DataUnit.BYTES) DataSize transcriptMaxBytes) {

    public BoardProperties {
        interval = interval != null ? interval : Duration.ofSeconds(5);
        stalledAfter = stalledAfter != null ? stalledAfter : Duration.ofMinutes(10);
        idleAfter = idleAfter != null ? idleAfter : Duration.ofHours(2);
        contextLimit = contextLimit > 0 ? contextLimit : 1_000_000L;
        transcriptMaxBytes = transcriptMaxBytes != null
                ? transcriptMaxBytes
                : DataSize.ofKilobytes(512);
    }
}
