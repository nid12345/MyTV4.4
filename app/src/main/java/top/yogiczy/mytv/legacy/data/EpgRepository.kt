package top.yogiczy.mytv.legacy.data

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import top.yogiczy.mytv.legacy.core.UiLog
import top.yogiczy.mytv.legacy.net.Http
import java.io.File
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import org.xmlpull.v1.XmlPullParser

/**
 * 节目单来源
 *
 * 来源：原版 `core/data/entities/epgsource/EpgSource.kt`（去掉 `@Serializable`）。
 * 未单独建文件：M2 指令清单里没有给 epgsource 实体留位置，
 * 而它只有两个字段、且只被本文件与 [Prefs] 使用，就地定义可少一个文件。
 */
data class EpgSource(
    /** 名称 */
    val name: String = "",

    /** 链接 */
    val url: String = "",
)

/**
 * 节目单获取
 *
 * ---------------------------------------------------------------------------
 * 来源：MyTV 1.7（源码 tag `custom-v1.7`，提交 7d1081f）
 *       `core/data/repositories/epg/EpgRepository.kt`
 *       （含原版同文件内的两个私有缓存类 `EpgParsedCache` / `EpgXmlRepository`）
 *
 * 移植方式：**聚合算法、缓存策略、解析循环、判定规则全部逐字保留**，必需改动如下：
 *   ① 去掉 kotlinx.coroutines（`suspend`/`withContext`）→ 全部改同步方法，
 *      由调用方保证在后台线程执行（见下「线程约定」）。
 *   ② 去掉 kotlinx.serialization → [EpgList] 的持久化改用 org.json（见 [epgListToJson]）。
 *   ③ 原版靠 `Globals.cacheDir` 全局单例拿缓存目录 → 改为**构造时注入** `cacheDir`。
 *   ④ 原版 `OkHttpClient()` 裸建 → 改用 [Http.client] 以保证「UA / 超时 / 跨协议重定向」一致。
 *
 * 保留的关键语义：
 *   - **主源 + 补齐源**：主源里「当前时刻查不到正在播」的频道，依次从备用源补上；
 *     这样某个源缺 CCTV1 / 翡翠台 / 海南卫视 时界面不会缺节目单。
 *   - **两级缓存**：XML 原文一份、解析结果一份，互不混淆。
 *   - **超时**：连接 15s / 读取 60s（见 [Http.EPG_CONNECT_TIMEOUT_MS] / [Http.EPG_READ_TIMEOUT_MS]）。
 *   - **凌晨不刷新**：`refreshTimeThreshold` 之前沿用跨天缓存，但**绝不返回空节目单**。
 *
 * ⚠️ 三处**有意的行为改进**（都是原版的健壮性缺口，不影响正常路径）：
 *   1. 解析结果缓存**损坏时**原版会在 `isExpired` 里抛异常并把该源整个跳过（且永不恢复）；
 *      这里改为**视为过期并重新拉取**。
 *   2. 原版失败时只抛 `"所有节目单来源都不可用"`，排查时看不出是哪个源、什么原因；
 *      这里按 M2 指令要求做**逐源失败原因拼接**（原文案保持不变，原因追加在后面）。
 *   3. 新增「非 XML / 空内容」判定（M2 指令要求）：http 返回 HTML 错误页同样算失败。
 *
 * 线程约定：**全部 public 方法同步阻塞**，严禁在主线程调用。
 * ---------------------------------------------------------------------------
 */
