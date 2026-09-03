<script setup lang="ts">
import { computed, ref } from 'vue'
import SessionLine from './SessionLine.vue'
import type { Project } from '@/lib/types'

/**
 * 프로젝트 한 건 — `current` 한 줄과, 접힌 `others`.
 *
 * 상호작용은 최소한만 둔다 (docs/03-프론트.md "상호작용"):
 * 줄 클릭은 펼치기/접기, 경로 클릭은 `cwd` 복사. **세션 조작 버튼은 없다.**
 */
const props = defineProps<{ project: Project; now: number }>()

const expanded = ref(false)
const hasOthers = computed(() => props.project.others.length > 0)

/** 경로 복사 결과를 잠깐 보인다. 실패해도 조용히 두지 않는다. */
const copied = ref<'ok' | 'fail' | null>(null)

async function copyCwd() {
  try {
    // 로컬 전용 도구라 clipboard API 가 없는 환경(비보안 컨텍스트)이 있을 수 있다.
    await navigator.clipboard.writeText(props.project.cwd)
    copied.value = 'ok'
  } catch {
    copied.value = 'fail'
  }
  setTimeout(() => (copied.value = null), 1200)
}

function toggle() {
  if (hasOthers.value) {
    expanded.value = !expanded.value
  }
}
</script>

<template>
  <div class="project">
    <div
      class="current"
      :class="{ clickable: hasOthers }"
      :role="hasOthers ? 'button' : undefined"
      :tabindex="hasOthers ? 0 : undefined"
      :aria-expanded="hasOthers ? expanded : undefined"
      @click="toggle"
      @keydown.enter.prevent="toggle"
      @keydown.space.prevent="toggle"
    >
      <SessionLine :session="project.current" :name="project.name" :now="now" />
      <div v-if="hasOthers" class="others-hint">
        {{ expanded ? '▾' : '▸' }} 세션 {{ project.others.length }}개 더
      </div>
    </div>

    <div v-if="expanded" class="others">
      <SessionLine
        v-for="session in project.others"
        :key="session.sessionId"
        :session="session"
        :now="now"
      />
    </div>

    <div class="foot">
      <button class="cwd" type="button" :title="project.cwd" @click.stop="copyCwd">
        {{ project.cwd }}
      </button>
      <span v-if="copied === 'ok'" class="copied">복사됨</span>
      <span v-else-if="copied === 'fail'" class="copied fail">복사 실패</span>
      <span class="count">기록 {{ project.sessionCount }}개</span>
    </div>
  </div>
</template>

<style scoped>
.project {
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-panel);
  overflow: hidden;
}

.current.clickable {
  cursor: pointer;
}

.current.clickable:hover {
  background: var(--color-panel-hover);
}

.current:focus-visible {
  outline: 2px solid var(--color-focus);
  outline-offset: -2px;
}

.others-hint {
  padding: 0 0.75rem 0.375rem 0.9375rem;
  color: var(--color-fg-faint);
  font-size: 0.75rem;
}

.others {
  border-top: 1px solid var(--color-border);
  background: var(--color-panel-sunken);
}

.foot {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.25rem 0.75rem;
  border-top: 1px solid var(--color-border);
  font-size: 0.75rem;
  color: var(--color-fg-faint);
}

.cwd {
  flex: 1 1 auto;
  min-width: 0;
  background: none;
  border: none;
  padding: 0;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cwd:hover {
  color: var(--color-fg-dim);
}

.copied {
  flex: none;
  color: var(--color-state-working);
}

.copied.fail {
  color: var(--color-state-stalled);
}

.count {
  flex: none;
}
</style>
