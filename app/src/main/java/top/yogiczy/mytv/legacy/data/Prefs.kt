package top.yogiczy.mytv.legacy.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 直播源条目。 */
data class IptvSource(val name: String, val url: String)

/**
 * 收藏的频道快照
 *
 * ---------------------------------------------------------------------------
 * 来源：原版 `core/data/entities/channel/FavoriteChannelList.kt` 的 `FavoriteChannel`。
 * 去掉 `@Serializable`（改由本文件用 org.json 存取）。
 *
 * 为什么必须存快照而不是只存频道名（原版注释原文）：
 * 「不同订阅源的频道互不重叠，只记名字的话，一切换订阅源收藏列表就空了。
 *   这里把频道的线路地址一起存下来，于是收藏可以跨订阅源保留。」
 * ---------------------------------------------------------------------------
 */
data class FavoriteChannel(
    /** 频道名称 */
    val name: String = "",

    /** 节目单名称，用于查询节目单 */
    val epgName: String = "",

    /** 播放地址（多个为多线路） */
    val urlList: List<String> = emptyList(),

    /** 台标 */
    val logo: String? = null,
) {
    /**
     * 快照里没有可用线路时无法播放（一般是旧版本只存了频道名）。
     */
    fun toChannelOrNull(): Channel? =
        if (name.isBlank() || urlList.isEmpty()) null
        else Channel(name = name, epgName = epgName, urlList = urlList, logo = logo)

    companion object {
        fun of(channel: Channel): FavoriteChannel =
            FavoriteChannel(
                name = channel.name,
                epgName = channel.epgName.orEmpty(),
                urlList = channel.urlList,
                logo = channel.logo,
            )
    }
}

/**
 * 播放器显示比例
 *
 * 来源：原版 `tv/ui/screens/videoplayer/VideoPlayerState.kt` 的 `VideoPlayerDisplayMode`，
 * **枚举项、label 与 value 逐条一致**。
 *
 * 为什么放在 Prefs 里而不是等 M3：它是**被持久化的配置项**（`VIDEO_PLAYER_DISPLAY_MODE`
 * + `VIDEO_PLAYER_DISPLAY_MODE_CHANNEL_MAP`），Preference 的默认值与读写必须先存在。
 * M3 播放器直接复用本枚举，不再重复定义。
 */
enum class VideoPlayerDisplayMode(val label: String, val value: Int) {
    /** 原始 */
    ORIGINAL("原始", 0),

    /** 填充 */
    FILL("填充", 1),

    /** 裁剪 */
    CROP("裁剪", 2),

    /** 4:3 */
    FOUR_THREE("4:3", 3),

    /** 16:9 */
    SIXTEEN_NINE("16:9", 4),

    /** 2.35:1 */
    WIDE("2.35:1", 5),

    /** 3:4（竖屏素材） */
    THREE_FOUR("3:4", 6);

    companion object {
        fun fromValue(value: Int): VideoPlayerDisplayMode =
            entries.firstOrNull { it.value == value } ?: ORIGINAL
    }
}

/**
 * 时间显示模式。来源：原版 `Configs.UiTimeShowMode`，值一一对应。
 */
enum class UiTimeShowMode(val value: Int) {
    HIDDEN(0), ALWAYS(1), EVERY_HOUR(2), HALF_HOUR(3);

    companion object {
        fun fromValue(value: Int): UiTimeShowMode = entries.firstOrNull { it.value == value } ?: ALWAYS
    }
}

/**
 * 混合模式。来源：原版 `Configs.IptvHybridMode`，值一一对应。
 */
enum class IptvHybridMode(val value: Int) {
    DISABLE(0), IPTV_FIRST(1), HYBRID_FIRST(2);

    companion object {
        fun fromValue(value: Int): IptvHybridMode = entries.firstOrNull { it.value == value } ?: DISABLE
    }
}

/**
 * 轻量持久化
 *
 * ---------------------------------------------------------------------------
 * 来源：MyTV 1.7（源码 tag `custom-v1.7`）`tv/ui/utils/Configs.kt`
 *
 * 移植方式：**键集与默认值对齐原版 Configs，读写机制等价重写**：
 *   ① 原版走 MMKV/SP + kotlinx.serialization → 这里用 **SharedPreferences + org.json**
 *      （M2 硬约束：不引 kotlinx.serialization）。
 *   ② 原版是「全局单例 + SP 静态门面」→ 这里每个方法显式接收 `Context`
 *      （与 M0 起既有的 Prefs API 风格一致，也便于测试）。
 *   ③ 原版 `Configs.KEY` 枚举的**键名字符串逐条沿用**（`"IPTV_SOURCE_GROUP_HIDDEN_MAP"` 等），
 *      这样将来与手机端推送页 / 原版配置互通时键名不需要再翻译。
 *
 * 三个特色机制**完整保留**（M2 指令点名）：
 *   - 分组隐藏**按订阅源分别记忆** → [iptvSourceGroupHiddenMap] / [iptvChannelGroupHiddenList]
 *   - 收藏**带线路快照**（跨订阅源） → [iptvChannelFavoriteItems]
 *   - **配置迁移** → [migrateIfNeeded] + [CURRENT_CONFIG_VERSION]
 *
 * 兼容性：M0/M1 已在用的 API（[sources] / [saveSources] / [addSource] / [removeSource] /
 * [currentSourceUrl] / [setCurrentSource] / [favorites] / [toggleFavorite]）**签名与行为一字未改**。
 * ---------------------------------------------------------------------------
 */
object Prefs {

    private const val FILE = "legacy_prefs"

