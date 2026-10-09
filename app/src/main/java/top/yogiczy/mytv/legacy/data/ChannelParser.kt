package top.yogiczy.mytv.legacy.data

import java.util.Locale

/** M3U/M3U8 播放列表 与 常见 TXT 源 的解析。 */
object ChannelParser {

    private val EXTINF_ATTR = Regex("""([\w-]+)\s*=\s*"([^"]*)"""")

    /** 分组名兜底：没写 group-title 时按关键词猜。 */
    fun guessGroup(name: String): String = when {
        name.contains("CCTV") || name.contains("央视") -> "央视频道"
        name.contains("卫视") -> "卫视频道"
        name.contains("港澳") || name.contains("翡翠") || name.contains("凤凰") -> "港澳频道"
        name.contains("少儿") || name.contains("卡通") -> "少儿频道"
        else -> "其他频道"
    }

    fun parse(text: String): List<Channel> {
        val src = text.trim()
        return when {
            src.startsWith("#EXTM3U") || src.contains("#EXTINF") -> parseM3u(src)
            else -> parseTxt(src)
        }
    }

    private fun parseM3u(src: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var group: String? = null
        var logo: String? = null
        var epg: String? = null

        src.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTINF") -> {
                    val meta = line.substringAfter(',', "").trim()
                    val attrs = EXTINF_ATTR.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                    group = attrs["group-title"]?.takeIf { it.isNotBlank() }
                    logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                    epg = attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                    name = meta.ifEmpty { null } ?: epg
                }

                line.startsWith("#") -> Unit

                else -> {
                    val n = name
                    if (!n.isNullOrBlank()) {
                        out += Channel(
                            name = n,
                            urlList = listOf(line),
                            logo = logo,
                            epgName = epg,
                            groupName = group ?: guessGroup(n),
                        )
                    }
                    name = null; group = null; logo = null; epg = null
                }
            }
        }
        return merge(out)
    }

    /** TXT 源：`分组名,#genre#` 起一段，随后每行 `频道名,url1#url2`。 */
    private fun parseTxt(src: String): List<Channel> {
        val out = ArrayList<Channel>()
        var group = "未分组"
        src.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            if (line.contains("#genre#")) {
                group = line.substringBefore(',').trim().ifEmpty { "未分组" }
                return@forEach
            }
            val idx = line.lastIndexOf(',')
            if (idx <= 0) return@forEach
            val n = line.substring(0, idx).trim()
            val urls = line.substring(idx + 1).trim()
                .split('#', '|').map { it.trim() }.filter { it.startsWith("http") }
            if (n.isEmpty() || urls.isEmpty()) return@forEach
            out += Channel(name = n, urlList = urls, epgName = n, groupName = group)
        }
        return merge(out)
    }

    /** 同名频道合并线路，分组取首次出现的那个。 */
    private fun merge(list: List<Channel>): List<Channel> {
        val byName = LinkedHashMap<String, Channel>()
        list.forEach { c ->
            val old = byName[c.name]
            byName[c.name] = if (old == null) c else old.copy(
                urlList = (old.urlList + c.urlList).distinct(),
                logo = old.logo ?: c.logo,
            )
        }
        return byName.values.toList()
    }

    fun toGroupList(channels: List<Channel>): ChannelGroupList {
        val grouped = LinkedHashMap<String, MutableList<Channel>>()
        channels.forEach { c ->
            grouped.getOrPut(c.groupName.ifBlank { guessGroup(c.name) }) { ArrayList() } += c
        }
        return ChannelGroupList(grouped.map { (k, v) -> ChannelGroup(k, v) })
    }

    fun formatCount(n: Int): String = String.format(Locale.CHINA, "%d个频道", n)
}
