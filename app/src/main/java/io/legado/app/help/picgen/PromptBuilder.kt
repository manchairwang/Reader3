package io.legado.app.help.picgen

/**
 * 提示词构建（移植自 LayaAirReaderWithPic Gen.ts）
 */
object PromptBuilder {

    //TAGS LLM 提示词（原样移植）
    const val SUMMARY_PROMPT =
        "You are a stable-diffusion prompt engineer. Read the novel excerpt below and write an English image-generation prompt.\n" +
                "OUTPUT RULES (mandatory):\n" +
                "1. Output English tags separated by commas. NO sentences, NO explanations.\n" +
                "2. Tags MUST cover: character count (1girl/2girls/1boy...), gender, hairstyle, clothing, action/pose, setting/scene, time of day, lighting, camera shot (close-up/wide shot/upper body...).\n" +
                "3. Use 15 to 30 tags total, Danbooru tag style, lowercase.\n" +
                "4. Each tag must be unique. Do not repeat tags.\n" +
                "5. Quality tags first: masterpiece, best quality, highly detailed, ancient chinese style.\n" +
                "6. First line must be exactly: TAGS:\n" +
                "Output format:\nTAGS:\n<comma separated english tags>\n"

    /** 拼提示词：带前后页上下文（保持人物/场景/画风连贯） */
    fun buildSummaryPrompt(pageText: String, contextText: String = ""): String {
        var prompt = SUMMARY_PROMPT + pageText.take(1500)
        if (contextText.isNotBlank()) {
            prompt += "\n\n前后页上下文（仅用于保持人物、场景、画风连贯，不必画进本图）：\n" + contextText.take(800)
        }
        return prompt
    }

    private val hanRegex = Regex("[\\u4e00-\\u9fff]+")
    private val tagLetterRegex = Regex("[a-z]", RegexOption.IGNORE_CASE)

    /** 清洗 LLM 输出：去中文、拆逗号、只留含英文字母的 token、去重保序 */
    fun cleanTags(text: String): String {
        val seen = HashSet<String>()
        return hanRegex.replace(text, " ")
            .split(Regex("[, ]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { tagLetterRegex.containsMatchIn(it) }
            .filter {
                val k = it.lowercase()
                if (seen.contains(k)) false else {
                    seen.add(k); true
                }
            }
            .joinToString(", ")
            .replace(Regex("[, ]{2,}"), ", ")
            .trim()
    }

    /** 数有效 tag 个数（含英文字母才算） */
    fun tagCount(s: String): Int =
        if (s.isEmpty()) 0
        else s.split(",").count { tagLetterRegex.containsMatchIn(it.trim()) }

    /** safety 响应识别（移植 isBadSummary 的 safety 部分） */
    fun isSafetyResponse(text: String): Boolean =
        Regex("safty|safety|User Safty", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** 从 LLM 响应文本提取 TAGS 行（宽容接受：无 TAGS: 行也打捞英文 tag） */
    fun extractTags(llmText: String): String? {
        if (isSafetyResponse(llmText)) return null
        val tags = cleanTags(llmText)
        if (tagCount(tags) < 5) return null
        return tags
    }

    //本地兜底：中文关键词 → 英文 tag 映射（移植 CN_TAG_MAP）
    private val CN_TAG_MAP = listOf(
        Regex("少女|女孩|姑娘|女子|她") to "1girl",
        Regex("男孩|男子|少年|青年|他") to "1boy",
        Regex("老者|老人|白须|老者|老翁") to "1old man",
        Regex("孩子|小孩|童") to "child",
        Regex("夜|深夜|月|星辰|星光") to "night, moonlight, starry sky",
        Regex("黄昏|夕阳|傍晚") to "sunset, dusk",
        Regex("清晨|黎明|朝阳|晨光") to "sunrise, morning light",
        Regex("雨|下雨|雨滴") to "rain, wet",
        Regex("雪|雪花|寒冬") to "snow, winter",
        Regex("风|狂风|微风") to "wind, flowing clothes",
        Regex("山|群山|峰|崇山") to "mountain, mountains",
        Regex("林|森林|树林|竹") to "forest, trees",
        Regex("江|河|湖|溪|水|海") to "river, water",
        Regex("城|宫殿|皇宫|府|庭院|楼阁") to "ancient chinese architecture, palace",
        Regex("村|小镇|茅屋|田园") to "village, countryside",
        Regex("花|桃花|梅花|牡丹|荷") to "flowers, petals",
        Regex("剑|刀|武|侠|战|打斗") to "sword, martial arts, dynamic pose",
        Regex("马|骑|驰") to "horse, riding",
        Regex("灯|烛|火|光") to "lantern, candlelight, warm lighting",
        Regex("云|雾|烟") to "clouds, mist",
        Regex("酒|宴|席") to "feast, banquet",
        Regex("书|读|字|卷") to "book, reading"
    )

    /** 本地兜底提示词：LLM 全挂时按中文关键词映射拼英文 tag */
    fun buildFallbackPrompt(pageText: String): String {
        val base = "masterpiece, best quality, highly detailed, chinese style illustration, " +
                "ancient china, flowing robes, cinematic lighting"
        val found = ArrayList<String>()
        for ((re, tag) in CN_TAG_MAP) {
            if (re.containsMatchIn(pageText) && tag !in found) found.add(tag)
        }
        return base + if (found.isNotEmpty()) ", " + found.joinToString(", ") else ""
    }

    /** pony 模型提示词（score_9 前缀） */
    fun buildPonyPrompt(prompt: String): String {
        val body = cleanTags(prompt)
        return (if (body.contains("score_9")) "" else "score_9, score_8_up, score_7_up, ") + body.take(900)
    }
}
