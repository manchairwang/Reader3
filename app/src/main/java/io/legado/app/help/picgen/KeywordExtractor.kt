package io.legado.app.help.picgen

/**
 * 中文关键字/形容词提取（移植自 LayaAirReaderWithPic Gen.ts）
 */
object KeywordExtractor {

    //停用单字（虚词/常用字）：出现在候选词首尾则剔除该候选
    private const val CN_EDGE_STOP =
        "的了着与及或是就在有不对被把也很之其和而于上下来去说他她它你我们啊呀吧吗呢么"

    private val CN_STOPWORDS = setOf(
        "自己", "一个", "什么", "没有", "他们", "她们", "我们", "你们", "就是", "还是", "但是",
        "于是", "然后", "已经", "因为", "所以", "如果", "只是", "这些", "那些", "时候", "知道",
        "说道", "起来", "出来", "过来", "一声", "一样", "一下", "这个", "那个", "不过", "有些",
        "只见", "只听", "这时", "此时", "此刻", "随后", "随即", "顿时", "接着", "虽然", "可是",
        "尽管", "如此", "若是", "却是", "正是", "已是", "仍是", "当即", "不停", "不断", "不住",
        "一般", "立刻"
    )

    //二字候选里任一字为虚字即弃
    private const val CN_FUNC2 =
        "的了着是与就也都把被很之其而于从给跟让使对吗呢吧啊呀哦嘛么你我这那他她它谁"

    //形容词白名单（移植自 Gen.ts CN_ADJ_WHITE）
    private val CN_ADJ_WHITE = setOf(
        "美丽", "漂亮", "英俊", "丑陋", "高大", "矮小", "瘦弱", "强壮", "肥胖", "苗条",
        "洁白", "漆黑", "鲜红", "翠绿", "金黄", "蔚蓝", "雪白", "乌黑", "通红", "碧绿",
        "明亮", "黑暗", "昏暗", "耀眼", "柔和", "刺眼", "温暖", "寒冷", "炎热", "凉爽",
        "干净", "肮脏", "整齐", "杂乱", "光滑", "粗糙", "柔软", "坚硬", "湿润", "干燥",
        "快速", "缓慢", "迅速", "敏捷", "迟钝", "轻盈", "沉重", "笨拙", "灵巧", "优雅",
        "快乐", "悲伤", "愤怒", "平静", "激动", "兴奋", "疲惫", "安详", "慌乱", "镇定",
        "聪明", "愚蠢", "机智", "狡猾", "老实", "诚实", "虚伪", "善良", "邪恶", "残忍",
        "勇敢", "胆小", "坚强", "脆弱", "固执", "灵活", "傲慢", "谦虚", "严肃", "幽默",
        "古老", "现代", "新颖", "奇特", "平凡", "特殊", "普通", "珍贵", "廉价", "昂贵",
        "巨大", "庞大", "微小", "细小", "粗大", "细长", "宽阔", "狭窄", "宽广", "辽阔",
        "安静", "嘈杂", "寂静", "热闹", "冷清", "繁华", "荒凉", "幽静", "喧哗", "喧嚣",
        "绚烂", "璀璨", "清澈", "浑浊", "湍急", "宁静", "喧闹", "繁华", "荒凉", "破旧",
        "崭新", "华丽", "朴素", "精致", "粗犷", "温柔", "凶狠", "狡猾", "坚毅", "疲惫",
        "惊恐", "慌张", "从容", "悠长", "漫长", "深邃", "辽阔", "秀美", "壮丽", "凄美",
        "朦胧", "精彩", "优美", "婉转", "清脆", "沙哑", "低沉", "洪亮", "轻盈", "矫健",
        "曼妙", "婀娜", "飘逸", "凌厉", "锋利", "锐利", "璀璨", "静谧", "幽深", "苍茫",
        "萧瑟", "凄凉", "雄伟", "恢弘", "古朴", "典雅", "神秘", "诡谲", "狰狞", "威严"
    )

