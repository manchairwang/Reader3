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

/**
 * 阅读页图片窗口：竖屏在正文下方、横屏在正文右侧。
 * 右上角关闭按钮：点击隐藏窗口（切换 PicGenConfig.windowShow）。
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
    }

    fun setBitmap(bitmap: Bitmap) {
        ivPic.setImageBitmap(bitmap)
        ivPic.visibility = VISIBLE
        pbPic.visibility = GONE
        tvStatus.visibility = GONE
    }

    fun showLoading() {
        ivPic.visibility = GONE
        pbPic.visibility = VISIBLE
        tvStatus.visibility = GONE
    }

    fun showError(msg: String) {
        ivPic.visibility = GONE
        pbPic.visibility = GONE
        tvStatus.visibility = VISIBLE
        tvStatus.text = msg
    }

    fun showStatus(msg: String) {
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
}