class EpgRepository(
    private val cacheDir: File,
    private val source: EpgSource,
    private val fallbackSources: List<EpgSource> = emptyList(),
) {

    /** 参与聚合的节目单来源（主源排第一，按 url 去重）。逐字保留。 */
    private val sources: List<EpgSource> = (listOf(source) + fallbackSources).distinctBy { it.url }

    /** 解析结果缓存（对应原版私有类 `EpgParsedCache`）。 */
    private val parsedCaches: Map<String, FileCache> =
        sources.associate { it.url to FileCache(cacheDir, "epg-${it.url.hashCode().toUInt().toString(16)}.json") }

    /** XML 原文缓存（对应原版私有类 `EpgXmlRepository`）。 */
    private val xmlCaches: Map<String, FileCache> =
        sources.associate { it.url to FileCache(cacheDir, "epg-${it.url.hashCode().toUInt().toString(16)}.xml") }

    private val client: OkHttpClient by lazy {
        Http.client(
            connectTimeoutMs = Http.EPG_CONNECT_TIMEOUT_MS,
            readTimeoutMs = Http.EPG_READ_TIMEOUT_MS,
        )
    }

    /** 清除全部来源的缓存。 */
    fun clearCache() {
        xmlCaches.values.forEach { runCatching { it.clear() } }
        parsedCaches.values.forEach { runCatching { it.clear() } }
        EpgList.clearCache()
    }

    // ---------------- 解析 ----------------

    /**
     * 解析节目单 xml
     *
     * **日志文案逐字保留原版**（M2 指令列为关键项）：
     * `解析节目单完成，共N个频道，M个节目` —— 这是接不上 adb 时唯一的排错通道，字面不能改。
     */
    private fun parseFromXml(
        xmlString: String,
        filteredChannels: List<String> = emptyList(),
    ): EpgList {
        fun parseTime(time: String): Long {
            if (time.length < 14) return 0

            return SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault())
                .parse(time)?.time ?: 0
        }

        val parser: XmlPullParser = android.util.Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xmlString))

        // 预先把待匹配频道归一化，避免遍历整份节目单时反复做正则运算
        val filteredNameSet = filteredChannels
            .map { ChannelName.normalize(it) }
            .filter { it.isNotBlank() }
            .toHashSet()

        val filteredCoreNameSet = filteredChannels
            .mapNotNull { ChannelName.coreKey(it) }
            .filter { it.startsWith("cctv") }
            .toHashSet()

        val epgMap = mutableMapOf<String, Epg>()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "channel") {
                        val channelId = parser.getAttributeValue(null, "id")
                        parser.nextTag()
                        val channelName = parser.nextText()

                        if (isChannelWanted(channelName, filteredNameSet, filteredCoreNameSet)) {
                            epgMap[channelId] = Epg(channelName, EpgProgrammeList())
                        }
                    } else if (parser.name == "programme") {
                        val channelId = parser.getAttributeValue(null, "channel")
                        val startTime = parser.getAttributeValue(null, "start")
                        val stopTime = parser.getAttributeValue(null, "stop")
                        parser.nextTag()
                        val title = parser.nextText()

                        epgMap[channelId]?.let { epg ->
                            epgMap[channelId] = epg.copy(
                                programmeList = EpgProgrammeList(
                                    epg.programmeList + listOf(
                                        EpgProgramme(
                                            startAt = parseTime(startTime),
                                            endAt = parseTime(stopTime),
                                            title = title,
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        UiLog.i(
            TAG,
            "解析节目单完成，共${epgMap.size}个频道，${epgMap.values.sumOf { it.programmeList.size }}个节目"
        )
        return EpgList(epgMap.values.toList())
    }

    /**
     * 判断节目单里的频道是否为当前直播源需要的频道
     *
     * 与 [EpgList.match] 保持同一套判定规则：先按归一化名称精确比较，
     * 央视系列再退化为核心标识比较。这里使用预先算好的集合，避免重复做正则运算。
     * 逐字保留原版。
     */
    private fun isChannelWanted(
        rawName: String,
        filteredNameSet: Set<String>,
        filteredCoreNameSet: Set<String>,
    ): Boolean {
        if (filteredNameSet.isEmpty()) return false

        val normalized = ChannelName.normalize(rawName)
        if (normalized.isBlank()) return false
        if (normalized in filteredNameSet) return true

        val core = ChannelName.coreKey(rawName) ?: return false
        return core.startsWith("cctv") && core in filteredCoreNameSet
    }

    // ---------------- 主流程 ----------------

    /**
     * 获取节目单列表
     *
     * @param filteredChannels 当前直播源的频道名列表（用于只解析需要的频道；空表示不筛）
     * @param refreshTimeThreshold 刷新时间阈值（小时）；该时刻之前不主动刷新
     * @throws Exception 所有来源都不可用；message 内**逐源拼接**失败原因
     */
    fun getEpgList(
        filteredChannels: List<String> = emptyList(),
        refreshTimeThreshold: Int,
    ): EpgList {
        try {
            // 刷新时间点之前优先沿用已有缓存，避免凌晨去拉源站还没生成的数据。
            // 注意这里只是「不主动刷新」，绝不再返回空节目单。
            val beforeThreshold =
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < refreshTimeThreshold

            var merged: EpgList? = null

            // 逐源失败原因（M2 指令要求拼接；原版只抛一句「都不可用」）
            val failures = mutableListOf<String>()

            for (epgSource in sources) {
                // 单个来源不可用（超时、返回旧文件、格式异常）时跳过，不要让整份节目单一起消失
                val loaded = runCatching { load(epgSource, filteredChannels, beforeThreshold) }
                    .onFailure {
                        UiLog.e(TAG, "节目单来源不可用：${epgSource.name}", it)
                        failures += "${epgSource.name}：${it.message ?: it.javaClass.simpleName}"
                    }
                    .getOrNull() ?: continue

                val current = merged
                if (current == null) {
                    // 第一个可用的来源作为主源
                    merged = loaded
                    continue
                }

                // 后续来源只用来补「当前时刻查不到节目」的频道
                val missing = current.channelsWithoutLiveProgramme(filteredChannels)
                if (missing.isEmpty()) break

                merged = current.fillMissingFrom(loaded, missing)
            }

            if (merged == null) {
                // 原文案保持不变，原因追加在后面（逐源失败原因拼接）
                throw Exception(
                    "所有节目单来源都不可用" +
                            if (failures.isEmpty()) "" else "（${failures.joinToString("；")}）"
                )
            }

            val epgList = merged

            UiLog.i(
                TAG,
                buildString {
                    append("节目单就绪：来源=").append(sources.size).append("个")
                    append("，频道").append(epgList.size).append("个")
                    append("，节目").append(epgList.sumOf { it.programmeList.size }).append("个")
                    if (filteredChannels.isNotEmpty()) {
                        append("，当前时刻覆盖 ")
                        append(epgList.liveChannelCount(filteredChannels))
                        append("/").append(filteredChannels.size)
                    }
                }
            )

            return epgList
        } catch (ex: Exception) {
            UiLog.e(TAG, "获取节目单失败", ex)
            throw Exception(ex)
        }
    }

    /**
     * 读取单个来源的节目单（优先用缓存）
     *
     * 两级缓存的协作方式与原版完全一致：
     *   - 解析结果缓存新鲜 → 直接返回，**不碰网络**；
     *   - 解析结果缓存过期 → 走 [FileCache.getOrRefresh] 的 refreshOp：
     *     先取 XML（`getOrRefresh(0)`，即原版的「每次都重新下载」），再解析。
     */
    private fun load(
        epgSource: EpgSource,
        filteredChannels: List<String>,
        beforeThreshold: Boolean,
    ): EpgList {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        val json = parsedCaches.getValue(epgSource.url).getOrRefresh(
            isExpired = { lastModified, cacheData ->
                when {
                    // 没有缓存 → 必须拉取
                    cacheData.isNullOrBlank() -> true

                    // 缓存损坏 → 视为过期（原版这里会抛异常并把该源永久跳过，见文件头「改进 1」）
                    decodeEpgListOrNull(cacheData) == null -> true

                    // 缓存里当前时刻一个节目都对不上 → 源多半已经更新，重新拉取
                    filteredChannels.isNotEmpty() &&
                            decodeEpgListOrNull(cacheData)!!
                                .liveChannelCount(filteredChannels) == 0 -> true

                    // 刷新时间点之前不主动刷新，沿用跨天缓存
                    beforeThreshold -> false

                    // 跨天 → 刷新
                    else -> dateFormat.format(System.currentTimeMillis()) !=
                            dateFormat.format(lastModified)
                }
            },
            refreshOp = {
                epgListToJson(
                    parseFromXml(
                        xmlCaches.getValue(epgSource.url).getOrRefresh(0) {
                            fetchXml(epgSource.url)
                        },
                        filteredChannels,
                    )
                )
            },
        )

        return decodeEpgListOrNull(json) ?: throw Exception("解析结果缓存无法解析")
    }

    /**
     * 获取远程 xml
     *
     * 按 url 后缀分派给 [EpgFetcher]；失败一律抛 [Http.HttpError]（message 为中文原因）。
     *
     * 新增的健壮性判定（M2 指令要求「非 XML / 空内容也算失败」）：
     * 源站偶尔会对过期路径回一个 HTML 错误页并带 200，若不拦，解析器会一路空转到文档结束，
     * 表现为「节目单就绪但 0 个频道」这种极难定位的症状。
     */
    private fun fetchXml(url: String): String {
        UiLog.i(TAG, "获取节目单xml: $url")

        val text = runCatching {
            Http.open(url, client) { response ->
                EpgFetcher.of(url).fetch(response)
            }
        }.getOrElse { ex ->
            UiLog.e(TAG, "获取节目单xml失败", ex)
            throw Http.HttpError("获取失败（${ex.message ?: ex.javaClass.simpleName}）")
        }

        if (text.isBlank()) throw Http.HttpError("内容为空")
        if (!text.trimStart().startsWith("<")) {
            val head = text.trimStart().take(40).replace('\n', ' ')
            throw Http.HttpError("非 XML 内容（开头：$head）")
        }

        return text
    }

    // ---------------- 「当前时刻是否查到节目」的判定 ----------------

    /**
     * 取「当前时刻正在播」的频道标识集合（归一化名 + 央视核心标识）
     *
     * 预先算一次集合，避免逐个频道去遍历整份节目单。逐字保留原版。
     */
    private fun EpgList.liveChannelKeys(now: Long = System.currentTimeMillis()): Set<String> {
        val keys = HashSet<String>()

        for (epg in this) {
            if (epg.programmeList.none { it.startAt <= now && now <= it.endAt }) continue

            ChannelName.normalize(epg.channel).takeIf { it.isNotBlank() }?.let { keys.add(it) }
            ChannelName.coreKey(epg.channel)?.takeIf { it.startsWith("cctv") }?.let { keys.add(it) }
        }

        return keys
    }

    /** 频道名是否落在上面算出来的集合里。逐字保留原版。 */
    private fun Set<String>.covers(channelName: String): Boolean {
        val normalized = ChannelName.normalize(channelName)
        if (normalized.isNotBlank() && normalized in this) return true

        val core = ChannelName.coreKey(channelName) ?: return false
        return core.startsWith("cctv") && core in this
    }

    /** 当前时刻已经能查到节目的频道数 */
    private fun EpgList.liveChannelCount(names: List<String>): Int {
        val keys = liveChannelKeys()
        return names.count { keys.covers(it) }
    }

    /** 当前时刻还查不到节目的频道 */
    private fun EpgList.channelsWithoutLiveProgramme(names: List<String>): List<String> {
        val keys = liveChannelKeys()
        return names.filterNot { keys.covers(it) }
    }

    /**
     * 用补齐源的数据替换掉主源中「当前时刻没有节目」的频道。逐字保留原版。
     */
    private fun EpgList.fillMissingFrom(extra: EpgList, missingNames: List<String>): EpgList {
        if (isEmpty()) return extra
        if (extra.isEmpty() || missingNames.isEmpty()) return this

        val replacements = LinkedHashMap<String, Epg>()

        missingNames.forEach { name ->
            val matched = extra.firstOrNull { epg ->
                epg.programmeList.isNotEmpty() && ChannelName.matches(epg.channel, name)
            } ?: return@forEach

            replacements[name] = matched
        }

        if (replacements.isEmpty()) return this

        val kept = filterNot { epg -> missingNames.any { ChannelName.matches(epg.channel, it) } }
        val added = replacements.values.distinct()

        return EpgList(kept + added)
    }

    companion object {
        private const val TAG = "节目单"

        // ---------------- EpgList ↔ JSON（替代原版的 kotlinx.serialization） ----------------

        /** 序列化解析结果。结构与原版的 `@Serializable EpgList` 一一对应。 */
        private fun epgListToJson(list: EpgList): String {
            val arr = JSONArray()
            list.forEach { epg ->
                val programmes = JSONArray()
                epg.programmeList.forEach { p ->
                    programmes.put(
                        JSONObject()
                            .put("s", p.startAt)
                            .put("e", p.endAt)
                            .put("t", p.title)
                    )
                }
                arr.put(
                    JSONObject()
                        .put("c", epg.channel)
                        .put("p", programmes)
                )
            }
            return arr.toString()
        }

        /** 反序列化；任何异常（含缓存损坏）返回 null，由调用方决定怎么处理。 */
        private fun decodeEpgListOrNull(json: String?): EpgList? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val arr = JSONArray(json)
                val out = ArrayList<Epg>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val programmes = o.optJSONArray("p") ?: JSONArray()
                    val list = ArrayList<EpgProgramme>(programmes.length())
                    for (j in 0 until programmes.length()) {
                        val p = programmes.optJSONObject(j) ?: continue
                        list += EpgProgramme(
                            startAt = p.optLong("s", 0L),
                            endAt = p.optLong("e", 0L),
                            title = p.optString("t", ""),
                        )
                    }
                    out += Epg(o.optString("c", ""), EpgProgrammeList(list))
                }
                EpgList(out)
            }.getOrNull()
        }
    }
}