    //中→英 tag 映射（本地正则兜底用）
    val CN_TAG_MAP = linkedMapOf(
        "剑" to "sword", "刀" to "blade", "枪" to "spear", "棍" to "staff", "弓" to "bow",
        "马" to "horse", "龙" to "dragon", "凤" to "phoenix", "虎" to "tiger", "狼" to "wolf",
        "蛇" to "snake", "鹤" to "crane", "鹰" to "eagle", "燕" to "swallow", "花" to "flower",
        "月" to "moon", "日" to "sun", "星" to "star", "云" to "cloud", "风" to "wind",
        "雨" to "rain", "雪" to "snow", "霜" to "frost", "雾" to "mist", "山" to "mountain",
        "水" to "water", "河" to "river", "湖" to "lake", "海" to "sea", "林" to "forest",
        "树" to "tree", "竹" to "bamboo", "石" to "rock", "火" to "fire", "冰" to "ice",
        "雷" to "lightning", "城" to "city", "宫" to "palace", "殿" to "hall", "楼" to "tower",
        "桥" to "bridge", "船" to "boat", "车" to "carriage", "门" to "gate", "窗" to "window",
        "茶" to "tea", "酒" to "wine", "饭" to "food", "药" to "medicine", "书" to "book",
        "琴" to "qin", "棋" to "chess", "画" to "painting", "灯" to "lantern", "香" to "incense",
        "僧" to "monk", "道" to "taoist", "佛" to "buddha", "仙" to "immortal", "魔" to "demon",
        "鬼" to "ghost", "神" to "god", "侠" to "swordsman", "王" to "king", "后" to "queen",
        "帝" to "emperor", "将" to "general", "军" to "army", "战" to "battle", "血" to "blood",
        "夜" to "night", "晨" to "morning", "暮" to "dusk", "春" to "spring", "夏" to "summer",
        "秋" to "autumn", "冬" to "winter", "衣" to "clothes", "袍" to "robe", "裙" to "skirt",
        "发" to "hair", "眼" to "eyes", "泪" to "tears", "笑" to "smile", "哭" to "crying",
        "梦" to "dream", "心" to "heart", "魂" to "soul", "伞" to "umbrella", "扇" to "fan",
        "笛" to "flute", "箫" to "xiao flute", "鼓" to "drum", "剑客" to "swordsman",
        "少年" to "young man", "少女" to "young girl", "老人" to "old man", "女子" to "woman",
        "男子" to "man", "书生" to "scholar", "姑娘" to "maiden", "夫人" to "madam",
        "公子" to "young noble", "师父" to "master", "弟子" to "disciple", "客栈" to "inn",
        "竹林" to "bamboo forest", "庭院" to "courtyard", "屋顶" to "rooftop", "江湖" to "jianghu",
        "寺庙" to "temple", "道观" to "taoist temple", "雪地" to "snowfield", "雨夜" to "rainy night",
        "月光" to "moonlight", "夕阳" to "sunset", "星空" to "starry sky", "日出" to "sunrise"
    )

    private const val CN_KW_PER_SENT = 3

    private fun isStop(s: String): Boolean {
        if (s in CN_STOPWORDS) return true
        if (CN_EDGE_STOP.indexOf(s.first()) >= 0 || CN_EDGE_STOP.indexOf(s.last()) >= 0) return true
        if (s.length == 2 && (CN_FUNC2.indexOf(s[0]) >= 0 || CN_FUNC2.indexOf(s[1]) >= 0)) return true
        return false
    }

    private val cnWordRegex = Regex("^[\\u4e00-\\u9fff]{2,}$")

    private fun okWord(w: String): Boolean = cnWordRegex.matches(w) && !isStop(w)

    private val splitRegex = Regex("[。！？；：…!?;:，、,]+")
    private val hanRegex = Regex("[^\\u4e00-\\u9fff]")

