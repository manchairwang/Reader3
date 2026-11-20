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

    //SenseNova（日日新）OpenAI 兼容站（token 前缀端点）：用户自备 key，文本+文生图模型自动发现
    val SENSENOVA_SITES = listOf(
        AgnesSite(
            key = "sensenova_cn",
            base = "https://token.sensenova.cn",
            tokens = listOf()
        ),
        AgnesSite(
            key = "sensenova_com",
            base = "https://token.sensenova.ai",
            tokens = listOf()
        )
    )

    //SenseNova 文本模型兜底（优先自动发现 /v1/models）
    val SENSENOVA_CHAT_MODELS =
        listOf("sensenova-6.8-flash-lite", "SenseChat-5", "SenseChat-Turbo")

    //NVIDIA NIM（build.nvidia.com 注册，nvapi- 开头 key）：OpenAI 兼容，模型自动发现
    val NIM_SITE = AgnesSite(
        key = "nim",
        base = "https://integrate.api.nvidia.com",
        tokens = listOf()
    )

    //NIM 兜底模型（来源：build.nvidia.com 免费预览模型页；优先运行时自动发现 /v1/models 并实测）
    //官方免费文生图：flux_1-dev / flux_1-schnell / flux_1-kontext-dev / flux.2-klein-4b /
    //               flux-3-image-klein / stable-diffusion-3_5-large / qwen-image
    val NIM_IMAGE_MODELS = listOf(
        "black-forest-labs/flux.1-schnell",
        "black-forest-labs/flux_1-schnell",
        "black-forest-labs/flux_1-dev",
        "qwen/qwen-image",
        "stability-ai/stable-diffusion-3_5-large"
    )
    val NIM_CHAT_MODELS = listOf(
        "z-ai/glm-5.3-flash",
        "google/gemma-4-31b-it",
        "minimaxai/minimax-m3.1-flash",
        "stepfun-ai/step-5-preview",
        "nvidia/nemotron-3.5-lightning-30b-a3b",
        "google/gemma-3-12b-it"
    )

    //omniroute 网关：tailscale win/cat + 本机
    val OMNI_HOSTS = listOf(
        OmniHost("win", "http://win.tail976197.ts.net:20128", "sk-4a9487328faacff4-0773dd-9bc78fc6"),
        OmniHost("win-local", "http://localhost:20128", "sk-4a9487328faacff4-0773dd-9bc78fc6"),
        OmniHost("cat", "http://cat.tail976197.ts.net:20128", "sk-77e71f5303ff9d7f-06e1ee-da49dc29")
    )

    //omniroute 生图模型
    val OMNI_IMAGE_MODELS = listOf("mybestvision")

    //omniroute 优先提示词 combo
    val PREFERRED_CHAT_COMBOS = listOf("mybestfast", "mygoodfast")

    //本地生图：候选地址（本机优先，其次 tailscale win 机），后台探活自动启用
    val PONY_HOSTS = listOf(
        "http://127.0.0.1:1234",
        "http://win.tail976197.ts.net:1234"
    )
    val PONY_NEG =
        "score_4, score_5, score_6, worst quality, low quality, jpeg artifacts, " +
                "blurry, watermark, signature, text, bad anatomy, bad hands"
    val QWEN_HOSTS = listOf(
        "http://127.0.0.1:1236",
        "http://win.tail976197.ts.net:1236",
        //tailscale IP 直连候选：不依赖手机端 MagicDNS 域名解析
        "http://100.117.227.114:1236",
        //局域网直连候选（同一 WiFi 下无需 tailscale）
        "http://192.168.1.27:1236"
    )

    data class AgnesSite(val key: String, val base: String, val tokens: List<String>)
    data class OmniHost(val name: String, val base: String, val token: String)
}
