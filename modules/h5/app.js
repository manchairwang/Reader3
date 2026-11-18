/* ============================================================
   阅读 H5 单文件版 - 规则引擎 + UI
   ============================================================ */

// ---------- 全局 ----------
const SOURCES = /*__SOURCES__*/[];
const state = {
  view: 'shelf',          // shelf | search | book | read
  searchKey: '',
  searchResults: [],       // {name,author,bookUrl,coverUrl,intro,lastChapter,sourceUrl,sourceName}
  currentBook: null,      // {name,author,bookUrl,tocUrl,coverUrl,intro,sourceUrl,sourceName}
  catalog: [],             // [{title,url}]
  chapterIndex: 0,
  chapterCache: {},        // bookUrl#idx -> [paragraphs]
};
const store = {
  get shelf() {
    try { return JSON.parse(localStorage.getItem('h5_shelf') || '[]') } catch { return [] }
  },
  set shelf(v) { localStorage.setItem('h5_shelf', JSON.stringify(v)) },
  get progress() {
    try { return JSON.parse(localStorage.getItem('h5_progress') || '{}') } catch { return {} }
  },
  set progress(v) { localStorage.setItem('h5_progress', JSON.stringify(v)) },
  get settings() {
    try { return Object.assign({ fontSize: 20, theme: 'day', proxy: '' },
      JSON.parse(localStorage.getItem('h5_settings') || '{}')) } catch {
      return { fontSize: 20, theme: 'day', proxy: '' }
    }
  },
  set settings(v) { localStorage.setItem('h5_settings', JSON.stringify(v)) },
};

// ---------- 工具 ----------
function absUrl(u, base) {
  if (!u) return '';
  try { return new URL(u, base).href } catch { return u }
}
function el(sel, root) { return (root || document).querySelector(sel) }
function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
}

// ---------- 代理抓取 ----------
// 策略探测（启动时一次）：本地代理 cors-proxy.py → 用户设置代理 → 公共代理
const LOCAL_PROXY = 'http://127.0.0.1:8727/?url={url}';
const PUBLIC_PROXIES = [
  'https://api.allorigins.win/raw?url={url}',
  'https://corsproxy.io/?url={url}',
  'https://api.codetabs.com/v1/proxy?quest={url}',
];
let fetchStrategy = null; // ['proxy', 模板] 或 ['direct']；null=未探测
let strategyProbing = null;

function userProxy() {
  const s = store.settings.proxy.trim();
  return s || null;
}
async function probeStrategy() {
  if (fetchStrategy) return fetchStrategy;
  if (strategyProbing) return strategyProbing;
  strategyProbing = (async () => {
    const testUrl = 'https://www.baidu.com/';
    // 1. 用户设置的代理优先
    const up = userProxy();
    if (up && await probeProxy(up, testUrl)) return ['proxy', up];
    // 2. 本地 cors-proxy.py（双击 start-proxy.bat 即有）
    if (await probeProxy(LOCAL_PROXY, testUrl)) return ['proxy', LOCAL_PROXY];
    // 3. 公共代理逐个探测（3s 快速失败）
    for (const p of PUBLIC_PROXIES) {
      if (await probeProxy(p, testUrl)) return ['proxy', p];
    }
    // 4. 兜底: 直连（仅对带 CORS 头的站点有效）
    try {
      await fetch(testUrl, { signal: AbortSignal.timeout(5000) });
      return ['direct'];
    } catch { }
    return ['proxy', LOCAL_PROXY]; // 全挂时仍返回本地代理（报错信息更明确）
  })();
  const r = await strategyProbing;
  strategyProbing = null;
  fetchStrategy = r;
  return r;
}
async function probeProxy(tpl, testUrl) {
  try {
    const purl = tpl.replace('{url}', encodeURIComponent(testUrl));
    const r = await fetch(purl, { signal: AbortSignal.timeout(4000) });
    return r.ok;
  } catch { return false }
}
// 策略失效时重置（网络变化/代理中途启动）
function resetFetchStrategy() { fetchStrategy = null }

