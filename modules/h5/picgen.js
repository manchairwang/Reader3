/* ============================================================
   AI 生图（移植自 app PicGenManager/PromptBuilder/PicGenChannels）
   - 文字 → 关键词（LLM 竞速 → 正则兜底） → 生图（agnes 竞速 + 自定义 OpenAI 兼容站）
   - 内存 + localStorage 缓存
   ============================================================ */

const PICGEN_STYLES = /*__PICGEN_STYLES__*/[];
const IMG_TIMEOUT_MS = 180000;
const CHAT_TIMEOUT_MS = 90000;

// agnes 双站多密钥（原样移植 app PicGenChannels.AGNES_SITES）
const AGNES_SITES = [
  {
    key: 'agnes_cn', base: 'https://api.agnes-ai.cn',
    tokens: [
      'sk-V3bHQQy6Fhw0RnWznCyBrFAk6bSUziLeo8Kjqmey5iE21qlI',
      'sk-hEyUeEYPM3ojUyVWBVJnWS769274VheURRe3FNeyYXMLEp0G',
      'sk-y931juFcWiduQ9o4s0EvfYYfPOD4mQEhCB2xBPwKQVsy6Nax',
      'sk-dlQqvPZKBPNwSrXXZxsGDAvMvKBQ0xhIix50mOucq5Z3cm11',
      'sk-CVJwagumMvV4AkhgH1fKKJyAje8fiunYgkGLNRvVKnPsfekc',
      'sk-e9o3ger5cGwPnENtqVCuYK0moNcpjEGJOLOIkr4SF9MREQfY',
    ],
  },
  {
    key: 'agnes_com', base: 'https://apihub.agnes-ai.com',
    tokens: [
      'sk-X8uy6GgdUk2Cgbf8uadY2AzERGOdDdmzoVlMfsJ1eXnpRRi8',
      'sk-vMTwCMiTX3qFNJjmSi08gCFzoPyFIC8Wj1Bukt0tu2EGMtFS',
      'sk-H6ZoELeYzSk9kRrZSQiLe3B1xutYasB4XgjSrbNb2VauT7F2',
      'sk-1pDgDCa1OtK7JU6XGKOYFjqkOgISB18gYLZIDcoEvluOSDgk',
    ],
  },
];
const AGNES_IMAGE_MODELS = ['agnes-image-2.5-flash', 'agnes-image-2.1-flash', 'agnes-image-2.0-flash'];
const AGNES_CHAT_MODELS = ['agnes-2.5-flash', 'agnes-3.0-flash'];
const PROMPT_MAX = 3000;

// ---------- 配置 ----------
const picgenStore = {
  get cfg() {
    try {
      return Object.assign(
        {
          windowShow: true,     // 图片窗口总开关
          showInfo: false,     // 左下角诊断层
          styleIndex: 0,       // 生图风格 0=未选
          includeFullText: true,
          agnesOn: true,       // agnes 渠道
          customBase: '',      // 用户自定义 OpenAI 兼容站
          customToken: '',
          customImageModel: '',
          customChatModel: '',
        },
        JSON.parse(localStorage.getItem('h5_picgen') || '{}'),
      )
    } catch { return { windowShow: true, showInfo: false, styleIndex: 0, includeFullText: true, agnesOn: true, customBase: '', customToken: '', customImageModel: '', customChatModel: '' } }
  },
  set cfg(v) { localStorage.setItem('h5_picgen', JSON.stringify(v)) },
};

// ---------- 缓存（localStorage dataURL，只存最近 40 张防超限） ----------
const picMemCache = new Map(); // cacheKey -> {dataUrl, info}
function picCacheGet(key) {
  if (picMemCache.has(key)) return picMemCache.get(key);
  try {
    const all = JSON.parse(localStorage.getItem('h5_pics') || '{}');
    if (all[key]) {
      picMemCache.set(key, all[key]);
      return all[key];
    }
  } catch { }
  return null;
}
function picCachePut(key, dataUrl, info) {
  picMemCache.set(key, { dataUrl, info });
  try {
    const all = JSON.parse(localStorage.getItem('h5_pics') || '{}');
    all[key] = { dataUrl, info };
    const keys = Object.keys(all);
    if (keys.length > 40) delete all[keys[0]]; // 简单 FIFO
    localStorage.setItem('h5_pics', JSON.stringify(all));
  } catch { /* 存储满则放弃持久化 */ }
}

