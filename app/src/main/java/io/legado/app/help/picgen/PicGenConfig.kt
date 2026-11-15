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

    var channelPony: Boolean
        get() = getBoolean(PreferKey.picGenChannelPony)
        set(value) = putBoolean(PreferKey.picGenChannelPony, value)

    var channelQwen: Boolean
        get() = getBoolean(PreferKey.picGenChannelQwen)
        set(value) = putBoolean(PreferKey.picGenChannelQwen, value)

    //提示词 LLM 竞速开关（关闭则直接本地正则兜底）
    var llmEnable: Boolean
        get() = if (contains(PreferKey.picGenLlmEnable)) getBoolean(PreferKey.picGenLlmEnable) else true
        set(value) = putBoolean(PreferKey.picGenLlmEnable, value)

    //tailscale 自动检测开关：每 10 分钟探活 win/cat 网关，可达则自动启用对应渠道
    var tailscaleAutoDetect: Boolean
        get() = if (contains("picGenTailscaleAutoDetect")) getBoolean("picGenTailscaleAutoDetect") else true
        set(value) = putBoolean("picGenTailscaleAutoDetect", value)

    //用户自定义 agnes key：多行文本（每行一个），生图时追加到各站 token 池
    var agnesKeys: String
        get() = getString("picGenAgnesKeys", null) ?: ""
        set(value) = putString("picGenAgnesKeys", value)

    //运行时缓存：tailscale 网关可达状态（不持久化，每 10 分钟刷新）
    @Volatile
    var omniWinReachable: Boolean = false
    @Volatile
    var omniCatReachable: Boolean = false
}