async function pfetch(url, options = {}) {
  const method = (options.method || 'GET').toUpperCase();
  let lastErr;
  for (let attempt = 0; attempt < 2; attempt++) {
    const [kind, tpl] = await probeStrategy();
    try {
      let text;
      if (kind === 'direct') {
        const r = await fetch(url, {
          method,
          headers: options.headers || {},
          signal: AbortSignal.timeout(options.timeoutMs || 15000),
        });
        if (!r.ok) throw new Error('HTTP ' + r.status);
        text = await r.text();
      } else {
        const purl = tpl.replace('{url}', encodeURIComponent(url));
        const opt = {
          method,
          headers: Object.assign({ 'x-requested-with': 'XMLHttpRequest' }, options.headers || {}),
          signal: AbortSignal.timeout(options.timeoutMs || 20000),
        };
        if (method !== 'GET' && options.body != null) opt.body = options.body;
        const r = await fetch(purl, opt);
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const buf = await r.arrayBuffer();
        const charset = (options.charset || 'utf-8').toLowerCase();
        text = new TextDecoder(charset === 'utf8' ? 'utf-8' : charset).decode(buf);
      }
      return text;
    } catch (e) {
      lastErr = e;
      // 第一次失败: 策略可能已失效(代理中途关了), 重探测一次再试
      if (attempt === 0) {
        fetchStrategy = null;
        continue;
      }
    }
  }
  throw lastErr || new Error('抓取失败');
}

// ---------- JSONPath 简化版 ----------
function jsonPath(obj, path) {
  if (!path.startsWith('$')) return undefined;
  path = path.replace(/\[\*\]/g, ''); // $..search[*] -> $..search
  const parts = path.replace(/\[(\d+)\]/g, '.$1').split('.').slice(1);
  // $..name 递归
  if (path.startsWith('$..')) {
    const name = parts[parts.length - 1];
    const out = [];
    (function walk(o) {
      if (o && typeof o === 'object') {
        if (Array.isArray(o)) o.forEach(walk);
        else {
          for (const k in o) {
            if (k === name) out.push(o[k]);
            walk(o[k]);
          }
        }
      }
    })(obj);
    return out;
  }
  let cur = obj;
  for (const p of parts) {
    if (cur == null) return undefined;
    if (p === '') continue;
    cur = cur[p];
  }
  return cur;
}

// ---------- 规则引擎(AnalyzeRule 简化移植) ----------
class Analyzer {
  constructor(content, baseUrl) {
    this.baseUrl = baseUrl || '';
    const s = String(content).trim();
    this.isJSON = s.length > 0 && (s.startsWith('{') || s.startsWith('['));
    if (this.isJSON) {
      try { this.json = JSON.parse(s) } catch { this.isJSON = false }
    }
    if (!this.isJSON) {
      this.doc = new DOMParser().parseFromString(s, 'text/html');
    }
  }
  raw() { return this.isJSON ? this.json : this.doc }

  // 主入口: 求值一条取文本规则
  getString(rule, ctx) {
    if (!rule) return '';
    rule = String(rule).trim();
    // || 备选
    for (const alt of splitTop(rule, '||')) {
      const v = this.getStringSingle(alt.trim(), ctx);
      if (v) return v;
    }
    return '';
  }
  getStringSingle(rule, ctx) {
    // && 拼接
    const ands = splitTop(rule, '&&');
    const parts = ands.map(r => this.evalSingle(r.trim(), ctx, true));
    if (ands.length > 1) return parts.filter(Boolean).join('\n');
    return parts[0] || '';
  }
  evalSingle(rule, ctx, asText) {
    // ## 正则清除
    let clean = null;
    if (rule.includes('##')) {
      const i = rule.indexOf('##');
      clean = rule.slice(i + 2);
      rule = rule.slice(0, i);
    }
    let result = '';
    if (rule.startsWith('@get:') || rule.startsWith('@js:')) {
      result = ''; // 简化: 不支持 js
    } else if (rule.startsWith('{{') && rule.endsWith('}}')) {
      // JSON 模板
      result = this.isJSON ? templateJson(this.json, rule) : '';
    } else if (this.isJSON || rule.startsWith('$')) {
      const v = jsonPath(this.json, rule);
      result = v == null ? '' : (typeof v === 'object' ? JSON.stringify(v) : String(v));
    } else if (ctx) {
      // 在元素上下文里求值(相对规则)
      result = analyzeHtmlEl(ctx, rule, this.baseUrl);
    } else {
      result = analyzeHtmlEl(this.doc, rule, this.baseUrl);
    }
    if (clean && result) {
      try {
        const re = new RegExp(clean, 'g');
        result = result.replace(re, '');
      } catch { /* 正则非法忽略 */ }
    }
    return (result || '').trim();
  }

  // 列表规则: 返回元素/对象数组
  getElements(rule) {
    rule = String(rule || '').trim();
    if (rule.startsWith('[') && rule.endsWith(']')) rule = rule.slice(1, -1); // 兼容 [xxx] 包裹
    for (const alt of splitTop(rule, '||')) {
      const r = alt.trim();
      let list = null;
      if (this.isJSON && !r.includes('@') || r.startsWith('$')) {
        const v = jsonPath(this.json, r);
        // 压平嵌套数组($..search 递归命中会包一层)
        list = (Array.isArray(v) ? v.flat(2) : (v == null ? [] : [v]));
      } else {
        list = selectElements(this.doc, r, this.baseUrl);
      }
      if (list && list.length) return list;
    }
    return [];
  }
}