// ---------- PromptBuilder 移植 ----------
const SUMMARY_PROMPT =
  'You are a stable-diffusion prompt engineer. Read the novel excerpt below and write an English image-generation prompt.\n' +
  'OUTPUT RULES (mandatory):\n' +
  '1. Output English tags separated by commas. NO sentences, NO explanations.\n' +
  '2. Tags MUST cover: character count (1girl/2girls/1boy...), gender, hairstyle, clothing, action/pose, setting/scene, time of day, lighting, camera shot (close-up/wide shot/upper body...).\n' +
  '3. Use 15 to 30 tags total, Danbooru tag style, lowercase.\n' +
  '4. Each tag must be unique. Do not repeat tags.\n' +
  '5. Quality tags first: masterpiece, best quality, highly detailed, ancient chinese style.\n' +
  '6. First line must be exactly: TAGS:\n' +
  'Output format:\nTAGS:\n<comma separated english tags>\n';

const CN_TAG_MAP = [
  ['少女|女孩|姑娘|女子|她', '1girl'],
  ['男孩|男子|少年|青年|他', '1boy'],
  ['老者|老人|白须|老翁', '1old man'],
  ['孩子|小孩|童', 'child'],
  ['夜|深夜|月|星辰|星光', 'night, moonlight, starry sky'],
  ['黄昏|夕阳|傍晚', 'sunset, dusk'],
  ['清晨|黎明|朝阳|晨光', 'sunrise, morning light'],
  ['雨|下雨|雨滴', 'rain, wet'],
  ['雪|雪花|寒冬', 'snow, winter'],
  ['风|狂风|微风', 'wind, flowing clothes'],
  ['山|群山|峰|崇山', 'mountain, mountains'],
  ['林|森林|树林|竹', 'forest, trees'],
  ['江|河|湖|溪|水|海', 'river, water'],
  ['城|宫殿|皇宫|府|庭院|楼阁', 'ancient chinese architecture, palace'],
  ['村|小镇|茅屋|田园', 'village, countryside'],
  ['花|桃花|梅花|牡丹|荷', 'flowers, petals'],
  ['剑|刀|武|侠|战|打斗', 'sword, martial arts, dynamic pose'],
  ['马|骑|驰', 'horse, riding'],
  ['灯|烛|火|光', 'lantern, candlelight, warm lighting'],
  ['云|雾|烟', 'clouds, mist'],
  ['酒|宴|席', 'feast, banquet'],
  ['书|读|字|卷', 'book, reading'],
].map(([re, tag]) => [new RegExp(re), tag]);

