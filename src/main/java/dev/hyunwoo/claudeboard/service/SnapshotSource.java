package dev.hyunwoo.claudeboard.service;

import dev.hyunwoo.claudeboard.domain.BoardSnapshot;

import java.time.Instant;

/**
 * 한 번 수집해 스냅샷을 내는 것. 실제 구현은 {@code collect/Aggregator} 다.
 *
 * <p>{@link BoardService} 가 {@code Aggregator} 를 직접 붙들지 않는 이유는
 * <b>{@code collect/} 를 Spring 무관한 순수 자바로 유지하기 위해서</b>다
 * (docs/02-백엔드.md) — {@code Aggregator} 에 인터페이스를 붙이거나 {@code final} 을
 * 떼는 대신, 서비스 쪽에서 필요한 모양만 선언한다. 배선은
 * {@code CollectConfig} 에서 메서드 참조로 이어진다.
 */
@FunctionalInterface
public interface SnapshotSource {

    BoardSnapshot collect(Instant now);
}
