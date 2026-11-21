// picgen 移植单测：PromptBuilder 逻辑 + 竞速/解析（不触网）
const fs = require('fs');
const src = fs.readFileSync(__dirname + '/../picgen.js', 'utf8');
const engine = src.slice(src.indexOf('const SUMMARY_PROMPT'), src.indexOf('async function chatOnce'));
const head = src.slice(0, src.indexOf('const PICGEN_STYLES'))
  .replace('/*__PICGEN_STYLES__*/[]', '[]');

// 最小 DOM stub
const sandbox = new Function('window', 'document', 'localStorage', 'fetch', 'AbortSignal', head + engine + '\nreturn { cleanTags, extractTags, buildFallbackPrompt, isShortPage, pageFragments, mergeKeywords, buildSummaryPrompt };');
const api = sandbox(
  { addEventListener() { } },
  {
    documentElement: { dataset: {} }, querySelector: () => null,
    createElement: () => ({ getContext: () => null }),
  },
  { getItem: () => null, setItem() { } },
  () => Promise.reject(new Error('no net')),
  { timeout: () => null },
);

let fails = 0;
function eq(actual, expected, name) {
  const a = JSON.stringify(actual), b = JSON.stringify(expected);
  if (a !== b) { console.log('FAIL', name, '\n  got     ', a, '\n  expected', b); fails++ }
  else console.log('ok  ', name);
}

// cleanTags: 去中文/去重/过滤（TAGS: 行里的英文部分会被拆开逗号，与 app 一致按 token 处理）
eq(api.cleanTags('TAGS:\nmasterpiece, 1girl, 夜空 night sky, 1girl'), 'TAGS:\nmasterpiece, 1girl, night, sky', 'cleanTags');
// extractTags: TAGS 行提取（与 app 一致: 多词 tag 被 cleanTags 规范化为逐词）
eq(api.extractTags('前置说明\nTAGS:\nmasterpiece, best quality, 1girl, night, moon, sword, ancient china'),
  'masterpiece, best, quality, 1girl, night, moon, sword, ancient, china', 'extractTags-TAGS行');
// extractTags: safety 响应拒绝
eq(api.extractTags('User Safty policy violation'), null, 'extractTags-safety');
// extractTags: tag 数不足拒绝
eq(api.extractTags('a, b'), null, 'extractTags-tooFew');
// 兜底提示词: 命中中文关键词（CN_TAG_MAP 顺序与 app 一致）
eq(api.buildFallbackPrompt('少女在雨夜持剑走向宫殿'),
  'masterpiece, best quality, highly detailed, ancient chinese style, novel illustration, 1girl, night, moonlight, starry sky, rain, wet, ancient chinese architecture, palace, sword, martial arts, dynamic pose',
  'fallback-tags');
// 短页判定
eq(api.isShortPage('短句'), true, 'isShortPage-true');
eq(api.isShortPage('很长的页'.repeat(30)), false, 'isShortPage-false');
// 分句
eq(api.pageFragments('少年拔剑。风起云涌！他望向远方：那是什么？'), ['少年拔剑', '风起云涌', '他望向远方', '那是什么'], 'pageFragments');
// 关键词合并去重
eq(api.mergeKeywords(['a, b', 'b, c', 'A, d']), 'a, b, c, d', 'mergeKeywords');
// summary 提示词拼接
eq(api.buildSummaryPrompt('XYZ').startsWith('You are a stable-diffusion prompt engineer'), true, 'summaryPrompt-head');
eq(api.buildSummaryPrompt('XYZ').endsWith('XYZ'), true, 'summaryPrompt-tail');

// parseImageResponse（OpenAI b64 与 NIM artifacts 两种）
const parseSandbox = new Function('window', 'document', 'localStorage', 'fetch', 'AbortSignal', 'FileReader',
  src.slice(0, src.indexOf('async function imageOnce'))
    .replace('/*__PICGEN_STYLES__*/[]', '[]')
  + '\nreturn parseImageResponse;');
const parseImageResponse = parseSandbox(
  { addEventListener() { } },
  { documentElement: {}, querySelector: () => null, createElement: () => ({ getContext: () => null }) },
  { getItem: () => null, setItem() { } },
  () => Promise.reject(new Error('no net')),
  { timeout: () => null },
  class FileReader { },
);
(async () => {
  const openai = await parseImageResponse(JSON.stringify({ data: [{ b64_json: 'QUJD' }] }));
  eq(openai, 'data:image/png;base64,QUJD', 'parse-openai-b64');
  const nim = await parseImageResponse(JSON.stringify({ artifacts: [{ base64: 'REVG' }] }));
  eq(nim, 'data:image/png;base64,REVG', 'parse-nim');
  let threw = false;
  try { await parseImageResponse('{}') } catch { threw = true }
  eq(threw, true, 'parse-empty-throws');

  if (fails) { console.log(fails + ' FAILURES'); process.exit(1) }
  console.log('PICGEN ALL TESTS DONE');
})();