// 顶层分割(忽略 {{}} 内的分隔符)
function splitTop(rule, sep) {
  const out = [];
  let depth = 0, cur = '';
  for (let i = 0; i < rule.length; i++) {
    const two = rule.substr(i, 2);
    if (two === '{{') { depth++; cur += two; i++; continue }
    if (two === '}}') { depth--; cur += two; i++; continue }
    if (depth === 0 && two === sep) { out.push(cur); cur = ''; i++; continue }
    cur += rule[i];
  }
  out.push(cur);
  return out;
}

// JSON 模板 {{$.a}} {{$.b}}
function templateJson(json, tpl) {
  return tpl.replace(/\{\{([^}]+)\}\}/g, (_, p) => {
    const v = jsonPath(json, p.trim());
    return v == null ? '' : String(v);
  }).trim();
}

// ---------- HTML 规则 ----------
const TERMINALS = new Set(['text', 'textNodes', 'html', 'all', 'ownText', 'href', 'src']);
function selectElements(root, rule, baseUrl) {
  // 返回元素数组; rule 可含 @ 终端
  const steps = rule.split('@');
  let cur = [root];
  for (let i = 0; i < steps.length; i++) {
    const step = steps[i].trim();
    if (!step) continue;
    const isLast = i === steps.length - 1;
    if (isLast && (TERMINALS.has(step) || step.startsWith('data-'))) break; // 终端由取值处理
    const next = [];
    for (const node of cur) {
      next.push(...selectStep(node, step));
    }
    cur = next;
  }
  return cur;
}
function selectStep(scope, step) {
  // legado 风格: class.x / tag.a / id.x / text.x + 索引; 或原生 CSS
  let m = step.match(/^(class|tag|id|text)\.(.+)$/);
  let css = null, textIdx = -1;
  if (m) {
    let kind = m[1], rest = m[2];
    let idx = null, exclude = false;
    const im = rest.match(/^(.*?)(!)?(\d+|-\d+)$/);
    if (im) { rest = im[1]; idx = parseInt(im[3]); exclude = !!im[2] }
    if (kind === 'class') css = '.' + rest.replace(/\./g, '.');
    else if (kind === 'tag') css = rest;
    else if (kind === 'id') css = '#' + rest;
    else if (kind === 'text') { textIdx = idx; idx = null; }
    const nodes = css != null
      ? Array.from((scope instanceof Document ? scope.documentElement : scope).querySelectorAll(css))
      : Array.from(scope.querySelectorAll('*')).filter(e =>
          e.children.length === 0 && (e.textContent || '').trim());
    if (textIdx != null && textIdx > -1) return [nodes[textIdx]].filter(Boolean);
    if (idx != null) {
      if (exclude) { nodes.splice(idx, 1); return nodes }
      return [nodes[idx]].filter(Boolean);
    }
    return nodes;
  }
  // 原生 CSS 兜底
  try { return Array.from(scope.querySelectorAll(step)) } catch { return [] }
}
function analyzeHtmlEl(ctx, rule, baseUrl) {
  // 在给定元素(ctx)上求值文本规则
  const steps = rule.split('@');
  let nodes = [ctx];
  let terminal = steps[steps.length - 1].trim();
  const hasTerminal = TERMINALS.has(terminal) || terminal.startsWith('data-')
    || /^[\w-]+$/.test(terminal) && steps.length > 1 && terminal !== 'text'
    ? TERMINALS.has(terminal) || terminal.startsWith('data-')
    : (TERMINALS.has(terminal) || terminal.startsWith('data-'));
  let elementSteps = hasTerminal ? steps.slice(0, -1) : steps;
  for (const step of elementSteps) {
    const s = step.trim();
    if (!s) continue;
    const next = [];
    for (const n of nodes) next.push(...selectStep(n, s));
    nodes = next;
    if (!nodes.length) return '';
  }
  if (!nodes.length) return '';
  const node = nodes[0];
  if (!hasTerminal) return node.textContent || '';
  if (terminal === 'text') return node.textContent || '';
  if (terminal === 'textNodes') {
    return Array.from(node.childNodes).map(n => n.textContent).join('');
  }
  if (terminal === 'html') return node.innerHTML || '';
  if (terminal === 'all') return node.outerHTML || '';
  if (terminal === 'href' || terminal === 'src') return absUrl(node.getAttribute(terminal), baseUrl);
  const attr = node.getAttribute(terminal);
  return attr == null ? '' : attr;
}

