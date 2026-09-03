package dev.hyunwoo.claudeboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

/**
 * 진입점.
 *
 * <p>{@code --json} 이면 웹 서버를 띄우지 않고 수집 결과를 표준출력에 찍고 끝낸다 —
 * docs/05-검증.md 의 1~3번이 이 모드를 쓴다.
 */
@SpringBootApplication
public class ClaudeBoardApplication {

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--json")) {
            System.exit(CliRunner.run());
        }
        SpringApplication.run(ClaudeBoardApplication.class, args);
    }
}
