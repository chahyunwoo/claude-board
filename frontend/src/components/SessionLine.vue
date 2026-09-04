<script setup lang="ts">
import { computed } from 'vue'
import { branchOf, contextLevelOf, contextOf, elapsedOf, promptOf, titleOf } from '@/lib/format'
import type { Session } from '@/lib/types'

/**
 * 세션 한 건. docs/03-프론트.md "한 줄에 담기는 것" 을 그대로 따른다.
 *
 * ```
 * 프로젝트명   브랜치   제목
 *   경과시간 · ctx 토큰 · N번째 세션
 *   ↳ 마지막 프롬프트 (1줄, 넘치면 말줄임)
 * ```
 *
 * `now` 를 **prop 으로 받는다** — 각 줄이 스스로 시계를 보면 16개가 제각기
 * 다른 시각을 기준으로 경과를 계산하게 된다.
 */
const props = defineProps<{
  session: Session
  /** 프로젝트명. `others` 로 접힌 줄에서는 비운다 — 같은 프로젝트라 반복이다. */
  name?: string
  now: number
}>()

const title = computed(() => titleOf(props.session))
const branch = computed(() => branchOf(props.session))
const context = computed(() => contextOf(props.session))
// 경고는 보조다 — 분모가 틀릴 수 있으므로 절대값 표시를 대체하지 않는다 (#23).
const contextLevel = computed(() => contextLevelOf(props.session))
const prompt = computed(() => promptOf(props.session))
const elapsed = computed(() => elapsedOf(props.session.lastActivityAt, props.now))
/** 제목이 없을 때만 흐리게 — 있으면 평범하게 읽혀야 한다. */
const untitled = computed(() => !props.session.title?.trim())
</script>

<template>
  <div class="line" :class="`state-${session.state.toLowerCase()}`">
    <div class="head">
      <span v-if="name" class="name">{{ name }}</span>
      <span class="branch" :title="session.branch ?? undefined">{{ branch }}</span>
      <span class="title" :class="{ untitled }">{{ title }}</span>
    </div>
    <div class="meta">
      <span>{{ elapsed }}</span>
      <template v-if="context">
        <span class="dot">·</span>
        <span :class="`ctx ctx-${contextLevel}`">ctx {{ context }}</span>
      </template>
      <template v-if="session.ordinal > 0">
        <span class="dot">·</span>
        <span>{{ session.ordinal }}번째 세션</span>
      </template>
    </div>
    <div v-if="prompt" class="prompt">
      <span class="arrow">↳</span>
      <span class="text">{{ prompt }}</span>
    </div>
  </div>
</template>

<style scoped>
.line {
  padding: 0.5rem 0.75rem;
  border-left: 3px solid var(--state-color, transparent);
}

.head {
  display: flex;
  align-items: baseline;
  gap: 0.75rem;
  /* 긴 브랜치명(실측: worktree-fix-imax-scan-throttle)이 와도 넘치지 않게 접힌다. */
  flex-wrap: wrap;
}

.name {
  color: var(--color-fg);
  font-weight: 600;
  /* 프로젝트명이 아무리 길어도 제목을 밀어내지 않는다. */
  max-width: 22ch;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.branch {
  color: var(--color-fg-dim);
  font-size: 0.8125rem;
  /* 긴 브랜치명은 여기서 잘린다. 전문은 title 속성으로 본다. */
  max-width: 24ch;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.title {
  color: var(--color-fg-soft);
  /* 남는 폭을 다 쓰되, 넘치면 말줄임. min-width:0 이 없으면 flex 가 안 줄어든다. */
  flex: 1 1 12rem;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.title.untitled {
  color: var(--color-fg-faint);
  font-style: italic;
}

.meta {
  margin-top: 0.1875rem;
  color: var(--color-fg-dim);
  font-size: 0.8125rem;
  display: flex;
  gap: 0.375rem;
  flex-wrap: wrap;
}

.dot {
  color: var(--color-fg-faint);
}

.prompt {
  margin-top: 0.1875rem;
  display: flex;
  gap: 0.375rem;
  /* 프롬프트는 "내가 뭘 시켰더라"를 복구하는 핵심 정보다 (docs/03-프론트.md).
     fg-faint 는 대비 3.01 로 WCAG AA(4.5) 미달이라 상시 표시에서 잘 안 보였다 (#23). */
  color: var(--color-fg-dim);
  font-size: 0.8125rem;
  min-width: 0;
}

.arrow {
  flex: none;
}

.text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
