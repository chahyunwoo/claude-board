import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import vueTsEslintConfig from '@vue/eslint-config-typescript'

export default [
  {
    // 빌드 결과는 백엔드 저장소 안에 떨어진다 — 린트 대상이 아니다.
    ignores: ['dist/**', '../src/main/resources/static/**', 'playwright-report/**', 'test-results/**'],
  },
  js.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  ...vueTsEslintConfig(),
  {
    rules: {
      // 조회 전용 로컬 도구다. console 은 프로덕션 코드에 남기지 않는다 (전역 security.md).
      'no-console': 'error',
      'no-debugger': 'error',

      // 아래 셋은 **줄바꿈 취향**이라 정확성과 무관하다. Prettier 를 두지 않은 상태에서
      // 켜두면 손으로 맞추는 일만 늘고, `--max-warnings 0` 때문에 CI 가 취향으로 빨개진다.
      // 나머지 vue 권장 규칙(사용하지 않는 변수, 잘못된 v-for 키 등)은 그대로 둔다.
      'vue/max-attributes-per-line': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/html-self-closing': 'off',
    },
  },
]
