#!/bin/bash
# 관찰 샘플러 — 이슈 #15
# 1분마다 (1) 상태 분포 (2) 프로세스 자원 (3) 세션별 상태 를 TSV 로 남긴다.
# 추측하지 않기 위한 도구다. 해석은 나중에 한다.
SP="$(cd "$(dirname "$0")" && pwd)"
PID="$1"
[ -z "$PID" ] && { echo "usage: sample.sh <pid>"; exit 1; }

STATES="$SP/states.tsv"
RES="$SP/resources.tsv"
SESS="$SP/sessions.tsv"

[ -s "$STATES" ] || printf 'ts\tWAITING\tSTALLED\tWORKING\tIDLE\tENDED\ttotal\n' > "$STATES"
[ -s "$RES" ]    || printf 'ts\trss_kb\tcpu_pct\tthreads\tsse_established\tfd_count\n' > "$RES"
# 세션별 — 전이(WORKING→STALLED)를 보려면 세션 단위로 남겨야 한다.
# quietSec = 샘플 시각 - lastActivityAt. STALLED 임계(600s)와 직접 대조할 값이다.
[ -s "$SESS" ]   || printf 'ts\tsessionId\tproject\tstate\tlastActivityAt\tquietSec\tctxTokens\n' > "$SESS"

while kill -0 "$PID" 2>/dev/null; do
  TS=$(date '+%Y-%m-%dT%H:%M:%S')
  NOW=$(date +%s)

  JSON=$(curl -s -m 10 http://127.0.0.1:7777/api/sessions 2>/dev/null)

  if [ -n "$JSON" ]; then
    # from_entries 는 쓰지 않는다 — 실측으로 "Cannot use null as object key" 가 났다.
    echo "$JSON" | jq -r --arg ts "$TS" '
      [.projects[] | (.current // empty), ((.others // [])[])] as $s
      | [$ts,
         ($s | map(select(.state=="WAITING")) | length),
         ($s | map(select(.state=="STALLED")) | length),
         ($s | map(select(.state=="WORKING")) | length),
         ($s | map(select(.state=="IDLE"))    | length),
         ($s | map(select(.state=="ENDED"))   | length),
         ($s | length)]
      | @tsv' >> "$STATES"

    echo "$JSON" | jq -r --arg ts "$TS" --argjson now "$NOW" '
      [.projects[] | .name as $p | (([.current] + .others) | map(select(. != null)) | .[] | {p: $p, s: .})]
      | .[]
      | [$ts, (.s.sessionId // "-"), .p, .s.state, (.s.lastActivityAt // "-"),
         (if .s.lastActivityAt then ($now - ((.s.lastActivityAt | sub("\\.[0-9]+Z$"; "Z")) | fromdateiso8601)) else -1 end),
         (.s.contextTokens // -1)]
      | @tsv' >> "$SESS" 2>/dev/null
  fi

  read -r RSS CPU <<< "$(ps -o rss=,pcpu= -p "$PID" | tr -s ' ')"
  THREADS=$(ps -M "$PID" 2>/dev/null | tail -n +2 | wc -l | tr -d ' ')
  # SSE 연결은 자원(포트)으로 센다 — 프로세스 이름으로 세지 않는다
  EST=$(lsof -nP -iTCP:7777 2>/dev/null | grep -c ESTABLISHED)
  FDS=$(lsof -p "$PID" 2>/dev/null | wc -l | tr -d ' ')
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$TS" "$RSS" "$CPU" "$THREADS" "$EST" "$FDS" >> "$RES"

  sleep 60
done
echo "샘플러 종료 — 대상 프로세스($PID)가 사라졌다" >&2
