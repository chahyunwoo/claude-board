package dev.hyunwoo.claudeboard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;

/**
 * 정적 리소스와 그 밖의 웹 설정.
 *
 * <p>정적 파일 자체는 Spring Boot 기본 동작({@code classpath:/static/})이 이미 서빙한다 —
 * 여기서 다시 매핑하면 그 기본값을 덮어써서 얻는 것 없이 깨질 여지만 는다.
 * 필요한 것은 <b>SPA 진입점 하나</b>뿐이다.
 *
 * <p><b>바인딩 주소는 여기서 정하지 않는다.</b> {@code application.yml} 의
 * {@code server.address: 127.0.0.1} 이 유일한 출처다 — 두 곳에서 정하면 어느 쪽이 이겼는지
 * 알 수 없게 되고, CI 가 검사하는 것도 그 파일이다. docs/00-개요.md
 * "데이터는 이 기계를 떠나지 않는다".
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 루트를 {@code index.html} 로 넘긴다.
     *
     * <p>{@code frontend/} 빌드 결과가 {@code static/} 에 들어오기 전에는 404 가 나는데,
     * 그게 맞는 동작이다 — 없는 것을 있는 척하지 않는다. API 는 그와 무관하게 뜬다.
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    /**
     * 시각을 Bean 으로 둔다 — 테스트가 시각을 고정할 수 있어야 상태 판별을 검증할 수 있다.
     * {@code collect/StateResolver} 가 {@code now} 를 주입받는 것과 같은 이유다.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
