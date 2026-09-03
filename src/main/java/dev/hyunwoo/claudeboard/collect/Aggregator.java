package dev.hyunwoo.claudeboard.collect;

import dev.hyunwoo.claudeboard.domain.AgentInfo;
import dev.hyunwoo.claudeboard.domain.BoardSnapshot;
import dev.hyunwoo.claudeboard.domain.Project;
import dev.hyunwoo.claudeboard.domain.Session;
import dev.hyunwoo.claudeboard.domain.SessionState;
import dev.hyunwoo.claudeboard.domain.TranscriptInfo;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 소스 A(살아있는 세션)와 소스 B(세션 기록)를 이어 프로젝트 단위로 집계한다.
 *
 * <p><b>세션이 아니라 프로젝트가 1급 단위다</b> — 한 프로젝트에 세션이 계속 이어진다.
 * docs/01-데이터.md "프로젝트 단위 집계".
 *
 * <p>Spring 에 의존하지 않는 순수 자바다.
 */
public final class Aggregator {

    private final AgentsReader agentsReader;
    private final TranscriptReader transcriptReader;
    private final StateResolver stateResolver;
    private final SessionFiles sessionFiles;
    private final long defaultContextLimit;

    public Aggregator(AgentsReader agentsReader,
                      TranscriptReader transcriptReader,
                      StateResolver stateResolver,
                      SessionFiles sessionFiles,
                      long defaultContextLimit) {
        this.agentsReader = agentsReader;
        this.transcriptReader = transcriptReader;
        this.stateResolver = stateResolver;
        this.sessionFiles = sessionFiles;
        this.defaultContextLimit = defaultContextLimit;
    }

    public static Aggregator withDefaults() {
        return new Aggregator(new AgentsReader(), new TranscriptReader(),
                StateResolver.withDefaults(), new SessionFiles(), 1_000_000L);
    }

    /**
     * 한 번 수집한다.
     *
     * <p>실패는 {@code errors} 로 <b>반드시 노출</b>한다 — 조용히 삼키면
     * "세션이 없다"와 "읽지 못했다"가 구별되지 않는다. docs/02-백엔드.md.
     */
    public BoardSnapshot collect(Instant now) {
        long start = System.nanoTime();
        List<String> errors = new ArrayList<>();

        List<AgentInfo> agents = readAgents(errors);
        Map<String, Path> files = readSessionFiles(errors);
        Map<Path, Integer> sessionCounts = readSessionCounts(errors);

        // cwd 로 묶는다. 입력 순서를 유지해 결과가 흔들리지 않게 한다.
        Map<String, List<Session>> byCwd = new LinkedHashMap<>();
        for (AgentInfo agent : agents) {
            Session session = toSession(agent, files.get(agent.sessionId()), now, errors);
            byCwd.computeIfAbsent(nullSafeCwd(agent), k -> new ArrayList<>()).add(session);
        }

        List<Project> projects = new ArrayList<>();
        for (Map.Entry<String, List<Session>> entry : byCwd.entrySet()) {
            projects.add(toProject(entry.getKey(), entry.getValue(), files, sessionCounts));
        }

        // 답변 대기가 최상단. 그 다음은 마지막 활동이 최근인 순.
        projects.sort(Comparator
                .comparingInt((Project p) -> p.current().state().sortOrder())
                .thenComparing(p -> p.current().lastActivityAt(),
                        Comparator.nullsLast(Comparator.reverseOrder())));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        return new BoardSnapshot(now, elapsedMs, projects, countByState(projects), errors);
    }

    private List<AgentInfo> readAgents(List<String> errors) {
        try {
            return agentsReader.read();
        } catch (IOException e) {
            errors.add("agents 목록을 읽지 못함: " + e.getMessage());
            return List.of();
        }
    }

    private Map<String, Path> readSessionFiles(List<String> errors) {
        try {
            return sessionFiles.bySessionId();
        } catch (IOException e) {
            errors.add("세션 기록 디렉터리를 훑지 못함: " + e.getMessage());
            return Map.of();
        }
    }

    /** 프로젝트 디렉터리별 기록 파일 개수 (종료된 세션 포함). */
    private Map<Path, Integer> readSessionCounts(List<String> errors) {
        try {
            Map<Path, Integer> counts = new LinkedHashMap<>();
            sessionFiles.byProjectDir().forEach((dir, list) -> counts.put(dir, list.size()));
            return counts;
        } catch (IOException e) {
            errors.add("세션 개수를 세지 못함: " + e.getMessage());
            return Map.of();
        }
    }

