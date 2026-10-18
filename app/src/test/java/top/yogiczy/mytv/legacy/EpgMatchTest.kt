package top.yogiczy.mytv.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.legacy.data.Channel
import top.yogiczy.mytv.legacy.data.ChannelName
import top.yogiczy.mytv.legacy.data.Epg
import top.yogiczy.mytv.legacy.data.EpgList
import top.yogiczy.mytv.legacy.data.EpgList.Companion.match
import top.yogiczy.mytv.legacy.data.EpgList.Companion.warmUp
import top.yogiczy.mytv.legacy.data.EpgProgramme
import top.yogiczy.mytv.legacy.data.EpgProgrammeList

/**
 * v1.21：EPG 匹配索引的**等价性 + 性能**验证。
 *
 * 背景：线上 ANR（后台指纹 7531bf224a66cb14，MiTV4-ANSM0 / Android 6.0.1）
 * 主线程被堵 11 秒，堆栈末端是 `Pattern.matcher` —— 根因是
 * `EpgList.match` 对每个频道线性扫整份节目单，每次比较跑 2~4 次正则归一化。
 *
 * v1.21 改成「节目单到手时建一次哈希索引 + O(1) 查表」。这个测试锁住两件事：
 *   ① **结果与旧的线性扫描完全一致**（否则节目单匹配会悄悄退化的）；
 *   ② 同样的规模下耗时从 O(N×M) 降到常数级。
 *
 * 只跑纯 Kotlin 逻辑，不需要 Robolectric（android.jar 的 Log/SystemClock 由
 * `testOptions.unitTests.isReturnDefaultValues` 兜住）。
 */
class EpgMatchTest {

    /** 节目单频道名（模拟真实源：写法五花八门）。 */
    private val epgNames: List<String> = buildList {
        for (i in 1..17) add("CCTV-$i")
        for (i in 1..17) add("CCTV$i")
        addAll(
            listOf(
                "湖南卫视", "浙江卫视", "江苏卫视", "东方卫视", "北京卫视", "山东卫视",
                "安徽卫视", "广东卫视", "深圳卫视", "湖北卫视", "四川卫视", "天津卫视",
                "翡翠台", "明珠台", "凤凰卫视中文台", "凤凰卫视资讯台", "星空卫视",
                "香港卫视", "中天新闻台", "民视新闻台", "台视", "中视", "华视", "公视",
                "金鹰卡通", "卡酷少儿", "CETV1", "CGTN",
            )
        )
        // 补足到 1000 个：模拟"节目单上千频道"的真实规模
        var n = size
        while (n < 1000) {
            add("测试频道%04d".format(n))
            n++
        }
    }

    private val epgList = EpgList(epgNames.map { name ->
        Epg(channel = name, programmeList = EpgProgrammeList(listOf(EpgProgramme(title = "$name 节目"))))
    })

    /** 直播源里的频道名（写法与节目单不一致才是常态）。 */
    private val queryNames: List<String> = buildList {
        for (i in 1..17) {
            add("CCTV-${i}综合")
            add("CCTV$i 高清")
            add("中央${i}台")
        }
        addAll(
            listOf(
                "湖南卫视高清", "浙江卫视", "翡翠台", "凤凰卫视中文台", "香港卫视",
                "CCTV-5+ 体育赛事", "CCTV5+", "CCTV-5+", "CCTV-1综合", "CCTV1综合",
                "东方卫视", "深圳卫视", "中天新闻台", "民视新闻台", "金鹰卡通",
                "不存在的频道A", "不存在的频道B", "CCTV-99", "测试频道0500",
            )
        )
    }

    /**
     * 旧实现（v1.20 及以前）：线性扫描 + [ChannelName.matches]。
     * 逐字照搬，用来和新实现做**结果对比**。
     */
    private fun oldMatch(list: EpgList, ch: Channel): Epg? {
        if (list.isEmpty()) return null
        return list.firstOrNull { epg -> ChannelName.matches(epg.channel, ch.epgName.orEmpty()) }
            ?: list.firstOrNull { epg -> ChannelName.matches(epg.channel, ch.name) }
            ?: Epg()
    }