// ---------- AnalyzeUrl 简化 ----------
function buildRequest(ruleUrl, key, page, sourceUrl) {
  let url = String(ruleUrl || ''), options = {};
  const i = url.lastIndexOf(',{');
  if (i > -1 && url.trim().endsWith('}')) {
    try {
      options = JSON.parse(url.slice(i + 1));
      url = url.slice(0, i);
    } catch { /* 不是选项 */ }
  }
  url = url.replace(/\{\{key\}\}/g, encodeURIComponent(key || ''))
    .replace(/\{\{page\}\}/g, page || 1)
    .replace(/searchKey/g, key || '')
    .replace(/searchPage/g, page || 1);
  if (options.body) {
    options.body = String(options.body)
      .replace(/\{\{key\}\}/g, key || '')
      .replace(/searchKey/g, key || '');
  }
  if (!/^https?:/.test(url)) url = absUrl(url, sourceUrl);
  return { url, options };
}

// ---------- 抓书流程 ----------
async function searchSource(source, key) {
  const { url, options } = buildRequest(source.searchUrl, key, 1, source.bookSourceUrl);
  const headers = {};
  try { Object.assign(headers, JSON.parse(source.header || '{}')) } catch { }
  Object.assign(headers, options.headers || {});
  const body = await pfetch(url, {
    method: options.method, body: options.body, headers, charset: options.charset,
  });
  const az = new Analyzer(body, url);
  const ruleS = source.ruleSearch || {};
  const list = az.getElements(ruleS.bookList);
  const results = [];
  for (const item of list) {
    let name = az.getString(ruleS.name) // 顶层再算一遍会重复body; 见下
    break; // 占位,实际循环在下方
  }
  // 正确做法: 每条记录创建子 Analyzer 上下文
  const out = [];
  for (const item of list) {
    const sub = new Analyzer(
      (typeof item === 'object' && item instanceof Element) ? item.outerHTML : JSON.stringify(item),
      url);
    if (sub.isJSON) sub.json = item;
    const g = (rule) => {
      if (!rule) return '';
      let r = rule;
      // 相对规则: 元素上下文
      return sub.getString(r, item);
    };
    const name = g(ruleS.name), author = g(ruleS.author);
    let bookUrl = g(ruleS.bookUrl);
    if (!name || !bookUrl) continue;
    if (!/^https?:/.test(bookUrl)) bookUrl = absUrl(bookUrl, source.bookSourceUrl);
    out.push({
      name, author: author || '',
      bookUrl,
      coverUrl: absUrl(g(ruleS.coverUrl), source.bookSourceUrl),
      intro: g(ruleS.intro),
      lastChapter: g(ruleS.lastChapter),
      kind: g(ruleS.kind),
      sourceUrl: source.bookSourceUrl, sourceName: source.bookSourceName,
    });
  }
  return out;
}

async function getBookInfo(book) {
  const source = findSource(book.sourceUrl);
  const headers = {};
  try { Object.assign(headers, JSON.parse(source.header || '{}')) } catch { }
  const body = await pfetch(book.bookUrl, { headers });
  const az = new Analyzer(body, book.bookUrl);
  const rule = source.ruleBookInfo || {};
  const g = r => az.getString(r);
  const info = {
    name: g(rule.name) || book.name,
    author: g(rule.author) || book.author,
    coverUrl: absUrl(g(rule.coverUrl) || book.coverUrl, book.bookUrl),
    intro: g(rule.intro) || book.intro,
    lastChapter: g(rule.lastChapter),
    tocUrl: g(rule.tocUrl) || book.bookUrl,
  };
  if (!/^https?:/.test(info.tocUrl)) info.tocUrl = absUrl(info.tocUrl, book.bookUrl);
  return info;
}

async function getCatalog(book, tocUrl) {
  const source = findSource(book.sourceUrl);
  const headers = {};
  try { Object.assign(headers, JSON.parse(source.header || '{}')) } catch { }
  const body = await pfetch(tocUrl, { headers });
  const az = new Analyzer(body, tocUrl);
  const rule = source.ruleToc || {};
  const list = az.getElements(rule.chapterList);
  const out = [];
  for (const item of list) {
    const sub = new Analyzer(
      (typeof item === 'object' && item instanceof Element) ? item.outerHTML : JSON.stringify(item),
      tocUrl);
    if (sub.isJSON) sub.json = item;
    let url = sub.getString(rule.chapterUrl, item);
    if (!url) continue;
    if (!/^https?:/.test(url)) url = absUrl(url, tocUrl);
    out.push({ title: (sub.getString(rule.chapterName, item) || '').trim(), url });
  }
  // 倒序检测: 第一章 url 大于最后一章则反转
  if (out.length > 1 && out[0].url > out[out.length - 1].url) out.reverse();
  return out;
}

