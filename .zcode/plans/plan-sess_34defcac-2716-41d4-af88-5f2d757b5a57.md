# Reader3Pic 移植：阅读界面图片窗口 v1.1

## 目标
把 LayaAirReaderWithPic 的「正文文字 → 关键字 → LLM/正则 → 生图 API 竞速 → 图片」移植进 Reader3Pic 阅读页：
- 竖屏图片窗口在正文下方；横屏在正文右侧
- 翻页自动触发 + 预读后面页面预生成；换页重估任务，不需要的中断
- 全渠道移植：agnes_cn(6 token)/agnes_com(4 token)/omniroute(win/win-local/cat × mybestvision)/pony/qwen；LLM TAGS 竞速可用走摘要，全挂走 CN_TAG_MAP 正则兜底
- 图片窗口右上角一个显示开关按钮，点击关闭/打开图片窗口
- 配置界面新增一页；不做关键字预览窗口

## 新增文件
逻辑层 `app/src/main/java/io/legado/app/help/picgen/`：
1. `PicGenConfig.kt` — object，SharedPreferences 存储渠道开关（agnes_cn/com、omni_win/cat、pony、qwen）、LLM 开关、窗口高度/宽度占比、图片窗口总开关。PreferKey 加常量
2. `PicGenChannels.kt` — 渠道表（gen.config.json 原样：agnes 双站 10 token + imageModels 3 个 + chatModels、omniroute 3 host + mybestvision、pony 1234、qwen 1236）+ 请求构建/响应解析（b64_json/url 双格式，Authorization: Bearer）
3. `KeywordExtractor.kt` — Gen.ts 移植：extractCnKeywords（n-gram 频次+同频长词优先+停用表）、extractCnAdjectives（CN_ADJ_WHITE）、CN_TAG_MAP 中英映射
4. `PromptBuilder.kt` — SUMMARY_PROMPT 原样 + buildSummaryPrompt；TAGS LLM 调用链（omni chat 竞速 → agnes chat 兜底 → 本地正则兜底）、cleanTags
5. `PicGenManager.kt` — 单例。onVisiblePagesChanged：当前页+预读页发起任务；协程 Job map，换页 diff，不要的 cancel()；生图竞速（agnes/omni 双族并行→最先成功赢，含冷却表）；磁盘缓存 {cacheDir}/picgen/{md5}.png + 内存 LruCache

UI 层 `ui/book/read/`：
6. `PicWindowView.kt` — FrameLayout：ImageView + 进度条 + 右上角关闭/显示开关按钮（ImageView，点击切换 AppConfig 开关并隐藏窗口；再从配置页打开）
7. `res/layout/view_pic_window.xml`
8. `config/PicGenConfigDialog.kt` + `res/layout/dialog_pic_gen_config.xml` — 配置页：窗口开关、高度/宽度占比滑条、各渠道开关、LLM 开关

## 修改文件（最小 diff）
9. `activity_book_read.xml` — 加 PicWindowView（id pic_window，默认 gone）
10. `ReadBookActivity.kt` — contentLoadFinish/pageChanged 后调 PicGenManager 重估任务；拿 page.text 喂 Manager；横竖屏切换重设窗口位置 + read_view margin（竖屏正文 bottomMargin=窗口高，横屏 endMargin=窗口宽）；菜单呼出时窗口随正文让位
11. `ReadMenu.kt` + `view_read_menu.xml` — 底部菜单「界面」旁不新增按钮：在 MoreConfigDialog（设置页）加「图片窗口设置」入口（最小侵入），点击 show PicGenConfigDialog
12. `PreferKey.kt` — 加 picgen 相关 key 常量

## 关键行为（照搬 LayaAir）
- 生图：POST {base}/v1/images/generations，body {model, prompt≤900字, n:1, size:"1024x1024", response_format:"b64_json"}，超时 180s，连续失败冷却
- TAGS：POST {base}/v1/chat/completions，max_tokens 800，TAGS: 首行英文 tag ≥5 个才算成；safety 响应拒收
- 本地 pony/qwen 探活（/sdcpp/v1/models/status、GET /v1/models）后才用

## 版本
v1.1，构建后按发布流程：Reader3Pic_Android_1.1.apk → W:\Others\Incoming\PRJ\Reader3pic，ReleaseNote.txt 顶部追加，旧 APK 移入 History

## 不做
关键字预览窗口；不改工具条/菜单结构

## 验证
assembleAppDebug → 装机测试：翻页出图、预读、换页中断、横竖屏窗口换位、右上角按钮关窗、配置页开窗