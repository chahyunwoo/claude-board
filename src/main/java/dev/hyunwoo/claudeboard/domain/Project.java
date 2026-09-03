package dev.hyunwoo.claudeboard.domain;

import java.util.List;

/**
 * 프로젝트 한 건. 세션이 아니라 <b>프로젝트가 1급 단위</b>다 — 한 프로젝트에 세션이 계속 이어진다.
 * docs/01-데이터.md "프로젝트 단위 집계" 참고.
 *
 * @param current 살아있는 세션 중 마지막 활동이 가장 최근인 것
 * @param others  나머지 살아있는 세션들 (접어서 표시)
 * @param sessionCount 그 프로젝트의 기록 파일 총 개수 (종료된 것 포함)
 */
public record Project(
        String cwd,
        String name,
        Session current,
        List<Session> others,
        int sessionCount) {
}