async function getContent(book, chapterUrl) {
  const source = findSource(book.sourceUrl);
  if (!source) throw new Error('书源不存在: ' + book.sourceUrl + '（书源可能已被移除）');
  const headers = {};
  try { Object.assign(headers, JSON.parse(source.header || '{}')) } catch { }
  const { url, options } = (() => {
    // 章节 url 可带 ,{options}
    let u = chapterUrl, o = {};
    const i = u.lastIndexOf(',{');
    if (i > -1 && u.trim().endsWith('}')) {
      try { o = JSON.parse(u.slice(i + 1)); u = u.slice(0, i) } catch { }
    }
    return { url: u, options: o };
  })();
  Object.assign(headers, options.headers || {});
  const body = await pfetch(url, { method: options.method, body: options.body, headers });
  const az = new Analyzer(body, url);
  const raw = az.getString(source.ruleContent?.content);
  return cleanContent(raw);
}

function cleanContent(raw) {
  if (!raw) return ['正文为空'];
  let s = raw
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/p>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'");
  const lines = s.split(/\n+/).map(l => l.trim()).filter(l => l.length > 0);
  return lines.length ? lines : ['正文为空'];
}

function findSource(sourceUrl) {
  return SOURCES.find(s => s.bookSourceUrl === sourceUrl);
}

// ---------- UI ----------
function render() {
  const app = el('#app');
  const s = store.settings;
  document.documentElement.dataset.theme = s.theme;
  if (state.view === 'shelf') renderShelf(app);
  else if (state.view === 'search') renderSearch(app);
  else if (state.view === 'book') renderBook(app);
  else if (state.view === 'read') renderRead(app);
}

function renderShelf(app) {
  const shelf = store.shelf;
  app.innerHTML = `
  <header class="topbar">
    <div class="brand">阅读 <span class="sub">H5</span></div>
    <form id="searchForm" class="search-form">
      <input id="searchInput" placeholder="搜索书名/作者" autocomplete="off">
      <button type="submit">搜索</button>
    </form>
    <button class="ghost" onclick="openSettings()">设置</button>
  </header>
  <main class="shelf-main">
    ${shelf.length === 0 ? `<div class="empty">
      <p>书架是空的</p>
      <p class="hint">顶部搜索，共内置 ${SOURCES.length} 个书源</p>
    </div>` : `<div class="shelf-grid">${shelf.map((b, i) => `
      <div class="shelf-book" onclick="openShelfBook(${i})">
        ${b.coverUrl ? `<img loading="lazy" src="${esc(b.coverUrl)}" onerror="this.style.visibility='hidden'">`
          : `<div class="cover-fallback">${esc(b.name.slice(0, 2))}</div>`}
        <div class="bname" title="${esc(b.name)}">${esc(b.name)}</div>
        <div class="bauthor">${esc(b.author || '')}</div>
      </div>`).join('')}
    </div>`}
  </main>`;
  el('#searchForm').onsubmit = e => {
    e.preventDefault();
    const q = el('#searchInput').value.trim();
    if (q) doSearch(q);
  };
}