    // ---- M0 起既有的键（保持原样，避免老配置失效）----
    private const val K_SOURCES = "sources"
    private const val K_CURRENT = "current_source"
    private const val K_FAVORITES = "favorites"

    /** 当前配置版本，每次需要把新默认值刷到老配置上时 +1。与 `Configs.CURRENT_CONFIG_VERSION` 一致。 */
    const val CURRENT_CONFIG_VERSION = 5

    /** 与 `Constants.LOG_HISTORY_MAX_SIZE` 无关，仅用于内置源展示。 */
    fun defaultSource() = IptvSource("自建源", "https://tvlive.nide.qzz.io")

    /**
     * 内置默认直播源列表。
     *
     * 顺序固定：**内置源（自建源）在前，随后虎牙 / 斗鱼 / YY轮播 / 潮汕节目回放**
     * （判据 6 用户明确要求前三条；问题 7 用户要求把潮汕回放也内置进来）。
     */
    fun defaultSources(): List<IptvSource> = listOf(
        defaultSource(),
        IptvSource("虎牙", "https://sub.ottiptv.cc/huyayqk.m3u"),
        IptvSource("斗鱼", "https://sub.ottiptv.cc/douyuyqk.m3u"),
        IptvSource("YY轮播", "https://sub.ottiptv.cc/yylunbo.m3u"),
        // 问题 7（用户给定地址）：潮汕节目回放。此前它是"浏览默认源时附加显示"的
        // 伪分组（开关 `IPTV_CHAOSHAN_SOURCE_ENABLE`）；现在改为独立内置订阅源，
        // 设置里那个开关也按要求删掉了。
        IptvSource("潮汕节目回放", "https://gitee.com/nid123/chaoshan-tv/raw/master/data/chaoshan.txt"),
    )

    /**
     * 内置节目单来源。逐条对齐 `Constants.EPG_SOURCE_LIST`：
     * 主源用**实时生成**的 `e.xml`（`e.xml.gz` 是预生成文件，凌晨会断档）。
     */
    val EPG_SOURCE_LIST: List<EpgSource> = listOf(
        EpgSource(name = "默认节目单 老张的EPG", url = "http://epg.51zmt.top:8000/e.xml"),
        EpgSource(name = "备用节目单 112114", url = "https://epg.112114.xyz/pp.xml"),
        EpgSource(name = "备用节目单 Fanmingming", url = "https://live.fanmingming.com/e.xml"),
    )

    /** 历史默认节目单地址，对齐 `Constants.EPG_SOURCE_LEGACY_URL`；读到时迁移到新默认源。 */
    private const val EPG_SOURCE_LEGACY_URL = "http://epg.51zmt.top:8000/e.xml.gz"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun putString(ctx: Context, key: String, value: String) {
        sp(ctx).edit().putString(key, value).commit()
    }

    private fun getString(ctx: Context, key: String, def: String = ""): String =
        sp(ctx).getString(key, def) ?: def

    // ==================== 直播源（M0 既有 API，签名未改） ====================

