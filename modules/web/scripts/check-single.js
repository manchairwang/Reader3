// 检查 dist/index.html 是否仍有外部引用(file:// 下会加载失败)
import { readFileSync } from 'node:fs'

const html = readFileSync('dist/index.html', 'utf8')
const bad = []
for (const m of html.matchAll(/(?:src|href)\s*=\s*["']([^"']+)["']/g)) {
  const url = m[1]
  if (/^(#|data:|about:)/.test(url) || !/\.(js|css|png|jpg|svg|woff2?|ttf|ico)/.test(url))
    continue
  bad.push(url)
}
console.log('外部资源引用:', bad.length ? bad.join(', ') : '无')
