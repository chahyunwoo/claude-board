<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue'
import ProjectRow from './components/ProjectRow.vue'
import { STATE_LABEL, clockOf } from '@/lib/format'
import { groupByState, summarize } from '@/lib/group'
import { useBoardStream } from '@/lib/useBoardStream'

/**
 * 단일 화면. 라우팅 없음, 스크롤만 (docs/03-프론트.md "화면").
 */
const { snapshot, connected, clientError } = useBoardStream()

/**
 * 경과 시간용 시계.
 *
 * 스냅샷과 **따로 돈다** — 스냅샷이 5초마다 와도 그 사이에 "6분"이 "7분"이 되어야 하고,
 * 반대로 수집이 멈춰도 경과는 계속 흘러야 "언제부터 안 오는지"가 보인다.
 */
const now = ref(Date.now())
const ticker = setInterval(() => (now.value = Date.now()), 1000)
onScopeDispose(() => clearInterval(ticker))


const groups = computed(() => groupByState(snapshot.value, (s) => STATE_LABEL[s]))
const totals = computed(() => summarize(snapshot.value))
const errors = computed(() => snapshot.value?.errors ?? [])

/** 첫 스냅샷이 오기 전. "세션 0개"와 **구별해서** 낸다. */
const loading = computed(() => snapshot.value === null)
/** 수집은 됐는데 프로젝트가 없다 — 정상 상태이고 화면이 깨지면 안 된다. */
const empty = computed(() => snapshot.value !== null && snapshot.value.projects.length === 0)
</script>

<template>
  <div class="board">
    <header class="top">
      <h1>CLAUDE SESSIONS</h1>
      <div class="summary">
        <span v-if="snapshot">{{ clockOf(snapshot.generatedAt) }}</span>
        <span class="dot">·</span>
        <span>{{ totals.projects }} 프로젝트</span>
        <span class="dot">·</span>
        <span>{{ totals.sessions }} 세션</span>
        <span class="dot">·</span>
        <span class="conn" :class="{ live: connected }">{{ connected ? '라이브' : '끊김' }}</span>
      </div>
    </header>

    <!--
      errors 를 반드시 낸다 — 파싱이 조용히 실패하면 "세션이 없다"와
      "읽지 못했다"가 구별되지 않는다. docs/02-백엔드.md
    -->
    <div v-if="clientError" class="errors">{{ clientError }}</div>
    <ul v-if="errors.length" class="errors">
      <li v-for="(error, index) in errors" :key="index">{{ error }}</li>
    </ul>

    <p v-if="loading" class="notice">연결하는 중…</p>
    <p v-else-if="empty" class="notice">살아있는 세션이 없습니다.</p>

    <section v-for="group in groups" :key="group.state" class="group">
      <h2 :class="`state-${group.state.toLowerCase()}`">
        {{ group.label }} <span class="n">({{ group.projects.length }})</span>
      </h2>
      <div class="rows">
        <ProjectRow
          v-for="project in group.projects"
          :key="project.cwd"
          :project="project"
          :now="now"
        />
      </div>
    </section>
  </div>
</template>

<style scoped>
.board {
  /*
   * 넓은 화면을 쓴다 (#23). 상시 표시(Studio Display 한쪽)가 전제인데
   * 68rem 고정이면 2560px 화면에서 42%만 쓰고 양옆이 텅 빈다 —
   * 세션이 20개만 넘어도 스크롤해야 해서 "한 화면에서 본다"(docs/00-개요.md 목표 1)가 깨진다.
   *
   * 폭 자체는 열어두고, 한 줄이 길어지는 문제는 아래 .rows 의 다열 배치로 푼다.
   */
  max-width: 160rem;
  margin: 0 auto;
  padding: 1.25rem 1rem 3rem;
}

.top {
  display: flex;
  align-items: baseline;
  gap: 1rem;
  flex-wrap: wrap;
  padding-bottom: 0.75rem;
  border-bottom: 1px solid var(--color-border);
}

h1 {
  margin: 0;
  font-size: 0.9375rem;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--color-fg);
}

.summary {
  display: flex;
  gap: 0.375rem;
  color: var(--color-fg-dim);
  font-size: 0.8125rem;
  /* 남는 폭을 먹어 토글을 오른쪽 끝으로 민다. */
  flex: 1 1 auto;
}

.dot {
  color: var(--color-fg-faint);
}

.conn {
  color: var(--color-state-stalled);
}

.conn.live {
  color: var(--color-state-working);
}

.errors {
  margin: 0.75rem 0 0;
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--color-state-stalled);
  border-radius: 6px;
  color: var(--color-state-stalled);
  font-size: 0.8125rem;
  list-style: none;
}

.notice {
  margin: 2rem 0;
  color: var(--color-fg-faint);
  text-align: center;
}

.group {
  margin-top: 1.5rem;
}

h2 {
  margin: 0 0 0.5rem;
  font-size: 0.8125rem;
  font-weight: 600;
  letter-spacing: 0.04em;
}

h2 .n {
  color: var(--color-fg-faint);
  font-weight: 400;
}

/*
 * 화면이 넓으면 열이 늘어난다 (#23).
 *
 * `auto-fill` + `minmax` 라 화면 폭에 따라 자동으로 1→2→3열이 되고,
 * 미디어 쿼리로 중단점을 손으로 관리하지 않아도 된다.
 *
 * ⚠️ `grid-auto-flow` 를 바꾸지 말 것. 기본값(row)이라 **왼쪽에서 오른쪽, 그 다음 아래**로
 * 채워진다 — 각 그룹 안은 이미 정렬돼 있고(오래된 순 또는 이름순, docs/03-프론트.md)
 * 위쪽이 더 급한 건이므로 그 순서가 읽기 순서와 같아야 한다.
 * `column` 으로 두면 첫 열을 다 채운 뒤 다음 열로 가서 순서가 뒤섞여 보인다.
 */
.rows {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(38rem, 1fr));
  gap: 0.5rem;
  align-items: start;
}
</style>