function renderSearch(app) {
  app.innerHTML = `
  <header class="topbar">
    <button class="ghost" onclick="go('shelf')">← 书架</button>
    <form id="searchForm" class="search-form">
      <input id="searchInput" value="${esc(state.searchKey)}" autocomplete="off">
      <button type="submit">搜索</button>
    </form>
    <button class="ghost" onclick="openSettings()">设置</button>
  </header>
  <main class="search-main">
    <div id="searchStatus"></div>
    <div id="searchList"></div>
  </main>`;
  el('#searchForm').onsubmit = e => {
    e.preventDefault();
    const q = el('#searchInput').value.trim();
    if (q) doSearch(q);
  };
}
async function doSearch(key) {
  state.searchKey = key;
  state.searchResults = [];
  state.view = 'search';
  render();
  const status = el('#searchStatus'), list = el('#searchList');
  status.innerHTML = `<div class="loading">探测网络…</div>`;
  const [kind] = await probeStrategy();
  let hint = '';
  if (kind === 'proxy' && fetchStrategy[1] === LOCAL_PROXY) {
    hint = `<div class="hint">已连接本地代理 (127.0.0.1:8727)。若持续无结果，请确认 start-proxy.bat 在运行。</div>`;
  } else if (kind === 'proxy') {
    hint = `<div class="hint">使用代理: ${esc(fetchStrategy[1].slice(0, 50))}</div>`;
  } else {
    hint = `<div class="hint">直连模式（仅部分带 CORS 的站点可用）。搜索无结果时请双击 start-proxy.bat 启动本地代理后重试。</div>`;
  }
  status.innerHTML = `<div class="loading">搜索中: 0/${SOURCES.length} 源</div>${hint}`;
  let done = 0, found = 0;
  const queue = [...SOURCES];
  const workers = new Array(Math.min(6, queue.length)).fill(0).map(async () => {
    while (queue.length) {
      const src = queue.shift();
      try {
        const rs = await searchSource(src, key);
        found += rs.length;
        state.searchResults.push(...rs);
        drawResults(list);
      } catch (e) { window.__searchErrors = (window.__searchErrors || []).concat(src.bookSourceName + ': ' + e.message) }
      done++;
      status.innerHTML = `<div class="loading">搜索中: ${done}/${SOURCES.length} 源, 已找到 ${found} 本</div>${hint}`;
    }
  });
  await Promise.all(workers);
  status.innerHTML = `<div class="done">完成: ${found} 本结果</div>${hint}`;
}
function drawResults(list) {
  const rs = [...state.searchResults].sort((a, b) => a.name.length - b.name.length);
  list.innerHTML = rs.map((b, i) => `
    <div class="result" onclick="openResult(${i})">
      ${b.coverUrl ? `<img loading="lazy" src="${esc(b.coverUrl)}" onerror="this.style.display='none'">`
        : `<div class="cover-fallback">${esc(b.name.slice(0, 2))}</div>`}
      <div class="rinfo">
        <div class="rname">${esc(b.name)}</div>
        <div class="rmeta">${esc(b.author || '佚名')} · <span class="chip">${esc(b.sourceName)}</span></div>
        ${b.lastChapter ? `<div class="rlast">${esc(b.lastChapter)}</div>` : ''}
        ${b.intro ? `<div class="rintro">${esc(b.intro.slice(0, 100))}</div>` : ''}
      </div>
    </div>`).join('');
}
function openResult(i) {
  state.currentBook = state.searchResults[i];
  state.view = 'book';
  render();
}

function renderBook(app) {
  const b = state.currentBook;
  app.innerHTML = `
  <header class="topbar">
    <button class="ghost" onclick="go('search')">← 搜索</button>
    <div class="grow"></div>
    <button class="ghost" onclick="openSettings()">设置</button>
  </header>
  <main class="book-main">
    <div id="bookHead" class="loading">加载书籍信息…</div>
    <div class="book-cols">
      <aside id="tocBox" class="toc-box"><div class="loading">加载目录…</div></aside>
      <div id="bookActions" class="book-actions"></div>
    </div>
  </main>`;
  (async () => {
    try {
      const info = await getBookInfo(b);
      Object.assign(b, info);
      state.catalog = await getCatalog(b, info.tocUrl);
      const prog = store.progress[b.bookUrl];
      const shelf = store.shelf;
      const inShelf = shelf.some(x => x.bookUrl === b.bookUrl);
      el('#bookHead').innerHTML = `
        <div class="bhead">
          ${b.coverUrl ? `<img class="bcover" src="${esc(b.coverUrl)}" onerror="this.style.display='none'">`
            : `<div class="cover-fallback big">${esc(b.name.slice(0, 2))}</div>`}
          <div>
            <h1>${esc(b.name)}</h1>
            <p class="bmeta">${esc(b.author || '')} · <span class="chip">${esc(b.sourceName)}</span> · ${state.catalog.length} 章</p>
            ${b.intro ? `<p class="bintro">${esc(b.intro.slice(0, 300))}</p>` : ''}
            <div class="bbtns">
              <button onclick="startRead(${prog ? prog.chapterIndex : 0})">
                ${prog ? `继续读 第${prog.chapterIndex + 1}章` : '开始阅读'}</button>
              <button class="ghost" onclick="toggleShelf()">${inShelf ? '移出书架' : '加入书架'}</button>
            </div>
          </div>
        </div>`;
      el('#tocBox').innerHTML = `<div class="toc-title">目录</div>
        <div class="toc-list">${state.catalog.map((c, i) => `
          <div class="toc-item ${prog && prog.chapterIndex === i ? 'cur' : ''}"
            onclick="startRead(${i})">${esc(c.title)}</div>`).join('')}</div>`;
    } catch (e) {
      el('#bookHead').innerHTML = `<div class="empty">加载失败: ${esc(e.message)}</div>`;
    }
  })();
}
function toggleShelf() {
  const b = state.currentBook;
  const shelf = store.shelf;
  const i = shelf.findIndex(x => x.bookUrl === b.bookUrl);
  if (i > -1) { shelf.splice(i, 1); store.shelf = shelf }
  else {
    shelf.push({
      name: b.name, author: b.author, bookUrl: b.bookUrl, tocUrl: b.tocUrl,
      coverUrl: b.coverUrl, sourceUrl: b.sourceUrl, sourceName: b.sourceName,
    });
    store.shelf = shelf;
  }
  render();
}
function openShelfBook(i) {
  const b = store.shelf[i];
  state.currentBook = { ...b };
  state.view = 'book';
  render();
}

