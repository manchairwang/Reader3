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
 * agnes key 编辑：agnes-ai.cn / agnes-ai.com 两个独立输入框，各可多行多个 key。
 * 保存后分别加入对应站的生图竞速池。
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
        view.findViewById<EditText>(R.id.et_agnes_keys_cn)
            .setText(PicGenConfig.agnesKeysCn)
        view.findViewById<EditText>(R.id.et_agnes_keys_com)
            .setText(PicGenConfig.agnesKeysCom)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        view?.findViewById<EditText>(R.id.et_agnes_keys_cn)?.let {
            PicGenConfig.agnesKeysCn = it.text.toString().trim()
        }
        view?.findViewById<EditText>(R.id.et_agnes_keys_com)?.let {
            PicGenConfig.agnesKeysCom = it.text.toString().trim()
        }
    }
}