function cleanTags(text) {
  const seen = new Set();
  return text.replace(/[\u4e00-\u9fff]+/g, ' ')
    .split(/[, ]+/).map(s => s.trim())
    .filter(s => s && /[a-z]/i.test(s))
    .filter(s => {
      const k = s.toLowerCase();
      if (seen.has(k)) return false;
      seen.add(k); return true;
    })
    .join(', ').replace(/[, ]{2,}/g, ', ').trim();
}
function extractTags(llmText) {
  const t = String(llmText || '');
  if (/safty|safety|User Safty/i.test(t)) return null;
  const m = t.match(/TAGS:\s*\n?([\s\S]*)/i);
  if (m) { const c = cleanTags(m[1]); if (c) return c }
  const c = cleanTags(t);
  // 数含英文字母的有效 tag（对应 app tagCount ≥ 5）
  const count = c ? c.split(',').filter(s => /[a-z]/i.test(s.trim())).length : 0;
  if (count < 5) return null;
  return c || null;
}
function buildFallbackPrompt(pageText) {
  const base = 'masterpiece, best quality, highly detailed, ancient chinese style, novel illustration';
  const found = [];
  for (const [re, tag] of CN_TAG_MAP) {
    if (re.test(pageText) && !found.includes(tag)) found.push(tag);
  }
  return base + (found.length ? ', ' + found.join(', ') : '');
}
function buildSummaryPrompt(pageText) { return SUMMARY_PROMPT + pageText.slice(0, 1500) }
function isShortPage(text) {
  const cjk = (text.match(/[\u4e00-\u9fff]/g) || []).length;
  return cjk < 100 && text.length - cjk < 200;
}
function pageFragments(text) {
  return text.replace(/\s+/g, ' ')
    .split(/[。！？；：…!?;:，、,\n]+/)
    .map(s => s.trim()).filter(s => s.length >= 2 && s.length <= 40)
    .filter((v, i, a) => a.indexOf(v) === i);
}
function mergeKeywords(parts) {
  const seen = new Set(), out = [];
  for (const p of parts) {
    for (const tok of String(p).split(/[,，;；\n]+/)) {
      const t = tok.trim();
      if (!t) continue;
      const k = t.toLowerCase();
      if (!seen.has(k)) { seen.add(k); out.push(t) }
    }
  }
  return out.slice(0, 90).join(', ');
}
function stylePrompt() {
  const st = PICGEN_STYLES[picgenStore.cfg.styleIndex - 1];
  return st ? st.prompt : '';
}

// ---------- HTTP ----------
async function postJson(url, body, token, timeout) {
  const r = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(timeout || IMG_TIMEOUT_MS),
  });
  if (!r.ok) throw new Error('HTTP ' + r.status);
  return await r.text();
}

// ---------- chat 竞速（提示词 LLM）----------
async function chatOnce(base, token, model, prompt) {
  try {
    const data = await postJson(base + '/v1/chat/completions', {
      model, max_tokens: 800,
      messages: [{ role: 'user', content: prompt }],
    }, token, CHAT_TIMEOUT_MS);
    const obj = JSON.parse(data);
    const msg = obj.choices?.[0]?.message || {};
    const text = msg.content || msg.reasoning_content || '';
    const tags = extractTags(text);
    return tags || null;
  } catch { return null }
}
function raceFirst(promises) {
  return new Promise(resolve => {
    let settled = false;
    promises.forEach(p =>
      p.then(v => { if (!settled && v) { settled = true; resolve(v) } })
       .catch(() => { }));
    Promise.all(promises).then(() => { if (!settled) { settled = true; resolve(null) } });
  });
}
async function chatRace(prompt) {
  const cfg = picgenStore.cfg;
  const tasks = [];
  if (cfg.agnesOn) {
    for (const site of AGNES_SITES) {
      for (const tk of site.tokens) {
        for (const m of AGNES_CHAT_MODELS) {
          tasks.push(chatOnce(site.base, tk, m, prompt));
        }
      }
    }
  }
  if (cfg.customBase && cfg.customToken) {
    const models = cfg.customChatModel ? [cfg.customChatModel] : AGNES_CHAT_MODELS;
    for (const m of models) {
      tasks.push(chatOnce(cfg.customBase, cfg.customToken, m, prompt));
    }
  }
  if (!tasks.length) return null;
  return await raceFirst(tasks);
}