function renderRead(app) {
  const b = state.currentBook;
  const s = store.settings;
  app.innerHTML = `
  <header class="read-topbar">
    <button class="ghost" onclick="backToBook()">← 目录</button>
    <div class="rtitle">${esc(state.catalog[state.chapterIndex]?.title || b.name)}</div>
    <div class="grow"></div>
    <button class="ghost" onclick="prevChapter()">上一章</button>
    <button class="ghost" onclick="nextChapter()">下一章</button>
    <button class="ghost" onclick="openSettings()">设置</button>
  </header>
  <main class="read-main">
    <div id="readBody" class="read-body"></div>
    <div id="picWin" class="pic-win" style="display:none">
      <div class="pic-status" id="picStatus"></div>
      <img id="picImg" alt="">
      <div class="pic-info" id="picInfo"></div>
    </div>
  </main>`;
  (async () => {
    const body = el('#readBody');
    body.innerHTML = `<div class="loading">加载中…</div>`;
    try {
      const ch = state.catalog[state.chapterIndex];
      const paras = await getContent(b, ch.url);
      body.innerHTML = paras.map(p => `<p>${esc(p)}</p>`).join('');
      window.scrollTo(0, 0);
      saveProgress(0);
      // AI 生图：按阅读窗口可视文本触发（对应 app onVisiblePagesChanged）
      triggerPicGen();
    } catch (e) {
      body.innerHTML = `<div class="empty">加载失败: ${esc(e.message)}<br>
        <button onclick="startRead(${state.chapterIndex})">重试</button></div>`;
    }
  })();
}

/** 取当前阅读窗口可视文本（viewport 内段落拼起来） */
function visibleText() {
  const body = el('#readBody');
  if (!body) return '';
  const paras = Array.from(body.querySelectorAll('p'));
  const vh = window.innerHeight;
  let text = '';
  for (const p of paras) {
    const r = p.getBoundingClientRect();
    if (r.bottom > 0 && r.top < vh) text += p.textContent + '\n';
  }
  return text.trim();
}
function triggerPicGen() {
  const cfg = picgenStore.cfg;
  const win = el('#picWin');
  if (!cfg.windowShow) { if (win) win.style.display = 'none'; return }
  win.style.display = 'flex';
  // 阅读位置 key：书+章+滚动位置（每屏一张，对应 app 分页生图）
  const key = state.currentBook.bookUrl + '#' + state.chapterIndex +
    '#' + Math.floor(window.scrollY / window.innerHeight);
  picGenFor(key, visibleText(),
    s => { el('#picStatus').textContent = s; el('#picImg').style.display = 'none' },
    (dataUrl, info) => {
      el('#picStatus').textContent = '';
      const img = el('#picImg');
      img.src = dataUrl;
      img.style.display = 'block';
      el('#picInfo').textContent = picgenStore.cfg.showInfo ? info : '';
    });
}
// 滚动节流重新触发
let picScrollTimer = null;
window.addEventListener('scroll', () => {
  if (state.view !== 'read') return;
  if (picScrollTimer) return;
  picScrollTimer = setTimeout(() => { picScrollTimer = null; triggerPicGen() }, 800);
});
function prevChapter() {
  if (state.chapterIndex > 0) startRead(state.chapterIndex - 1);
}
function nextChapter() {
  if (state.chapterIndex < state.catalog.length - 1) startRead(state.chapterIndex + 1);
}
function backToBook() {
  state.view = 'book';
  render();
}
function startRead(idx) {
  state.chapterIndex = idx;
  state.view = 'read';
  render();
}
function saveProgress(pos) {
  const p = store.progress;
  p[state.currentBook.bookUrl] = { chapterIndex: state.chapterIndex, pos };
  store.progress = p;
}

