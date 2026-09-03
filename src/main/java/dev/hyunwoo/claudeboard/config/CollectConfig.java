package dev.hyunwoo.claudeboard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hyunwoo.claudeboard.collect.AgentsReader;
import dev.hyunwoo.claudeboard.collect.Aggregator;
import dev.hyunwoo.claudeboard.collect.SessionFiles;
import dev.hyunwoo.claudeboard.collect.StateResolver;
import dev.hyunwoo.claudeboard.collect.TranscriptReader;
import dev.hyunwoo.claudeboard.service.SnapshotSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code collect/} 를 Bean 으로 조립한다.
 *
 * <p><b>조립만 여기서 한다.</b> {@code collect/} 자체는 Spring 을 모르는 순수 자바로 두어
 * 단위 테스트가 빠르고 다른 곳으로 옮겨도 그대로 가게 한다. docs/02-백엔드.md.
 *
 * <p>설정값은 전부 {@link BoardProperties} 에서 온다 — 코드에 상수를 따로 두면
 * 설정을 바꿔도 아무 일이 안 일어나는 죽은 설정이 생긴다.
 */
@Configuration
public class CollectConfig {

    /** {@code collect/} 와 {@code service/} 를 잇는다. 메서드 참조 한 줄이면 된다. */
    @Bean
    SnapshotSource snapshotSource(Aggregator aggregator) {
        return aggregator::collect;
    }

    @Bean
    Aggregator aggregator(BoardProperties props, ObjectMapper mapper) {
        return new Aggregator(
                new AgentsReader(),
                new TranscriptReader(mapper, props.transcriptMaxBytes().toBytes()),
                new StateResolver(props.stalledAfter(), props.idleAfter()),
                new SessionFiles(),
                props.contextLimit());
    }
}
