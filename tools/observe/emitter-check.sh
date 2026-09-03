#!/bin/bash
# SSE emitter 정리 검증 — 05-검증 4번
# ⚠️ 계측을 붙인 것과 같은 스크립트 안에서 한다.
# 실측(#11): 셸 sleep 으로 타이밍을 맞추려다 "연결 3개일 때"가 0 으로 나왔다
#            (브라우저가 이미 닫힌 뒤에 셌다). 그대로면 "정리됐다"와 "측정 안 됨"이 구별되지 않는다.
PID="${1:-}"
[ -z "$PID" ] && { echo "usage: emitter-check.sh <서버 PID>"; exit 1; }
# 클래스명은 4번째 필드다. 줄 전체에 정규식을 걸면 안 맞는다 — 실측으로 빈 값이 나왔다.
# $4 가 정확히 SseEmitter 인 줄만 고른다 (DefaultSseEmitterHandler 같은 내부 클래스 제외).
count() { jcmd $PID GC.class_histogram 2>/dev/null | awk '$4 == "org.springframework.web.servlet.mvc.method.annotation.SseEmitter" {print $2; exit}'; }
est()   { lsof -nP -iTCP:7777 2>/dev/null | grep -c ESTABLISHED; }

echo "기준선(브라우저 1개 상시 연결):  emitter=$(count)  est=$(est)"

# curl 5개를 붙인다
for i in 1 2 3 4 5; do
  curl -s -N --no-buffer http://127.0.0.1:7777/api/stream > /dev/null 2>&1 &
  echo $! >> /tmp/sse-pids.txt
done
sleep 6
echo "연결 +5 직후:                    emitter=$(count)  est=$(est)"   # ← 여기서 늘어야 계측이 유효하다

# 모두 끊는다
while read -r p; do kill "$p" 2>/dev/null; done < /tmp/sse-pids.txt
rm -f /tmp/sse-pids.txt
sleep 3
echo "끊은 직후:                       emitter=$(count)  est=$(est)"

# 하트비트(15초) 를 지나면 서버가 죽은 연결을 걷어낸다
sleep 20
echo "하트비트 후(20초):               emitter=$(count)  est=$(est)"
sleep 20
echo "하트비트 후(40초):               emitter=$(count)  est=$(est)"
