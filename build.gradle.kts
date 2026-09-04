plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.hyunwoo"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// deprecated API 를 **이름까지** 보여준다 (#30).
//
// 기본값은 "uses or overrides a deprecated API" 라는 요약만 내서,
// Spring Boot 를 올렸을 때 무엇이 deprecated 인지 알 수 없었다 —
// 실측으로 Jackson 의 setSerializationInclusion 이었고, 켜야 이름이 나왔다.
// CI 로그의 경고를 읽으라는 docs/06-개발환경.md 항목과 같은 취지다.
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // 벤치마크는 -Dbenchmark=true 로만 켠다 (이 기계의 실제 세션 기록을 읽는다)
    systemProperty("benchmark", System.getProperty("benchmark") ?: "false")
    testLogging {
        showStandardStreams = true
    }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "claude-board.jar"
    // 세션 데이터가 jar 에 섞이지 않도록 — docs/04-배포.md
    exclude("**/*.jsonl", "**/*.log")
}