    /**
     * 从中文正文抽取核心词（2-4 字，最多 [max] 个）：
     * ① 全页出现 ≥2 次的 n-gram（频次降序、同频长词优先、占位去重叠）
     * ② 白名单形容词按频次补缺
     * 返回逗号串，无则空串
     */
    fun extractCnKeywords(text: String, max: Int = 30): String {
        val raw = text.replace(Regex("\\s+"), "")
        if (raw.isEmpty()) return ""
        val sents = ArrayList<String>()
        for (s in splitRegex.split(raw)) {
            val c = hanRegex.replace(s, "")
            if (c.length >= 2) sents.add(c)
        }
        if (sents.isEmpty()) return ""
        //全页 2-4 字 n-gram 频次
        val freq = HashMap<String, Int>()
        for (s in sents) {
            for (n in 2..4) {
                var i = 0
                while (i + n <= s.length) {
                    val g = s.substring(i, i + n)
                    freq[g] = (freq[g] ?: 0) + 1
                    i++
                }
            }
        }
        val picked = ArrayList<String>()
        val dup = { w: String -> picked.any { it.contains(w) || w.contains(it) } }
        //占位：[句下标, 起点, 终点]，与已选词重叠的候选出局
        val occ = ArrayList<Triple<Int, Int, Int>>()
        val free = { si: Int, sp: List<Pair<Int, Int>> ->
            sp.none { (a, b) -> occ.any { (o, c, d) -> o == si && a < d && c < b } }
        }
        //① 强词：全页出现 ≥2 次；频次降序、同频长词优先
        val strong = freq.filter { it.value >= 2 && okWord(it.key) }.map { it.key to it.value }
        strong.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenByDescending { it.first.length })
        for ((w, _) in strong) {
            if (picked.size >= max) break
            if (dup(w)) continue
            var hit = false
            for (si in sents.indices) {
                val s = sents[si]
                val sp = ArrayList<Pair<Int, Int>>()
                var i = s.indexOf(w)
                while (i >= 0) {
                    sp.add(i to i + w.length)
                    i = s.indexOf(w, i + 1)
                }
                if (sp.isNotEmpty() && free(si, sp)) {
                    sp.forEach { occ.add(Triple(si, it.first, it.second)) }
                    hit = true
                }
            }
            if (hit) picked.add(w)
        }
        //② 形容词白名单按句序补缺（移植 LayaAir 词典分词补缺位：无 pinyin-pro，改用白名单词表匹配）
        for (si in sents.indices) {
            if (picked.size >= max) break
            var taken = 0
            val s = sents[si]
            for (adj in CN_ADJ_WHITE) {
                if (taken >= CN_KW_PER_SENT || picked.size >= max) break
                if (s.contains(adj) && !dup(adj) && free(si, listOf(s.indexOf(adj) to s.indexOf(adj) + adj.length))) {
                    occ.add(Triple(si, s.indexOf(adj), s.indexOf(adj) + adj.length))
                    picked.add(adj)
                    taken++
                }
            }
        }
        return picked.joinToString(",")
    }

    private val adjRegex = Regex("([\\u4e00-\\u9fff]{2,3})的")

    /**
     * 从正文抽取形容词（最多 [max] 个）：白名单词优先按频次，其余取「××的」结构非停用候选
     * 返回逗号串，无则空串
     */
    fun extractCnAdjectives(text: String, max: Int = 24): String {
        val body = text.replace(Regex("\\s+"), "")
        if (body.isEmpty()) return ""
        val freq = HashMap<String, Int>()
        for (m in adjRegex.findAll(body)) {
            val w = m.groupValues[1]
            freq[w] = (freq[w] ?: 0) + 1
            //3 字贪婪捕获补登 2 字后缀
            if (w.length == 3) {
                val w2 = w.substring(1)
                freq[w2] = (freq[w2] ?: 0) + 1
            }
        }
        val out = LinkedHashSet<String>()
        //白名单优先按频次
        val whiteHits = freq.filter { it.key in CN_ADJ_WHITE }.entries
            .sortedByDescending { it.value }.map { it.key }
        for (w in whiteHits) {
            if (out.size >= max) break
            out.add(w)
        }
        //非停用候选补缺
        if (out.size < max) {
            val others = freq.filter { it.key !in CN_ADJ_WHITE }.entries
                .sortedByDescending { it.value }.map { it.key }
            for (w in others) {
                if (out.size >= max) break
                if (!okWord(w)) continue
                out.add(w)
            }
        }
        return out.joinToString(",")
    }
}
