// 规则引擎单测: node 直接跑, 验证 Analyzer 对 legado 规则的解析正确性
const fs = require('fs');
const src = fs.readFileSync(__dirname + '/../app.js', 'utf8');
const start = src.indexOf('function absUrl');
const end = src.indexOf('function findSource');
const engine = src.slice(start, end);

// 注入 fake DOMParser: JSON 内容不应触碰; HTML 内容用极简解析器
const fakeDoc = (html) => html;
const sandbox = new Function('window', 'document', 'AbortSignal', 'DOMParser',
  engine + '\nreturn { Analyzer, templateJson, splitTop, buildRequest, cleanContent };');
const api = sandbox(
  { addEventListener() { } },
  {
    documentElement: { style: { setProperty() { } }, dataset: {} },
    querySelector: () => null, addEventListener() { },
    createElement: () => ({ appendChild() { }, addEventListener() { }, remove() { } }),
    body: { appendChild() { } },
  },
  { timeout: () => null },
  class DOMParser {
    parseFromString(s) { throw new Error('DOMParser hit for: ' + s.slice(0, 30)) }
  },
);
const { Analyzer, templateJson, splitTop, buildRequest, cleanContent } = api;

// ---- 测试1: JSONPath ----
const az = new Analyzer('{"search":[{"book_name":"凡人修仙传","author":"忘语","url":"/book/1"}]}', '');
const list = az.getElements('$..search[*]');
console.assert(list.length === 1, 'list len ' + list.length);
const sub = new Analyzer(JSON.stringify(list[0]), '');
sub.json = list[0];
console.assert(sub.getString('$.book_name') === '凡人修仙传', sub.getString('$.book_name'));
console.assert(sub.getString('$.author') === '忘语', sub.getString('$.author'));

// ---- 测试2: {{}} 模板 ----
console.assert(templateJson({ a: 'x', b: 'y' }, '{{$.a}}-{{$.b}}') === 'x-y',
  templateJson({ a: 'x', b: 'y' }, '{{$.a}}-{{$.b}}'));

// ---- 测试3: splitTop ----
console.assert(JSON.stringify(splitTop('a&&b||c', '||')) === JSON.stringify(['a&&b', 'c']));
console.assert(JSON.stringify(splitTop('{{$.a}}&&b', '&&')) === JSON.stringify(['{{$.a}}', 'b']));

// ---- 测试4: buildRequest ----
let r = buildRequest('/api/search?q={{key}}&page={{page}}', '凡人', 2, 'https://x.com/');
console.assert(r.url === 'https://x.com/api/search?q=' + encodeURIComponent('凡人') + '&page=2', r.url);
r = buildRequest('https://x.com/s,{"method":"POST","body":"k=searchKey"}', '斗破', 1, '');
console.assert(r.options.method === 'POST' && r.options.body === 'k=斗破', JSON.stringify(r.options));
console.assert(r.url === 'https://x.com/s');

// ---- 测试5: cleanContent ----
const c = cleanContent('<p>第一段<br/>第二行</p><p>第二段</p>');
console.assert(c.length === 3 && c[0] === '第一段', JSON.stringify(c));

// ---- 测试6: ## 清除正则 ----
const az3 = new Analyzer(JSON.stringify({ t: '最新更新 第1章' }), '');
az3.json = { t: '最新更新 第1章' };
console.assert(az3.getString('$.t##最新更新 ') === '第1章', az3.getString('$.t'));

console.log('ALL TESTS DONE');
