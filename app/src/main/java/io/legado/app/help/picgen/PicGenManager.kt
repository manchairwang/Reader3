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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
    private const val IMG_TIMEOUT_MS = 110_000L  //单次生图请求上限(mybestvision 实测 105s)
    private const val CHAT_TIMEOUT_MS = 60_000L
    private const val RACE_TIMEOUT_MS = 115_000L //整场竞速上限(超时即判失败,不再无限转圈)
    private const val COOLDOWN_SEC = 45L          //连续失败冷却
    private const val MAX_FAIL = 2                //连续失败次数阈值
    private const val PEEK_PAGES = 2              //预读页数：当前页 + 后 2 页
    private const val MAX_CONCURRENT = 5         //并发任务上限
    private const val AGNES_WAVE = 2             //agnes 一波并发请求数(与服务器并发闸一致)
    private const val PROMPT_MAX = 3000         //生图提示词上限（含当前页全文，远超旧 900）

    //动态分辨率档位：综合就绪度+耗时决策（decideSize）
    //注意: cat 网关 schnell 仅支持 >=768 的尺寸(512/576/640 均 422), 下限即 768
    private const val LOW_SIZE = "768x768"
    private const val BASE_SIZE = "768x768"
    private const val HIGH_SIZE = "1024x1024"
    private const val FAST_MS = 2_000L    //耗时≤2s 视为足够快
    private const val SLOW_MS = 30_000L   //耗时>30s 视为偏慢
    @Volatile
    private var dynamicSizePx = BASE_SIZE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 供 UI 层(PicWindowView)启动拼图生成协程 */
    fun mosaicScope(): CoroutineScope = scope

    init {
        //注意：Kotlin object 属性和 init 块按声明顺序执行，此处(文件头部)await 不到靠后的字段，
        //组合注册/恢复循环放到协程内执行——那时所有字段已初始化完成。
        scope.launch {
            registerAllCombos()
            startComboRecoveryLoop()
            startRetryLoop()
            //全渠道探活循环(自动重试 win/cat/tailscale/本地模型):
            //内网断链或本地渠道缺失时 30s 快速重试; 全部健康后 2min 慢扫
            //模型发现(svcModels)带 5min 节流, 不随探活频率打满 agnes 限流额度
            while (true) {
                if (PicGenConfig.windowShow) {
                    probeOmniHosts()
                    refreshSvcModels()
                }
                val healthy = internalReachable &&
                    (PicGenConfig.channelQwen || PicGenConfig.channelPony || PicGenConfig.channelOmniCat)
                kotlinx.coroutines.delay(if (healthy) 120_000L else 30_000L)
            }
        }
    }

    /**
     * 失败页定时重试：每 30s 扫描最近可见页集中未就绪、无任务且上次失败已超过
     * 退避时间(60s)的页, 重新排队生成——阅读中"没图的页"自动补图。
     * 渠道全冷却时 genFor 秒回失败(不计耗时样本), 下一轮重试继续等。
     */
    private var lastPages: List<Pair<String, String>> = emptyList()
    private val lastFailAt = ConcurrentHashMap<String, Long>()
    private const val RETRY_BACKOFF_MS = 60_000L
    private fun startRetryLoop() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000L)
                if (!PicGenConfig.windowShow) continue
                val pages = lastPages
                if (pages.isEmpty()) continue
                val now = System.currentTimeMillis()
                for ((key, text) in pages) {
                    if (isReady(key)) { lastFailAt.remove(key); continue }
                    if (jobs.containsKey(key)) continue
                    val failAt = lastFailAt[key] ?: 0L
                    if (now - failAt < RETRY_BACKOFF_MS) continue
                    genFor(key, text)
                }
            }
        }
    }

    /**
     * 图片窗口关闭时调用：取消全部生图任务与拼图轮换，停掉一切文生图相关的后台活动。
     * （探活/模型发现循环按 windowShow 跳过；jobs 全部取消清空）
     */
    fun shutdownAll() {
        if (jobsMutex.tryLock()) {
            try {
                jobs.values.forEach { it.cancel() }
                jobs.clear()
            } finally {
                jobsMutex.unlock()
            }
        }
    }

    /** 探测一个 HTTP 地址：任何响应（含 5xx）都视为服务存在；401/403 为 key 无效，生图同样会失败，视为不可达 */
    private suspend fun probeHttp(url: String, token: String?): Boolean {
        return try {
            withTimeoutOrNull(8_000L) {
                val builder = Request.Builder().url(url)
                if (token != null) builder.header("Authorization", "Bearer $token")
                okHttpClient.newCall(builder.build()).execute().use { it.code !in 401..403 }
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 全渠道探活：omni win/cat/win-local 网关 + pony/qwen 生图服务（本机、tailscale win 机候选）。
     * 能用就用，不能用不提示，仅记调试日志。
     * 同时判定内置 key 启用条件：cat 网关可达 → 内置 agnes key 启用；
     * 不可达 → 只用用户 key；win/qwen/pony 仅代表本地生图可用，不启用内置 key。
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
        //win/cat 网关任一可达即参与生图(mybestvision)，都可用则都启用
        PicGenConfig.channelOmniWin = winTs || winLocal
        PicGenConfig.channelOmniCat = cat
        //pony/qwen：候选地址逐个探活，取首个可达的作为活跃地址
        ponyActiveBase = PicGenChannels.PONY_HOSTS.firstOrNull { probeHttp("$it/v1/models", null) }
        qwenActiveBase = PicGenChannels.QWEN_HOSTS.firstOrNull { probeHttp("$it/v1/models", null) }
        PicGenConfig.channelPony = ponyActiveBase != null
        PicGenConfig.channelQwen = qwenActiveBase != null
        //win/cat 网关任一可达即视为内网可用（探活结果缓存，供诊断用）
        val gatewayOk = winTs || winLocal || cat
        internalReachable = gatewayOk
        if (gatewayOk) {
            needUserKey = false
            //win/cat 可达: 生图走 win/cat 的 mybestvision, 禁止直接访问 agnes(省流量防风控);
            //其它用户 key 渠道(sensenova/nim/自定义agnes)照常可用
            PicGenConfig.channelAgnesCn = false
            PicGenConfig.channelAgnesCom = false
            //确保内置 key 已注册进组合健康表（用户可能从未打开过 key 管理窗）
            registerAllCombos()
        } else {
            //win/cat 均不可达: 不使用内置 key, 云端只用用户自己配置的 key; win/cat 网关也不参与
            PicGenConfig.channelAgnesCn = false
            PicGenConfig.channelAgnesCom = false
            //无本地生图渠道(qwen 在 win 上, 任何时候可达都算可用)且无用户 key 时,
            //首次生图前弹窗要求配置 key
            needUserKey = ponyActiveBase == null && qwenActiveBase == null
        }
        LogUtils.d(TAG, "渠道探活: omni_win_ts=$winTs omni_win_local=$winLocal omni_cat=$cat " +
            "pony=$ponyActiveBase qwen=$qwenActiveBase builtinKeys=$gatewayOk needUserKey=$needUserKey")
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

    // ---- 组合健康管理：每个(供应商, base, token) 一个状态；慢/失败自动下线，指数退避恢复 ----
    data class ComboHealth(
        val siteKey: String,
        val base: String,
        val token: String,
        @Volatile var ok: Boolean = true,
        @Volatile var slowStreak: Int = 0,
        @Volatile var retryDelayMs: Long = COMBO_RETRY_INIT_MS,
        @Volatile var nextProbeAt: Long = 0L
    ) {
        //健康组合路由 key（与旧冷却表共用一套）
        val key: String get() = "combo:$base:${token.takeLast(8)}"
        fun isSlow(): Boolean = slowStreak >= COMBO_SLOW_MAX
    }

    //注意：combos 必须声明在 init{registerAllCombos()} 之前（object 属性按声明顺序初始化）
    private val combos = ConcurrentHashMap<String, ComboHealth>()

    //冷却表：路由 key -> 恢复时间戳
    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private val failCount = ConcurrentHashMap<String, Int>()
    private val routeMutex = Mutex()

    //慢判定与恢复重试参数（用户确认：>90s 连 2 次下线；60s 起翻倍封顶 30min）
    private const val COMBO_SLOW_MS = 90_000L
    private const val COMBO_SLOW_MAX = 2
    private const val COMBO_RETRY_INIT_MS = 60_000L
    private const val COMBO_RETRY_MAX_MS = 30 * 60_000L
    private const val COMBO_PROBE_INTERVAL_MS = 30_000L   //恢复探测扫描周期

    /** 注册一个组合（启动/新增 key 时调用；已存在则复用） */
    private fun combo(siteKey: String, base: String, token: String): ComboHealth =
        combos.getOrPut("$base:${token.takeLast(8)}") { ComboHealth(siteKey, base, token) }

    /** 该组合当前是否可用（未下线且未在退避期） */
    private fun comboOk(c: ComboHealth): Boolean =
        c.ok && (c.nextProbeAt == 0L || c.nextProbeAt <= System.currentTimeMillis())

    /** 记录一次成功：清零慢计数与退避 */
    private fun comboSuccess(c: ComboHealth) {
        c.slowStreak = 0
        c.retryDelayMs = COMBO_RETRY_INIT_MS
        c.ok = true
    }

    /** 记录一次慢/失败：连慢 COMBO_SLOW_MAX 次 → 下线并安排恢复探测 */
    private fun comboSlow(c: ComboHealth) {
        c.slowStreak++
        if (c.isSlow()) {
            c.ok = false
            c.nextProbeAt = System.currentTimeMillis() + c.retryDelayMs
        }
    }

    /** 恢复探测成功：立即回到可用列表 */
    private fun comboRecovered(c: ComboHealth) {
        c.ok = true
        c.slowStreak = 0
        c.retryDelayMs = COMBO_RETRY_INIT_MS
        c.nextProbeAt = 0L
    }

    /** 探测失败：翻倍退避（封顶 30min），安排下次探测 */
    private fun comboProbeFail(c: ComboHealth) {
        c.retryDelayMs = (c.retryDelayMs * 2).coerceAtMost(COMBO_RETRY_MAX_MS)
        c.nextProbeAt = System.currentTimeMillis() + c.retryDelayMs
    }

    /** 启动时全量注册所有供应商×token 组合（生图+摘要统一登记） */
    private fun registerAllCombos() {
        //agnes 内置 + 用户自定义
        for (site in PicGenChannels.AGNES_SITES) {
            site.tokens.forEach { combo(site.key, site.base, it) }
            val custom = if (site.key == "agnes_cn") PicGenConfig.agnesKeysCn else PicGenConfig.agnesKeysCom
            userKeys(custom).forEach { combo(site.key, site.base, it) }
        }
        //nim / sensenova 用户 key
        userKeys(PicGenConfig.nimKeys).forEach { combo(PicGenChannels.NIM_SITE.key, PicGenChannels.NIM_SITE.base, it) }
        for (site in PicGenChannels.SENSENOVA_SITES) {
            val custom = if (site.key == "sensenova_cn") PicGenConfig.sensenovaKeysCn else PicGenConfig.sensenovaKeysCom
            userKeys(custom).forEach { combo(site.key, site.base, it) }
        }
        //omni 网关
        for (h in PicGenChannels.OMNI_HOSTS) combo(h.name, h.base, h.token)
    }

    /** 后台恢复探测循环：扫描下线的组合，到点探测 /v1/models，恢复则回可用列表 */
    private fun startComboRecoveryLoop() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(COMBO_PROBE_INTERVAL_MS)
                if (!PicGenConfig.windowShow) continue
                val now = System.currentTimeMillis()
                for (c in combos.values) {
                    if (c.ok || c.nextProbeAt > now) continue
                    try {
                        //探测也过 agnes 限流闸(恢复循环会对下线组合逐个探测, 不闸会突破 8/min)
                        rateGate("${c.base}/v1/models")
                        val reachable = withTimeoutOrNull(5_000L) {
                            val req = Request.Builder().url("${c.base}/v1/models")
                                .header("Authorization", "Bearer ${c.token}")
                                .build()
                            okHttpClient.newCall(req).execute().use { it.isSuccessful }
                        } == true
                        if (reachable) comboRecovered(c) else comboProbeFail(c)
                    } catch (e: Exception) {
                        comboProbeFail(c)
                    }
                }
            }
        }
    }

    //轮换计数器
    @Volatile
    private var agnesRR = 0

    //omni 同上游归一并发闸：cat(win) 的域名/IP 双候选视为同一服务器, 各限 2 并发
    private val omniUpstreamSemaphores = ConcurrentHashMap<String, Semaphore>()

    //pony/qwen 探活后的活跃地址（null 表示全部不可达）
    @Volatile
    private var ponyActiveBase: String? = null
    @Volatile
    private var qwenActiveBase: String? = null

    //tailscale 内网是否可达（win/cat 网关任一可达即 true；诊断用缓存）
    @Volatile
    var internalReachable: Boolean = false

    //完全离线时置 true：首次生图前要求用户配置 key（复用 AgnesKeysDialog）
    @Volatile
    private var needUserKey: Boolean = false

    /** 生图前是否需要用户先配置 key（win/cat 网关和本地生图均不可用且未配置任何用户 key） */
    fun userKeyRequired(): Boolean {
        //win/cat 网关可达(生图走 mybestvision)或有本地生图渠道时不要求
        if (PicGenConfig.channelOmniWin || PicGenConfig.channelOmniCat ||
            PicGenConfig.channelPony || PicGenConfig.channelQwen
        ) return false
        //用户配置了任一来源 key 即视为已满足（sensenova/nim/agnes 自定义）
        val hasUserKey = userKeys(PicGenConfig.sensenovaKeysCn).isNotEmpty() ||
            userKeys(PicGenConfig.sensenovaKeysCom).isNotEmpty() ||
            userKeys(PicGenConfig.nimKeys).isNotEmpty() ||
            userKeys(PicGenConfig.agnesKeysCn).isNotEmpty() ||
            userKeys(PicGenConfig.agnesKeysCom).isNotEmpty()
        if (hasUserKey) needUserKey = false
        return !hasUserKey
    }

    /** 配置窗口回调：无内网时首次生图前弹 AgnesKeysDialog 让用户填 key */
    @Volatile
    var onRequestUserKey: (() -> Unit)? = null

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
            //agnes 的 /v1/models 同样计入每分钟 8 请求限额
            rateGate("$base/v1/models")
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

    /** 探活周期刷新所有用户服务的模型清单（agnes 用户 key；sensenova/nim 用户 key）
     *  规则: cat 可达时不直连 agnes(生图走 cat 的 mybestvision, 模型发现也不必打 agnes);
     *  cat 不可达时 agnes 仅用用户自定义 key(不用内置 key)。
     *  节流: 缓存 5 分钟内有效不重发——探活 30s 一轮时模型发现不再消耗限流额度 */
    @Volatile
    private var lastSvcRefreshAt = 0L
    private suspend fun refreshSvcModels() {
        val now = System.currentTimeMillis()
        if (now - lastSvcRefreshAt < 5 * 60_000L && svcCache.isNotEmpty()) return
        lastSvcRefreshAt = now
        val targets = ArrayList<Triple<String, String, String>>() //siteKey, base, token
        if (!PicGenConfig.omniCatReachable) {
            //仅用户自定义 agnes key 参与发现
            for (site in PicGenChannels.AGNES_SITES) {
                val tk = userKeys(if (site.key == "agnes_cn") PicGenConfig.agnesKeysCn else PicGenConfig.agnesKeysCom)
                    .firstOrNull()
                if (tk != null) targets.add(Triple(site.key, site.base, tk))
            }
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
        //候选 key：用户输入的 key 优先，随后轮换该站全部内置 key（agns 免费站常被风控，
        //第一个 key 403 不代表服务不可用，必须挨个试）
        val candidateTokens = buildList {
            if (token.isNotBlank()) add(token)
            if (siteKey == "agnes_cn" || siteKey == "agnes_com") {
                addAll(PicGenChannels.AGNES_SITES.first { it.key == siteKey }.tokens)
            }
        }.distinct()
        if (candidateTokens.isEmpty()) return "$siteKey: ✗ 无可用 key"
        var lastFail: String? = null
        for (tk in candidateTokens) {
            val r = testServiceOnce(siteKey, tk)
            if (r.contains("✓")) return r
            lastFail = r
        }
        return lastFail ?: "$siteKey: ✗ 无可用 key"
    }

    private suspend fun testServiceOnce(siteKey: String, token: String): String {
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
            //实测试所有候选模型（发现 + 兜底去重）：单个模型 403/404/429 不代表服务不可用，
            //任一模型返回 HTTP 200（含空响应）即判定服务可用
            val attempts = LinkedHashSet(chatModels)
            attempts += fallbackChat
            var okModel: String? = null
            var lastRes: String? = null
            var lastModel = attempts.first()
            for (m in attempts) {
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
                "$siteKey: ✗ 试了${attempts.size}个模型全失败(最后 $lastModel) ${lastRes ?: "无响应"} " +
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
     * @param pages (stableKey, 生图文字) 列表。stableKey 由调用方给（阅读位置，横竖屏不变）。
     * 调用方负责把当前章剩余真实分页 + 后续页一并传入（实现全书预读）。
     */
    /**
     * 稀疏生成步长: 平均单页阅读耗时 / 平均单张生图耗时, 向上取整, 封顶 10。
     * 例: 10s/页 阅读, 70s/张 生图 → 每 7 页生成 1 张, 让阅读推进 7 页时正好有 1 张就绪。
     * 无耗时数据或生图足够快(≤阅读)时为 1(每页都生成)。
     */
    fun sparseStride(): Int {
        val readMs = avgReadMs()
        val genMs = avgGenTimeMs()
        if (readMs <= 0 || genMs <= 0) return 1
        if (genMs <= readMs) return 1
        return ((genMs + readMs - 1) / readMs).toInt().coerceIn(1, 10)
    }

    fun onVisiblePagesChanged(pages: List<Pair<String, String>>) {
        if (!PicGenConfig.windowShow) return
        val cleaned = pages.filter { it.second.isNotBlank() }
            .map { (key, text) -> key to text }
            .filter { it.second.length >= 20 }
        if (cleaned.isEmpty()) return
        //稀疏生成: 出图比翻页慢时按步长隔 N 页生成 1 张(锚点 = 距离为 stride 整数倍的页),
        //当前页始终参与; 避免每页任务都排队超时, 一张也等不到
        val stride = sparseStride()
        val sparse = if (stride <= 1) cleaned else {
            //i 是列表位置=与当前页的距离: 取当前页(0)与距离为 stride, 2*stride… 的锚点页
            cleaned.filterIndexed { i, _ -> i == 0 || i % stride == 0 }
        }
        if (sparse.isEmpty()) return
        lastPages = sparse
        scope.launch {
            val keys = sparse.map { it.first }.toSet()
            //diff：停掉不再需要的任务
            jobsMutex.withLock {
                val toCancel = jobs.filterKeys { it !in keys }
                toCancel.values.forEach { it.cancel() }
                toCancel.keys.forEach { jobs.remove(it) }
            }
            //动态分辨率：综合 就绪度 + 近期生图耗时 决策
            //目标：用户尽量少等待 → 图片质量尽量好
            dynamicSizePx = decideSize(keys)
            sparse.forEach { (key, text) ->
                genFor(key, text)
            }
            if (stride > 1) {
                LogUtils.d(TAG, "稀疏生成: stride=$stride (阅读${avgReadMs()}ms/页, 生图${avgGenTimeMs()}ms/张)")
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

    //按组合(base|token) 记录的最近 10 次生图耗时：用于估计该组合出图速度
    private val comboTimes = ConcurrentHashMap<String, ArrayDeque<Long>>()

    //最近 10 次翻页间隔（单页阅读耗时）：若用户看一页比生成一页还快 → 降分辨率
    private val readTimes = ArrayDeque<Long>(10)
    private val readTimesMutex = Any()

    /** 记录一次生图耗时（毫秒），source 形如 "model@site" */
    private fun recordGenTime(ms: Long, @Suppress("UNUSED_PARAMETER") source: String) {
        synchronized(genTimesMutex) {
            genTimes.addLast(ms)
            if (genTimes.size > 6) genTimes.removeFirst()
        }
        LogUtils.d(TAG, "gen time: ${ms}ms via $source size=$dynamicSizePx")
    }

    /** 按组合记录最近 10 次耗时（base+token 维度，竞速各组合在 imageOpenai/localTxt2Img 成功处调用） */
    private fun recordComboTime(base: String, token: String, ms: Long) {
        val key = "$base|${token.takeLast(8)}"
        val q = comboTimes.getOrPut(key) { ArrayDeque(10) }
        synchronized(q) {
            q.addLast(ms)
            if (q.size > 10) q.removeFirst()
        }
    }

    /** 某组合最近 10 次平均耗时，无样本返回 0 */
    fun avgComboTimeMs(base: String, token: String): Long {
        val q = comboTimes["$base|${token.takeLast(8)}"] ?: return 0L
        synchronized(q) {
            if (q.isEmpty()) return 0L
            return q.sum() / q.size
        }
    }

    /** 记录一次翻页间隔（单页阅读耗时），供分辨率决策 */
    fun recordPageRead(ms: Long) {
        if (ms <= 0 || ms > 60 * 60_000L) return
        synchronized(readTimesMutex) {
            readTimes.addLast(ms)
            if (readTimes.size > 10) readTimes.removeFirst()
        }
    }

    /** 用户最近 10 次平均单页阅读耗时，无样本返回 0 */
    fun avgReadMs(): Long = synchronized(readTimesMutex) {
        if (readTimes.isEmpty()) 0L else readTimes.sum() / readTimes.size
    }

    // ---- 模型级调度：每(base, model) 最近 5 次生图耗时 → ETA 排序, 最快模型优先占用并发闸 ----
    private val modelTimes = ConcurrentHashMap<String, ArrayDeque<Long>>() //key "base|model"
    private const val ETA_UNKNOWN_MS = 60_000L  //无实测数据模型的预估耗时(排在已知快模型之后)

    data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    /** 记录一次(base, model)生图耗时(成功/真实慢失败都记, 秒回失败由调用方过滤) */
    private fun recordModelTime(base: String, model: String, ms: Long) {
        val q = modelTimes.getOrPut("$base|$model") { ArrayDeque(5) }
        synchronized(q) {
            q.addLast(ms)
            if (q.size > 5) q.removeFirst()
        }
    }

    /** (base, model) 最近平均生图耗时，无样本返回 0 */
    private fun avgModelTimeMs(base: String, model: String): Long {
        val q = modelTimes["$base|$model"] ?: return 0L
        synchronized(q) {
            if (q.isEmpty()) return 0L
            return q.sum() / q.size
        }
    }

    /** 该服务器上实测最快的模型；全部无数据时按 seed 轮换(保证都能被采样) */
    private fun fastestModel(base: String, models: List<String>, seed: Int): String {
        if (models.isEmpty()) return ""
        val known = models.map { it to avgModelTimeMs(base, it) }.filter { it.second > 0 }
        return if (known.isNotEmpty()) {
            //同速差异小(<1s)时轮换, 避免永远打同一个模型
            val best = known.minByOrNull { it.second }!!
            if (known.size > 1 && best.second - (known.maxByOrNull { it.second }!!.second) < 1_000L) {
                models[seed % models.size]
            } else best.first
        } else models[seed % models.size]
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
        //阅读耗时 < 生图耗时 → 用户翻页比出图快（看图等不及），降一档保证能追上前一页
        val readMs = if (noData) 0L else avgReadMs()
        val tooSlowForReading = readMs in 1 until avg || (avg > 0 && noData && readMs == 0L)
        return when {
            allReady && (noData || avg <= SLOW_MS) && !tooSlowForReading -> HIGH_SIZE
            allReady -> BASE_SIZE
            noneReady && noData -> BASE_SIZE
            noneReady && avg <= FAST_MS -> BASE_SIZE
            noneReady && tooSlowForReading -> LOW_SIZE
            noneReady -> LOW_SIZE
            else -> if (avg <= FAST_MS && !tooSlowForReading) HIGH_SIZE else BASE_SIZE
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
        //首次生图前的 key 拦截：tailscale 不可达且无用户 key 时，弹出配置窗口直到填好
        if (userKeyRequired()) {
            withContext(Dispatchers.Main) { onRequestUserKey?.invoke() }
            //等待用户完成配置（最多 3 分钟，期间每 2s 复查是否已配好）
            var waited = 0
            while (userKeyRequired() && waited < 180) {
                kotlinx.coroutines.delay(2_000L)
                waited += 2
            }
            if (userKeyRequired()) {
                withContext(Dispatchers.Main) { onStatus?.invoke(cacheKey, "需先配置生图 key（设置→图片生成→key 管理）") }
                return
            }
        }
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
            var timing = false
            var t0 = 0L
            try {
                status(cacheKey, "生成提示词…")
                val prompt = buildPrompt(pageText)
                status(cacheKey, "生成图片…")
                t0 = System.currentTimeMillis()
                timing = true
                val (bytes, source) = generateImage(prompt)
                if (isBlackImage(bytes)) {
                    throw IllegalStateException("生图返回全黑图")
                }
                //记录本次生图耗时（滑动窗口，供动态分辨率决策）
                recordGenTime(System.currentTimeMillis() - t0, source)
                genInfoCache[cacheKey] = "$source\n$prompt"
                val bmp = decodeBytes(bytes) ?: throw IllegalStateException("图片解码失败")
                file.writeBytes(bytes)
                memCache.put(cacheKey, bmp)
                withContext(Dispatchers.Main) { onImageReady?.invoke(cacheKey, bmp, source) }
                lastFailAt.remove(cacheKey)
            } catch (e: Exception) {
                //失败也记耗时让降档拿到慢信号——但渠道全冷却的秒回失败不算生图慢, 不记录:
                //ms 级失败样本会把均值拉低, 误判"很快"反而排满任务, 恶性循环
                if (e !is kotlinx.coroutines.CancellationException && timing) {
                    val cost = System.currentTimeMillis() - t0
                    if (cost >= 5_000L) recordGenTime(cost, "失败")
                }
                if (e !is kotlinx.coroutines.CancellationException) {
                    //失败退避: 记录失败时间, 定时重试循环在退避期后自动补图
                    lastFailAt[cacheKey] = System.currentTimeMillis()
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
        var prompt = mergeKeywords(parts)
        //当前阅读窗口全文：作为独立输入附加（保留原文，不参与关键词去重）
        if (PicGenConfig.promptIncludeFullText && pageText.isNotBlank()) {
            prompt = "$prompt\n${pageText.trim()}"
        }
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
        //agnes 文本模型（仅用户自定义 key；内网 win/cat 可达时不允许直连 agnes）
        if (!internalReachable) {
            for (site in PicGenChannels.AGNES_SITES) {
                val tk = userKeys(if (site.key == "agnes_cn") PicGenConfig.agnesKeysCn else PicGenConfig.agnesKeysCom)
                    .firstOrNull() ?: continue
                val models = svcModels(site.key, site.base, tk)?.chat?.takeIf { it.isNotEmpty() }
                    ?: PicGenChannels.AGNES_CHAT_MODELS
                for (m in models.take(3)) {
                    tasks.add { chatOnce(site.base, tk, m, prompt) }
                }
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
        val c = combo("chat", base, token)
        if (!comboOk(c)) return null  //该组合已下线退避,不参与摘要竞速
        val t0 = System.currentTimeMillis()
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
            comboSuccess(c)
            return PromptBuilder.extractTags(text)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            recordFailure(rkey)
            comboSlow(c)
            return null
        } finally {
            if (System.currentTimeMillis() - t0 >= CHAT_TIMEOUT_MS) comboSlow(c)
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
            val choices = obj.getAsJsonArray("choices") ?: return "无choices"
            val message = choices[0]?.asJsonObject?.getAsJsonObject("message")
            val text = message?.get("content")?.takeIf { !it.isJsonNull }?.asString
            //DeepSeek 等推理型模型把内容放在 reasoning_content，HTTP 200 即视为服务可用
            if (text.isNullOrEmpty()) {
                val reasoning = message?.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString
                if (!reasoning.isNullOrEmpty()) {
                    "✓ ${System.currentTimeMillis() - t0}ms(推理模型)"
                } else {
                    "空响应"
                }
            } else {
                "✓ ${System.currentTimeMillis() - t0}ms"
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.message ?: e.javaClass.simpleName
        }
    }

    // ---- 生图：agnes/sensenova/NIM/omni 多族并行竞速，最先成功赢；本地 pony/qwen 探活后用 ----
    private suspend fun generateImage(prompt: String): Pair<ByteArray, String> {
        val families = ArrayList<suspend () -> Pair<ByteArray, String>?>()
        //agnes 族：非内网用用户自定义 key；内网(win/cat 可达)禁直连 agnes
        val userAgnesKeys = userKeys(PicGenConfig.agnesKeysCn).isNotEmpty() || userKeys(PicGenConfig.agnesKeysCom).isNotEmpty()
        if (!internalReachable && userAgnesKeys) {
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
                families.add { localTxt2Img(base, prompt.take(PROMPT_MAX))?.let { it to "qwen@local" } }
            }
        }
        if (families.isEmpty()) throw IllegalStateException(
            if (internalReachable) "没有启用任何生图渠道"
            else "无可用渠道: 内网(win/cat)不可达, 请在配置窗口填写 sensenova/agnes/nim 的 key"
        )
        //开场先探一轮：全部渠道都在冷却/下线时立即报真实原因，别伪装成超时
        val t0 = System.currentTimeMillis()
        val result = withTimeoutOrNull(RACE_TIMEOUT_MS) {
            raceFirst(families.map { scope.async { it() } }) { it != null }
        }
        @Suppress("UNCHECKED_CAST")
        return (result as? Pair<ByteArray, String>)
            ?: throw IllegalStateException(
                if (System.currentTimeMillis() - t0 >= RACE_TIMEOUT_MS)
                    "生图超时(${RACE_TIMEOUT_MS / 1000}s)"
                else
                    "所有渠道暂不可用(冷却中,稍后自动恢复)"
            )
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
        val c = combo("openai", base, token)
        if (!comboOk(c)) return null  //该组合已下线退避,直接不参与本轮竞速
        val t0 = System.currentTimeMillis()
        return try {
            val body = gson.toJson(
                mapOf(
                    "model" to model,
                    "prompt" to prompt.take(PROMPT_MAX),
                    "n" to 1,
                    "size" to dynamicSizePx,
                    "response_format" to "b64_json"
                )
            )
            val data = withTimeoutOrNull(IMG_TIMEOUT_MS) {
                postJsonRequest("$base/v1/images/generations", body, token)
            }
            if (data == null) {
                //超时(>=75s 但 <90s 慢阈值)不立刻下线,交由调用方记录;直接失败冷却
                recordFailure(rkey)
                return null
            }
            val item = parseImageResponse(data) ?: run {
                recordFailure(rkey)
                return null
            }
            recordSuccess(rkey)
            comboSuccess(c)
            recordComboTime(base, token, System.currentTimeMillis() - t0)
            recordModelTime(base, model, System.currentTimeMillis() - t0)
            item.first to model
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            recordFailure(rkey)
            comboSlow(c)
            val cost = System.currentTimeMillis() - t0
            //真实慢失败(超时/挂起)计入模型耗时样本, 让调度器下次跳过该模型
            if (cost >= 5_000L) recordModelTime(base, model, cost)
            null
        } finally {
            //竞速中被取消/慢完成也记一笔：>SLOW_MS 连 COMBO_SLOW_MAX 次下线
            val cost = System.currentTimeMillis() - t0
            if (cost >= COMBO_SLOW_MS) comboSlow(c)
        }
    }

    private suspend fun imageAgnes(prompt: String): Pair<ByteArray, String>? {
        //内网(win/cat 网关任一可达) → 生图走网关, 不允许直连 agnes(任何 key)
        if (internalReachable) return null
        //非内网 → 仅用户自定义 agnes key 参与, 不用内置 key
        //调度: 每个 token 选其实测最快的模型, 候选按预计完成时间(ETA)升序, 取最快的 AGNES_WAVE 个;
        //并发(2)与每分钟请求(<9)由 postJsonRequest 的 per-host 信号闸+限流闸兜底, 排队不丢弃
        val candidates = ArrayList<Quadruple<String, String, String, Long>>() //base, token, siteKey, eta
        for (site in PicGenChannels.AGNES_SITES) {
            val custom = if (site.key == "agnes_cn") PicGenConfig.agnesKeysCn else PicGenConfig.agnesKeysCom
            for (tk in userKeys(custom)) {
                val models = PicGenChannels.AGNES_IMAGE_MODELS
                val model = fastestModel(site.base, models, agnesRR + candidates.size)
                val eta = avgModelTimeMs(site.base, model).let { if (it > 0) it else ETA_UNKNOWN_MS }
                candidates.add(Quadruple(site.base, tk, "agnes_user", eta))
            }
        }
        if (candidates.isEmpty()) return null
        val wave = candidates.sortedBy { it.fourth }.take(minOf(AGNES_WAVE, candidates.size))
        val tasks = wave.map { (base, tk, siteKey, _) ->
            val models = PicGenChannels.AGNES_IMAGE_MODELS
            val model = fastestModel(base, models, agnesRR)
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
            snRR++
            //调度: 每 key 用该站实测最快的 2 个模型(无数据按 snRR 轮换采样)
            keys.take(2).forEach { tk ->
                val first = fastestModel(base, models, snRR)
                val second = models.filter { it != first }.let {
                    if (it.isEmpty()) first else fastestModel(base, it, snRR + 1)
                }
                listOf(first, second).distinct().forEach { model ->
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
        val tasks = ArrayList<suspend () -> Pair<ByteArray, String>?>()
        for (host in PicGenChannels.OMNI_HOSTS) {
            val chOn = if (host.name == "cat") PicGenConfig.channelOmniCat else PicGenConfig.channelOmniWin
            if (!chOn || !hostReachable(host)) continue
            for (m in PicGenChannels.OMNI_IMAGE_MODELS) {
                tasks.add {
                    omniOnce(host.base, host.token, host.name, m, prompt)
                }
            }
        }
        if (tasks.isEmpty()) return null
        @Suppress("UNCHECKED_CAST")
        return raceFirst(tasks.map { scope.async { it() } }) { it != null } as? Pair<ByteArray, String>
    }

    /** 单个 omni host×model 生图：失败记冷却，成功记 rkey+轮换 */
    private suspend fun omniOnce(
        base: String, tk: String, hostName: String, model: String, prompt: String
    ): Pair<ByteArray, String>? {
        val rkey = "omni:$hostName:$model"
        if (!routeAvailable(rkey)) return null
        //同上游归一闸：cat 的域名/IP 双候选、win 的域名/本机双候选是同一台服务器,
        //按 hostName 归一限 2 并发——否则双候选并发翻倍, 慢模型占满闸会堵死快模型
        val gate = omniUpstreamSemaphores.getOrPut(
            if (hostName == "win-local") "win" else hostName
        ) { Semaphore(2) }
        return try {
            val body = gson.toJson(
                mapOf(
                    "model" to model,
                    "prompt" to prompt.take(PROMPT_MAX),
                    "n" to 1,
                    "size" to dynamicSizePx,
                    "response_format" to "b64_json"
                )
            )
            val data = gate.withPermit {
                withTimeoutOrNull(IMG_TIMEOUT_MS) {
                    postJsonRequest("$base/v1/images/generations", body, tk)
                }
            } ?: throw IllegalStateException("超时")
            val item = parseImageResponse(data)
                ?: throw IllegalStateException("响应无 b64_json 也无 url")
            recordSuccess(rkey)
            item.first to "${model}@${hostName}"
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            recordFailure(rkey)
            LogUtils.d(TAG, "omni fail: $hostName $model ${e.message}")
            null
        }
    }

    private suspend fun localTxt2Img(base: String, prompt: String): ByteArray? {
        val rkey = "local:$base"
        if (!routeAvailable(rkey)) return null
        val c = combo("local", base, "")
        if (!comboOk(c)) return null
        val t0 = System.currentTimeMillis()
        return try {
            //OpenAI 兼容格式优先: sdcpp 系服务不认 size/response_format 而用默认超大尺寸导致超时,
            //直接发 width/height(steps 省时间)——OpenAI 兼容层通常也容忍这些字段
            val px = dynamicSizePx.split("x").let { if (it.size == 2) it[0].toInt() else 768 }
            val body = gson.toJson(
                mapOf(
                    "prompt" to prompt,
                    "n" to 1,
                    "width" to px,
                    "height" to px,
                    "steps" to 20,
                    "response_format" to "b64_json"
                )
            )
            val data = withTimeoutOrNull(RACE_TIMEOUT_MS) {
                postJsonRequest("$base/v1/images/generations", body, null)
            }
            val item = if (data != null) parseImageResponse(data) else null
            if (item != null) {
                recordSuccess(rkey)
                comboSuccess(c)
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
            val data2 = withTimeoutOrNull(RACE_TIMEOUT_MS) {
                postJsonRequest("$base/sdapi/v1/txt2img", body2, null)
            } ?: run {
                recordFailure(rkey)
                return null
            }
            val obj = parseJson(data2) ?: run {
                recordFailure(rkey)
                return null
            }
            val b64 = obj.getAsJsonArray("images")?.get(0)?.asString
            if (b64 != null) {
                recordSuccess(rkey)
                comboSuccess(c)
                decodeB64(b64)
            } else null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            recordFailure(rkey)
            comboSlow(c)
            LogUtils.d(TAG, "local fail: $base ${System.currentTimeMillis() - t0}ms ${e.message}")
            null
        } finally {
            //本地专家通常 30-50s；超过慢阈值视为慢，连 COMBO_SLOW_MAX 次下线
            if (System.currentTimeMillis() - t0 >= COMBO_SLOW_MS) comboSlow(c)
        }
    }

    // ---- HTTP ----
    //每服务器并发闸：同一 host 同时最多 2 个请求（不论多少 key），防止瞬时开火触发上游风控
    private val serverSemaphores = ConcurrentHashMap<String, Semaphore>()
    private fun semaphoreFor(url: String): Semaphore {
        val host = try {
            java.net.URI(url).host ?: url
        } catch (e: Exception) {
            url
        }
        return serverSemaphores.getOrPut(host) { Semaphore(2) }
    }

    //agnes 限流：.cn/.com 是两个不同服务器, 各自最多 2 并发(信号闸)且每分钟 ≤8 个请求(滑动窗口)
    private const val AGNES_RATE_PER_MIN = 8
    private val agnesRequestTimes = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private fun isAgnesHost(url: String): Boolean = try {
        (java.net.URI(url).host ?: "").contains("agnes-ai")
    } catch (e: Exception) {
        false
    }

    private suspend fun rateGate(url: String) {
        if (!isAgnesHost(url)) return
        val host = try {
            java.net.URI(url).host ?: url
        } catch (e: Exception) {
            url
        }
        val q = agnesRequestTimes.getOrPut(host) { ArrayDeque() }
        while (true) {
            val wait: Long
            synchronized(q) {
                val now = System.currentTimeMillis()
                while (q.isNotEmpty() && now - q.first() > 60_000L) q.removeFirst()
                wait = if (q.size < AGNES_RATE_PER_MIN) {
                    q.addLast(now)
                    0L
                } else {
                    60_000L - (now - q.first()) + 200L
                }
            }
            if (wait <= 0L) return
            kotlinx.coroutines.delay(wait)
        }
    }

    private suspend fun postJsonRequest(url: String, json: String, token: String?): String {
        val builder = Request.Builder().url(url)
        builder.postJson(json)
        if (token != null) builder.header("Authorization", "Bearer $token")
        val request = builder.build()
        rateGate(url)
        semaphoreFor(url).withPermit {
            okHttpClient.newBuilder()
                .callTimeout(IMG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(IMG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    return response.body?.string() ?: throw IllegalStateException("空响应")
                }
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

    /**
     * 拼图背景：取缓存中与当前页"最相关"的 16 张图拼成 4x4。
     * 相关度 = |章差|*大权重 + |字符位置差|（同章、位置近者优先，跨章靠后）。
     * @param currentKey 当前页 key（"c{ch}@p{pos}"）
     * @param offset 轮换偏移：每 30s +1，改变取图起始位置实现背景更替
     */
    fun buildMosaic(currentKey: String, offset: Int): Bitmap? {
        val cur = parseKeyPos(currentKey) ?: return null
        val files = cacheDir.listFiles { f -> f.extension == "png" } ?: return null
        val scored = files.mapNotNull { f ->
            parseKeyPos(f.nameWithoutExtension)?.let { (ch, pos) ->
                val d = kotlin.math.abs(ch - cur.first) * 1_000_000L + kotlin.math.abs(pos - cur.second)
                Triple(d, f, ch)
            }
        }.sortedBy { it.first }
        if (scored.isEmpty()) return null
        val cell = 256
        val mosaic = Bitmap.createBitmap(cell * 4, cell * 4, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(mosaic)
        val paint = android.graphics.Paint().apply { isAntiAlias = true; isFilterBitmap = true }
        for (i in 0 until 16) {
            val (_, f, _) = scored[(offset * 16 + i) % scored.size]
            val bmp = decodeScaled(f, cell)
            if (bmp != null) {
                val x = (i % 4) * cell
                val y = (i / 4) * cell
                canvas.drawBitmap(centerCrop(bmp, cell), x.toFloat(), y.toFloat(), paint)
                if (bmp !== mosaic) bmp.recycle()
            }
        }
        return mosaic
    }

    /** 解析 "c{ch}@p{pos}" → (ch, pos) */
    private fun parseKeyPos(key: String): Pair<Int, Int>? {
        val m = Regex("""^c(\d+)@p(\d+)$""").find(key) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    private fun decodeScaled(f: File, size: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= size && opts.outHeight / (sample * 2) >= size) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(f.absolutePath, o2)
    }

    /** 居中裁剪成正方形 */
    private fun centerCrop(src: Bitmap, size: Int): Bitmap {
        val s = minOf(src.width, src.height)
        val x = (src.width - s) / 2
        val y = (src.height - s) / 2
        return Bitmap.createBitmap(src, x, y, s, s)
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

    /** 生图任务表占用数量（预读放量用） */
    fun isBusy(threshold: Int): Boolean = jobs.size >= threshold

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
