package io.legado.app.ui.book.read

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import io.legado.app.R
import io.legado.app.help.picgen.PicGenConfig
import io.legado.app.utils.sendToClip

/**
 * 阅读页图片窗口：竖屏在正文下方、横屏在正文右侧。
 * 右上角关闭按钮：点击隐藏窗口（切换 PicGenConfig.windowShow）。
 * 图片未就绪时保留上一张图叠转圈，避免闪烁。
 */
class PicWindowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val ivPic: ImageView
    private val pbPic: ProgressBar
    private val tvStatus: TextView
    private val btnClose: ImageView

    var onClose: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_pic_window, this, true)
        ivPic = findViewById(R.id.iv_pic)
        pbPic = findViewById(R.id.pb_pic)
        tvStatus = findViewById(R.id.tv_pic_status)
        btnClose = findViewById(R.id.btn_pic_close)
        btnClose.setOnClickListener {
            PicGenConfig.windowShow = false
            visibility = GONE
            onClose?.invoke()
        }
        //点击错误/状态文字：复制到剪贴板
        tvStatus.setOnClickListener {
            val text = tvStatus.text.toString()
            if (text.isNotBlank()) {
                context.sendToClip(text)
            }
        }
    }

    fun setBitmap(bitmap: Bitmap) {
        ivPic.setImageBitmap(bitmap)
        ivPic.visibility = VISIBLE
        pbPic.visibility = GONE
        tvStatus.visibility = GONE
    }

    //加载中：保留旧图，仅叠转圈，避免黑底闪烁
    fun showLoading() {
        pbPic.visibility = VISIBLE
        tvStatus.visibility = GONE
    }

    fun showError(msg: String) {
        pbPic.visibility = GONE
        ivPic.visibility = GONE
        tvStatus.visibility = VISIBLE
        tvStatus.text = msg
    }

    fun showStatus(msg: String) {
        //错误信息保留不被进度覆盖（点击可复制）
        if (tvStatus.visibility == VISIBLE && tvStatus.text.startsWith("失败")) return
        if (ivPic.visibility != VISIBLE) {
            pbPic.visibility = VISIBLE
            tvStatus.visibility = VISIBLE
            tvStatus.text = msg
        }
    }

    fun clear() {
        ivPic.setImageDrawable(null)
        ivPic.visibility = GONE
        pbPic.visibility = GONE
        tvStatus.visibility = GONE
    }

    fun hasImage(): Boolean = ivPic.visibility == VISIBLE
}
