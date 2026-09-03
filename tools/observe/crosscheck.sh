#!/bin/bash
# 상태 판별 교차검증 — 05-검증 1번
# 보드의 판정 vs 기록 파일의 마지막 레코드를 독립적으로 읽어 대조한다.
# 보드 코드를 쓰지 않고 jq 로 직접 읽는다 — 같은 코드로 검증하면 의미가 없다.
printf 'boardState\tindependent\tmatch\tproject\tquietMin\tlastKind\n'
curl -s http://127.0.0.1:7777/api/sessions | jq -r '
  .projects[] | .name as $p | ((.current // empty), ((.others // [])[]))
  | [.sessionId, .state, $p, (.lastActivityAt // "-")] | @tsv' |
while IFS=$'\t' read -r SID STATE PROJ LAST; do
  F=$(find ~/.claude/projects -name "$SID.jsonl" 2>/dev/null | head -1)
  [ -z "$F" ] && { printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$STATE" "NOFILE" "?" "$PROJ" "-" "-"; continue; }

  # 마지막 "대화" 레코드를 뒤에서 찾는다 (요약/메타 레코드는 건너뛴다).
  KIND=$(tail -300 "$F" | jq -rs '
    map(select(.type=="user" or .type=="assistant"))
    | last
    | if .type=="assistant" then
        (if ((.message.content // []) | map(select(.type=="tool_use")) | length) > 0
         then "ASSISTANT_TOOL_USE" else "ASSISTANT" end)
      elif .type=="user" then
        (if ((.message.content // []) | if type=="array" then map(select(.type=="tool_result")) | length else 0 end) > 0
         then "TOOL_RESULT" else "USER_TEXT" end)
      else "?" end' 2>/dev/null)

  # 독립 판정: ASSISTANT → WAITING, 진행중 계열 → 조용한 시간에 따라 WORKING/STALLED
  QUIET=-1
  if [ "$LAST" != "-" ]; then
    # 반드시 TZ=UTC 로 읽는다 — 값이 'Z'(UTC)인데 로컬(KST)로 읽으면 9시간이 더해진다.
    # 실측으로 밟았다: 방금 활동한 세션이 quietMin=540 으로 나와 MISMATCH 4건이 났다.
    LS=$(TZ=UTC date -j -f "%Y-%m-%dT%H:%M:%S" "$(echo "$LAST" | sed -E 's/\.[0-9]+Z$//')" +%s 2>/dev/null)
    [ -n "$LS" ] && QUIET=$(( ( $(date +%s) - LS ) / 60 ))
  fi
  case "$KIND" in
    ASSISTANT) IND=WAITING ;;
    # docs/01-데이터.md: "진행 중"은 오래 조용할수록 STALLED 다 — IDLE 로 내려가지 않는다.
    # (처음엔 IDLE 을 우선해 MISMATCH 1건이 났는데, 문서를 보니 보드 쪽이 맞았다.)
    TOOL_RESULT|ASSISTANT_TOOL_USE)
      if [ "$QUIET" -gt 10 ]; then IND=STALLED; else IND=WORKING; fi ;;
    USER_TEXT)
      if [ "$QUIET" -gt 120 ]; then IND=IDLE; else IND=WORKING; fi ;;
    *) IND="?" ;;
  esac
  M=$([ "$STATE" = "$IND" ] && echo OK || echo MISMATCH)
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$STATE" "$IND" "$M" "$PROJ" "$QUIET" "$KIND"
done
