package io.legado.app.help.picgen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import io.legado.app.constant.AppLog
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        //tailscale 网关探活循环：启动即探活一轮，之后每 10 分钟检测；可达则自动启用渠道
        scope.launch {
            while (true) {
                if (PicGenConfig.tailscaleAutoDetect) {
                    probeOmniHosts()
                }
                kotlinx.coroutines.delay(10 * 60 * 1000L)
            }
        }
    }

    /** 探活 win/cat 网关：GET /v1/models 8s 超时；可达则自动打开对应渠道开关 */
    private suspend fun probeOmniHosts() {
        for (host in PicGenChannels.OMNI_HOSTS) {
            if (host.name == "win-local") continue
            val reachable = try {
                withTimeoutOrNull(8_000L) {
                    val request = Request.Builder().url("${host.base}/v1/models")
                        .header("Authorization", "Bearer ${host.token}")
                        .build()
                    okHttpClient.newCall(request).execute().use { it.isSuccessful }
                } ?: false
            } catch (e: Exception) {
                false
            }
            if (host.name == "cat") {
                PicGenConfig.omniCatReachable = reachable
            } else {
                PicGenConfig.omniWinReachable = reachable
            }
            //可达则自动启用对应渠道
            if (reachable) {
                if (host.name == "cat" && !PicGenConfig.channelOmniCat) {
                    PicGenConfig.channelOmniCat = true
                    LogUtils.d(TAG, "tailscale cat 网关可达，自动启用 omni_cat")
                }
                if (host.name != "cat" && !PicGenConfig.channelOmniWin) {
                    PicGenConfig.channelOmniWin = true
                    LogUtils.d(TAG, "tailscale win 网关可达，自动启用 omni_win")
                }
            }
        }
    }

    /** 网关可达性判断：localhost 永远视为可达；tailscale host 用探活缓存值 */
    private fun hostReachable(host: PicGenChannels.OmniHost): Boolean {
        if (host.name == "win-local") return true
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

    //冷却表：路由 key -> 恢复时间戳
    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private val failCount = ConcurrentHashMap<String, Int>()
    private val routeMutex = Mutex()

    //轮换计数器
    @Volatile
    private var agnesRR = 0
    @Volatile
    private var omniRR = 0

    /** 结果回调：cacheKey -> bitmap */
    @Volatile
    var onImageReady: ((cacheKey: String, bitmap: Bitmap, source: String) -> Unit)? = null

    @Volatile
    var onStatus: ((cacheKey: String, status: String) -> Unit)? = null

    /**
     * 翻页/加载后调用：重估任务集。
     * @param pages 可见+预读页文字列表
     */
    fun onVisiblePagesChanged(pages: List<String>) {
        if (!PicGenConfig.windowShow) return
        val cleaned = pages.filter { it.isNotBlank() }
            .map { it.take(1600) }
            .filter { it.length >= 20 }
        if (cleaned.isEmpty()) return
        scope.launch {
            val keys = cleaned.map { pageKey(it) }.toSet()
            //diff：停掉不再需要的任务
            jobsMutex.withLock {
                val toCancel = jobs.filterKeys { it !in keys }
                toCancel.values.forEach { it.cancel() }
                toCancel.keys.forEach { jobs.remove(it) }
            }
            cleaned.forEach { text ->
                genFor(pageKey(text), text)
            }
        }
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
                val (bytes, source) = generateImage(prompt)
                if (isBlackImage(bytes)) {
                    throw IllegalStateException("生图返回全黑图")
                }
                val bmp = decodeBytes(bytes) ?: throw IllegalStateException("图片解码失败")
                file.writeBytes(bytes)
                memCache.put(cacheKey, bmp)
                withContext(Dispatchers.Main) { onImageReady?.invoke(cacheKey, bmp, source) }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    val detail = "生图失败: ${e.message}\n页: ${pageText.take(50)}…\n渠道开关: " +
                        "agnes_cn=${PicGenConfig.channelAgnesCn} agnes_com=${PicGenConfig.channelAgnesCom} " +
                        "omni_win=${PicGenConfig.channelOmniWin} omni_cat=${PicGenConfig.channelOmniCat} " +
                        "pony=${PicGenConfig.channelPony} qwen=${PicGenConfig.channelQwen}"
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

    /** 提示词：LLM 竞速可用走 TAGS，全挂/关闭走本地正则兜底 */
    private suspend fun buildPrompt(pageText: String): String {
        if (!PicGenConfig.llmEnable) {
            return PromptBuilder.buildFallbackPrompt(pageText)
        }
        val summary = chatRace(pageText)
        return summary ?: PromptBuilder.buildFallbackPrompt(pageText)
    }

    // ---- TAGS LLM 竞速：omni 优先 combo × 双机 + agnes 文本模型，最先成功赢 ----
    private suspend fun chatRace(pageText: String): String? {
        val prompt = PromptBuilder.buildSummaryPrompt(pageText)
        val tasks = ArrayList<suspend () -> String?>()
        //omni 网关（探活判定不可达的直接跳过，避免长超时等待）
        for (host in PicGenChannels.OMNI_HOSTS) {
            val chOn = if (host.name == "cat") PicGenConfig.channelOmniCat else PicGenConfig.channelOmniWin
            if (!chOn || !hostReachable(host)) continue
            for (combo in PicGenChannels.PREFERRED_CHAT_COMBOS) {
                tasks.add { chatOnce(host.base, host.token, combo, prompt) }
            }
        }
        //agnes 文本模型兜底
        for (site in PicGenChannels.AGNES_SITES) {
            val chOn = if (site.key == "agnes_cn") PicGenConfig.channelAgnesCn else PicGenConfig.channelAgnesCom
            if (!chOn) continue
            val tk = site.tokens.firstOrNull() ?: continue
            for (m in PicGenChannels.AGNES_CHAT_MODELS) {
                tasks.add { chatOnce(site.base, tk, m, prompt) }
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

    // ---- 生图：agnes 与 omni 双族并行竞速，最先成功赢；本地 pony/qwen 探活后用 ----
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
        //本地族
        if (PicGenConfig.channelPony) {
            families.add { localTxt2Img(PicGenChannels.PONY_BASE, PromptBuilder.buildPonyPrompt(prompt))?.let { it to "pony@local" } }
        }
        if (PicGenConfig.channelQwen) {
            families.add { localTxt2Img(PicGenChannels.QWEN_BASE, prompt.take(900))?.let { it to "qwen@local" } }
        }
        if (families.isEmpty()) throw IllegalStateException("没有启用任何生图渠道")
        val result = raceFirst(families.map { scope.async { it() } }) { it != null }
        @Suppress("UNCHECKED_CAST")
        return result as? Pair<ByteArray, String>
            ?: throw IllegalStateException("全部生图渠道失败")
    }

    private suspend fun imageAgnes(prompt: String): Pair<ByteArray, String>? {
        val combos = ArrayList<Triple<String, String, String>>() //base, token, siteKey
        for (site in PicGenChannels.AGNES_SITES) {
            val chOn = if (site.key == "agnes_cn") PicGenConfig.channelAgnesCn else PicGenConfig.channelAgnesCom
            if (!chOn) continue
            site.tokens.forEach { tk -> combos.add(Triple(site.base, tk, site.key)) }
        }
        if (combos.isEmpty()) return null
        //一波并发：所有未冷却的 token 各发一个请求，最先出图赢
        val tasks = combos.mapIndexed { ti, (base, tk, siteKey) ->
            val model = PicGenChannels.AGNES_IMAGE_MODELS[(ti + agnesRR) % PicGenChannels.AGNES_IMAGE_MODELS.size]
            scope.async {
                val rkey = "agnes:$base:${tk.takeLast(8)}:$model"
                if (!routeAvailable(rkey)) return@async null
                try {
                    val body = gson.toJson(
                        mapOf(
                            "model" to model,
                            "prompt" to prompt.take(900),
                            "n" to 1,
                            "size" to "768x768",
                            "response_format" to "b64_json"
                        )
                    )
                    val data = withTimeoutOrNull(IMG_TIMEOUT_MS) {
                        postJsonRequest("$base/v1/images/generations", body, tk)
                    } ?: return@async null
                    val item = parseImageResponse(data) ?: return@async null
                    recordSuccess(rkey)
                    item.first to "${model}@${siteKey}"
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    recordFailure(rkey)
                    null
                }
            }
        }
        agnesRR++
        @Suppress("UNCHECKED_CAST")
        return raceFirst(tasks) { it != null } as? Pair<ByteArray, String>
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
                        "size" to "768x768",
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
                    "size" to "768x768",
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

    /** 解析生图响应：b64_json 或 url 双格式 */
    private suspend fun parseImageResponse(data: String): Pair<ByteArray, Unit>? {
        val obj = parseJson(data) ?: return null
        val arr = obj.getAsJsonArray("data") ?: return null
        if (arr.size() == 0) return null
        val item = arr[0].asJsonObject
        val b64 = item.get("b64_json")?.takeIf { !it.isJsonNull }?.asString
        if (!b64.isNullOrEmpty()) {
            return decodeB64(b64) to Unit
        }
        val url = item.get("url")?.takeIf { !it.isJsonNull }?.asString
        if (!url.isNullOrEmpty()) {
            return fetchBytes(url) to Unit
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
