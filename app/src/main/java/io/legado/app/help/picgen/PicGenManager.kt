package io.legado.app.help.picgen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.help.book.BookHelp
import io.legado.app.data.appDb
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import io.legado.app.constant.AppLog
import io.legado.app.model.ReadBook
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import splitties.init.appCtx
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 生图管理器（移植自 LayaAirReaderWithPic Gen.ts）：
 * - 文字 → 关键字 → TAGS LLM（竞速）→ 正则兜底 → 生图（agnes/omni 双族竞速 + pony/qwen 本地）
 * - 翻页任务集 diff：换页时中断不再需要的任务，命中的继续
 * - 磁盘 + 内存缓存
 */
object PicGenManager {

    private const val TAG = "PicGenManager"
    private val gson = Gson()
    private const val IMG_TIMEOUT_MS = 180_000L
    private const val CHAT_TIMEOUT_MS = 90_000L
    private const val COOLDOWN_SEC = 45L          //连续失败冷却
    private const val MAX_FAIL = 2                //连续失败次数阈值
    private const val PEEK_PAGES = 2              //预读页数：当前页 + 后 2 页
    private const val MAX_CONCURRENT = 5         //并发任务上限

    //动态分辨率档位：综合就绪度+耗时决策（decideSize）
    private const val LOW_SIZE = "512x512"
    private const val BASE_SIZE = "768x768"
    private const val HIGH_SIZE = "1024x1024"
    private const val FAST_MS = 2_000L    //耗时≤2s 视为足够快
    private const val SLOW_MS = 30_000L   //耗时>30s 视为偏慢
    @Volatile
    private var dynamicSizePx = BASE_SIZE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        //全渠道探活循环：启动即探活一轮；本地渠道(pony/qwen)未启用时 30s 快速重试，
        //全部启用后切回 10 分钟；云端渠道错过一轮也无妨（下轮补充）
        scope.launch {
            while (true) {
                probeOmniHosts()
                refreshSvcModels()
                val needFast = !PicGenConfig.channelPony || !PicGenConfig.channelQwen
                kotlinx.coroutines.delay(if (needFast) 30_000L else 10 * 60 * 1000L)
            }
        }
    }

    /** 探测一个 HTTP 地址：任何响应（含 4xx/5xx）都视为服务存在；连接失败/超时视为不可达 */
    private suspend fun probeHttp(url: String, token: String?): Boolean {
        return try {
            withTimeoutOrNull(8_000L) {
                val builder = Request.Builder().url(url)
                if (token != null) builder.header("Authorization", "Bearer $token")
                okHttpClient.newCall(builder.build()).execute().use { true }
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 全渠道探活：omni win/cat/win-local 网关 + pony/qwen 生图服务（本机、tailscale win 机候选）。
     * 能用就用，不能用不提示，仅记调试日志。
     */
    private suspend fun probeOmniHosts() {
        var winTs = false
        var winLocal = false
        var cat = false
        for (host in PicGenChannels.OMNI_HOSTS) {
            val reachable = probeHttp("${host.base}/v1/models", host.token)
            when (host.name) {
                "win" -> winTs = reachable
                "win-local" -> winLocal = reachable
                "cat" -> cat = reachable
            }
        }
        PicGenConfig.omniWinReachable = winTs || winLocal
        PicGenConfig.omniCatReachable = cat
        PicGenConfig.channelOmniWin = winTs || winLocal
        PicGenConfig.channelOmniCat = cat
        //pony/qwen：候选地址逐个探活，取首个可达的作为活跃地址
        ponyActiveBase = PicGenChannels.PONY_HOSTS.firstOrNull { probeHttp("$it/v1/models", null) }
        qwenActiveBase = PicGenChannels.QWEN_HOSTS.firstOrNull { probeHttp("$it/v1/models", null) }
        PicGenConfig.channelPony = ponyActiveBase != null
        PicGenConfig.channelQwen = qwenActiveBase != null
        LogUtils.d(TAG, "渠道探活: omni_win_ts=$winTs omni_win_local=$winLocal omni_cat=$cat " +
            "pony=$ponyActiveBase qwen=$qwenActiveBase")
    }

    /** 网关可达性判断：用探活缓存值（win-local 也以本轮探活结果为准） */
    private fun hostReachable(host: PicGenChannels.OmniHost): Boolean {
        return if (host.name == "cat") PicGenConfig.omniCatReachable
        else PicGenConfig.omniWinReachable
    }

    //缓存
    private val cacheDir by lazy { File(appCtx.cacheDir, "picgen").apply { mkdirs() } }
    private val memCache = object : LruCache<String, Bitmap>(12) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    //任务表：cacheKey -> Job；换页 diff 用
    private val jobs = ConcurrentHashMap<String, Job>()
    private val jobsMutex = Mutex()

    //生图信息表：cacheKey -> "prompt\nsource"（图片窗口左下角诊断层用）
    private val genInfoCache = ConcurrentHashMap<String, String>()

    //冷却表：路由 key -> 恢复时间戳
    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private val failCount = ConcurrentHashMap<String, Int>()
    private val routeMutex = Mutex()

    //轮换计数器
    @Volatile
    private var agnesRR = 0
    @Volatile
    private var omniRR = 0

    //pony/qwen 探活后的活跃地址（null 表示全部不可达）
    @Volatile
    private var ponyActiveBase: String? = null
    @Volatile
    private var qwenActiveBase: String? = null

    // ---- 服务模型发现：/v1/models → 文生图/摘要模型分组（探活周期刷新，检测按钮即时刷新）----
    data class SvcModels(val image: List<String>, val chat: List<String>)

    private val svcCache = ConcurrentHashMap<String, SvcModels>()

    //文生图模型名特征 / 非对话模型排除
    private val IMAGE_MODEL_RE =
        Regex("(?i)flux|diffusion|sd3|sdxl|stable|image|dall|kolors|cogview|seedream|mirage|irag|sora|janus")
    private val NON_CHAT_RE =
        Regex("(?i)embed|rerank|rank|whisper|clip|ocr|guard|moderation|retriev|speech|tts|asr|safety|yolo|detect")

    private fun classifyModels(ids: List<String>): SvcModels {
        val image = ids.filter { IMAGE_MODEL_RE.containsMatchIn(it) }
        val chat = ids.filter { !IMAGE_MODEL_RE.containsMatchIn(it) && !NON_CHAT_RE.containsMatchIn(it) }
        //偏好常见摘要模型排前
        val prefer = Regex("(?i)instruct|nemotron|llama|qwen|deepseek|sensenova|sensechat|agnes|step|gpt|flash")
        return SvcModels(image.take(6), chat.sortedByDescending { prefer.containsMatchIn(it) }.take(6))
    }

    /** GET /v1/models 结果：ids 为 null 表示发现失败（httpCode/err 供诊断） */
    data class ModelsDiscovery(val ids: List<String>?, val httpCode: Int?, val err: String?)

    private suspend fun discoverModels(base: String, token: String): ModelsDiscovery {
        return try {
            var code: Int? = null
            val data = withTimeoutOrNull(15_000L) {
                val req = Request.Builder().url("$base/v1/models")
                    .header("Authorization", "Bearer $token")
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    code = resp.code
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            }
            if (data == null) return ModelsDiscovery(null, code, if (code == null) "超时" else null)
            val obj = parseJson(data) ?: return ModelsDiscovery(null, code, "解析失败")
            val arr = obj.getAsJsonArray("data") ?: return ModelsDiscovery(null, code, "无data")
            ModelsDiscovery(
                arr.mapNotNull { it.asJsonObject?.get("id")?.takeIf { id -> !id.isJsonNull }?.asString },
                code, null
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ModelsDiscovery(null, null, e.message ?: e.javaClass.simpleName)
        }
    }

    /** 某站可用模型组（缓存优先，缓存为空则现场发现一次） */
    private suspend fun svcModels(siteKey: String, base: String, token: String): SvcModels? {
        svcCache[siteKey]?.let { return it }
        val models = discoverModels(base, token).ids?.let { classifyModels(it) }
        if (models != null) svcCache[siteKey] = models
        return models
    }

    /** 探活周期刷新所有用户服务的模型清单（agnes 内置 key；sensenova/nim 用户 key） */
    private suspend fun refreshSvcModels() {
        val targets = ArrayList<Triple<String, String, String>>() //siteKey, base, token
        for (site in PicGenChannels.AGNES_SITES) {
            site.tokens.firstOrNull()?.let { targets.add(Triple(site.key, site.base, it)) }
        }
        for (site in PicGenChannels.SENSENOVA_SITES) {
            val tk = userKeys(if (site.key == "sensenova_cn") PicGenConfig.sensenovaKeysCn else PicGenConfig.sensenovaKeysCom)
                .firstOrNull()
            if (tk != null) targets.add(Triple(site.key, site.base, tk))
        }
        userKeys(PicGenConfig.nimKeys).firstOrNull()?.let {
            targets.add(Triple(PicGenChannels.NIM_SITE.key, PicGenChannels.NIM_SITE.base, it))
        }
        targets.map { (k, b, t) ->
            scope.async {
                discoverModels(b, t).ids?.let { svcCache[k] = classifyModels(it) }
            }
        }.forEach { it.join() }
    }

    /** 用户多行 key 拆分 */
    private fun userKeys(text: String): List<String> =
        text.split('\n', ',').map { it.trim() }.filter { it.length > 8 }

    /** key 管理对话框检测：模型发现 + 摘要模型实测，返回结果描述 */
    suspend fun testService(siteKey: String, token: String): String {
        val (base, fallbackChat) = when (siteKey) {
            "agnes_cn" -> PicGenChannels.AGNES_SITES.first { it.key == "agnes_cn" }.base to PicGenChannels.AGNES_CHAT_MODELS
            "agnes_com" -> PicGenChannels.AGNES_SITES.first { it.key == "agnes_com" }.base to PicGenChannels.AGNES_CHAT_MODELS
            "sensenova_cn" -> PicGenChannels.SENSENOVA_SITES.first { it.key == "sensenova_cn" }.base to PicGenChannels.SENSENOVA_CHAT_MODELS
            "sensenova_com" -> PicGenChannels.SENSENOVA_SITES.first { it.key == "sensenova_com" }.base to PicGenChannels.SENSENOVA_CHAT_MODELS
            else -> PicGenChannels.NIM_SITE.base to PicGenChannels.NIM_CHAT_MODELS
        }
        return try {
            val t0 = System.currentTimeMillis()
            //发现失败不算服务不可用：很多站（如 agnes）不提供 /v1/models，直接用兜底模型实测
            val disc = discoverModels(base, token)
            if (disc.ids != null) svcCache[siteKey] = classifyModels(disc.ids)
            val ms = System.currentTimeMillis() - t0
            val svc = svcCache[siteKey]
            val chatModels = svc?.chat?.takeIf { it.isNotEmpty() } ?: fallbackChat
            //实测最多试 3 个模型：发现的单个模型可能 404/403/429/空响应，不代表服务不可用
            var okModel: String? = null
            var lastRes: String? = null
            var lastModel = chatModels.first()
            for (m in chatModels.take(3)) {
                lastModel = m
                lastRes = testChat(base, token, m)
                if (lastRes != null && lastRes.startsWith("✓")) {
                    okModel = m
                    break
                }
            }
            if (okModel != null) {
                "$siteKey: ✓ ${ms}ms · 发现模型${disc.ids?.size ?: 0}个(图${svc?.image?.size ?: 0}/文${svc?.chat?.size ?: 0}) " +
                    "摘要模型[$okModel] · 实测 $lastRes"
            } else {
                "$siteKey: ✗ 摘要实测失败($lastModel) ${lastRes ?: "无响应"} " +
                    "· 发现: HTTP ${disc.httpCode ?: "-"} ${disc.err ?: ""}"
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            "$siteKey: ✗ ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 结果回调：cacheKey -> bitmap */
    @Volatile
    var onImageReady: ((cacheKey: String, bitmap: Bitmap, source: String) -> Unit)? = null

    @Volatile
    var onStatus: ((cacheKey: String, status: String) -> Unit)? = null

    /**
     * 翻页/加载后调用：重估任务集。
     * @param pages (stableKey, 生图文字) 列表。stableKey 由调用方给（阅读位置，横竖屏不变）
     */
    fun onVisiblePagesChanged(pages: List<Pair<String, String>>) {
        if (!PicGenConfig.windowShow) return
        val cleaned = pages.filter { it.second.isNotBlank() }
            .map { (key, text) -> key to text.take(1600) }
            .filter { it.second.length >= 20 }
        if (cleaned.isEmpty()) return
        scope.launch {
            val keys = cleaned.map { it.first }.toSet()
            //diff：停掉不再需要的任务
            jobsMutex.withLock {
                val toCancel = jobs.filterKeys { it !in keys }
                toCancel.values.forEach { it.cancel() }
                toCancel.keys.forEach { jobs.remove(it) }
            }
            //动态分辨率：综合 就绪度 + 近期生图耗时 决策
            //目标：用户尽量少等待 → 图片质量尽量好
            dynamicSizePx = decideSize(keys)
            cleaned.forEach { (key, text) ->
                genFor(key, text)
            }
        }
    }

    /** 某页图片是否已就绪（内存缓存或磁盘缓存） */
    private fun isReady(cacheKey: String): Boolean {
        if (memCache.get(cacheKey) != null) return true
        return File(cacheDir, "$cacheKey.png").exists()
    }

    // ---- 生图耗时统计（全局滑动窗口，最近 6 次）----
    private val genTimes = ArrayDeque<Long>(6)
    private val genTimesMutex = Any()

    /** 记录一次生图耗时（毫秒），source 形如 "model@site" */
    private fun recordGenTime(ms: Long, @Suppress("UNUSED_PARAMETER") source: String) {
        synchronized(genTimesMutex) {
            genTimes.addLast(ms)
            if (genTimes.size > 6) genTimes.removeFirst()
        }
        LogUtils.d(TAG, "gen time: ${ms}ms via $source size=$dynamicSizePx")
    }

    /** 近期平均生图耗时（毫秒），无样本返回 0 */
    private fun avgGenTimeMs(): Long {
        synchronized(genTimesMutex) {
            if (genTimes.isEmpty()) return 0L
            return genTimes.sum() / genTimes.size
        }
    }

    /**
     * 动态分辨率决策：综合 就绪度 + 近期耗时
     * - 全部就绪 且 均耗时快(≤FAST_MS)  → 维持高档（够快，质量优先）
     * - 全部就绪 但 均耗时慢(>SLOW_MS)  → 降回基准（别让下一张等太久）
     * - 全部就绪 且 中等耗时            → 高档（有余量）
     * - 当前页就绪、部分预读缺          → 中等耗时升高档，慢则基准
     * - 当前页都没图                    → 快(≤FAST_MS)时基准即可（已够快），
     *                                     慢时低档加速追赶
     */
    private fun decideSize(keys: Set<String>): String {
        val readyCount = keys.count { isReady(it) }
        val allReady = keys.isNotEmpty() && readyCount >= keys.size
        val noneReady = readyCount == 0
        val avg = avgGenTimeMs()
        val noData = genTimesSize() == 0
        return when {
            allReady && (noData || avg <= SLOW_MS) -> HIGH_SIZE
            allReady -> BASE_SIZE
            noneReady && noData -> BASE_SIZE
            noneReady && avg <= FAST_MS -> BASE_SIZE
            noneReady -> LOW_SIZE
            else -> if (avg <= FAST_MS) HIGH_SIZE else BASE_SIZE
        }
    }

    private fun genTimesSize(): Int {
        synchronized(genTimesMutex) { return genTimes.size }
    }

    fun onActivityDestroy() {
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        jobs.clear()
    }

    /** 单页生图：缓存命中直接回调，否则起任务 */
    private suspend fun genFor(cacheKey: String, pageText: String) {
        if (jobs.containsKey(cacheKey)) return
        if (jobs.size >= MAX_CONCURRENT) return
        memCache.get(cacheKey)?.let {
            withContext(Dispatchers.Main) { onImageReady?.invoke(cacheKey, it, "cache") }
            return
        }
        val file = File(cacheDir, "$cacheKey.png")
        if (file.exists()) {
            decodeFile(file)?.let { bmp ->
                memCache.put(cacheKey, bmp)
                withContext(Dispatchers.Main) { onImageReady?.invoke(cacheKey, bmp, "cache") }
                return
            }
        }
        val job = scope.launch {
            try {
                status(cacheKey, "生成提示词…")
                val prompt = buildPrompt(pageText)
                status(cacheKey, "生成图片…")
                val t0 = System.currentTimeMillis()
                val (bytes, source) = generateImage(prompt)
                if (isBlackImage(bytes)) {
                    throw IllegalStateException("生图返回全黑图")
                }
                //记录本次生图耗时（滑动窗口，供动态分辨率决策）
                recordGenTime(System.currentTimeMillis() - t0, source)
                genInfoCache[cacheKey] = "$prompt\n$source"
                val bmp = decodeBytes(bytes) ?: throw IllegalStateException("图片解码失败")
                file.writeBytes(bytes)
                memCache.put(cacheKey, bmp)
                withContext(Dispatchers.Main) { onImageReady?.invoke(cacheKey, bmp, source) }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    val detail = "生图失败: ${e.message}\n页: ${pageText.take(50)}…\n渠道: " +
                        "agnes_cn=${PicGenConfig.channelAgnesCn} agnes_com=${PicGenConfig.channelAgnesCom} " +
                        "omni_win=${PicGenConfig.channelOmniWin} omni_cat=${PicGenConfig.channelOmniCat} " +
                        "pony=${PicGenConfig.channelPony} qwen=${PicGenConfig.channelQwen} " +
                        "sensenova_keys=${userKeys(PicGenConfig.sensenovaKeysCn).size + userKeys(PicGenConfig.sensenovaKeysCom).size} " +
                        "nim_keys=${userKeys(PicGenConfig.nimKeys).size}"
                    LogUtils.d(TAG, "gen fail: ${e.message}")
                    AppLog.putNotSave(detail, e)
                    withContext(Dispatchers.Main) {
                        onStatus?.invoke(cacheKey, "失败（点击复制）: ${e.message?.take(40)}")
                    }
                }
            } finally {
                jobs.remove(cacheKey)
            }
        }
        jobs[cacheKey] = job
    }

    /**
     * 提示词三段聚合（去重）：
     * ① 本页所在章 ±1 三章全文 → LLM 摘要词（几十个，按章缓存）
     * ② 本页文字：少于 100 汉字且少于 200 英文字符 → 本页全文分句作关键词；否则 LLM 摘要（关闭/失败→本地正则兜底）
     * ③ 已选生图风格 → 风格英文关键词组；未选则空缺
     */
    private suspend fun buildPrompt(pageText: String): String {
        val parts = ArrayList<String>()
        if (PicGenConfig.llmEnable) {
            chapterKeywords()?.let { parts.add(it) }
        }
        if (isShortPage(pageText)) {
            val frags = pageFragments(pageText).take(12)
            if (frags.isNotEmpty()) parts.add(frags.joinToString(", "))
        } else if (PicGenConfig.llmEnable) {
            parts.add(chatRace(PromptBuilder.buildSummaryPrompt(pageText)) ?: PromptBuilder.buildFallbackPrompt(pageText))
        } else {
            parts.add(PromptBuilder.buildFallbackPrompt(pageText))
        }
        PicStyleConfig.promptFor(ReadBook.book)?.let { parts.add(it) }
        val prompt = mergeKeywords(parts)
        //单图约束：禁多格拼图/组图，只出一张完整图（对全渠道统一追加）
        return "$prompt, single image only, one complete picture, no grid, no collage, no multiple panels, no split panels, no comic panels, no text"
    }

    //① 三章摘要词缓存：章号 → 关键词串
    private val chapterKwMutex = Mutex()
    @Volatile
    private var chapterKwCache: Pair<Int, String>? = null

    private suspend fun chapterKeywords(): String? = chapterKwMutex.withLock {
        val book = ReadBook.book ?: return@withLock null
        val idx = ReadBook.durChapterIndex
        chapterKwCache?.takeIf { it.first == idx }?.let { return@withLock it.second.ifBlank { null } }
        val chapters = withContext(Dispatchers.IO) {
            try {
                appDb.bookChapterDao.getChapterList(book.bookUrl, (idx - 1).coerceAtLeast(0), idx + 1)
            } catch (e: Exception) {
                LogUtils.d(TAG, "chapter list fail: ${e.message}")
                null
            }
        } ?: return@withLock null
        val sb = StringBuilder()
        for (ch in chapters) {
            try {
                BookHelp.getContent(book, ch)?.let { sb.append(it).append('\n') }
            } catch (e: Exception) {
                LogUtils.d(TAG, "chapter content fail: ${e.message}")
            }
        }
        if (sb.isBlank()) return@withLock null
        val kw = chatRace(PromptBuilder.buildChapterPrompt(sb.toString())) ?: ""
        chapterKwCache = idx to kw
        kw.ifBlank { null }
    }

    /** 本页文字是否过少：少于 100 个汉字且少于 200 个其他字符 */
    private fun isShortPage(text: String): Boolean {
        val cjk = Regex("[\\u4e00-\\u9fff]").findAll(text).count()
        return cjk < 100 && text.length - cjk < 200
    }

    /** 本页全文分句作关键词：按标点切分，保留 2-40 字片段 */
    private fun pageFragments(text: String): List<String> =
        text.replace(Regex("\\s+"), " ")
            .split(Regex("[。！？；：…!?;:，、,\\n]+"))
            .map { it.trim() }
            .filter { it.length in 2..40 }
            .distinct()

    /** 多段关键词聚合：拆逗号 → trim → 忽略大小写去重 → 截 90 个 */
    private fun mergeKeywords(parts: List<String>): String {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (p in parts) {
            for (tok in p.split(Regex("[,，;；\\n]+"))) {
                val t = tok.trim()
                if (t.isEmpty()) continue
                if (seen.add(t.lowercase())) out.add(t)
            }
        }
        return out.take(90).joinToString(", ")
    }

    // ---- TAGS LLM 竞速：omni 优先 combo × 双机 + agnes/sensenova/NIM 文本模型，最先成功赢 ----
    private suspend fun chatRace(prompt: String): String? {
        val tasks = ArrayList<suspend () -> String?>()
        //omni 网关（探活判定不可达的直接跳过，避免长超时等待）
        for (host in PicGenChannels.OMNI_HOSTS) {
            val chOn = if (host.name == "cat") PicGenConfig.channelOmniCat else PicGenConfig.channelOmniWin
            if (!chOn || !hostReachable(host)) continue
            for (combo in PicGenChannels.PREFERRED_CHAT_COMBOS) {
                tasks.add { chatOnce(host.base, host.token, combo, prompt) }
            }
        }
        //agnes 文本模型（内置 key × 发现/兜底模型）
        for (site in PicGenChannels.AGNES_SITES) {
            val chOn = if (site.key == "agnes_cn") PicGenConfig.channelAgnesCn else PicGenConfig.channelAgnesCom
            if (!chOn) continue
            val tk = site.tokens.firstOrNull() ?: continue
            val models = svcModels(site.key, site.base, tk)?.chat?.takeIf { it.isNotEmpty() }
                ?: PicGenChannels.AGNES_CHAT_MODELS
            for (m in models.take(3)) {
                tasks.add { chatOnce(site.base, tk, m, prompt) }
            }
        }
        //SenseNova 文本模型：用户 key × 发现/兜底模型
        for (site in PicGenChannels.SENSENOVA_SITES) {
            val custom = if (site.key == "sensenova_cn") PicGenConfig.sensenovaKeysCn else PicGenConfig.sensenovaKeysCom
            val tk = userKeys(custom).firstOrNull() ?: continue
            val models = svcModels(site.key, site.base, tk)?.chat?.takeIf { it.isNotEmpty() }
                ?: PicGenChannels.SENSENOVA_CHAT_MODELS
            for (m in models.take(3)) {
                tasks.add { chatOnce(site.base, tk, m, prompt) }
            }
        }
        //Nvidia NIM 文本模型：用户 key × 发现/兜底模型
        userKeys(PicGenConfig.nimKeys).firstOrNull()?.let { tk ->
            val base = PicGenChannels.NIM_SITE.base
            val models = svcModels(PicGenChannels.NIM_SITE.key, base, tk)?.chat?.takeIf { it.isNotEmpty() }
                ?: PicGenChannels.NIM_CHAT_MODELS
            for (m in models.take(3)) {
                tasks.add { chatOnce(base, tk, m, prompt) }
            }
        }
        if (tasks.isEmpty()) return null
        @Suppress("UNCHECKED_CAST")
        return raceFirst(tasks.map { scope.async { it() } }) { it != null } as? String
    }

    private suspend fun chatOnce(base: String, token: String, model: String, prompt: String): String? {
        val rkey = "chat:$base:$model"
        if (!routeAvailable(rkey)) return null
        try {
            val body = gson.toJson(
                mapOf(
                    "model" to model,
                    "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
                    "max_tokens" to 800
                )
            )
            val data = withTimeoutOrNull(CHAT_TIMEOUT_MS) {
                postJsonRequest("$base/v1/chat/completions", body, token)
            } ?: throw IllegalStateException("chat 超时")
            val obj = parseJson(data) ?: throw IllegalStateException("chat 响应解析失败")
            val text = obj.getAsJsonArray("choices")?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")?.get("content")?.asString ?: ""
            recordSuccess(rkey)
            return PromptBuilder.extractTags(text)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            recordFailure(rkey)
            return null
        }
    }

    /**
     * key 测试（key 管理对话框用）：发一次极小 chat 请求。
     * 返回 null 表示成功（含耗时描述），否则为错误信息。
     */
    suspend fun testChat(base: String, token: String, model: String): String? {
        return try {
            val body = gson.toJson(
                mapOf(
                    "model" to model,
                    "messages" to listOf(mapOf("role" to "user", "content" to "hi")),
                    //部分服务拒绝过小的 max_tokens(内容不够切出即报错/返回空 choices),16 足够且仍极便宜
                    "max_tokens" to 16
                )
            )
            val t0 = System.currentTimeMillis()
            val data = withTimeoutOrNull(CHAT_TIMEOUT_MS) {
                postJsonRequest("$base/v1/chat/completions", body, token)
            } ?: return "超时"
            val obj = parseJson(data) ?: return "响应解析失败"
            val text = obj.getAsJsonArray("choices")?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")?.get("content")?.asString
            if (text.isNullOrEmpty()) "空响应" else "✓ ${System.currentTimeMillis() - t0}ms"
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.message ?: e.javaClass.simpleName
        }
    }

    // ---- 生图：agnes/sensenova/NIM/omni 多族并行竞速，最先成功赢；本地 pony/qwen 探活后用 ----
    private suspend fun generateImage(prompt: String): Pair<ByteArray, String> {
        val families = ArrayList<suspend () -> Pair<ByteArray, String>?>()
        //agnes 族
        if (PicGenConfig.channelAgnesCn || PicGenConfig.channelAgnesCom) {
            families.add { imageAgnes(prompt) }
        }
        //omni 族
        if (PicGenConfig.channelOmniWin || PicGenConfig.channelOmniCat) {
            families.add { imageOmniroute(prompt) }
        }
        //NIM 族：填了 key 即参与
        if (userKeys(PicGenConfig.nimKeys).isNotEmpty()) {
            families.add { imageNim(prompt) }
        }
        //SenseNova 族：发现到文生图模型才参与
        if (userKeys(PicGenConfig.sensenovaKeysCn).isNotEmpty() ||
            userKeys(PicGenConfig.sensenovaKeysCom).isNotEmpty()
        ) {
            families.add { imageSensenova(prompt) }
        }
        //本地族：后台探活可达才参与（本机或 tailscale win 机）
        if (PicGenConfig.channelPony) {
            ponyActiveBase?.let { base ->
                families.add { localTxt2Img(base, PromptBuilder.buildPonyPrompt(prompt))?.let { it to "pony@local" } }
            }
        }
        if (PicGenConfig.channelQwen) {
            qwenActiveBase?.let { base ->
                families.add { localTxt2Img(base, prompt.take(900))?.let { it to "qwen@local" } }
            }
        }
        if (families.isEmpty()) throw IllegalStateException("没有启用任何生图渠道")
        val result = raceFirst(families.map { scope.async { it() } }) { it != null }
        @Suppress("UNCHECKED_CAST")
        return result as? Pair<ByteArray, String>
            ?: throw IllegalStateException("全部生图渠道失败")
    }

    //轮换计数器：NIM 组合起点
    @Volatile
    private var nimRR = 0
    @Volatile
    private var snRR = 0

    /** 通用 OpenAI 兼容生图：/v1/images/generations，b64_json 或 url 或 NIM artifacts */
    private suspend fun imageOpenai(base: String, token: String, model: String, prompt: String): Pair<ByteArray, String>? {
        val rkey = "img:$base:${token.takeLast(8)}:$model"
        if (!routeAvailable(rkey)) return null
        return try {
            val body = gson.toJson(
                mapOf(
                    "model" to model,
                    "prompt" to prompt.take(900),
                    "n" to 1,
                    "size" to dynamicSizePx,
                    "response_format" to "b64_json"
                )
            )
            val data = withTimeoutOrNull(IMG_TIMEOUT_MS) {
                postJsonRequest("$base/v1/images/generations", body, token)
            } ?: return null
            val item = parseImageResponse(data) ?: return null
            recordSuccess(rkey)
            item.first to model
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            recordFailure(rkey)
            null
        }
    }

    private suspend fun imageAgnes(prompt: String): Pair<ByteArray, String>? {
        val combos = ArrayList<Triple<String, String, String>>() //base, token, siteKey
        for (site in PicGenChannels.AGNES_SITES) {
            val chOn = if (site.key == "agnes_cn") PicGenConfig.channelAgnesCn else PicGenConfig.channelAgnesCom
            if (!chOn) continue
            site.tokens.forEach { tk -> combos.add(Triple(site.base, tk, site.key)) }
        }
        //用户自定义 agnes key：cn/com 两个输入框各自多行，分别加入对应站竞速
        for (site in PicGenChannels.AGNES_SITES) {
            val custom = if (site.key == "agnes_cn") PicGenConfig.agnesKeysCn else PicGenConfig.agnesKeysCom
            userKeys(custom).forEach { tk -> combos.add(Triple(site.base, tk, "agnes_user")) }
        }
        if (combos.isEmpty()) return null
        //一波并发：所有未冷却的 token 各发一个请求，最先出图赢；模型用发现的文生图模型，无则兜底表
        val tasks = combos.mapIndexed { ti, (base, tk, siteKey) ->
            val svcKey = if (siteKey == "agnes_user") null else siteKey
            val models = svcKey?.let { svcModels(it, base, tk)?.image?.takeIf { im -> im.isNotEmpty() } }
                ?: PicGenChannels.AGNES_IMAGE_MODELS
            val model = models[(ti + agnesRR) % models.size]
            scope.async {
                imageOpenai(base, tk, model, prompt)?.let { (bytes, m) -> bytes to "${m}@${siteKey}" }
            }
        }
        agnesRR++
        @Suppress("UNCHECKED_CAST")
        return raceFirst(tasks) { it != null } as? Pair<ByteArray, String>
    }

    /** NIM 生图：用户 key × 发现/兜底模型，一波最多 6 个组合竞速 */
    private suspend fun imageNim(prompt: String): Pair<ByteArray, String>? {
        val keys = userKeys(PicGenConfig.nimKeys)
        if (keys.isEmpty()) return null
        val base = PicGenChannels.NIM_SITE.base
        val models = svcModels(PicGenChannels.NIM_SITE.key, base, keys.first())?.image?.takeIf { it.isNotEmpty() }
            ?: PicGenChannels.NIM_IMAGE_MODELS
        val combos = ArrayList<Pair<String, String>>() //model, token
        keys.forEach { tk -> models.forEach { m -> combos.add(m to tk) } }
        if (combos.isEmpty()) return null
        val off = nimRR % combos.size
        nimRR++
        val tasks = (0 until minOf(6, combos.size)).map { i ->
            val (model, tk) = combos[(off + i) % combos.size]
            scope.async {
                imageOpenai(base, tk, model, prompt)?.let { (bytes, m) -> bytes to "${m}@nim" }
            }
        }
        @Suppress("UNCHECKED_CAST")
        return raceFirst(tasks) { it != null } as? Pair<ByteArray, String>
    }

    /** SenseNova 生图：仅在 /v1/models 发现到文生图模型时参与 */
    private suspend fun imageSensenova(prompt: String): Pair<ByteArray, String>? {
        val tasks = ArrayList<suspend () -> Pair<ByteArray, String>?>()
        for (site in PicGenChannels.SENSENOVA_SITES) {
            val custom = if (site.key == "sensenova_cn") PicGenConfig.sensenovaKeysCn else PicGenConfig.sensenovaKeysCom
            val keys = userKeys(custom)
            if (keys.isEmpty()) continue
            val svc = svcCache[site.key]
            val models = svc?.image?.takeIf { it.isNotEmpty() } ?: continue
            val base = site.base
            val off = snRR % models.size
            snRR++
            keys.take(2).forEach { tk ->
                (0 until minOf(2, models.size)).forEach { i ->
                    val model = models[(off + i) % models.size]
                    tasks.add {
                        imageOpenai(base, tk, model, prompt)?.let { (bytes, m) -> bytes to "${m}@${site.key}" }
                    }
                }
            }
        }
        if (tasks.isEmpty()) return null
        @Suppress("UNCHECKED_CAST")
        return raceFirst(tasks.map { scope.async { it() } }) { it != null } as? Pair<ByteArray, String>
    }

    private suspend fun imageOmniroute(prompt: String): Pair<ByteArray, String>? {
        val combos = ArrayList<Triple<String, String, String>>() //base, token, hostName
        for (host in PicGenChannels.OMNI_HOSTS) {
            val chOn = if (host.name == "cat") PicGenConfig.channelOmniCat else PicGenConfig.channelOmniWin
            if (!chOn || !hostReachable(host)) continue
            for (m in PicGenChannels.OMNI_IMAGE_MODELS) {
                combos.add(Triple(host.base, host.token, host.name))
            }
        }
        if (combos.isEmpty()) return null
        var lastErr: String? = null
        //轮换顺序尝试（与 LayaAir 一致：失败冷却跳过）
        for (k in combos.indices) {
            val (base, tk, hostName) = combos[(omniRR + k) % combos.size]
            val model = PicGenChannels.OMNI_IMAGE_MODELS[(k + omniRR) % PicGenChannels.OMNI_IMAGE_MODELS.size]
            val rkey = "omni:$hostName:$model"
            if (!routeAvailable(rkey)) continue
            try {
                val body = gson.toJson(
                    mapOf(
                        "model" to model,
                        "prompt" to prompt.take(900),
                        "n" to 1,
                        "size" to dynamicSizePx,
                        "response_format" to "b64_json"
                    )
                )
                val data = withTimeoutOrNull(IMG_TIMEOUT_MS) {
                    postJsonRequest("$base/v1/images/generations", body, tk)
                } ?: throw IllegalStateException("超时")
                val item = parseImageResponse(data)
                    ?: throw IllegalStateException("响应无 b64_json 也无 url")
                recordSuccess(rkey)
                omniRR = (omniRR + k + 1) % combos.size
                return item.first to "${model}@${hostName}"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                recordFailure(rkey)
                lastErr = e.message
            }
        }
        if (lastErr != null) LogUtils.d(TAG, "omni fail: $lastErr")
        return null
    }

    private suspend fun localTxt2Img(base: String, prompt: String): ByteArray? {
        val rkey = "local:$base"
        if (!routeAvailable(rkey)) return null
        return try {
            //OpenAI 兼容格式优先
            val body = gson.toJson(
                mapOf(
                    "prompt" to prompt,
                    "n" to 1,
                    "size" to dynamicSizePx,
                    "response_format" to "b64_json"
                )
            )
            val data = withTimeoutOrNull(300_000L) {
                postJsonRequest("$base/v1/images/generations", body, null)
            }
            val item = if (data != null) parseImageResponse(data) else null
            if (item != null) {
                recordSuccess(rkey)
                return item.first
            }
            //sdapi/v1/txt2img 兜底
            val body2 = gson.toJson(
                mapOf(
                    "prompt" to prompt,
                    "width" to 512,
                    "height" to 512,
                    "steps" to 25,
                    "cfg_scale" to 1,
                    "sampler_name" to "euler",
                    "scheduler" to "simple",
                    "seed" to -1,
                    "batch_size" to 1
                )
            )
            val data2 = withTimeoutOrNull(300_000L) {
                postJsonRequest("$base/sdapi/v1/txt2img", body2, null)
            } ?: return null
            val obj = parseJson(data2) ?: return null
            val b64 = obj.getAsJsonArray("images")?.get(0)?.asString
            if (b64 != null) {
                recordSuccess(rkey)
                decodeB64(b64)
            } else null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            recordFailure(rkey)
            null
        }
    }

    // ---- HTTP ----
    private suspend fun postJsonRequest(url: String, json: String, token: String?): String {
        val builder = Request.Builder().url(url)
        builder.postJson(json)
        if (token != null) builder.header("Authorization", "Bearer $token")
        val request = builder.build()
        okHttpClient.newBuilder()
            .callTimeout(IMG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(IMG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
            .newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                return response.body?.string() ?: throw IllegalStateException("空响应")
            }
    }

    private suspend fun fetchBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            return response.body?.bytes() ?: throw IllegalStateException("空响应")
        }
    }

    /** 解析生图响应：b64_json / url / NIM artifacts 三格式 */
    private suspend fun parseImageResponse(data: String): Pair<ByteArray, Unit>? {
        val obj = parseJson(data) ?: return null
        val arr = obj.getAsJsonArray("data")
        if (arr != null && arr.size() > 0) {
            val item = arr[0].asJsonObject
            val b64 = item.get("b64_json")?.takeIf { !it.isJsonNull }?.asString
            if (!b64.isNullOrEmpty()) {
                return decodeB64(b64) to Unit
            }
            val url = item.get("url")?.takeIf { !it.isJsonNull }?.asString
            if (!url.isNullOrEmpty()) {
                return fetchBytes(url) to Unit
            }
        }
        //NIM 原生格式：{"artifacts":[{"base64":"…"}]}
        val arts = obj.getAsJsonArray("artifacts")
        if (arts != null && arts.size() > 0) {
            val b64 = arts[0].asJsonObject?.get("base64")?.takeIf { !it.isJsonNull }?.asString
            if (!b64.isNullOrEmpty()) return decodeB64(b64) to Unit
        }
        return null
    }

    // ---- 竞速工具：任一 async 成功即返回，其余 cancel ----
    private suspend fun raceFirst(
        deferred: List<Deferred<Any?>>,
        ok: (Any?) -> Boolean
    ): Any? {
        if (deferred.isEmpty()) return null
        val winner = CompletableDeferred<Any?>()
        val mainJob = scope.launch {
            try {
                val done = ArrayList<Deferred<Any?>>()
                while (done.size < deferred.size) {
                    val pending = deferred.filter { !it.isCompleted && it !in done }
                    if (pending.isEmpty()) break
                    val (d, r) = select {
                        pending.forEach { p ->
                            p.onAwait { p to it }
                        }
                    }
                    done.add(d)
                    if (ok(r)) {
                        winner.complete(r)
                        return@launch
                    }
                }
                winner.complete(null)
            } catch (e: Exception) {
                winner.completeExceptionally(e)
            }
        }
        val result = winner.await()
        mainJob.cancel()
        deferred.filter { it.isActive }.forEach { it.cancel() }
        return result
    }

    // ---- 冷却路由 ----
    private suspend fun routeAvailable(rkey: String): Boolean = routeMutex.withLock {
        val until = cooldownUntil[rkey] ?: 0
        until <= System.currentTimeMillis()
    }

    private suspend fun recordSuccess(rkey: String) = routeMutex.withLock {
        failCount.remove(rkey)
        cooldownUntil.remove(rkey)
    }

    private suspend fun recordFailure(rkey: String) = routeMutex.withLock {
        val fails = (failCount[rkey] ?: 0) + 1
        failCount[rkey] = fails
        if (fails >= MAX_FAIL) {
            cooldownUntil[rkey] = System.currentTimeMillis() + COOLDOWN_SEC * 1000
            failCount.remove(rkey)
        }
    }

    // ---- 工具 ----
    private fun pageKey(text: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun decodeB64(b64: String): ByteArray = Base64.decode(b64, Base64.NO_WRAP)

    private fun decodeFile(f: File): Bitmap? = BitmapFactory.decodeFile(f.absolutePath)

    private fun decodeBytes(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    /** 全黑图检测（移植自 LayaAir Cache.ts：低采样均值） */
    private fun isBlackImage(bytes: ByteArray): Boolean {
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return false
        var sum = 0L
        val w = bmp.width
        val h = bmp.height
        val step = (w * h / 100).coerceAtLeast(1)
        var sampled = 0
        var i = 0
        while (i < w * h) {
            val x = i % w
            val y = i / w
            if (y >= h) break
            val px = bmp.getPixel(x, y)
            sum += (px shr 16 and 0xFF) + (px shr 8 and 0xFF) + (px and 0xFF)
            sampled++
            i += step
        }
        bmp.recycle()
        val avg = if (sampled == 0) 255.0 else sum.toDouble() / sampled / 3.0
        return avg < 8.0   //均值 < 8 视为全黑
    }

    private suspend fun status(key: String, s: String) {
        withContext(Dispatchers.Main) { onStatus?.invoke(key, s) }
    }

    fun cachedBitmap(cacheKey: String): Bitmap? = memCache.get(cacheKey)

    /** 某页生图信息："提示词\n来源(model@site)"，未记录返回 null（图片窗口诊断层用） */
    fun infoFor(cacheKey: String): String? = genInfoCache[cacheKey]

    /** Gson 解析 JSON 字符串为 JsonObject */
    private fun parseJson(data: String?): JsonObject? {
        if (data.isNullOrBlank()) return null
        return try {
            JsonParser.parseString(data).asJsonObject
        } catch (e: Exception) {
            null
        }
    }
}
