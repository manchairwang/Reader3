package io.legado.app.help

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 搜索命中积分：每次某书源搜索返回图书即 +1。
 * 持久化到 filesDir/search_scores.json（应用私有目录，应用升级不丢失）。
 * 用于调整分类站点 top100 排序。
 */
object SearchScoreStore {

    private val gson = Gson()
    private val file: File by lazy {
        File(appCtx.filesDir, "search_scores.json")
    }

    //内存缓存：bookSourceUrl -> 累计积分
    private val scores = ConcurrentHashMap<String, Long>()
    private val mutex = Any()
    @Volatile
    private var loaded = false

    private fun loadIfNeeded() {
        if (loaded) return
        synchronized(mutex) {
            if (loaded) return
            try {
                if (file.exists()) {
                    val type = object : TypeToken<Map<String, Long>>() {}.type
                    val map: Map<String, Long> = gson.fromJson(file.readText(), type) ?: emptyMap()
                    scores.putAll(map)
                }
            } catch (e: Exception) {
                //文件损坏时从空开始，不崩溃
            }
            loaded = true
        }
    }

    /**
     * 搜索命中加分（每源每次搜索 +1）
     */
    fun hit(bookSourceUrl: String) = hitAll(listOf(bookSourceUrl))

    /**
     * 批量加分（一次搜索结果涉及多个源，各 +1）
     */
    fun hitAll(urls: List<String>) {
        if (urls.isEmpty()) return
        loadIfNeeded()
        synchronized(mutex) {
            urls.forEach { u ->
                val url = u.split("#")[0]
                if (url.isNotBlank()) scores.merge(url, 1L, Long::plus)
            }
            persistLocked()
        }
    }

    /**
     * 读取某源积分
     */
    fun score(bookSourceUrl: String): Long {
        loadIfNeeded()
        return scores[bookSourceUrl.split("#")[0]] ?: 0L
    }

    /**
     * 全量快照
     */
    fun snapshot(): Map<String, Long> {
        loadIfNeeded()
        return HashMap(scores)
    }

    /**
     * 合并外部积分（导入 top100 文件自带的初始积分）
     */
    fun mergeInto(external: Map<String, Long>) {
        loadIfNeeded()
        synchronized(mutex) {
            external.forEach { (url, s) ->
                if (s > 0) scores.merge(url.split("#")[0], s, Long::plus)
            }
            persistLocked()
        }
    }

    fun clear() {
        loadIfNeeded()
        synchronized(mutex) {
            scores.clear()
            persistLocked()
        }
    }

    private fun persist() {
        synchronized(mutex) { persistLocked() }
    }

    private fun persistLocked() {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(gson.toJson(scores))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            //同步一份到外部存储（无需root可读，供导出top100排序）
            appCtx.getExternalFilesDir(null)?.let { ext ->
                File(ext, "search_scores.json").writeText(gson.toJson(scores))
            }
        } catch (e: Exception) {
            //持久化失败不影响主流程
        }
    }
}