// ---------- 生图 ----------
function parseImageResponse(data) {
  const obj = JSON.parse(data);
  // OpenAI 格式: {data:[{b64_json|url}]}
  if (obj.data && obj.data[0]) {
    const item = obj.data[0];
    if (item.b64_json) return Promise.resolve('data:image/png;base64,' + item.b64_json);
    if (item.url) {
      return fetch(item.url, { signal: AbortSignal.timeout(IMG_TIMEOUT_MS) })
        .then(r => {
          if (!r.ok) throw new Error('HTTP ' + r.status);
          return r.blob();
        })
        .then(blob => new Promise(res => {
          const fr = new FileReader();
          fr.onload = () => res(fr.result);
          fr.readAsDataURL(blob);
        }));
    }
  }
  // NIM 格式: {artifacts:[{base64}]}
  if (obj.artifacts && obj.artifacts[0] && obj.artifacts[0].base64) {
    return Promise.resolve('data:image/png;base64,' + obj.artifacts[0].base64);
  }
  return Promise.reject(new Error('生图响应无可识别图片'));
}
async function imageOnce(base, token, model, prompt) {
  const data = await postJson(base + '/v1/images/generations', {
    model, prompt: prompt.slice(0, PROMPT_MAX), n: 1,
    size: '1024x1024', response_format: 'b64_json',
  }, token, IMG_TIMEOUT_MS);
  const dataUrl = await parseImageResponse(data);
  // 全黑检测
  const black = await isBlackImage(dataUrl);
  if (black) throw new Error('生图返回全黑图');
  return { dataUrl, source: model + '@' + base.replace(/^https?:\/\//, '') };
}
function isBlackImage(dataUrl) {
  return new Promise(res => {
    const img = new Image();
    img.onload = () => {
      const c = document.createElement('canvas');
      c.width = img.width; c.height = img.height;
      const ctx = c.getContext('2d');
      ctx.drawImage(img, 0, 0);
      let sum = 0, n = 0;
      const step = Math.max(1, Math.floor((img.width * img.height) / 400));
      const px = ctx.getImageData(0, 0, img.width, img.height).data;
      for (let i = 0; i < px.length; i += 4 * step) {
        sum += px[i] + px[i + 1] + px[i + 2]; n++;
      }
      res(n > 0 && sum / n / 3 < 8);
    };
    img.onerror = () => res(false);
    img.src = dataUrl;
  });
}
async function generateImage(prompt) {
  const cfg = picgenStore.cfg;
  const tasks = [];
  if (cfg.agnesOn) {
    for (const site of AGNES_SITES) {
      const model = AGNES_IMAGE_MODELS[Math.floor(Math.random() * AGNES_IMAGE_MODELS.length)];
      for (const tk of site.tokens.slice(0, 2)) { // 每站最多 2 key 并发，防限流
        tasks.push(imageOnce(site.base, tk, model, prompt).catch(() => null));
      }
    }
  }
  if (cfg.customBase && cfg.customToken && cfg.customImageModel) {
    tasks.push(imageOnce(cfg.customBase, cfg.customToken, cfg.customImageModel, prompt).catch(() => null));
  }
  if (!tasks.length) return null;
  const r = await raceFirst(tasks);
  return r;
}

// ---------- 主入口（对应 app onVisiblePagesChanged + genFor，简化为单页）----------
const picgenJobs = new Map(); // key -> controller
async function picGenFor(cacheKey, pageText, onStatus, onReady) {
  if (!picgenStore.cfg.windowShow) return;
  const cached = picCacheGet(cacheKey);
  if (cached) { onReady(cached.dataUrl, cached.info); return }
  if (picgenJobs.has(cacheKey)) return;
  const ctrl = { cancelled: false };
  picgenJobs.set(cacheKey, ctrl);
  try {
    onStatus('生成提示词…');
    const parts = [];
    if (isShortPage(pageText)) {
      const frags = pageFragments(pageText).slice(0, 12);
      if (frags.length) parts.push(frags.join(', '));
    } else {
      parts.push((await chatRace(buildSummaryPrompt(pageText))) || buildFallbackPrompt(pageText));
    }
    const sp = stylePrompt();
    if (sp) parts.push(sp);
    let prompt = mergeKeywords(parts);
    if (picgenStore.cfg.includeFullText && pageText.trim()) {
      prompt = prompt + '\n' + pageText.trim();
    }
    if (ctrl.cancelled) return;
    onStatus('生成图片…');
    const t0 = Date.now();
    const r = await generateImage(prompt);
    if (ctrl.cancelled) return;
    if (!r) throw new Error('全部生图渠道失败');
    picCachePut(cacheKey, r.dataUrl, r.source);
    onReady(r.dataUrl, r.source + ' ' + ((Date.now() - t0) / 1000).toFixed(1) + 's');
  } catch (e) {
    onStatus('生图失败: ' + e.message);
  } finally {
    picgenJobs.delete(cacheKey);
  }
}
function picGenCancelAll() {
  for (const ctrl of picgenJobs.values()) ctrl.cancelled = true;
  picgenJobs.clear();
}
