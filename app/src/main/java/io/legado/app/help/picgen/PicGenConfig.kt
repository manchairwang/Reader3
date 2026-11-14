package io.legado.app.help.picgen

import android.content.Context
import android.content.SharedPreferences
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getBoolean
import io.legado.app.utils.getFloat
import io.legado.app.utils.putBoolean
import io.legado.app.utils.putFloat
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

    //竖屏：图片窗口高度占屏幕高度比例
    var heightRatio: Float
        get() = getFloat(PreferKey.picWindowHeightRatio, 0.30f).takeIf { it > 0f } ?: 0.30f
        set(value) = putFloat(PreferKey.picWindowHeightRatio, value)

    //横屏：图片窗口宽度占屏幕宽度比例
    var widthRatio: Float
        get() = getFloat(PreferKey.picWindowWidthRatio, 0.30f).takeIf { it > 0f } ?: 0.30f
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
}
