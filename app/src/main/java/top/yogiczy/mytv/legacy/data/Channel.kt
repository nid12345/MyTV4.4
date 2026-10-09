package top.yogiczy.mytv.legacy.data

/** 频道。多线路存在 urlList 里，换源即换 urlList 下标。 */
data class Channel(
    val name: String,
    val urlList: List<String> = emptyList(),
    val logo: String? = null,
    val epgName: String? = null,
    val groupName: String = "",
) {
    val url: String get() = urlList.firstOrNull().orEmpty()
    val isPlayable: Boolean get() = urlList.isNotEmpty()
    val lineCount: Int get() = urlList.size
}

/** 分组。对应界面上「央视频道 18个频道」这样的一行。 */
data class ChannelGroup(
    val name: String,
    val channels: List<Channel> = emptyList(),
) {
    val size: Int get() = channels.size
}

/**
 * 频道分组列表
 *
 * ---------------------------------------------------------------------------
 * 索引 API 对齐原版（MyTV 1.7 tag `custom-v1.7`）：
 *
 *   原版文件                                          → 本文件对应
 *   ------------------------------------------------------------------------
 *   entities/channel/ChannelGroupList.kt
 *     `ChannelGroupList.channelGroupIdx(channel)`     → 同名扩展，逻辑一致
 *     `ChannelGroupList.channelIdx(channel)`          → 同名扩展，逻辑一致
 *     `ChannelGroupList.channelList`（扁平化）        → 同名扩展属性，逻辑一致
 *   entities/channel/ChannelList.kt（`List<Channel>` 包装类型）
 *     → **本工程不做该包装**，相关 API 一律用 `List<Channel>`：
 *        `ChannelList` 本质只是 `: List<Channel> by value` 的零逻辑包装，
 *        Kotlin 的 `kotlinx.serialization` 才是它存在的唯一理由（`@Serializable`），
 *        而本工程禁止引入该依赖。用 `List<Channel>` 可 100% 表达同一语义。
 *
 * 与 M2 之前版本的关系：**只新增 API，现有字段与构造一字未改**
 * （`groups` / `size` / `channelCount` / `flatChannels()` / `groupIndexOf()` / `getOrNull()`
 * 原样保留，避免影响已跑通的 M0/M1 代码）。
 *
 * 与原版的一处必要差异：原版 `ChannelGroupList : List<ChannelGroup> by value`，
 * 因此它的扩展函数里可以直接 `indexOfFirst {}`；本工程是普通 data class，
 * 扩展里显式写 `groups.indexOfFirst {}`。判定逻辑与返回值完全相同。
 * ---------------------------------------------------------------------------
 */
data class ChannelGroupList(val groups: List<ChannelGroup> = emptyList()) {
    val size: Int get() = groups.size
    val channelCount: Int get() = groups.sumOf { it.size }

    fun flatChannels(): List<Channel> = groups.flatMap { it.channels }

    /** 按分组名取序号，找不到返回 -1（界面焦点恢复依赖它）。 */
    fun groupIndexOf(name: String): Int = groups.indexOfFirst { it.name == name }

    fun getOrNull(i: Int): ChannelGroup? = groups.getOrNull(i)

    companion object {
        /**
         * 频道所在分组的序号，找不到返回 -1。
         * 对应原版 `ChannelGroupList.channelGroupIdx`。
         */
        fun ChannelGroupList.channelGroupIdx(channel: Channel): Int =
            groups.indexOfFirst { group -> group.channels.any { it == channel } }

        /**
         * 频道在**整表扁平化后**的序号，找不到返回 -1。
         * 对应原版 `ChannelGroupList.channelIdx`。
         *
         * 注意这是全表连续序号（跨分组累加），与 [groupIndexOf] 的「组序号」不同。
         */
        fun ChannelGroupList.channelIdx(channel: Channel): Int =
            flatChannels().indexOfFirst { it == channel }

        /**
         * 扁平化后的频道列表。对应原版 `ChannelGroupList.channelList`
         * （原版返回 `ChannelList` 包装，本工程直接给 `List<Channel>`，见文件头）。
         */
        val ChannelGroupList.channelList: List<Channel>
            get() = flatChannels()
    }
}
