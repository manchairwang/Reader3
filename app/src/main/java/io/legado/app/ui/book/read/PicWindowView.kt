package io.legado.app.ui.book.read

import android.content.Context
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.animation.ValueAnimator
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
    private val ivLoading: ImageView
    private val tvStatus: TextView
    private val btnClose: ImageView
    private val tvInfo: TextView
    private lateinit var loadingAnimator: ObjectAnimator

    var onClose: (() -> Unit)? = null

    /** 窗口手势翻页：true=下一页，false=上一页 */
    var onPageTurn: ((next: Boolean) -> Unit)? = null

    /** 中 1/3 单击(正文默认开菜单)，交 activity 处理 */
    var onCenterTap: (() -> Unit)? = null

    //整体窗口几何(正文+图片窗口视为一个大窗口): activity 布局后提供, 单击分区用
    var wholeWidthPx: Int = 0
    var wholeLeftPx: Int = 0

    //本次触摸是否发生了拖动(拖动不算单击)
    private var gestureDetectorDragged = false

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
            gestureDetectorDragged = true
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
            gestureDetectorDragged = true
            onPageTurn?.invoke(e2.x - e1.x < 0)
            return true
        }
    })

    init {
        LayoutInflater.from(context).inflate(R.layout.view_pic_window, this, true)
        ivPic = findViewById(R.id.iv_pic)
        ivLoading = findViewById(R.id.iv_pic_loading)
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
        //加载转圈：自绘环形匀速旋转。系统 progressBarStyle 在部分 ROM 渲染成
        //1/4 弧段闪烁且不转（用户反馈"一直 1/4 段、没有进展"），故代码动画代替
        loadingAnimator = ObjectAnimator.ofFloat(ivLoading, View.ROTATION, 0f, 360f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    //与正文 ReadView 相同的拦截模式：除按钮区域外全部拦截，手势在 onTouchEvent 统一处理。
    //setOnTouchListener 方案不可靠：事件先派发给子 view(iv_pic 占满窗口)，
    //父 view 的 listener 只在子 view 不消费时才被调用，翻页时灵时不灵。
    private val hitRect = android.graphics.Rect()

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            //关闭按钮/状态文字区域内不拦截，保住点击
            btnClose.getHitRect(hitRect)
            if (hitRect.contains(ev.x.toInt(), ev.y.toInt())) return false
            if (tvStatus.visibility == VISIBLE) {
                tvStatus.getHitRect(hitRect)
                if (hitRect.contains(ev.x.toInt(), ev.y.toInt())) return false
            }
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP && !gestureDetectorDragged) {
            //单击翻页：与正文同区域划分(左1/3上一页, 中1/3不翻页或开菜单, 右1/3下一页)。
            //正文尺寸(窗口+正文整体)由 activity 提供,保证两个窗口的点击区域视觉上连续。
            val xInWhole = wholeWidthPx > 0
            when {
                xInWhole && event.x + wholeLeftPx < wholeWidthPx * 0.33f -> onPageTurn?.invoke(false)
                xInWhole && event.x + wholeLeftPx > wholeWidthPx * 0.66f -> onPageTurn?.invoke(true)
                //中 1/3: 正文默认开菜单; 图片窗口没有菜单逻辑,同样回调交 activity 决定
                else -> onCenterTap?.invoke()
            }
        }
        if (event.action == MotionEvent.ACTION_DOWN) gestureDetectorDragged = false
        //消费事件流，保证后续 MOVE/UP 持续到达
        return true
    }

    fun setBitmap(bitmap: Bitmap) {
        ivPic.setImageBitmap(bitmap)
        ivPic.visibility = VISIBLE
        stopLoading()
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
        ivLoading.visibility = VISIBLE
        loadingAnimator.start()
        tvStatus.visibility = GONE
    }

    fun showError(msg: String) {
        stopLoading()
        ivPic.visibility = GONE
        tvStatus.visibility = VISIBLE
        tvStatus.text = msg
    }

    fun showStatus(msg: String) {
        //错误信息保留不被进度覆盖（点击可复制）
        if (tvStatus.visibility == VISIBLE && tvStatus.text.startsWith("失败")) return
        if (ivPic.visibility != VISIBLE) {
            //失败时停转圈：否则一直转个不停（用户看到"没有进展"）
            if (msg.startsWith("失败")) stopLoading() else {
                ivLoading.visibility = VISIBLE
                loadingAnimator.start()
            }
            tvStatus.visibility = VISIBLE
            tvStatus.text = msg
        }
    }

    fun clear() {
        ivPic.setImageDrawable(null)
        ivPic.visibility = GONE
        stopLoading()
        tvStatus.visibility = GONE
    }

    fun hasImage(): Boolean = ivPic.visibility == VISIBLE

    private fun stopLoading() {
        ivLoading.visibility = GONE
        loadingAnimator.cancel()
    }
}
