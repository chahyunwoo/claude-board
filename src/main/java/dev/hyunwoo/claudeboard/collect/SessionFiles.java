package dev.hyunwoo.claudeboard.collect;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code ~/.claude/projects/} 아래에서 세션 기록 파일을 찾는다.
 *
 * <p><b>경로 인코딩 규칙에 의존하지 않는다.</b> {@code sessionId} 로 글롭 탐색한다 —
 * 하이픈 치환 규칙이 바뀌어도 안전하다. docs/01-데이터.md "소스 B".
 *
 * <p>Spring 에 의존하지 않는 순수 자바다.
 */
public final class SessionFiles {

    private final Path projectsRoot;

    public SessionFiles() {
        this(Path.of(System.getProperty("user.home"), ".claude", "projects"));
    }

    public SessionFiles(Path projectsRoot) {
        this.projectsRoot = projectsRoot;
    }

    /**
     * 모든 세션 기록 파일. {@code sessionId -> 파일 경로}.
     *
     * <p>같은 sessionId 가 여러 디렉터리에 있으면 가장 최근에 수정된 것을 쓴다
     * (worktree 등으로 경로가 갈릴 수 있다).
     */
    public Map<String, Path> bySessionId() throws IOException {
        Map<String, Path> found = new HashMap<>();
        if (!Files.isDirectory(projectsRoot)) {
            return found;
        }

        try (Stream<Path> stream = Files.walk(projectsRoot, 2)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .filter(Files::isRegularFile)
                    .forEach(p -> {
                        String id = stripExtension(p.getFileName().toString());
                        found.merge(id, p, SessionFiles::newerOf);
                    });
        }
        return found;
    }

    /**
     * 프로젝트 디렉터리별 세션 파일 목록 (시각 오름차순).
     *
     * <p>세션 <b>순번</b>을 매기는 근거다 — 그 프로젝트 기록을 시각순 정렬했을 때의 index.
     */
    public Map<Path, List<Path>> byProjectDir() throws IOException {
        Map<Path, List<Path>> grouped = new HashMap<>();
        if (!Files.isDirectory(projectsRoot)) {
            return grouped;
        }

        try (Stream<Path> dirs = Files.list(projectsRoot)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                try (Stream<Path> files = Files.list(dir)) {
                    List<Path> sessions = new ArrayList<>(files
                            .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                            .filter(Files::isRegularFile)
                            .toList());
                    sessions.sort(Comparator.comparingLong(SessionFiles::modifiedAt));
                    if (!sessions.isEmpty()) {
                        grouped.put(dir, sessions);
                    }
                }
            }
        }
        return grouped;
    }

    private static Path newerOf(Path a, Path b) {
        return modifiedAt(a) >= modifiedAt(b) ? a : b;
    }

    static long modifiedAt(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
