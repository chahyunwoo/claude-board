// 레이아웃 튐 진단 — 무엇이 움직이는지 이름과 함께 본다.
// "shifted=N" 만으로는 어느 줄이 왜 움직였는지 모른다.
// playwright 는 frontend/node_modules 에 있다. ESM 은 스크립트 위치 기준으로 해석하므로
// 패키지명만 쓰면 이 디렉터리에서 못 찾는다 — 경로를 명시한다.
import { chromium } from '../../frontend/node_modules/playwright/index.mjs'
const b = await chromium.launch()
const p = await b.newPage({ viewport: { width: 1440, height: 900 } })
await p.goto('http://127.0.0.1:7777', { waitUntil: 'networkidle' })
await p.waitForTimeout(3000)

const snap = () => p.evaluate(() => {
  const rows = [...document.querySelectorAll('.line')]
  return rows.map((e) => {
    const r = e.getBoundingClientRect()
    const name = e.querySelector('.name')?.textContent?.trim() || '(이름없음)'
    const title = e.querySelector('.title')?.textContent?.trim().slice(0, 22) || ''
    return { key: name + '|' + title, y: Math.round(r.top), h: Math.round(r.height),
             state: [...e.classList].find((c) => c.startsWith('state-')) || '?' }
  })
})

let prev = await snap()
console.log(`기준: ${prev.length}줄`)
for (let i = 0; i < 14; i++) {
  await p.waitForTimeout(5200)
  const cur = await snap()
  const moved = []
  for (const c of cur) {
    const q = prev.find((x) => x.key === c.key)
    if (q && q.y !== c.y) moved.push(`${c.key.slice(0, 30)} y:${q.y}→${c.y} h:${q.h}→${c.h} ${q.state}→${c.state}`)
  }
  const gone = prev.filter((q) => !cur.find((c) => c.key === q.key)).map((q) => q.key.slice(0, 30))
  const added = cur.filter((c) => !prev.find((q) => q.key === c.key)).map((c) => c.key.slice(0, 30))
  if (moved.length || gone.length || added.length) {
    console.log(`\n[${i}] 줄수 ${prev.length}→${cur.length}`)
    moved.forEach((m) => console.log('  MOVED  ' + m))
    gone.forEach((g) => console.log('  GONE   ' + g))
    added.forEach((a) => console.log('  ADDED  ' + a))
  }
  prev = cur
}
await b.close()
