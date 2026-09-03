# 실사용 관찰 도구

#15 에서 만든 것. **"테스트가 통과한다"와 "한 번 렌더된다"로는 안 보이는 것**을
시간에 걸쳐 관찰한다 — 상태 전이, SSE 재연결, 레이아웃 안정성, 메모리 추이.

[docs/05-검증.md](../../docs/05-검증.md) 의 항목 중 **시간이 지나야 드러나는 것**을 담당한다.

## 준비

```bash
(cd frontend && npm run build) && ./gradlew bootJar
java -jar build/libs/claude-board.jar &
PID=$!            # 아래에서 쓴다
```

## 각 도구

### `sample.sh <서버 PID>` — 1분마다 상태·자원 기록

```bash
tools/observe/sample.sh $PID &
```

세 파일을 TSV 로 남긴다:

| 파일 | 내용 |
|---|---|
| `states.tsv` | 상태 분포 (WAITING/STALLED/WORKING/IDLE/ENDED) |
| `resources.tsv` | RSS·CPU·스레드·SSE 연결 수·fd |
| `sessions.tsv` | 세션별 상태와 `quietSec` — **상태 전이를 보려면 이것** |

`quietSec` 이 `stalled-after`(600초)를 넘는 순간 `WORKING → STALLED` 가 일어나야 한다.
전이만 뽑으려면:

```bash
awk -F'\t' 'NR>1 { k=$2; if((k in p) && p[k]!=$4) print $1, $3, p[k]"->"$4, "quietSec="$6; p[k]=$4 }' sessions.tsv
```

> ⚠️ `jq` 의 `from_entries` 를 쓰지 않는다 — `Cannot use null as object key` 로 조용히 죽는다(실측).
> 상태 분포는 `map(select(...)) | length` 로 직접 센다.

### `crosscheck.sh` — 상태 판별 독립 교차검증

[docs/05-검증.md](../../docs/05-검증.md) 1번. **보드 코드를 쓰지 않고** `jq` 로
기록 파일의 마지막 대화 레코드를 직접 읽어 판정한 뒤 보드와 대조한다.
같은 코드로 검증하면 의미가 없기 때문이다.

```bash
tools/observe/crosscheck.sh | column -t
```

`match` 열이 전부 `OK` 여야 한다.

> ⚠️ 시각은 **반드시 `TZ=UTC`** 로 읽는다. 값이 `Z`(UTC)인데 로컬(KST)로 읽으면
> 9시간이 더해져 방금 활동한 세션이 `quietMin=540` 으로 나온다 — 실측으로 밟았고
> MISMATCH 4건이 전부 이 버그였다.
>
> ⚠️ "진행 중"(`tool_result`·`assistant+tool_use`)은 오래 조용하면 **IDLE 이 아니라 STALLED** 다
> ([docs/01-데이터.md](../../docs/01-데이터.md)). 이걸 반대로 알고 짜서 MISMATCH 가 났었다.

### `emitter-check.sh <서버 PID>` — SSE emitter 정리 검증

[docs/05-검증.md](../../docs/05-검증.md) 4번. 연결을 5개 붙였다 끊고 걷히는지 본다.

```bash
tools/observe/emitter-check.sh $PID
```

기대 출력:
```
기준선:            emitter=1   est=2
연결 +5 직후:      emitter=6   est=12    ← 실제로 세어진다
끊은 직후:         emitter=6   est=2
하트비트 후(20초):  emitter=1   est=2     ← 걷혔다
```

> ⚠️ **"0"만 보고 통과시키지 않는다.** 0 이 "정리됐다"인지 "측정이 안 된다"인지
> 구별되지 않는다. **연결 중일 때 실제로 세어지는 것을 먼저 보인 뒤** 0 을 근거로 쓴다.
>
> ⚠️ `jcmd GC.class_histogram` 에서 클래스명은 **4번째 필드**다. 줄 전체에 정규식을 걸면
> 안 맞아 **빈 값**이 나오는데, 그것을 0 으로 읽으면 위 함정에 정확히 빠진다(실측).

### `browser-watch.mjs <출력디렉터리> <분>` — 브라우저를 붙여 장기 관찰

저장소 루트에서 실행한다:

```bash
node tools/observe/browser-watch.mjs /tmp/observe 300
```

> ⚠️ playwright 는 `frontend/node_modules` 에 있는데 **ESM 은 스크립트 위치 기준으로
> 해석**하므로 `import ... from 'playwright'` 로는 못 찾는다. `cd frontend` 로 옮겨도,
> `NODE_PATH` 를 줘도 안 된다(둘 다 실측으로 실패). 그래서 스크립트가 경로를 명시한다.

e2e 와 달리 `EventSource` 를 주입하지 않는다 — **진짜 백엔드에 붙어 실제 값**을 본다.
`layout.tsv` 에 30초마다 기록하고, `EventSource` 를 감싸 `opens`/`errors` 를 센다.

`opens` 가 늘어나면 **30분 만료 후 재연결**이 일어난 것이다(`StreamController.TIMEOUT_MS`).

### `jitter.mjs` — 레이아웃이 왜 튀는지 진단

`browser-watch` 의 `shifted=N` 만으로는 **어느 줄이 왜 움직였는지** 모른다.
이쪽은 줄마다 이름을 붙여 y 좌표 변화를 출력한다.

```bash
node tools/observe/jitter.mjs
```

`줄 수·높이·상태가 그대로인데 y 만 바뀌면` 그것이 진짜 레이아웃 튐이다(#18).

## 셀렉터 주의

화면의 세션 한 줄은 `.line`, 프로젝트 한 건은 `.project` 다.
`section > div` 로 세면 `.rows` 컨테이너 3개만 잡혀 **변화가 안 보인다**(실측).