    /** 살아있는 세션 하나를 기록과 이어 붙인다. */
    private Session toSession(AgentInfo agent, Path file, Instant now, List<String> errors) {
        TranscriptInfo info = TranscriptInfo.empty();
        if (file == null) {
            errors.add("세션 기록을 찾지 못함: " + agent.sessionId());
        } else {
            try {
                info = transcriptReader.read(file);
            } catch (IOException e) {
                errors.add("세션 기록을 읽지 못함 (" + agent.sessionId() + "): " + e.getMessage());
            }
        }

        boolean alive = agent.pid() > 0;
        SessionState state = stateResolver.resolve(
                info.lastRecordKind(), info.lastActivityAt(), alive, now);

        Long limit = contextLimitFor(info.contextTokens());
        Double ratio = (info.contextTokens() == null || limit == null || limit == 0)
                ? null
                : (double) info.contextTokens() / limit;

        return new Session(
                agent.sessionId(),
                alive ? agent.pid() : null,
                state,
                info.aiTitle(),
                info.lastPrompt(),
                info.branch(),
                info.permissionMode(),
                info.model(),
                info.contextTokens(),
                limit,
                ratio,
                info.lastActivityAt(),
                info.startedAt() != null ? info.startedAt() : agent.startedAt(),
                0);
    }

    /**
     * 컨텍스트 상한. <b>관측값이 기본 상한을 넘으면 자동으로 올린다.</b>
     *
     * <p>세션 기록의 모델명은 {@code claude-opus-5} 로만 남아 {@code [1m]} 변형이
     * 구분되지 않는다 (실측: 671,229 토큰이 관측됐으나 기록상 모델명은 동일).
     * docs/00-개요.md 결정사항 3, docs/02-백엔드.md "컨텍스트 상한 — 주의".
     */
    private Long contextLimitFor(Long observed) {
        if (observed == null) {
            return defaultContextLimit;
        }
        return Math.max(defaultContextLimit, observed);
    }

    /**
     * 한 프로젝트의 세션들을 묶는다.
     *
     * <p>여러 세션이 동시에 살아있을 수 있다 — <b>가장 최근 것을 현재로</b> 삼고 나머지는 접는다.
     */
    private Project toProject(String cwd,
                              List<Session> sessions,
                              Map<String, Path> files,
                              Map<Path, Integer> sessionCounts) {
        List<Session> sorted = new ArrayList<>(sessions);
        sorted.sort(Comparator.comparing(Session::lastActivityAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        Session current = sorted.get(0);
        List<Session> others = sorted.subList(1, sorted.size());

        // 세션 순번 — 그 프로젝트 기록을 시각순 정렬했을 때의 위치.
        Path dir = dirOf(files.get(current.sessionId()));
        int count = dir != null ? sessionCounts.getOrDefault(dir, sessions.size()) : sessions.size();

        return new Project(cwd, nameOf(cwd), withOrdinal(current, count), List.copyOf(others), count);
    }

    /** 현재 세션의 순번은 그 프로젝트의 마지막 세션이므로 총 개수와 같다. */
    private static Session withOrdinal(Session s, int count) {
        return new Session(s.sessionId(), s.pid(), s.state(), s.title(), s.lastPrompt(),
                s.branch(), s.permissionMode(), s.model(), s.contextTokens(), s.contextLimit(),
                s.contextRatio(), s.lastActivityAt(), s.startedAt(), count);
    }

    private static Path dirOf(Path file) {
        return file == null ? null : file.getParent();
    }

    private static String nullSafeCwd(AgentInfo agent) {
        return agent.cwd() != null ? agent.cwd() : "(알 수 없음)";
    }

    /** 경로의 마지막 조각을 프로젝트 이름으로 쓴다. */
    static String nameOf(String cwd) {
        if (cwd == null || cwd.isBlank()) {
            return "(알 수 없음)";
        }
        Path p = Path.of(cwd).getFileName();
        return p == null ? cwd : p.toString();
    }

    /** 상태별 개수. 0인 상태도 키를 남겨 화면이 흔들리지 않게 한다. */
    private static Map<String, Integer> countByState(List<Project> projects) {
        Map<String, Integer> counts = new TreeMap<>();
        for (SessionState state : SessionState.values()) {
            counts.put(state.name().toLowerCase(), 0);
        }
        for (Project p : projects) {
            bump(counts, p.current());
            p.others().forEach(s -> bump(counts, s));
        }
        return counts;
    }

    private static void bump(Map<String, Integer> counts, Session s) {
        counts.merge(s.state().name().toLowerCase(), 1, Integer::sum);
    }
}