    fun sources(ctx: Context): List<IptvSource> {
        val raw = sp(ctx).getString(K_SOURCES, null) ?: return defaultSources()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isBlank()) null
                else IptvSource(o.optString("name").ifBlank { url }, url)
            }
        }.getOrNull().orEmpty().ifEmpty { defaultSources() }
    }

    fun saveSources(ctx: Context, list: List<IptvSource>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url)) }
        putString(ctx, K_SOURCES, arr.toString())
    }

    fun addSource(ctx: Context, src: IptvSource) {
        val list = sources(ctx).filterNot { it.url == src.url }
        saveSources(ctx, list + src)
    }

    fun removeSource(ctx: Context, url: String) {
        val left = sources(ctx).filterNot { it.url == url }
        saveSources(ctx, left.ifEmpty { defaultSources() })
        if (currentSourceUrl(ctx) == url) setCurrentSource(ctx, "")
    }

    fun currentSourceUrl(ctx: Context): String =
        sp(ctx).getString(K_CURRENT, null)?.takeIf { it.isNotBlank() }
            ?: sources(ctx).firstOrNull()?.url.orEmpty()

    fun setCurrentSource(ctx: Context, url: String) {
        putString(ctx, K_CURRENT, url)
    }

    // ==================== 直播源（M2 新增，对齐 Configs） ====================

    /** 上一次频道序号（`IPTV_LAST_CHANNEL_IDX`）。 */
    fun iptvLastChannelIdx(ctx: Context): Int =
        sp(ctx).getInt("IPTV_LAST_CHANNEL_IDX", 0)

    fun setIptvLastChannelIdx(ctx: Context, value: Int) {
        sp(ctx).edit().putInt("IPTV_LAST_CHANNEL_IDX", value).commit()
    }

    /** 启动时是否自动播放上次退出的频道（`IPTV_LAST_CHANNEL_REMEMBER_ENABLE`，默认 true）。 */
    fun iptvLastChannelRememberEnable(ctx: Context): Boolean =
        sp(ctx).getBoolean("IPTV_LAST_CHANNEL_REMEMBER_ENABLE", true)

    fun setIptvLastChannelRememberEnable(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("IPTV_LAST_CHANNEL_REMEMBER_ENABLE", value).commit()
    }

    /** 换台反转（`IPTV_CHANNEL_CHANGE_FLIP`，默认 false）。 */
    fun iptvChannelChangeFlip(ctx: Context): Boolean =
        sp(ctx).getBoolean("IPTV_CHANNEL_CHANGE_FLIP", false)

    fun setIptvChannelChangeFlip(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("IPTV_CHANNEL_CHANGE_FLIP", value).commit()
    }

    /** 是否启用数字选台（`IPTV_CHANNEL_NO_SELECT_ENABLE`，默认 true）。 */
    fun iptvChannelNoSelectEnable(ctx: Context): Boolean =
        sp(ctx).getBoolean("IPTV_CHANNEL_NO_SELECT_ENABLE", true)

    fun setIptvChannelNoSelectEnable(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("IPTV_CHANNEL_NO_SELECT_ENABLE", value).commit()
    }

    /** 是否在分组栏顶部显示「换源」入口（`IPTV_SOURCE_QUICK_SWITCH_ENABLE`，默认 true）。 */
    fun iptvSourceQuickSwitchEnable(ctx: Context): Boolean =
        sp(ctx).getBoolean("IPTV_SOURCE_QUICK_SWITCH_ENABLE", true)

    fun setIptvSourceQuickSwitchEnable(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("IPTV_SOURCE_QUICK_SWITCH_ENABLE", value).commit()
    }

    /** 是否启用线路测速排序（`IPTV_SOURCE_LINE_SPEED_SORT_ENABLE`，默认 true）。 */
    fun iptvSourceLineSpeedSortEnable(ctx: Context): Boolean =
        sp(ctx).getBoolean("IPTV_SOURCE_LINE_SPEED_SORT_ENABLE", true)

    fun setIptvSourceLineSpeedSortEnable(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("IPTV_SOURCE_LINE_SPEED_SORT_ENABLE", value).commit()
    }

    /** 混合模式（`IPTV_HYBRID_MODE`，默认 DISABLE）。 */
    fun iptvHybridMode(ctx: Context): IptvHybridMode =
        IptvHybridMode.fromValue(sp(ctx).getInt("IPTV_HYBRID_MODE", IptvHybridMode.DISABLE.value))

    fun setIptvHybridMode(ctx: Context, value: IptvHybridMode) {
        sp(ctx).edit().putInt("IPTV_HYBRID_MODE", value.value).commit()
    }

    // ==================== 收藏（M0 既有 API + M2 线路快照） ====================

    /** 只暴露频道名（M0 既有 API，供「按名字判断是否已收藏」用）。 */
    fun favorites(ctx: Context): List<String> =
        iptvChannelFavoriteItems(ctx).map { it.name }

    /** 返回切换后的状态：true = 已收藏。 */
    fun toggleFavorite(ctx: Context, channelName: String): Boolean {
        val items = iptvChannelFavoriteItems(ctx).toMutableList()
        val added = if (items.any { it.name == channelName }) {
            items.removeAll { it.name == channelName }; false
        } else {
            items.add(FavoriteChannel(name = channelName)); true
        }
        setIptvChannelFavoriteItems(ctx, items)
        return added
    }

    /**
     * 返回切换后的状态：true = 已收藏。
     *
     * 问题 5（用户要求：收藏要能跨订阅源保留）：收藏时**连同线路地址一起快照**，
     * 这样换到别的订阅源后，收藏列表里这条仍然存在、并且还能直接播。
     * （只用 [toggleFavorite] 只存名字的话，换源后如果新源里没有这个频道，
     *  收藏列表就查不到它 —— 真机现象。）
     */
    fun toggleFavorite(ctx: Context, channel: Channel): Boolean {
        val items = iptvChannelFavoriteItems(ctx).toMutableList()
        val added = if (items.any { it.name == channel.name }) {
            items.removeAll { it.name == channel.name }; false
        } else {
            items.add(FavoriteChannel.of(channel)); true
        }
        setIptvChannelFavoriteItems(ctx, items)
        return added
    }

    /**
     * 直播源频道收藏列表（含线路快照）
     *
     * 首次读取时把旧版本「只存频道名」的收藏迁移过来（这种条目要等再次收藏到
     * 对应频道的订阅源时才会补上线路地址）。逻辑对齐 `Configs.iptvChannelFavoriteItems`。
     */
    fun iptvChannelFavoriteItems(ctx: Context): List<FavoriteChannel> {
        val raw = getString(ctx, "IPTV_CHANNEL_FAVORITE_ITEMS")
        if (raw.isNotBlank()) {
            runCatching { return decodeFavoriteItems(raw) }.onFailure { /* 落回旧字段 */ }
        }

        // 旧字段：StringSet 里只有频道名
        val legacy = sp(ctx).getStringSet(K_FAVORITES, emptySet()).orEmpty()
        return legacy.map { FavoriteChannel(name = it) }
    }

    fun setIptvChannelFavoriteItems(ctx: Context, items: List<FavoriteChannel>) {
        putString(ctx, "IPTV_CHANNEL_FAVORITE_ITEMS", encodeFavoriteItems(items))
        // 旧字段同步一份，保证旧版本回退读取、以及手机端页面推送时表现一致
        sp(ctx).edit().putStringSet(K_FAVORITES, items.map { it.name }.toSet()).commit()
    }

    /**
     * 把收藏里的频道快照对齐到当前订阅源的频道
     *
     * 收藏来自多个订阅源，只有在该源里出现过，才能补上/刷新它的线路地址。
     * 对齐 `Configs.syncFavoriteChannels`。
     */
    fun syncFavoriteChannels(ctx: Context, channelList: List<Channel>) {
        if (channelList.isEmpty()) return

        val items = iptvChannelFavoriteItems(ctx)
        var changed = false

        val synced = items.map { favorite ->
            val channel = channelList.firstOrNull { it.name == favorite.name } ?: return@map favorite
            val fresh = FavoriteChannel.of(channel)
            if (fresh != favorite) changed = true
            fresh
        }

        if (changed) setIptvChannelFavoriteItems(ctx, synced)
    }

    /** 是否启用直播源频道收藏（`IPTV_CHANNEL_FAVORITE_ENABLE`，默认 true）。 */
    fun iptvChannelFavoriteEnable(ctx: Context): Boolean =
        sp(ctx).getBoolean("IPTV_CHANNEL_FAVORITE_ENABLE", true)

    fun setIptvChannelFavoriteEnable(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("IPTV_CHANNEL_FAVORITE_ENABLE", value).commit()
    }

    /** 显示收藏列表（`IPTV_CHANNEL_FAVORITE_LIST_VISIBLE`，默认 false）。 */
    fun iptvChannelFavoriteListVisible(ctx: Context): Boolean =
        sp(ctx).getBoolean("IPTV_CHANNEL_FAVORITE_LIST_VISIBLE", false)

    fun setIptvChannelFavoriteListVisible(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("IPTV_CHANNEL_FAVORITE_LIST_VISIBLE", value).commit()
    }

    /** 收藏换台到边界时跳出（`IPTV_CHANNEL_FAVORITE_CHANGE_BOUNDARY_JUMP_OUT`，默认 true）。 */
    fun iptvChannelFavoriteChangeBoundaryJumpOut(ctx: Context): Boolean =
        sp(ctx).getBoolean("IPTV_CHANNEL_FAVORITE_CHANGE_BOUNDARY_JUMP_OUT", true)

    fun setIptvChannelFavoriteChangeBoundaryJumpOut(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("IPTV_CHANNEL_FAVORITE_CHANGE_BOUNDARY_JUMP_OUT", value).commit()
    }

    // ==================== 分组隐藏：按订阅源分别记忆 ★特色机制 ====================

    /**
     * 各订阅源记住的分组隐藏列表（订阅源地址 → 隐藏的分组名）
     *
     * 按源分别记忆：切换订阅源、重启应用后，只要分组名没变就保持原设定。
     * 对齐 `Configs.iptvSourceGroupHiddenMap`。
     */
    fun iptvSourceGroupHiddenMap(ctx: Context): Map<String, List<String>> =
        decodeStringListMap(getString(ctx, "IPTV_SOURCE_GROUP_HIDDEN_MAP", "{}"))

    fun setIptvSourceGroupHiddenMap(ctx: Context, value: Map<String, List<String>>) {
        putString(ctx, "IPTV_SOURCE_GROUP_HIDDEN_MAP", encodeStringListMap(value))
    }

    /** 各订阅源出现过的分组名缓存（订阅源地址 → 分组名列表）。 */
    fun iptvSourceGroupNamesMap(ctx: Context): Map<String, List<String>> =
        decodeStringListMap(getString(ctx, "IPTV_SOURCE_GROUP_NAMES_MAP", "{}"))

    fun setIptvSourceGroupNamesMap(ctx: Context, value: Map<String, List<String>>) {
        putString(ctx, "IPTV_SOURCE_GROUP_NAMES_MAP", encodeStringListMap(value))
    }

    /**
     * 当前订阅源视图下的分组隐藏列表
     *
     * 读写都落在 [iptvSourceGroupHiddenMap] 里**当前订阅源**那一项上，
     * 切换订阅源后自然切换到该源自己的记忆，不需要额外清理。
     * 对齐 `Configs.iptvChannelGroupHiddenList`。
     */
    fun iptvChannelGroupHiddenList(ctx: Context): Set<String> =
        iptvSourceGroupHiddenMap(ctx)[currentSourceUrl(ctx)]?.toSet() ?: emptySet()

    fun setIptvChannelGroupHiddenList(ctx: Context, value: Set<String>) {
        setIptvSourceGroupHiddenMap(
            ctx,
            iptvSourceGroupHiddenMap(ctx) + (currentSourceUrl(ctx) to value.toList())
        )
    }

    /** 旧字段：全局一份的分组隐藏列表（仅迁移用）。 */
    private fun legacyGroupHiddenList(ctx: Context): Set<String> =
        sp(ctx).getStringSet("IPTV_CHANNEL_GROUP_HIDDEN_LIST", emptySet()).orEmpty()

    // ==================== 节目单 ====================

    /** 启用节目单（`EPG_ENABLE`，默认 true）。 */
    fun epgEnable(ctx: Context): Boolean = sp(ctx).getBoolean("EPG_ENABLE", true)

    fun setEpgEnable(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("EPG_ENABLE", value).commit()
    }

    /**
     * 当前节目单来源。对齐 `Configs.epgSourceCurrent` 的迁移逻辑：
     * 老默认源指向预生成的 `e.xml.gz`，内容经常停在生成那一刻，凌晨会断档 → 换成实时 `e.xml`。
     */
    fun epgSourceCurrent(ctx: Context): EpgSource {
        val raw = getString(ctx, "EPG_SOURCE_CURRENT")
        val source = if (raw.isBlank()) EPG_SOURCE_LIST.first()
        else runCatching { decodeEpgSource(raw) }.getOrElse { EPG_SOURCE_LIST.first() }

        return if (source.url == EPG_SOURCE_LEGACY_URL) EPG_SOURCE_LIST.first() else source
    }

    fun setEpgSourceCurrent(ctx: Context, value: EpgSource) {
        putString(ctx, "EPG_SOURCE_CURRENT", encodeEpgSource(value))
    }

    /** 用户自定义的节目单来源列表（内置源不存这里）。 */
    fun epgSourceList(ctx: Context): List<EpgSource> {
        val raw = getString(ctx, "EPG_SOURCE_LIST")
        if (raw.isBlank()) return emptyList()
        return runCatching { decodeEpgSourceList(raw) }.getOrElse { emptyList() }
    }

    fun setEpgSourceList(ctx: Context, value: List<EpgSource>) {
        putString(ctx, "EPG_SOURCE_LIST", encodeEpgSourceList(value))
    }

    /** 节目单刷新时间阈值（小时），对齐 `Constants.EPG_REFRESH_TIME_THRESHOLD = 2`。 */
    fun epgRefreshTimeThreshold(ctx: Context): Int =
        sp(ctx).getInt("EPG_REFRESH_TIME_THRESHOLD", 2)

    fun setEpgRefreshTimeThreshold(ctx: Context, value: Int) {
        sp(ctx).edit().putInt("EPG_REFRESH_TIME_THRESHOLD", value).commit()
    }

    // ==================== 界面 ====================

    /** 显示台标（`UI_SHOW_CHANNEL_LOGO`，默认 true）。 */
    fun uiShowChannelLogo(ctx: Context): Boolean = sp(ctx).getBoolean("UI_SHOW_CHANNEL_LOGO", true)

    fun setUiShowChannelLogo(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("UI_SHOW_CHANNEL_LOGO", value).commit()
    }

    /** 使用经典三段式选台界面（`UI_USE_CLASSIC_PANEL_SCREEN`，默认 true）。 */
    fun uiUseClassicPanelScreen(ctx: Context): Boolean =
        sp(ctx).getBoolean("UI_USE_CLASSIC_PANEL_SCREEN", true)

    fun setUiUseClassicPanelScreen(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("UI_USE_CLASSIC_PANEL_SCREEN", value).commit()
    }

    /** 显示节目进度（`UI_SHOW_EPG_PROGRAMME_PROGRESS`，默认 true）。 */
    fun uiShowEpgProgrammeProgress(ctx: Context): Boolean =
        sp(ctx).getBoolean("UI_SHOW_EPG_PROGRAMME_PROGRESS", true)

    fun setUiShowEpgProgrammeProgress(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("UI_SHOW_EPG_PROGRAMME_PROGRESS", value).commit()
    }

    /** 界面密度缩放比例（`UI_DENSITY_SCALE_RATIO`，默认 0f＝不缩放）。 */
    fun uiDensityScaleRatio(ctx: Context): Float = sp(ctx).getFloat("UI_DENSITY_SCALE_RATIO", 0f)

    fun setUiDensityScaleRatio(ctx: Context, value: Float) {
        sp(ctx).edit().putFloat("UI_DENSITY_SCALE_RATIO", value).commit()
    }

    /** 界面字体缩放比例（`UI_FONT_SCALE_RATIO`，默认 1f）。 */
    fun uiFontScaleRatio(ctx: Context): Float = sp(ctx).getFloat("UI_FONT_SCALE_RATIO", 1f)

    fun setUiFontScaleRatio(ctx: Context, value: Float) {
        sp(ctx).edit().putFloat("UI_FONT_SCALE_RATIO", value).commit()
    }

    /** 时间显示模式（`UI_TIME_SHOW_MODE`，默认 HIDDEN）。 */
    fun uiTimeShowMode(ctx: Context): UiTimeShowMode =
        UiTimeShowMode.fromValue(sp(ctx).getInt("UI_TIME_SHOW_MODE", UiTimeShowMode.HIDDEN.value))

    fun setUiTimeShowMode(ctx: Context, value: UiTimeShowMode) {
        sp(ctx).edit().putInt("UI_TIME_SHOW_MODE", value.value).commit()
    }

    /** 焦点优化（`UI_FOCUS_OPTIMIZE`，默认 true）。 */
    fun uiFocusOptimize(ctx: Context): Boolean = sp(ctx).getBoolean("UI_FOCUS_OPTIMIZE", true)

    fun setUiFocusOptimize(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("UI_FOCUS_OPTIMIZE", value).commit()
    }

    /**
     * 自动关闭界面延时（`UI_SCREEN_AUTO_CLOSE_DELAY`）。
     *
     * ⚠️ 判据 1：默认值由原版 15 秒改为 **5 秒**（用户明确要求）。
     * `0` 仍表示"不自动关闭"（与 [ScreenAutoClose] 的约定一致）。
     */
    fun uiScreenAutoCloseDelay(ctx: Context): Long =
        sp(ctx).getLong("UI_SCREEN_AUTO_CLOSE_DELAY", 1_000L * 5)

    fun setUiScreenAutoCloseDelay(ctx: Context, value: Long) {
        sp(ctx).edit().putLong("UI_SCREEN_AUTO_CLOSE_DELAY", value).commit()
    }

    // ==================== 播放器 ====================

    /** 播放器自定义 UA，对齐 `Configs.videoPlayerUserAgent`（空 → 用内置 `ExoPlayer`）。 */
    fun videoPlayerUserAgent(ctx: Context): String =
        getString(ctx, "VIDEO_PLAYER_USER_AGENT").ifBlank { top.yogiczy.mytv.legacy.net.Http.DEFAULT_USER_AGENT }

    fun setVideoPlayerUserAgent(ctx: Context, value: String) {
        putString(ctx, "VIDEO_PLAYER_USER_AGENT", value)
    }

    /** 加载超时，对齐 `Configs.videoPlayerLoadTimeout`（默认 15s）。 */
    fun videoPlayerLoadTimeout(ctx: Context): Long =
        sp(ctx).getLong("VIDEO_PLAYER_LOAD_TIMEOUT", 1_000L * 15)

    fun setVideoPlayerLoadTimeout(ctx: Context, value: Long) {
        sp(ctx).edit().putLong("VIDEO_PLAYER_LOAD_TIMEOUT", value).commit()
    }

    /** 播放器显示模式（`VIDEO_PLAYER_DISPLAY_MODE`，默认 16:9）。 */
    fun videoPlayerDisplayMode(ctx: Context): VideoPlayerDisplayMode =
        VideoPlayerDisplayMode.fromValue(
            sp(ctx).getInt("VIDEO_PLAYER_DISPLAY_MODE", VideoPlayerDisplayMode.SIXTEEN_NINE.value)
        )

    fun setVideoPlayerDisplayMode(ctx: Context, value: VideoPlayerDisplayMode) {
        sp(ctx).edit().putInt("VIDEO_PLAYER_DISPLAY_MODE", value.value).commit()
    }

    /** 各频道单独记住的显示比例（频道名 → 模式值）。 */
    fun videoPlayerDisplayModeChannelMap(ctx: Context): Map<String, Int> =
        decodeIntMap(getString(ctx, "VIDEO_PLAYER_DISPLAY_MODE_CHANNEL_MAP", "{}"))

    fun setVideoPlayerDisplayModeChannelMap(ctx: Context, value: Map<String, Int>) {
        putString(ctx, "VIDEO_PLAYER_DISPLAY_MODE_CHANNEL_MAP", encodeIntMap(value))
    }

    /**
     * 当前频道实际生效的显示比例
     *
     * 全局默认（默认 16:9）铺底，只有被单独设过的频道才用自己记住的比例。
     * 对齐 `Configs.resolveVideoPlayerDisplayMode`。
     */
    fun resolveVideoPlayerDisplayMode(ctx: Context, channel: Channel): VideoPlayerDisplayMode {
        videoPlayerDisplayModeChannelMap(ctx)[channel.name]?.let {
            return VideoPlayerDisplayMode.fromValue(it)
        }
        return videoPlayerDisplayMode(ctx)
    }

    /** 记住某个频道单独的显示比例（只影响这个频道）。 */
    fun rememberVideoPlayerDisplayMode(ctx: Context, mode: VideoPlayerDisplayMode, channel: Channel) {
        setVideoPlayerDisplayModeChannelMap(
            ctx,
            videoPlayerDisplayModeChannelMap(ctx) + (channel.name to mode.value)
        )
    }

    /** 忘掉某个频道的单独设置，回到全局默认比例。 */
    fun forgetVideoPlayerDisplayMode(ctx: Context, channel: Channel) {
        val map = videoPlayerDisplayModeChannelMap(ctx)
        if (!map.containsKey(channel.name)) return
        setVideoPlayerDisplayModeChannelMap(ctx, map - channel.name)
    }

    // ==================== 更新 ====================

    /** 升级提醒（`UPDATE_REMIND_ENABLE`，默认 true）。 */
    fun updateRemindEnable(ctx: Context): Boolean = sp(ctx).getBoolean("UPDATE_REMIND_ENABLE", true)

    fun setUpdateRemindEnable(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("UPDATE_REMIND_ENABLE", value).commit()
    }

    /** 更新通道（`UPDATE_CHANNEL`，默认 "stable"）。 */
    fun updateChannel(ctx: Context): String = getString(ctx, "UPDATE_CHANNEL", "stable")

    fun setUpdateChannel(ctx: Context, value: String) {
        putString(ctx, "UPDATE_CHANNEL", value)
    }

    // ==================== 配置迁移 ★特色机制 ====================

    /**
     * 升级后的一次性配置迁移
     *
     * 逻辑逐条对齐 `Configs.migrateIfNeeded`：
     *  - v2（1.4）：分组隐藏从「全局一份」改成「按订阅源记忆」。
     *    老配置里的全局隐藏列表迁移到**当时的订阅源**名下，避免设定丢失。
     *  - v3（1.5）：潮汕节目回放改为默认关闭（见 [iptvChaoshanSourceEnable] 的 getter 默认值）。
     *
     * 注意：SharedPreferences 分不清「没设置」和「设置为默认值」，所以新默认值一般直接改
     * getter 的默认值即可——没设置过的用户会自动跟随，显式设置过的保持不变。
     */
    fun migrateIfNeeded(ctx: Context) {
        val savedVersion = sp(ctx).getInt("APP_CONFIG_VERSION", 0)
        if (savedVersion >= CURRENT_CONFIG_VERSION) return

        if (savedVersion < 2) {
            val legacy = legacyGroupHiddenList(ctx)
            if (legacy.isNotEmpty()) {
                val sourceUrl = currentSourceUrl(ctx).ifBlank { defaultSource().url }
                setIptvSourceGroupHiddenMap(
                    ctx,
                    iptvSourceGroupHiddenMap(ctx) + (sourceUrl to legacy.toList())
                )
            }
        }

        // v1（1.3）：三段式选台与 16:9 显示改为默认值（getter 默认值已更新，无需写库）

        if (savedVersion < 3) {
            setIptvChaoshanSourceEnable(ctx, false)
        }

        // v4（判据 6）：内置默认源由 1 条扩到 4 条（自建源 / 虎牙 / 斗鱼 / YY轮播）。
        // 已经保存过源列表的老配置不会自动拿到新源 —— 这里把**缺失的内置源按原顺序补到末尾**。
        // 只按 url 去重：用户自建源与已有顺序都不动；被删掉的内置源也不恢复（v4 只跑一次）。
        if (savedVersion < 4) {
            val cur = sources(ctx)
            val missing = defaultSources().filter { d -> cur.none { it.url == d.url } }
            if (missing.isNotEmpty()) saveSources(ctx, cur + missing)
        }

        // v5（问题 7）：新增内置源「潮汕节目回放」。老配置按 url 去重补到末尾。
        if (savedVersion < 5) {
            val cur = sources(ctx)
            val missing = defaultSources().filter { d -> cur.none { it.url == d.url } }
            if (missing.isNotEmpty()) saveSources(ctx, cur + missing)
        }

        sp(ctx).edit().putInt("APP_CONFIG_VERSION", CURRENT_CONFIG_VERSION).commit()
    }

    /** 是否显示内置的「潮汕节目回放」分组（`IPTV_CHAOSHAN_SOURCE_ENABLE`，默认 false）。 */
    fun iptvChaoshanSourceEnable(ctx: Context): Boolean =
        sp(ctx).getBoolean("IPTV_CHAOSHAN_SOURCE_ENABLE", false)

    fun setIptvChaoshanSourceEnable(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("IPTV_CHAOSHAN_SOURCE_ENABLE", value).commit()
    }

    // ==================== M9：设置页新增项 ====================
    // 以下键名对齐原版 `Constants`，默认值按 MyTV 1.7 的 `Configs` 取值。

    /** 开机自启（`APP_BOOT_LAUNCH_ENABLE`，默认 false）。 */
    fun appBootLaunchEnable(ctx: Context): Boolean =
        sp(ctx).getBoolean("APP_BOOT_LAUNCH_ENABLE", false)

    fun setAppBootLaunchEnable(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("APP_BOOT_LAUNCH_ENABLE", value).commit()
    }

    /** 直播源缓存时间（毫秒，`IPTV_SOURCE_CACHE_TIME`，默认 0 = 不缓存）。 */
    fun iptvSourceCacheTime(ctx: Context): Long =
        sp(ctx).getLong("IPTV_SOURCE_CACHE_TIME", 0L)

    fun setIptvSourceCacheTime(ctx: Context, value: Long) {
        sp(ctx).edit().putLong("IPTV_SOURCE_CACHE_TIME", value).commit()
    }

    /** 自动连播（`IPTV_CHANNEL_AUTO_PLAY_NEXT`，默认 true）。 */
    fun iptvAutoPlayNext(ctx: Context): Boolean =
        sp(ctx).getBoolean("IPTV_CHANNEL_AUTO_PLAY_NEXT", true)

    fun setIptvAutoPlayNext(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("IPTV_CHANNEL_AUTO_PLAY_NEXT", value).commit()
    }

    /** 常驻底部节目进度（`UI_SHOW_EPG_PROGRAMME_PERMANENT_PROGRESS`，默认 false）。 */
    fun uiShowEpgProgrammePermanentProgress(ctx: Context): Boolean =
        sp(ctx).getBoolean("UI_SHOW_EPG_PROGRAMME_PERMANENT_PROGRESS", false)

    fun setUiShowEpgProgrammePermanentProgress(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("UI_SHOW_EPG_PROGRAMME_PERMANENT_PROGRESS", value).commit()
    }

    /** 更新强提醒（`UPDATE_FORCE_REMIND_ENABLE`，默认 false）。 */
    fun updateForceRemindEnable(ctx: Context): Boolean =
        sp(ctx).getBoolean("UPDATE_FORCE_REMIND_ENABLE", false)

    fun setUpdateForceRemindEnable(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("UPDATE_FORCE_REMIND_ENABLE", value).commit()
    }

    /** 下载线路（`UPDATE_DOWNLOAD_LINE`，默认 "auto"）。 */
    fun updateDownloadLine(ctx: Context): String =
        getString(ctx, "UPDATE_DOWNLOAD_LINE").ifBlank { "auto" }

    fun setUpdateDownloadLine(ctx: Context, value: String) {
        putString(ctx, "UPDATE_DOWNLOAD_LINE", value)
    }

    /** 渲染方式：0=SurfaceView，1=TextureView（`VIDEO_PLAYER_RENDER_MODE`，默认 0）。 */
    fun videoPlayerRenderMode(ctx: Context): Int =
        sp(ctx).getInt("VIDEO_PLAYER_RENDER_MODE", 0)

    fun setVideoPlayerRenderMode(ctx: Context, value: Int) {
        sp(ctx).edit().putInt("VIDEO_PLAYER_RENDER_MODE", value).commit()
    }

    /** 强制音频软解（`VIDEO_PLAYER_FORCE_AUDIO_SOFTWARE_DECODE`，默认 false）。 */
    fun videoPlayerForceAudioSoftwareDecode(ctx: Context): Boolean =
        sp(ctx).getBoolean("VIDEO_PLAYER_FORCE_AUDIO_SOFTWARE_DECODE", false)

    fun setVideoPlayerForceAudioSoftwareDecode(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("VIDEO_PLAYER_FORCE_AUDIO_SOFTWARE_DECODE", value).commit()
    }

    /** 停止上一媒体项（`VIDEO_PLAYER_STOP_PREVIOUS_MEDIA_ITEM`，默认 true）。 */
    fun videoPlayerStopPreviousMediaItem(ctx: Context): Boolean =
        sp(ctx).getBoolean("VIDEO_PLAYER_STOP_PREVIOUS_MEDIA_ITEM", true)

    fun setVideoPlayerStopPreviousMediaItem(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("VIDEO_PLAYER_STOP_PREVIOUS_MEDIA_ITEM", value).commit()
    }

    /** 跳过多帧渲染（`VIDEO_PLAYER_SKIP_MULTI_FRAME`，默认 true）。 */
    fun videoPlayerSkipMultiFrame(ctx: Context): Boolean =
        sp(ctx).getBoolean("VIDEO_PLAYER_SKIP_MULTI_FRAME", true)

    fun setVideoPlayerSkipMultiFrame(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("VIDEO_PLAYER_SKIP_MULTI_FRAME", value).commit()
    }

    /** HTTP 请求重试次数（`HTTP_RETRY_COUNT`，默认 3）。 */
    fun httpRetryCount(ctx: Context): Int = sp(ctx).getInt("HTTP_RETRY_COUNT", 3)

    fun setHttpRetryCount(ctx: Context, value: Int) {
        sp(ctx).edit().putInt("HTTP_RETRY_COUNT", value).commit()
    }

    /** HTTP 请求重试间隔（毫秒，`HTTP_RETRY_INTERVAL`，默认 1000）。 */
    fun httpRetryInterval(ctx: Context): Int = sp(ctx).getInt("HTTP_RETRY_INTERVAL", 1000)

    fun setHttpRetryInterval(ctx: Context, value: Int) {
        sp(ctx).edit().putInt("HTTP_RETRY_INTERVAL", value).commit()
    }

    /** 显示 FPS（`DEBUG_SHOW_FPS`，默认 false）。 */
    fun debugShowFps(ctx: Context): Boolean = sp(ctx).getBoolean("DEBUG_SHOW_FPS", false)

    fun setDebugShowFps(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("DEBUG_SHOW_FPS", value).commit()
    }

    /** 显示播放器信息（`DEBUG_SHOW_PLAYER_INFO`，默认 false）。 */
    fun debugShowPlayerInfo(ctx: Context): Boolean =
        sp(ctx).getBoolean("DEBUG_SHOW_PLAYER_INFO", false)

    fun setDebugShowPlayerInfo(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("DEBUG_SHOW_PLAYER_INFO", value).commit()
    }

    /** 显示布局网格（`DEBUG_SHOW_LAYOUT_GRID`，默认 false）。 */
    fun debugShowLayoutGrid(ctx: Context): Boolean =
        sp(ctx).getBoolean("DEBUG_SHOW_LAYOUT_GRID", false)

    fun setDebugShowLayoutGrid(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("DEBUG_SHOW_LAYOUT_GRID", value).commit()
    }

    /** 预约列表（频道名，`EPG_RESERVE_LIST`，默认空）。 */
    fun epgReserveList(ctx: Context): List<String> {
        val raw = getString(ctx, "EPG_RESERVE_LIST")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun setEpgReserveList(ctx: Context, value: List<String>) {
        val arr = JSONArray()
        value.forEach { arr.put(it) }
        putString(ctx, "EPG_RESERVE_LIST", arr.toString())
    }

    /** 加入/移除预约，返回加入后是否在列表里。 */
    fun toggleReserve(ctx: Context, channelName: String): Boolean {
        val cur = epgReserveList(ctx).toMutableList()
        val had = cur.remove(channelName)
        if (!had) cur.add(channelName)
        setEpgReserveList(ctx, cur)
        return !had
    }

    /** 恢复初始化：清掉全部配置（换源面板等保留默认源）。 */
    fun resetAll(ctx: Context) {
        sp(ctx).edit().clear().apply()
        sp(ctx).edit().putInt("APP_CONFIG_VERSION", CURRENT_CONFIG_VERSION).apply()
    }

    // ==================== org.json 编解码小工具 ====================

    private fun encodeFavoriteItems(items: List<FavoriteChannel>): String {
        val arr = JSONArray()
        items.forEach { f ->
            arr.put(
                JSONObject()
                    .put("name", f.name)
                    .put("epgName", f.epgName)
                    .put("urls", JSONArray(f.urlList))
                    .put("logo", f.logo ?: JSONObject.NULL)
            )
        }
        return arr.toString()
    }

    private fun decodeFavoriteItems(raw: String): List<FavoriteChannel> {
        val arr = JSONArray(raw)
        val out = ArrayList<FavoriteChannel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val urls = o.optJSONArray("urls") ?: JSONArray()
            out += FavoriteChannel(
                name = o.optString("name"),
                epgName = o.optString("epgName"),
                urlList = (0 until urls.length()).map { urls.optString(it) },
                logo = o.optString("logo").takeIf { it.isNotBlank() && it != "null" },
            )
        }
        return out
    }

    private fun encodeEpgSource(s: EpgSource): String =
        JSONObject().put("name", s.name).put("url", s.url).toString()

    private fun decodeEpgSource(raw: String): EpgSource {
        val o = JSONObject(raw)
        return EpgSource(o.optString("name"), o.optString("url"))
    }

    private fun encodeEpgSourceList(list: List<EpgSource>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url)) }
        return arr.toString()
    }

    private fun decodeEpgSourceList(raw: String): List<EpgSource> {
        val arr = JSONArray(raw)
        val out = ArrayList<EpgSource>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += EpgSource(o.optString("name"), o.optString("url"))
        }
        return out
    }

    private fun encodeStringListMap(map: Map<String, List<String>>): String {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, JSONArray(v)) }
        return o.toString()
    }

    private fun decodeStringListMap(raw: String): Map<String, List<String>> =
        runCatching {
            val o = JSONObject(raw)
            val out = LinkedHashMap<String, List<String>>()
            o.keys().forEach { k ->
                val arr = o.optJSONArray(k) ?: return@forEach
                out[k] = (0 until arr.length()).map { arr.optString(it) }
            }
            out
        }.getOrElse { emptyMap() }

    private fun encodeIntMap(map: Map<String, Int>): String {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }

    private fun decodeIntMap(raw: String): Map<String, Int> =
        runCatching {
            val o = JSONObject(raw)
            val out = LinkedHashMap<String, Int>()
            o.keys().forEach { k -> out[k] = o.optInt(k) }
            out
        }.getOrElse { emptyMap() }
}
