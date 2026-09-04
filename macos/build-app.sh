#!/bin/bash
#
# 메뉴바 앱을 .app 번들로 빌드한다.
#
#   macos/build-app.sh            빌드
#   macos/build-app.sh install    빌드 + /Applications 에 설치 + 로그인 항목 등록
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

APP="$HERE/ClaudeBoardMenu.app"
NAME="ClaudeBoardMenu"

echo "▸ 빌드"
swift build -c release

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp ".build/release/$NAME" "$APP/Contents/MacOS/"

cat > "$APP/Contents/Info.plist" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleExecutable</key><string>ClaudeBoardMenu</string>
  <key>CFBundleIdentifier</key><string>local.claude-board.menu</string>
  <key>CFBundleName</key><string>Claude Board</string>
  <key>CFBundleShortVersionString</key><string>0.1.0</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <!-- 메뉴바 전용 — Dock 에 아이콘을 만들지 않는다 -->
  <key>LSUIElement</key><true/>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
</dict></plist>
XML

# 실행 파일이 실제로 들어갔는지 본다. "빌드 성공"과 "번들이 온전하다"는 다르다.
[ -x "$APP/Contents/MacOS/$NAME" ] || { echo "✗ 번들에 실행 파일이 없다" >&2; exit 1; }
echo "✓ $APP"

[ "${1:-}" = "install" ] || exit 0

echo "▸ /Applications 로 복사"
pkill -f "$NAME" 2>/dev/null || true
sleep 1
rm -rf "/Applications/Claude Board.app"
cp -R "$APP" "/Applications/Claude Board.app"

echo "▸ 로그인 항목 등록"
# ⚠️ launchd(LaunchAgent)가 아니라 **로그인 항목**을 쓴다.
# 이 기계의 launchd 는 KeepAlive·StartInterval 이 동작하지 않는다 (#37 에서 최소 재현).
# 로그인 항목은 다른 메커니즘이고 실제로 잘 뜬다 (Rectangle·Notion 등이 그렇게 뜬다).
osascript -e 'tell application "System Events" to make login item at end with properties {path:"/Applications/Claude Board.app", hidden:true}' >/dev/null 2>&1 || {
  echo "  ⚠️ 자동 등록 실패 — 시스템 설정 > 일반 > 로그인 항목에서 직접 추가하세요"
}

open "/Applications/Claude Board.app"
echo "✓ 설치 완료 — 메뉴바를 보세요"
