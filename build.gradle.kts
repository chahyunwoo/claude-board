plugins {
    java
    id("org.springframework.boot") version "3.5.0"
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
    testImplementation("org.assertj:assertj-core")
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
