package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogPicGenConfigBinding
import io.legado.app.help.picgen.PicGenConfig
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 图片窗口设置：开关、窗口比例、生图渠道、提示词 LLM
 */
class PicGenConfigDialog : BaseDialogFragment(R.layout.dialog_pic_gen_config) {

    private val binding by viewBinding(DialogPicGenConfigBinding::bind)

    override fun onStart() {
        super.onStart()
        dialog?.window?.let {
            it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val attr = it.attributes
            attr.dimAmount = 0.0f
            it.attributes = attr
        }
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog?.setCanceledOnTouchOutside(true)
    }

    override fun onFragmentCreated(view: android.view.View, savedInstanceState: Bundle?) {
        initData()
        initView()
    }

    private fun initData() = binding.run {
        cbPicWindowShow.isChecked = PicGenConfig.windowShow
        cbChannelAgnesCn.isChecked = PicGenConfig.channelAgnesCn
        cbChannelAgnesCom.isChecked = PicGenConfig.channelAgnesCom
        cbChannelOmniWin.isChecked = PicGenConfig.channelOmniWin
        cbChannelOmniCat.isChecked = PicGenConfig.channelOmniCat
        cbChannelPony.isChecked = PicGenConfig.channelPony
        cbChannelQwen.isChecked = PicGenConfig.channelQwen
        cbLlmEnable.isChecked = PicGenConfig.llmEnable
        cbTailscaleDetect.isChecked = PicGenConfig.tailscaleAutoDetect
        dsbPicHeightRatio.valueFormat = { "${it.coerceAtLeast(15)}%" }
        dsbPicHeightRatio.progress = (PicGenConfig.heightRatio * 100).toInt()
        dsbPicWidthRatio.valueFormat = { "${it.coerceAtLeast(15)}%" }
        dsbPicWidthRatio.progress = (PicGenConfig.widthRatio * 100).toInt()
    }

    private fun initView() = binding.run {
        cbPicWindowShow.onCheckedChangeListener = { _, isChecked ->
            PicGenConfig.windowShow = isChecked
        }
        cbChannelAgnesCn.onCheckedChangeListener = { _, isChecked ->
            PicGenConfig.channelAgnesCn = isChecked
        }
        cbChannelAgnesCom.onCheckedChangeListener = { _, isChecked ->
            PicGenConfig.channelAgnesCom = isChecked
        }
        cbChannelOmniWin.onCheckedChangeListener = { _, isChecked ->
            PicGenConfig.channelOmniWin = isChecked
        }
        cbChannelOmniCat.onCheckedChangeListener = { _, isChecked ->
            PicGenConfig.channelOmniCat = isChecked
        }
        cbChannelPony.onCheckedChangeListener = { _, isChecked ->
            PicGenConfig.channelPony = isChecked
        }
        cbChannelQwen.onCheckedChangeListener = { _, isChecked ->
            PicGenConfig.channelQwen = isChecked
        }
        cbLlmEnable.onCheckedChangeListener = { _, isChecked ->
            PicGenConfig.llmEnable = isChecked
        }
        dsbPicHeightRatio.onChanged = {
            PicGenConfig.heightRatio = it.coerceIn(15, 60) / 100f
        }
        dsbPicWidthRatio.onChanged = {
            PicGenConfig.widthRatio = it.coerceIn(15, 60) / 100f
        }
        cbTailscaleDetect.onCheckedChangeListener = { _, isChecked ->
            PicGenConfig.tailscaleAutoDetect = isChecked
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? io.legado.app.ui.book.read.ReadBookActivity)?.onPicConfigChanged()
    }
}
