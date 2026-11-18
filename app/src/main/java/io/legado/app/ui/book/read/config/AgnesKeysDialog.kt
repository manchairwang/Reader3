package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.help.picgen.PicGenChannels
import io.legado.app.help.picgen.PicGenConfig
import io.legado.app.help.picgen.PicGenManager
import io.legado.app.utils.setLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * key 管理（3 个服务 5 个输入框）：
 * ① agnes-ai.cn / agnes-ai.com（留空用内置 key）
 * ② SenseNova 日日新 国内/国际（自备 key）
 * ③ Nvidia NIM（自备 key）
 * 「检 测」按钮并行检测每个服务第一个 key：模型发现 + 摘要实测，结果逐行回显。
 * 确定=保存关闭；取消=放弃关闭。检测会即时刷新模型发现缓存。
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
        setLayout(0.92f, 0.9f)
        dialog?.setCanceledOnTouchOutside(false)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<EditText>(R.id.et_agnes_keys_cn)
            .setText(PicGenConfig.agnesKeysCn)
        view.findViewById<EditText>(R.id.et_agnes_keys_com)
            .setText(PicGenConfig.agnesKeysCom)
        view.findViewById<EditText>(R.id.et_sensenova_keys_cn)
            .setText(PicGenConfig.sensenovaKeysCn)
        view.findViewById<EditText>(R.id.et_sensenova_keys_com)
            .setText(PicGenConfig.sensenovaKeysCom)
        view.findViewById<EditText>(R.id.et_nim_keys)
            .setText(PicGenConfig.nimKeys)
        view.findViewById<View>(R.id.tv_ok).setOnClickListener {
            //view 销毁前立即保存（onDismiss 时 view 已为 null，保存不会生效）
            save()
            dismissAllowingStateLoss()
        }
        view.findViewById<View>(R.id.tv_cancel).setOnClickListener {
            dismissAllowingStateLoss()
        }
        view.findViewById<View>(R.id.tv_test).setOnClickListener { testAll() }
    }

    /** 把 5 个输入框内容写回配置 */
    private fun save() {
        val v = view ?: return
        PicGenConfig.agnesKeysCn = v.findViewById<EditText>(R.id.et_agnes_keys_cn).text.toString().trim()
        PicGenConfig.agnesKeysCom = v.findViewById<EditText>(R.id.et_agnes_keys_com).text.toString().trim()
        PicGenConfig.sensenovaKeysCn = v.findViewById<EditText>(R.id.et_sensenova_keys_cn).text.toString().trim()
        PicGenConfig.sensenovaKeysCom = v.findViewById<EditText>(R.id.et_sensenova_keys_com).text.toString().trim()
        PicGenConfig.nimKeys = v.findViewById<EditText>(R.id.et_nim_keys).text.toString().trim()
    }

    private fun etText(id: Int): String? =
        view?.findViewById<EditText>(id)?.text?.toString()?.trim()

    private fun firstKey(text: String?): String? =
        text?.split('\n', ',')?.map { it.trim() }?.firstOrNull { it.length > 8 }

    /** 统一检测：每个服务取第一个 key（agnes 缺省用内置 key）并行测，结果逐行回显 */
    private fun testAll() {
        val tvBtn = view?.findViewById<TextView>(R.id.tv_test) ?: return
        val tvResult = view?.findViewById<TextView>(R.id.tv_test_result) ?: return
        tvResult.visibility = View.VISIBLE
        tvResult.text = ""
        tvBtn.text = getString(R.string.keys_testing)
        //siteKey -> 测试 token
        val targets = ArrayList<Pair<String, String>>()
        firstKey(etText(R.id.et_agnes_keys_cn))?.let { targets.add("agnes_cn" to it) }
            ?: PicGenChannels.AGNES_SITES.first { it.key == "agnes_cn" }.tokens.firstOrNull()
                ?.let { targets.add("agnes_cn" to it) }
        firstKey(etText(R.id.et_agnes_keys_com))?.let { targets.add("agnes_com" to it) }
            ?: PicGenChannels.AGNES_SITES.first { it.key == "agnes_com" }.tokens.firstOrNull()
                ?.let { targets.add("agnes_com" to it) }
        firstKey(etText(R.id.et_sensenova_keys_cn))?.let { targets.add("sensenova_cn" to it) }
        firstKey(etText(R.id.et_sensenova_keys_com))?.let { targets.add("sensenova_com" to it) }
        firstKey(etText(R.id.et_nim_keys))?.let { targets.add("nim" to it) }
        if (targets.isEmpty()) {
            appendResult("没有可检测的 key")
            tvBtn.text = getString(R.string.keys_test)
            return
        }
        var pending = targets.size
        targets.forEach { (siteKey, tk) ->
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val line = PicGenManager.testService(siteKey, tk)
                withContext(Dispatchers.Main) {
                    appendResult(line)
                    pending--
                    if (pending <= 0) {
                        tvBtn.text = getString(R.string.keys_test)
                    }
                }
            }
        }
    }

    private fun appendResult(msg: String) {
        val tv = view?.findViewById<TextView>(R.id.tv_test_result) ?: return
        tv.append(msg + "\n")
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        //兜底：万一没走「确定」按钮（如返回键关闭），view 尚存时也保存一次
        if (view != null) save()
    }
}