    @Test
    fun 索引结果与旧线性扫描完全一致() {
        EpgList.clearCache()
        epgList.warmUp()

        var checked = 0
        var hitCount = 0
        queryNames.forEach { raw ->
            val ch = Channel(name = raw, epgName = raw, urlList = listOf("http://x/y.m3u8"))
            val old = oldMatch(epgList, ch)
            val new = epgList.match(ch)
            assertEquals(
                "「$raw」的新旧匹配结果不一致：old=${old?.channel} new=${new?.channel}",
                old?.channel, new?.channel,
            )
            checked++
            if (new != null && new.channel.isNotBlank()) hitCount++
        }
        assertTrue("样本太少，测试没意义", checked >= 50)
        assertTrue("应当有相当比例的频道能匹配上节目单（实测 $hitCount/$checked）", hitCount > checked / 3)
        println("等价性：检查 $checked 个频道名，命中 $hitCount 个，全部与旧实现一致")
    }

    @Test
    fun 索引后匹配耗时降到常数级() {
        EpgList.clearCache()
        val channels = queryNames.map {
            Channel(name = it, epgName = it, urlList = listOf("http://x/y.m3u8"))
        }

        // 旧实现：先跑一遍（含正则归一化），计时
        val t0 = System.nanoTime()
        channels.forEach { oldMatch(epgList, it) }
        val oldMs = (System.nanoTime() - t0) / 1_000_000

        // 新实现：建索引（一次性）+ 全部匹配
        EpgList.clearCache()
        val t1 = System.nanoTime()
        epgList.warmUp()
        val buildMs = (System.nanoTime() - t1) / 1_000_000

        val t2 = System.nanoTime()
        channels.forEach { epgList.match(it) }
        val newMs = (System.nanoTime() - t2) / 1_000_000

        println("规模：节目单 ${epgList.size} 频道 × 查询 ${channels.size} 个频道名")
        println("旧实现（线性+正则）：${oldMs}ms")
        println("新实现：建索引 ${buildMs}ms + 匹配 ${newMs}ms")

        // 关键断言：匹配阶段必须远快于旧实现（这里取"不超过旧实现的 1/3"作为下限，
        // JVM 上旧实现通常几十毫秒、新实现 0~1ms）
        assertTrue(
            "匹配耗时没有降下来：old=${oldMs}ms new=${newMs}ms",
            newMs <= (oldMs / 3).coerceAtLeast(1),
        )
        // 把规模放大 4 倍，新实现的耗时不应线性放大（证明不是 O(N×M)）
        val big = EpgList(buildList {
            addAll(epgList.value)
            repeat(3) { addAll(epgList.value) }
        })
        EpgList.clearCache()
        big.warmUp()
        val t3 = System.nanoTime()
        repeat(4) { channels.forEach { big.match(it) } }
        val bigMs = (System.nanoTime() - t3) / 1_000_000
        println("节目单放大 4 倍 + 查询量放大 4 倍后：${bigMs}ms（旧实现同规模会涨约 16 倍）")
        assertTrue("放大规模后耗时暴涨，说明仍是 O(N×M)", bigMs <= (newMs * 8 + 200))
    }

    @Test
    fun 换源后索引会重建() {
        EpgList.clearCache()
        epgList.warmUp()
        val ch = Channel(name = "湖南卫视", epgName = "湖南卫视", urlList = listOf("http://x"))
        assertEquals("湖南卫视", epgList.match(ch)?.channel)

        // 换了一份节目单（只有 CCTV-1），同名频道应当**匹配不到**，而不是命中旧索引
        val other = EpgList(listOf(Epg(channel = "CCTV-1")))
        other.warmUp()
        assertEquals("", other.match(ch)?.channel ?: "（空）")
        assertEquals("CCTV-1", other.match(
            Channel(name = "CCTV-1", epgName = "CCTV-1", urlList = listOf("http://x"))
        )?.channel)
    }
}
