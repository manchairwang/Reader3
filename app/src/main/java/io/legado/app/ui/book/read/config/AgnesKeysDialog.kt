package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.help.picgen.PicGenConfig
import io.legado.app.utils.setLayout

/**
 * agnes key 编辑：多行输入（每行一个 key），保存后参与双站生图竞速。
 * key 以 cn/com 结尾可自动分站，其余两站都试。
 */
class AgnesKeysDialog : BaseDialogFragment(R.layout.dialog_agnes_keys) {

    override fun onStart() {
        super.onStart()
        dialog?.window?.let {
            it.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val attr = it.attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.CENTER
            it.attributes = attr
        }
        setLayout(0.92f, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog?.setCanceledOnTouchOutside(false)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val et = view.findViewById<EditText>(R.id.et_agnes_keys)
        et.setText(PicGenConfig.agnesKeys)
        dialog?.let { dlg ->
            dlg.setTitle(getString(R.string.agnes_keys_config))
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        val et = view?.findViewById<EditText>(R.id.et_agnes_keys) ?: return
        PicGenConfig.agnesKeys = et.text.toString().trim()
    }
}
