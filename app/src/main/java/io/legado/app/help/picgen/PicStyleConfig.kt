package io.legado.app.help.picgen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.legado.app.data.entities.Book
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefInt
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap

/**
 * 生图风格：30 种图片风格，assets/picgen/ 下 {index}.webp + styles.json。
 * 选中风格后，其提示词在每次生图时追加。按书（bookUrl）记忆，卸载重装前一直保留。
 */
object PicStyleConfig {

    data class Style(val index: Int, val name: String, val prompt: String)

    private val gson = Gson()
    private var styles: List<Style>? = null
    private val bmpCache = ConcurrentHashMap<Int, Bitmap>()

    /** 全部风格（懒加载 assets/picgen/styles.json） */
    fun loadStyles(): List<Style> {
        styles?.let { return it }
        val list = try {
            val json = appCtx.assets.open("picgen/styles.json").bufferedReader().readText()
            gson.fromJson<List<Style>>(json, object : TypeToken<List<Style>>() {}.type)
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        styles = list
        return list
    }

    /** 取某风格提示词（index 从 1 开始；0/非法返回 null） */
    fun promptForIndex(index: Int): String? =
        loadStyles().firstOrNull { it.index == index }?.prompt?.takeIf { it.isNotBlank() }

    /** 当前书已选风格 index（0=未选） */
    fun styleIndex(book: Book?): Int {
        book ?: return 0
        return appCtx.getPrefInt(styleKey(book), 0)
    }

    /** 保存当前书选中的风格（0=清除） */
    fun setStyle(book: Book?, index: Int) {
        book ?: return
        appCtx.putPrefInt(styleKey(book), index)
    }

    /** 当前书的风格提示词（未选返回 null） */
    fun promptFor(book: Book?): String? = promptForIndex(styleIndex(book))

    /** 方形缩略图（center-crop 到正方形，最多 512px，供列表用；带缓存） */
    fun thumb(index: Int): Bitmap? {
        bmpCache[index]?.let { return it }
        val bmp = try {
            val src = BitmapFactory.decodeStream(appCtx.assets.open("picgen/$index.webp"))
                ?: return null
            val side = minOf(src.width, src.height)
            val x = (src.width - side) / 2
            val y = (src.height - side) / 2
            val out = Bitmap.createBitmap(src, x, y, side, side)
            if (out != src) src.recycle()
            out
        } catch (e: Exception) {
            null
        } ?: return null
        bmpCache[index] = bmp
        return bmp
    }

    private fun styleKey(book: Book): String = "picStyle_${book.origin}_${book.bookUrl}".hashCode().toString()
}
