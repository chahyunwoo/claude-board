package dev.hyunwoo.claudeboard.web;

import dev.hyunwoo.claudeboard.domain.BoardSnapshot;
import dev.hyunwoo.claudeboard.service.SnapshotSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/sessions}. docs/02-백엔드.md 의 응답 스키마와 대응한다.
 */
@SpringBootTest(properties = "claude-board.interval=1h")
class BoardControllerTest {

    /** 수집 호출을 세는 스텁. 실제 서브프로세스를 타지 않는다. */
    static final AtomicInteger COLLECTS = new AtomicInteger();

    @TestConfiguration
    static class StubSource {
        @Bean
        @Primary
        SnapshotSource stubSource() {
            return now -> {
                COLLECTS.incrementAndGet();
                return new BoardSnapshot(now, 7, List.of(),
                        Map.of("waiting", 3), List.of("표본 오류"));
            };
        }
    }

    @Autowired
    WebApplicationContext context;

    @Test
    void 응답에_errors_가_반드시_실린다() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

        // errors 를 빠뜨리면 "세션이 없다"와 "읽지 못했다"가 구별되지 않는다.
        // docs/02-백엔드.md.
        mvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").exists())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.counts").exists())
                .andExpect(jsonPath("$.projects").isArray());
    }

    @Test
    void 요청은_수집을_유발하지_않는다() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        int before = COLLECTS.get();

        for (int i = 0; i < 20; i++) {
            mvc.perform(get("/api/sessions")).andExpect(status().isOk());
        }

        // 캐시를 둔 이유 전체가 여기에 있다 — 수집의 ~200ms 가 요청 경로에 들어오면 안 된다.
        assertThat(COLLECTS.get()).isEqualTo(before);
    }
}
