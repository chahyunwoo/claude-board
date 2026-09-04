#!/bin/bash
#
# 프론트 → jar 를 한 번에 빌드한다.
#
# ⚠️ 순서가 중요하다. 프론트 빌드 결과가 src/main/resources/static/ 으로 가고
# bootJar 가 그것을 담는다 — 순서를 바꾸면 화면 없는 jar 가 나온다
# (docs/04-배포.md "프론트를 빌드하지 않으면 화면이 없는 jar 가 나온다").
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "▸ 프론트 빌드"
(cd frontend && npm run build)

echo "▸ jar 빌드"
./gradlew bootJar -q

JAR="$ROOT/build/libs/claude-board.jar"

# ⚠️ grep -q 를 쓰지 않는다. 매치 즉시 종료해 unzip 이 SIGPIPE 로 죽고,
# set -o pipefail 이 그것을 파이프 실패로 잡아 **있는데 없다고 판정**한다.
# 실측으로 밟았다 — grep -c 는 1 을 내는데 if ! ... grep -q 는 "없다"가 됐다.
# 목록을 한 번만 읽어 변수에 담고 개수로 판정한다.
LIST="$(unzip -l "$JAR")"

# 화면이 실제로 들어갔는지 본다. "빌드 성공"과 "화면이 있다"는 다르다.
if [ "$(printf '%s' "$LIST" | grep -c 'static/index.html')" -eq 0 ]; then
  echo "✗ jar 에 화면이 없다 — 프론트 빌드가 반영되지 않았다" >&2
  exit 1
fi

# 세션 데이터 혼입은 되돌릴 수 없다 (CLAUDE.md). CI 도 검사하지만 로컬에서도 막는다.
LEAK="$(printf '%s' "$LIST" | grep -iE '\.jsonl|\.log' || true)"
if [ -n "$LEAK" ]; then
  echo "✗ jar 에 세션 데이터가 섞였다" >&2
  echo "$LEAK" >&2
  exit 1
fi

echo "✓ $JAR ($(du -h "$JAR" | cut -f1))"
