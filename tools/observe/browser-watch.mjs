// 실사용 관찰 — 이슈 #15
// e2e 와 달리 EventSource 를 주입하지 않는다. 진짜 백엔드에 붙어 실제 값을 본다.
// 보는 것: (1) 레이아웃이 갱신마다 튀는가 (2) EventSource 재연결 (3) 콘솔 에러
//          (4) ended 토글로 세션 수를 늘렸을 때
// playwright 는 frontend/node_modules 에 있다. ESM 은 스크립트 위치 기준으로 해석하므로
// 패키지명만 쓰면 이 디렉터리에서 못 찾는다 — 경로를 명시한다.
import { chromium } from '../../frontend/node_modules/playwright/index.mjs'
import fs from 'node:fs'

const OUT = process.argv[2]
const MINUTES = Number(process.argv[3] || 40)
const ts = () => new Date().toTimeString().slice(0, 8)
const log = (m) => {
  const line = `${ts()}\t${m}`
  console.log(line)
  fs.appendFileSync(`${OUT}/browser.log`, line + '\n')
}

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })

page.on('console', (m) => {
  if (m.type() === 'error' || m.type() === 'warning') log(`CONSOLE-${m.type()}\t${m.text().slice(0, 200)}`)
})
page.on('pageerror', (e) => log(`PAGEERROR\t${String(e).slice(0, 200)}`))

// EventSource 재연결을 계측한다. 브라우저가 자동 재연결하므로
// "몇 번 열렸는가"를 세면 30분 타임아웃 뒤 재연결이 실제로 됐는지 보인다.
await page.addInitScript(() => {
  window.__sse = { opens: 0, errors: 0, snapshots: 0, log: [] }
  const Native = window.EventSource
  window.EventSource = class extends Native {
    constructor(...args) {
      super(...args)
      const stamp = () => new Date().toTimeString().slice(0, 8)
      this.addEventListener('open', () => {
        window.__sse.opens++
        window.__sse.log.push(`${stamp()} open#${window.__sse.opens}`)
      })
      this.addEventListener('error', () => {
        window.__sse.errors++
        window.__sse.log.push(`${stamp()} error#${window.__sse.errors} rs=${this.readyState}`)
      })
      this.addEventListener('snapshot', () => { window.__sse.snapshots++ })
    }
  }
})

await page.goto('http://127.0.0.1:7777', { waitUntil: 'networkidle' })
await page.waitForTimeout(3000)
log('페이지 로드 완료')

// 레이아웃 안정성: "튄다"를 눈이 아니라 숫자로 본다.
// 세션 줄의 y 좌표를 전부 받아 갱신 전후로 비교한다 —
// 내용만 바뀌고 위치가 그대로여야 "안 튄다"이다.
const measure = () =>
  page.evaluate(() => {
    // .line = 세션 한 줄, .project = 프로젝트 한 건 (실제 DOM 을 probe 로 확인했다).
    // 'section > div' 로 세면 .rows 컨테이너 3개만 잡혀 변화가 안 보인다 — 실측으로 밟았다.
    const rows = [...document.querySelectorAll('.line')]
    const projects = [...document.querySelectorAll('.project')]
    return {
      projectCount: projects.length,
      scrollH: document.documentElement.scrollHeight,
      scrollW: document.documentElement.scrollWidth,
      clientW: document.documentElement.clientWidth,
      rowCount: rows.length,
      ys: rows.map((e) => Math.round(e.getBoundingClientRect().top)),
      text: document.body.innerText.length,
      sse: window.__sse,
    }
  })

// ended 토글 — 세션 수를 늘려 본다 (05-검증 / 이슈 #15)
const toggleEnded = async (on) => {
  const box = page.locator('input[type="checkbox"]').first()
  if ((await box.count()) === 0) { log('토글 없음 — ended 관찰 불가'); return null }
  if ((await box.isChecked()) !== on) await box.click()
  await page.waitForTimeout(2500)
  // "눌렀다"와 "반영됐다"는 다르다. 체크 상태를 되읽어 확인한다.
  const actual = await box.isChecked()
  if (actual !== on) { log(`토글 실패: 원함=${on} 실제=${actual}`); return null }
  return await measure()
}

const first = await measure()
log(`초기: scrollH=${first.scrollH} 세션줄=${first.rowCount} 프로젝트=${first.projectCount} textLen=${first.text} 가로오버플로=${first.scrollW > first.clientW}`)
await page.screenshot({ path: `${OUT}/shot-00-initial.png`, fullPage: true })

// ended 켠 상태를 먼저 재고 스크린샷 — 세션 수가 늘었을 때의 화면
const withEnded = await toggleEnded(true)
if (withEnded) {
  log(`ended 켬: scrollH=${withEnded.scrollH} 세션줄=${withEnded.rowCount} 프로젝트=${withEnded.projectCount} 가로오버플로=${withEnded.scrollW > withEnded.clientW}`)
  await page.screenshot({ path: `${OUT}/shot-01-ended-on.png`, fullPage: true })
  fs.writeFileSync(`${OUT}/ended-on.json`, JSON.stringify(withEnded, null, 2))
}
// 다시 끄고 장기 관찰에 들어간다
await toggleEnded(false)

const samples = []
const endAt = Date.now() + MINUTES * 60 * 1000
let i = 0
let prevYs = null
while (Date.now() < endAt) {
  await page.waitForTimeout(30_000)
  i++
  const m = await measure()
  samples.push({ ts: ts(), scrollH: m.scrollH, rowCount: m.rowCount, text: m.text, sse: { ...m.sse, log: undefined } })

  // y 좌표가 직전 샘플과 몇 개나 달라졌는가 = 레이아웃이 튄 정도
  let shifted = -1
  if (prevYs && prevYs.length === m.ys.length) {
    shifted = m.ys.filter((y, k) => y !== prevYs[k]).length
  }
  prevYs = m.ys

  fs.appendFileSync(
    `${OUT}/layout.tsv`,
    `${ts()}\t${m.scrollH}\t${m.rowCount}\t${shifted}\t${m.text}\t${m.sse.opens}\t${m.sse.errors}\t${m.sse.snapshots}\t${m.scrollW > m.clientW}\n`
  )
  if (i % 10 === 0) {
    log(`[${i}] scrollH=${m.scrollH} rows=${m.rowCount} shifted=${shifted} snapshots=${m.sse.snapshots} opens=${m.sse.opens} errors=${m.sse.errors}`)
    await page.screenshot({ path: `${OUT}/shot-${String(i).padStart(2, '0')}.png`, fullPage: true })
  }
}

const final = await measure()
log(`최종: opens=${final.sse.opens} errors=${final.sse.errors} snapshots=${final.sse.snapshots}`)
log(`SSE 이벤트 로그: ${JSON.stringify(final.sse.log)}`)
await page.screenshot({ path: `${OUT}/shot-99-final.png`, fullPage: true })
fs.writeFileSync(`${OUT}/browser-final.json`, JSON.stringify({ first, withEnded, final, samples }, null, 2))
await browser.close()
