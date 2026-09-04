#!/bin/bash
#
# LaunchAgent 를 설치한다 (상시 실행).
#
#   scripts/install-agent.sh            설치·기동
#   scripts/install-agent.sh uninstall  제거
#
# ⚠️ 상시 실행이 필요 없다면 `claude-board` 래퍼로 그때그때 띄우면 된다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LABEL="local.claude-board"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
JAR="$ROOT/build/libs/claude-board.jar"
LOG="$HOME/Library/Logs/claude-board/claude-board.log"
DOMAIN="gui/$(id -u)"

if [ "${1:-install}" = "uninstall" ]; then
  launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
  rm -f "$PLIST"
  echo "✓ 제거했다"
  exit 0
fi

[ -f "$JAR" ] || { echo "✗ jar 이 없다 — scripts/build.sh 를 먼저 돌려라" >&2; exit 1; }
mkdir -p "$(dirname "$PLIST")" "$(dirname "$LOG")"

# claude CLI 가 있는 디렉터리를 PATH 에 넣는다 — launchd 는 PATH 를 안 물려준다.
CLAUDE_BIN="$(command -v claude || true)"
[ -n "$CLAUDE_BIN" ] || { echo "✗ claude CLI 를 찾지 못했다 — 세션 목록을 못 읽는다" >&2; exit 1; }
AGENT_PATH="$(dirname "$CLAUDE_BIN"):/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"

sed -e "s|__JAR__|$JAR|" -e "s|__LOG__|$LOG|" -e "s|__ROOT__|$ROOT|" \
    -e "s|__PATH__|$AGENT_PATH|" \
    "$ROOT/scripts/$LABEL.plist" > "$PLIST"

# 이미 떠 있으면 포트가 겹친다 — 먼저 내린다.
launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
pid="$(lsof -nP -iTCP:7777 -sTCP:LISTEN -t 2>/dev/null | head -1 || true)"
[ -n "$pid" ] && { kill "$pid" 2>/dev/null || true; sleep 2; }

launchctl bootstrap "$DOMAIN" "$PLIST"

# ⚠️ bootstrap 만으로는 시작되지 않는 경우가 있다 (runs = 0) — docs/04-배포.md 실측.
# "등록했다"와 "돌고 있다"는 다르다. 확인하고, 아니면 kickstart 한다.
# 최대 15초 기다린다. 3초는 성급했다 — launchd 가 띄우는 데 그보다 걸릴 수 있고,
# 그 사이에 "실패"로 판정하면 멀쩡한 기동을 죽이게 된다 (실측).
wait_port() {
  for _ in $(seq 1 15); do
    lsof -nP -iTCP:7777 -sTCP:LISTEN -t >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}

if ! wait_port; then
  echo "  bootstrap 만으로 안 떴다 — kickstart 한다"
  launchctl kickstart -k "$DOMAIN/$LABEL"
  wait_port || true
fi

pid="$(lsof -nP -iTCP:7777 -sTCP:LISTEN -t 2>/dev/null | head -1 || true)"
if [ -z "$pid" ]; then
  echo "✗ 기동 실패 — $LOG 를 봐라" >&2
  launchctl print "$DOMAIN/$LABEL" 2>/dev/null | grep -E "state|runs|last exit" >&2 || true
  exit 1
fi

echo "✓ 상시 실행 등록 (pid $pid) — http://127.0.0.1:7777"
launchctl print "$DOMAIN/$LABEL" | grep -E "state = |runs = " | sed 's/^/  /'
