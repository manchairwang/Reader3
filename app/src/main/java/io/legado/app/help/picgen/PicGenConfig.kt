package io.legado.app.help.picgen

import android.content.Context
import android.content.SharedPreferences
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getBoolean
import io.legado.app.utils.getFloat
import io.legado.app.utils.putBoolean
import io.legado.app.utils.putFloat
import io.legado.app.utils.putString
import splitties.init.appCtx
import splitties.init.appCtx

/**
 * 图片窗口配置（SharedPreferences，与 LocalConfig 同机制）
 */
@Suppress("ConstPropertyName")
object PicGenConfig : SharedPreferences
by appCtx.getSharedPreferences("picgen", Context.MODE_PRIVATE) {

    //总开关：显示图片窗口
    var windowShow: Boolean
        get() = if (contains(PreferKey.picWindowShow)) getBoolean(PreferKey.picWindowShow) else true
        set(value) = putBoolean(PreferKey.picWindowShow, value)

    //左下角诊断层：提示词 + 生成来源(服务器/模型)
    var showInfo: Boolean
        get() = if (contains(PreferKey.picWindowShowInfo)) getBoolean(PreferKey.picWindowShowInfo) else false
        set(value) = putBoolean(PreferKey.picWindowShowInfo, value)

    //竖屏：图片窗口高度占屏幕高度比例（旧默认 0.30 自动升级 0.45）
    var heightRatio: Float
        get() = getFloat(PreferKey.picWindowHeightRatio, 0f)
            .takeIf { it >= 0.15f && it != 0.30f } ?: 0.45f
        set(value) = putFloat(PreferKey.picWindowHeightRatio, value)

    //横屏：图片窗口宽度占屏幕宽度比例（旧默认 0.30 自动升级 0.45）
    var widthRatio: Float
        get() = getFloat(PreferKey.picWindowWidthRatio, 0f)
            .takeIf { it >= 0.15f && it != 0.30f } ?: 0.45f
        set(value) = putFloat(PreferKey.picWindowWidthRatio, value)

    //渠道开关
    var channelAgnesCn: Boolean
        get() = if (contains(PreferKey.picGenChannelAgnesCn)) getBoolean(PreferKey.picGenChannelAgnesCn) else true
        set(value) = putBoolean(PreferKey.picGenChannelAgnesCn, value)

    var channelAgnesCom: Boolean
        get() = if (contains(PreferKey.picGenChannelAgnesCom)) getBoolean(PreferKey.picGenChannelAgnesCom) else true
        set(value) = putBoolean(PreferKey.picGenChannelAgnesCom, value)

    var channelOmniWin: Boolean
        get() = if (contains(PreferKey.picGenChannelOmniWin)) getBoolean(PreferKey.picGenChannelOmniWin) else true
        set(value) = putBoolean(PreferKey.picGenChannelOmniWin, value)

    var channelOmniCat: Boolean
        get() = if (contains(PreferKey.picGenChannelOmniCat)) getBoolean(PreferKey.picGenChannelOmniCat) else true
        set(value) = putBoolean(PreferKey.picGenChannelOmniCat, value)

    //pony/qwen 生图：后台探活自动启用（不持久化、不在界面显示）
    @Volatile
    var channelPony: Boolean = false
    @Volatile
    var channelQwen: Boolean = false

    //提示词 LLM 竞速开关（关闭则直接本地正则兜底）
    var llmEnable: Boolean
        get() = if (contains(PreferKey.picGenLlmEnable)) getBoolean(PreferKey.picGenLlmEnable) else true
        set(value) = putBoolean(PreferKey.picGenLlmEnable, value)

    //用户自定义 agnes key：cn / com 各自多行文本（每行一个 key），分别加入对应站竞速
    var agnesKeysCn: String
        get() = getString("picGenAgnesKeysCn", null) ?: ""
        set(value) = putString("picGenAgnesKeysCn", value)

    var agnesKeysCom: String
        get() = getString("picGenAgnesKeysCom", null) ?: ""
        set(value) = putString("picGenAgnesKeysCom", value)

    //用户自定义 SenseNova key：cn（api.sensenova.cn）/ com（api.sensenova.ai）各自多行文本，加入摘要 LLM 竞速
    var sensenovaKeysCn: String
        get() = getString("picGenSensenovaKeysCn", null) ?: ""
        set(value) = putString("picGenSensenovaKeysCn", value)

    var sensenovaKeysCom: String
        get() = getString("picGenSensenovaKeysCom", null) ?: ""
        set(value) = putString("picGenSensenovaKeysCom", value)

    //用户自定义 Nvidia NIM key：多行文本（每行一个 nvapi-…），加入摘要 LLM 与生图竞速
    var nimKeys: String
        get() = getString("picGenNimKeys", null) ?: ""
        set(value) = putString("picGenNimKeys", value)

    //运行时缓存：tailscale 网关可达状态（不持久化，每 10 分钟刷新）
    @Volatile
    var omniWinReachable: Boolean = false
    @Volatile
    var omniCatReachable: Boolean = false
}
