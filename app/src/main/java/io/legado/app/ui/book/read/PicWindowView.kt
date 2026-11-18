package io.legado.app.ui.book.read

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
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
 * 窗口上支持与正文相同的翻页手势：左右滑/快滑（fling）触发 [onPageTurn]。
 */
class PicWindowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val ivPic: ImageView
    private val pbPic: ProgressBar
    private val tvStatus: TextView
    private val btnClose: ImageView
    private val tvInfo: TextView

    var onClose: (() -> Unit)? = null

    /** 窗口手势翻页：true=下一页，false=上一页 */
    var onPageTurn: ((next: Boolean) -> Unit)? = null

    //与正文翻页一致的手势判定：横向位移为主即翻页，速度阈值同 PageDelegate fling
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            vx: Float,
            vy: Float
        ): Boolean {
            if (e1 == null) return false
            val dx = e2.x - e1.x
            if (kotlin.math.abs(dx) < 50) return false
            onPageTurn?.invoke(dx < 0)
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            dx: Float,
            dy: Float
        ): Boolean {
            if (e1 == null) return false
            if (kotlin.math.abs(e2.x - e1.x) < 80) return false
            onPageTurn?.invoke(e2.x - e1.x < 0)
            return true
        }
    })

    init {
        LayoutInflater.from(context).inflate(R.layout.view_pic_window, this, true)
        ivPic = findViewById(R.id.iv_pic)
        pbPic = findViewById(R.id.pb_pic)
        tvStatus = findViewById(R.id.tv_pic_status)
        btnClose = findViewById(R.id.btn_pic_close)
        tvInfo = findViewById(R.id.tv_pic_info)
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
        setOnTouchListener { _, e ->
            val handled = gestureDetector.onTouchEvent(e)
            performClick()
            //必须返回 true 消费 DOWN,否则后续 MOVE/UP 不再派发,翻页手势失效
            handled
        }
    }

    fun setBitmap(bitmap: Bitmap) {
        ivPic.setImageBitmap(bitmap)
        ivPic.visibility = VISIBLE
        pbPic.visibility = GONE
        tvStatus.visibility = GONE
    }

    /** 左下角诊断层：info 形如 "提示词\n来源"，null 或开关关闭则隐藏 */
    fun showInfo(info: String?) {
        if (info != null && PicGenConfig.showInfo) {
            tvInfo.text = info
            tvInfo.visibility = VISIBLE
        } else {
            tvInfo.visibility = GONE
        }
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
