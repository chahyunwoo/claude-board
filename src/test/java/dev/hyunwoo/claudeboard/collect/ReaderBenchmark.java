package dev.hyunwoo.claudeboard.collect;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hyunwoo.claudeboard.domain.TranscriptInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 실제 세션 기록으로 역순 리더의 성능과 정보 손실을 잰다.
 * docs/05-검증.md 2번 "성능".
 *
 * <p><b>이 기계의 실제 데이터를 읽으므로 기본적으로 꺼져 있다.</b>
 * CI 나 다른 기계에서는 데이터가 없거나 달라 결과가 무의미하다.
 *
 * <p>실행:
 * <pre>{@code ./gradlew test --tests '*ReaderBenchmark*' -Dbenchmark=true -i}</pre>
 *
 * <p>{@link TranscriptReader#DEFAULT_MAX_BYTES} 의 값은 이 벤치마크로 골랐다 —
 * 수치를 바꾸려면 여기를 돌려 표를 다시 뽑고 그 근거를 javadoc 에 남긴다.
 */
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class ReaderBenchmark {

    private static final Path ROOT =
            Path.of(System.getProperty("user.home"), ".claude", "projects");

    private static List<Path> transcripts() throws Exception {
        try (Stream<Path> s = Files.walk(ROOT, 2)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .filter(Files::isRegularFile)
                    .toList();
        }
    }

    /**
     * 상한별 소요 시간과 정보 손실. 이 표가 {@code DEFAULT_MAX_BYTES} 선택의 근거다.
     */
    @Test
    void 상한별_시간과_정보손실() throws Exception {
        List<Path> files = transcripts();
        long totalBytes = 0;
        for (Path p : files) {
            totalBytes += Files.size(p);
        }

        System.out.printf("파일 %d개, 총 %d MB%n", files.size(), totalBytes / 1024 / 1024);
        System.out.printf("%8s %8s %10s %10s %10s%n",
                "상한", "시간", "제목없음", "발화없음", "부분결과");

        for (long cap : new long[]{64, 128, 256, 512, 1024}) {
            long capBytes = cap * 1024;
            TranscriptReader reader = new TranscriptReader(new ObjectMapper(), capBytes);

            for (Path p : files) {
                reader.read(p);   // 워밍업 — 페이지 캐시와 JIT 를 데운다
            }

            long start = System.nanoTime();
            int noTitle = 0;
            int noPrompt = 0;
            int truncated = 0;
            for (Path p : files) {
                TranscriptInfo info = reader.read(p);
                if (info.aiTitle() == null) {
                    noTitle++;
                }
                if (info.lastPrompt() == null) {
                    noPrompt++;
                }
                if (info.truncated()) {
                    truncated++;
                }
            }
            long ms = (System.nanoTime() - start) / 1_000_000;

            System.out.printf("%7dK %7dms %10d %10d %10d%n",
                    cap, ms, noTitle, noPrompt, truncated);
        }
    }

    /**
     * 역순 리더 vs 전체 파싱. docs/05-검증.md 는 "10배 이상 차이가 나야 정상"이라고 한다.
     *
     * <p>비교 대상은 {@code cat} 이 아니라 <b>실제 파싱</b>이어야 공정하다 —
     * 파일을 바이트로만 읽으면 OS 페이지 캐시 덕에 비현실적으로 빠르게 나온다.
     */
    @Test
    void 역순_리더가_전체_파싱보다_빠른가() throws Exception {
        List<Path> files = transcripts();
        TranscriptReader reader = new TranscriptReader();
        ObjectMapper mapper = new ObjectMapper();

        for (Path p : files) {
            reader.read(p);
        }

        long start = System.nanoTime();
        for (Path p : files) {
            reader.read(p);
        }
        long reverseMs = (System.nanoTime() - start) / 1_000_000;

        start = System.nanoTime();
        long lines = 0;
        for (Path p : files) {
            try (Stream<String> s = Files.lines(p)) {
                for (String line : (Iterable<String>) s::iterator) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        mapper.readTree(line);
                        lines++;
                    } catch (Exception ignored) {
                        // 깨진 줄은 세지 않는다
                    }
                }
            }
        }
        long fullMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("역순 리더:      %5dms%n", reverseMs);
        System.out.printf("전체 파싱(%d줄): %5dms%n", lines, fullMs);
        System.out.printf("배수:           %.1f배%n", (double) fullMs / Math.max(reverseMs, 1));
    }
}
