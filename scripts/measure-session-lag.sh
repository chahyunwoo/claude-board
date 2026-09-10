#!/bin/bash
# 세션이 뜬 뒤 .jsonl 기록이 생기기까지 몇 초 걸리는지 잰다.
#
# claude-board 의 Aggregator 는 "살아있는 세션"(claude agents --json)에
# 대응하는 .jsonl 이 없으면 무조건 에러로 찍는다. 그 간격이 실제로
# 얼마인지 알아야 grace 값을 정할 수 있다 — 지어내지 않는다.
set -u
export PATH="$HOME/.nvm/versions/node/v22.14.0/bin:/opt/homebrew/bin:/usr/bin:/bin"

PROJ="$HOME/.claude/projects"
known=$(claude agents --json 2>/dev/null | python3 -c "
import sys,json
try: print(' '.join(a['sessionId'] for a in json.load(sys.stdin)))
except Exception: pass
")
echo "  기존 세션: $(echo $known | wc -w | tr -d ' ')개"

# 새 세션을 백그라운드로 띄운다 (print 모드 — 한 번 응답하고 끝난다)
cd /tmp || exit 1
claude -p "1+1은?" >/dev/null 2>&1 &
BG=$!
echo "  새 세션 띄움 (pid $BG)"

new=""
start=$(python3 -c "import time;print(time.time())")

# 새 세션 id 를 잡는다
for i in $(seq 1 100); do
  cur=$(claude agents --json 2>/dev/null | python3 -c "
import sys,json
try: print(' '.join(a['sessionId'] for a in json.load(sys.stdin)))
except Exception: pass
")
  for s in $cur; do
    case " $known " in *" $s "*) ;; *) new="$s"; break 2;; esac
  done
  sleep 0.2
done

if [ -z "$new" ]; then
  echo "  ⚪ 새 세션을 못 잡았다 (너무 빨리 끝났을 수 있다)"
  wait $BG 2>/dev/null
  exit 0
fi

t_seen=$(python3 -c "import time;print(round(time.time()-$start,2))")
echo "  새 세션 id 포착: $new  (+${t_seen}s)"

# 그 세션의 jsonl 이 언제 생기는지
found=""
for i in $(seq 1 300); do
  f=$(find "$PROJ" -name "$new.jsonl" -maxdepth 2 2>/dev/null | head -1)
  [ -n "$f" ] && { found="$f"; break; }
  sleep 0.2
done

if [ -n "$found" ]; then
  t_file=$(python3 -c "import time;print(round(time.time()-$start,2))")
  echo "  ✅ jsonl 생성: +${t_file}s"
  echo "     간격(세션 포착 → 기록 생성): $(python3 -c "print(round($t_file-$t_seen,2))")s"
else
  echo "  🔴 60초 안에 jsonl 이 안 생겼다 — 가설 재검토 필요"
fi
wait $BG 2>/dev/null
