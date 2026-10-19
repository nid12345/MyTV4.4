package top.yogiczy.mytv.legacy.data

/**
 * 按**订阅源**决定频道行的台标策略（v1.22 需求 2 / 3）。
 *
 * ---------------------------------------------------------------------------
 * 为什么要按源区分（用户原话）：
 *   ② 「内置源的虎牙、斗鱼、yy轮播的源的头像就使用自带的台标 logo 就好」
 *   ③ 「潮汕节目回放这个订阅源，里面的台标 logo 位置可以去掉，把位置让给标题」
 *
 * 实测这三个源（2026-10-19）：
 *   · 虎牙 `huyayqk.m3u`：1086 条全带 `tvg-logo`，指向
 *     `huyaimg.msstatic.com/avatar/…`（主播头像）与 `cdn.jsdelivr.net/gh/feiyang666999/testvideo/tg.jpg`；
 *   · 斗鱼 `douyuyqk.m3u` / YY轮播 `yylunbo.m3u` 同理。
 *   这些头像对「看电视」没有任何信息量，还每次都要联网取 → 用户要求改用内置台标。
 *
 *   · 潮汕节目回放 `chaoshan.txt`：`名称,#genre#` + `标题,url` 格式，**根本没有 logo**，
 *     而标题极长（例：「潮语新声唱不停！"海墘歌声"2026潮语歌曲唱作大赛第九场再掀热潮」），
 *     留着空的台标位纯属浪费宽度 → 用户要求去掉、让位给标题。
 *
 * 判据用 **URL 子串**而不是源名：源名允许用户在设置里改，URL 不会变。
 * ---------------------------------------------------------------------------
 */
object SourceLogoPolicy {

    /** 台标策略。 */
    enum class Mode {
        /** 默认：内置 → 源自带 `tvg-logo` → 联网台标仓库（与 v1.21 一致）。 */
        DEFAULT,

        /** 只用**内置**台标，不联网、不看源里的 `tvg-logo`（虎牙 / 斗鱼 / YY轮播）。 */
        BUILTIN_ONLY,

        /** 完全不显示台标，把台标位让给标题（潮汕节目回放）。 */
        NONE,
    }

    /** 虎牙 / 斗鱼 / YY轮播 —— 只认内置台标。 */
    private val BUILTIN_ONLY_MARKS = listOf("huyayqk", "douyuyqk", "yylunbo")

    /** 潮汕节目回放 —— 不显示台标。 */
    private val NO_LOGO_MARKS = listOf("chaoshan")

    /**
     * 取某个订阅源的台标策略。
     *
     * @param sourceUrl 订阅源地址（[Prefs.currentSourceUrl]）
     */
    fun modeOf(sourceUrl: String?): Mode {
        val url = sourceUrl?.trim()?.lowercase().orEmpty()
        if (url.isBlank()) return Mode.DEFAULT

        // ⚠️ 只匹配**路径部分**（剥掉协议 / 主机 / 端口 / 查询串）。
        // 早先的写法是把 `sub.ottiptv.cc/huyayqk` 当成连续子串去 contains，
        // 于是 `http://sub.ottiptv.cc:8080/douyuyqk.m3u?x=1` 这种带端口/带 query 的
        // 地址会匹配失败（端口插在主机与路径之间，把子串打断了）——
        // 单测 `SourceLogoPolicyTest` 抓到的就是这个边界。取路径后只剩文件名，最稳。
        val path = url.substringAfter("://").substringAfter('/')

        if (BUILTIN_ONLY_MARKS.any { path.contains(it) }) return Mode.BUILTIN_ONLY
        if (NO_LOGO_MARKS.any { path.contains(it) }) return Mode.NONE
        return Mode.DEFAULT
    }
}
