package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.help.picgen.PicStyleConfig
import io.legado.app.model.ReadBook
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setLayout
import io.legado.app.utils.visible

/**
 * 生图风格选择：3 列方图列表；点击选中（按书记忆）；右上角"清除风格"恢复无附加。
 */
class PicStyleDialog : BaseDialogFragment(R.layout.dialog_pic_style) {

    private lateinit var rvStyle: RecyclerView
    private lateinit var tvCurrent: TextView

    override fun onStart() {
        super.onStart()
        dialog?.window?.let {
            it.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val attr = it.attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.CENTER
            it.attributes = attr
        }
        //全屏：宽高都占满
        setLayout(1.0f, 0.98f)
        dialog?.setCanceledOnTouchOutside(false)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        rvStyle = view.findViewById(R.id.rv_style)
        tvCurrent = view.findViewById(R.id.tv_current_style)
        view.findViewById<TextView>(R.id.tv_clear_style).setOnClickListener {
            PicStyleConfig.setStyle(ReadBook.book, 0)
            upCurrent()
            (rvStyle.adapter as? StyleAdapter)?.notifyDataSetChanged()
        }
        view.findViewById<TextView>(R.id.btn_style_ok).setOnClickListener {
            dismissAllowingStateLoss()
        }
        rvStyle.layoutManager = GridLayoutManager(requireContext(), 2)
        rvStyle.adapter = StyleAdapter()
        upCurrent()
    }

    private fun upCurrent() {
        val idx = PicStyleConfig.styleIndex(ReadBook.book)
        if (idx <= 0) {
            tvCurrent.text = getString(R.string.pic_style_none)
        } else {
            val name = PicStyleConfig.loadStyles().firstOrNull { it.index == idx }?.name
                ?: idx.toString()
            tvCurrent.text = getString(R.string.pic_style_current, name)
        }
    }

    inner class StyleAdapter : RecyclerView.Adapter<StyleHolder>() {

        private val styles = PicStyleConfig.loadStyles()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StyleHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_pic_style, parent, false)
            return StyleHolder(v)
        }

        override fun getItemCount(): Int = styles.size

        override fun onBindViewHolder(holder: StyleHolder, position: Int) {
            val style = styles[position]
            holder.bind(style)
        }
    }

    inner class StyleHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivStyle: ImageView = view.findViewById(R.id.iv_style)
        private val ivCheck: ImageView = view.findViewById(R.id.iv_check)
        private val tvPrompt: TextView = view.findViewById(R.id.tv_prompt)
        private val flImage: View = view.findViewById(R.id.fl_image)

        fun bind(style: PicStyleConfig.Style) {
            val bmp: Bitmap? = PicStyleConfig.thumb(style.index)
            if (bmp != null) ivStyle.setImageBitmap(bmp)
            //方图：高随宽度自适应
            ivStyle.post {
                val side = ivStyle.width
                if (side > 0) {
                    val lp = ivStyle.layoutParams
                    lp.height = side
                    ivStyle.layoutParams = lp
                }
            }
            tvPrompt.text = style.name
            val selected = PicStyleConfig.styleIndex(ReadBook.book) == style.index
            ivCheck.visible(selected)
            //选中：彩色加粗边框；未选：细灰边框
            flImage.setBackgroundResource(
                if (selected) R.drawable.bg_pic_style_selected
                else R.drawable.bg_pic_style_normal
            )
            itemView.setOnClickListener {
                PicStyleConfig.setStyle(ReadBook.book, style.index)
                upCurrent()
                (rvStyle.adapter as? StyleAdapter)?.notifyDataSetChanged()
            }
        }
    }
}
