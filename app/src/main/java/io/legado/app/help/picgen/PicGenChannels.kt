package io.legado.app.help.picgen

/**
 * 生图渠道表（移植自 LayaAirReaderWithPic gen.config.json，字段原样）
 */
object PicGenChannels {

    //agnes-ai 免费生图：双站多密钥轮换
    val AGNES_SITES = listOf(
        AgnesSite(
            key = "agnes_cn",
            base = "https://api.agnes-ai.cn",
            tokens = listOf(
                "sk-V3bHQQy6Fhw0RnWznCyBrFAk6bSUziLeo8Kjqmey5iE21qlI",
                "sk-hEyUeEYPM3ojUyVWBVJnWS769274VheURRe3FNeyYXMLEp0G",
                "sk-y931juFcWiduQ9o4s0EvfYYfPOD4mQEhCB2xBPwKQVsy6Nax",
                "sk-dlQqvPZKBPNwSrXXZxsGDAvMvKBQ0xhIix50mOucq5Z3cm11",
                "sk-CVJwagumMvV4AkhgH1fKKJyAje8fiunYgkGLNRvVKnPsfekc",
                "sk-e9o3ger5cGwPnENtqVCuYK0moNcpjEGJOLOIkr4SF9MREQfY"
            )
        ),
        AgnesSite(
            key = "agnes_com",
            base = "https://apihub.agnes-ai.com",
            tokens = listOf(
                "sk-X8uy6GgdUk2Cgbf8uadY2AzERGOdDdmzoVlMfsJ1eXnpRRi8",
                "sk-vMTwCMiTX3qFNJjmSi08gCFzoPyFIC8Wj1Bukt0tu2EGMtFS",
                "sk-H6ZoELeYzSk9kRrZSQiLe3B1xutYasB4XgjSrbNb2VauT7F2",
                "sk-1pDgDCa1OtK7JU6XGKOYFjqkOgISB18gYLZIDcoEvluOSDgk"
            )
        )
    )

    //agnes 文生图模型：三模型轮换
    val AGNES_IMAGE_MODELS = listOf(
        "agnes-image-2.5-flash",
        "agnes-image-2.1-flash",
        "agnes-image-2.0-flash"
    )

    //agnes 文本模型（提示词兜底）
    val AGNES_CHAT_MODELS = listOf("agnes-2.5-flash", "agnes-3.0-flash")

    //omniroute 网关：tailscale win/cat + 本机
    val OMNI_HOSTS = listOf(
        OmniHost("win", "http://win.tail976197.ts.net:20128", "sk-4a9487328faacff4-d5e5f6-932e7e52"),
        OmniHost("win-local", "http://localhost:20128", "sk-4a9487328faacff4-d5e5f6-932e7e52"),
        OmniHost("cat", "http://cat.tail976197.ts.net:20128", "sk-3b1b80865d32ec86-fe3da1-1afe5927")
    )

    //omniroute 生图模型
    val OMNI_IMAGE_MODELS = listOf("mybestvision")

    //omniroute 优先提示词 combo
    val PREFERRED_CHAT_COMBOS = listOf("mybestfast", "mygoodfast")

    //本地生图
    const val PONY_BASE = "http://127.0.0.1:1234"
    const val PONY_NEG =
        "score_4, score_5, score_6, worst quality, low quality, jpeg artifacts, " +
                "blurry, watermark, signature, text, bad anatomy, bad hands"
    const val QWEN_BASE = "http://127.0.0.1:1236"

    data class AgnesSite(val key: String, val base: String, val tokens: List<String>)
    data class OmniHost(val name: String, val base: String, val token: String)
}
