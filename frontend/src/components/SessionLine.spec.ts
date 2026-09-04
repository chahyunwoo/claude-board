import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { Session } from '@/lib/types'
import SessionLine from './SessionLine.vue'

/**
 * "제목 없는 세션이 **렌더되는가**" (docs/05-검증.md 5번) 는 순수 함수 테스트로는
 * 답이 안 나온다 — 헬퍼가 옳아도 템플릿이 그 헬퍼를 안 부르면 화면엔 안 보인다.
 * 그래서 여기서는 실제로 마운트해서 **DOM 텍스트**를 본다.
 */
const NOW = Date.parse('2026-09-03T10:00:00Z')

function session(overrides: Partial<Session> = {}): Session {
  return {
    sessionId: 's1',
    pid: 100,
    state: 'WAITING',
    title: '제목',
    lastPrompt: '프롬프트',
    branch: 'main',
    permissionMode: 'auto',
    model: 'claude-opus-5',
    contextTokens: 544102,
    contextLimit: 1000000,
    contextRatio: 0.544102,
    lastActivityAt: '2026-09-03T09:54:00Z',
    startedAt: '2026-08-30T05:58:55.537Z',
    ordinal: 16,
    ...overrides,
  }
}

describe('SessionLine', () => {
  it('제목이 없는 세션도 렌더된다 (실측: 16개 중 5개)', () => {
    const wrapper = mount(SessionLine, {
      props: { session: session({ title: null }), name: 'docs-site', now: NOW },
    })
    expect(wrapper.text()).toContain('(제목 없음)')
    expect(wrapper.text()).toContain('docs-site')
  })

  it('브랜치가 없어도 렌더된다', () => {
    const wrapper = mount(SessionLine, {
      props: { session: session({ branch: null }), now: NOW },
    })
    expect(wrapper.find('.branch').text()).toBe('—')
  })

  // ⭐ 절대값·상한·% 셋이 실제로 DOM 에 있는지 본다 — 결정사항 3.
  it('컨텍스트 절대값·상한·% 가 모두 화면에 나온다', () => {
    const wrapper = mount(SessionLine, { props: { session: session(), now: NOW } })
    const text = wrapper.text()
    expect(text).toContain('544K')
    expect(text).toContain('1.0M')
    expect(text).toContain('54%')
  })

  it('컨텍스트가 없으면 그 칸을 통째로 뺀다', () => {
    const wrapper = mount(SessionLine, {
      props: { session: session({ contextTokens: null }), now: NOW },
    })
    expect(wrapper.text()).not.toContain('ctx')
  })

  it('긴 브랜치명이 그대로 들어가고 전문은 title 속성에 남는다', () => {
    const long = 'worktree-fix-imax-scan-throttle'
    const wrapper = mount(SessionLine, {
      props: { session: session({ branch: long }), now: NOW },
    })
    expect(wrapper.find('.branch').attributes('title')).toBe(long)
  })

  it('프롬프트가 없으면 화살표 줄이 아예 없다', () => {
    const wrapper = mount(SessionLine, {
      props: { session: session({ lastPrompt: null }), now: NOW },
    })
    expect(wrapper.find('.prompt').exists()).toBe(false)
  })

  it('경과 시간이 나온다', () => {
    const wrapper = mount(SessionLine, { props: { session: session(), now: NOW } })
    expect(wrapper.text()).toContain('6분')
  })

  it('상태가 클래스로 나간다 — 색이 아니라 위치와 라벨이 1차, 색은 보조', () => {
    const wrapper = mount(SessionLine, {
      props: { session: session({ state: 'STALLED' }), now: NOW },
    })
    expect(wrapper.classes()).toContain('state-stalled')
  })

  // #23 — 헬퍼가 옳아도 템플릿이 그것을 안 부르면 화면엔 안 보인다.
  // 그래서 순수 함수 테스트(format.spec.ts)와 **별도로** 마운트한 DOM 을 본다.
  it('컨텍스트가 위험 수위면 클래스로 드러난다', () => {
    const wrapper = mount(SessionLine, {
      props: { session: session({ contextRatio: 0.9, contextTokens: 900_000, contextLimit: 1_000_000 }), now: NOW },
    })
    expect(wrapper.find('.ctx-danger').exists()).toBe(true)
    // 절대값 표시는 사라지지 않는다 — 분모가 틀릴 수 있어 경고는 보조다
    expect(wrapper.text()).toContain('900K')
  })

  it('경고 수위면 warn 클래스', () => {
    const wrapper = mount(SessionLine, {
      props: { session: session({ contextRatio: 0.75, contextTokens: 750_000, contextLimit: 1_000_000 }), now: NOW },
    })
    expect(wrapper.find('.ctx-warn').exists()).toBe(true)
    expect(wrapper.find('.ctx-danger').exists()).toBe(false)
  })

  it('평소에는 경고 클래스가 붙지 않는다 — 조용해야 경고가 보인다', () => {
    const wrapper = mount(SessionLine, {
      props: { session: session({ contextRatio: 0.2, contextTokens: 200_000, contextLimit: 1_000_000 }), now: NOW },
    })
    expect(wrapper.find('.ctx-warn').exists()).toBe(false)
    expect(wrapper.find('.ctx-danger').exists()).toBe(false)
    expect(wrapper.find('.ctx-normal').exists()).toBe(true)
  })
})
