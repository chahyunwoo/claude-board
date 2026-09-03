# claude-board

Claude Code 세션을 한 화면에서 보는 **로컬 전용** 대시보드.

착수 전 [docs/00-개요.md](docs/00-개요.md) 의 **결정된 사항**과
[docs/06-개발환경.md](docs/06-개발환경.md) 를 확인할 것.

**JDK 함정**: `java -version` 이 실패해도 설치가 안 된 게 아닐 수 있다 —
Homebrew `openjdk@21` 은 keg-only 라 PATH 에 안 잡힌다. 06-개발환경 참고.

**훅 설치**: 클론 후 `git config core.hooksPath .githooks` (되돌리기 검증 잔재 차단).

## 절대 원칙

**데이터는 이 기계를 떠나지 않는다.**
중앙 서버·계정·외부 전송 없음. 백엔드는 `127.0.0.1` 에만 바인딩한다.
세션 기록에는 프롬프트 전문·절대 경로·브랜치명·PR 정보가 들어 있다.

## 이 프로젝트에서 "되돌리기 어렵거나 조용히 번지는" 작업

전역 `model-selection.md` 의 기준을 이 도메인에 구체화한 것.
아래에 해당하면 서브에이전트를 **Opus** 로 배정한다.

| 작업 | 이유 |
|---|---|
| **바인딩 주소 변경** | `0.0.0.0` 으로 새면 세션 기록이 네트워크에 노출된다 |
| **상태 판별 로직** (`collect/StateResolver.java`) | 틀리면 "답변 대기"와 "작업 중"이 뒤바뀌어 도구 전체가 거짓말을 한다 |
| **블록 판별** (`collect/TranscriptReader.java` 의 `hasBlock`) | `tool_use`/`tool_result` 를 놓치면 위와 같은 결과가 된다. 실측으로 이미 한 번 났다 |
| **역순 리더** (`collect/TranscriptReader.java`) | 전체 파싱으로 퇴행하면 갱신마다 수백 ms 가 날아가고 체감이 무너진다 |
| **SSE emitter 정리** (`web/StreamController.java`) | 누수는 예외 없이 조용히 쌓인다. 콜백 셋을 다 걸어도 안 막힌다 — 실측으로 한 번 났다 ([docs/02-백엔드.md](docs/02-백엔드.md)) |
| **배포 패키징** | 세션 데이터가 jar 에 섞여 공개되면 되돌릴 수 없다 |

단순 조회·포맷팅·스타일 작업은 Sonnet.

## 검증

[docs/05-검증.md](docs/05-검증.md) 를 따른다.
특히 **상태 판별 대조**와 **바인딩 경계 확인**은 생략하지 않는다.

## 스택

```
백엔드   Spring Boot 3 · Java 21 · Gradle · JUnit5
프론트   Vue 3 · Vite · TypeScript
```

`collect/` 는 Spring 에 의존하지 않는 순수 자바로 둔다.

## 커밋·PR

- 커밋 형식: `[#이슈번호] 한글 요약` (예: `[#1] collect 구현`)
- **AI attribution 을 넣지 않는다** (전역 `git-workflow.md`)
- PR 본문에 **실행한 명령과 그 출력**을 남긴다 ([docs/05-검증.md](docs/05-검증.md))
- **머지 전에 "머지할까요?" 라고 묻는다.** 권한은 있다 — CI 가 초록이면
  물어보고 `gh pr merge <N> --squash --delete-branch` 로 머지한다.
- **보호 브랜치를 직접 움직이지 않는다.** `main` 은 보호돼 있고 `ci` 통과가 필수다.
  특히 **게이트 작동을 확인한다며 push 해보지 말 것** — `--dry-run` 은 클라이언트 측이라
  서버 훅 판정을 안 탄다. 실측으로 PR 이 머지된 사고가 있었다
  ([docs/06-개발환경.md](docs/06-개발환경.md)). 검사가 필요하면 CI 를 안 돌린 커밋을
  임시 브랜치에서 밀어 거부되는지 본다.

## 브랜치

**`main` + `feature/*`.** `dev`·`stg` 는 두지 않는다.

근거: 작업협약 6번은 표준 계층을 `main ← stg ← dev ← feature/*` 로 두되
**"기여자가 1명이고 feature 가 동시에 진행되지 않으면 `dev` 는 병합만 늘리고
얻는 게 없다"** 고 명시한다. 이 저장소는 개인 도구이고 기여자 1명이므로
중간 단계를 생략한다. 빠뜨린 것이 아니라 의도한 생략이다.