// ---------- 设置 ----------
function openSettings() {
  const s = store.settings;
  const p = picgenStore.cfg;
  let dlg = el('#settingsDlg');
  if (dlg) dlg.remove();
  dlg = document.createElement('div');
  dlg.id = 'settingsDlg';
  dlg.className = 'dlg-mask';
  dlg.innerHTML = `
    <div class="dlg">
      <h3>设置</h3>
      <label>正文字号 <span id="fsVal">${s.fontSize}</span>px
        <input id="fsRange" type="range" min="14" max="40" value="${s.fontSize}">
      </label>
      <label>主题
        <select id="themeSel">
          <option value="day" ${s.theme === 'day' ? 'selected' : ''}>日间</option>
          <option value="night" ${s.theme === 'night' ? 'selected' : ''}>夜间</option>
        </select>
      </label>
      <label>CORS 代理 (推荐双击 start-proxy.bat 启动本地代理自动生效; 也可填自有代理, 含 {url} 占位符)
        <input id="proxyInput" placeholder="https://your-proxy.com/?url={url}"
          value="${esc(s.proxy)}">
      </label>
      <hr>
      <h4>AI 生图</h4>
      <label><input type="checkbox" id="pgWin" ${p.windowShow ? 'checked' : ''}> 阅读时显示图片窗口</label>
      <label>生图风格
        <select id="pgStyle">
          <option value="0" ${p.styleIndex === 0 ? 'selected' : ''}>未选择</option>
          ${PICGEN_STYLES.map((st, i) =>
            `<option value="${i + 1}" ${p.styleIndex === i + 1 ? 'selected' : ''}>${esc(st.name)}</option>`).join('')}
        </select>
      </label>
      <label><input type="checkbox" id="pgAgnes" ${p.agnesOn ? 'checked' : ''}> agnes 免费生图渠道（内置 key 竞速）</label>
      <label>自定义 OpenAI 兼容站 Base（如 https://api.openai.com）
        <input id="pgBase" placeholder="https://your-gateway.com" value="${esc(p.customBase)}">
      </label>
      <label>API Key
        <input id="pgToken" placeholder="sk-..." value="${esc(p.customToken)}">
      </label>
      <label>生图模型名
        <input id="pgImgModel" placeholder="如 flux.1-schnell / agnes-image-2.5-flash"
          value="${esc(p.customImageModel)}">
      </label>
      <label>摘要模型名（留空用 agnes 兜底）
        <input id="pgChatModel" placeholder="如 gpt-4o-mini" value="${esc(p.customChatModel)}">
      </label>
      <label><input type="checkbox" id="pgInfo" ${p.showInfo ? 'checked' : ''}> 图片窗口显示生成信息</label>
      <label><input type="checkbox" id="pgFull" ${p.includeFullText ? 'checked' : ''}> 提示词附带当前屏全文</label>
      <div class="dlg-btns">
        <button class="ghost" onclick="this.closest('.dlg-mask').remove()">关闭</button>
      </div>
    </div>`;
  document.body.appendChild(dlg);
  el('#fsRange').oninput = e => {
    el('#fsVal').textContent = e.target.value;
    const ns = store.settings; ns.fontSize = +e.target.value; store.settings = ns;
    document.documentElement.style.setProperty('--read-font', e.target.value + 'px');
  };
  el('#themeSel').onchange = e => {
    const ns = store.settings; ns.theme = e.target.value; store.settings = ns;
    document.documentElement.dataset.theme = e.target.value;
  };
  el('#proxyInput').onchange = e => {
    const ns = store.settings; ns.proxy = e.target.value.trim(); store.settings = ns;
    resetFetchStrategy();
  };
  // AI 生图设置
  const savePg = () => { picgenStore.cfg = p };
  el('#pgWin').onchange = e => { p.windowShow = e.target.checked; savePg(); triggerPicGen() };
  el('#pgStyle').onchange = e => { p.styleIndex = +e.target.value; savePg() };
  el('#pgAgnes').onchange = e => { p.agnesOn = e.target.checked; savePg() };
  el('#pgBase').onchange = e => { p.customBase = e.target.value.trim(); savePg() };
  el('#pgToken').onchange = e => { p.customToken = e.target.value.trim(); savePg() };
  el('#pgImgModel').onchange = e => { p.customImageModel = e.target.value.trim(); savePg() };
  el('#pgChatModel').onchange = e => { p.customChatModel = e.target.value.trim(); savePg() };
  el('#pgInfo').onchange = e => { p.showInfo = e.target.checked; savePg() };
  el('#pgFull').onchange = e => { p.includeFullText = e.target.checked; savePg() };
  dlg.addEventListener('click', e => { if (e.target === dlg) dlg.remove() });
}

// ---------- 键盘 ----------
document.addEventListener('keydown', e => {
  if (state.view !== 'read') return;
  if (e.target.tagName === 'INPUT' || e.target.tagName === 'SELECT') return;
  if (e.key === 'ArrowLeft') prevChapter();
  if (e.key === 'ArrowRight') nextChapter();
});

// ---------- 路由 ----------
function go(view) { state.view = view; render(); }
window.go = go; window.doSearch = doSearch; window.openResult = openResult;
window.openShelfBook = openShelfBook; window.startRead = startRead;
window.toggleShelf = toggleShelf; window.openSettings = openSettings;
window.prevChapter = prevChapter; window.nextChapter = nextChapter;
window.backToBook = backToBook;
window.resetFetchStrategy = resetFetchStrategy;

// 启动
document.documentElement.style.setProperty('--read-font', store.settings.fontSize + 'px');
render();
