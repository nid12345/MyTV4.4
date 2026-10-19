package top.yogiczy.mytv.legacy

import org.junit.Assert.assertEquals
import org.junit.Test
import top.yogiczy.mytv.legacy.data.SourceLogoPolicy

/**
 * v1.22 需求 2/3：按订阅源决定的台标策略。
 *
 * 判据是 URL 子串而不是源名（源名允许用户在设置里改，URL 不会变），
 * 所以这里把「带端口 / 带 query / 大小写 / 前后空格」这些扰动都覆盖到。
 */
class SourceLogoPolicyTest {

    @Test
    fun 虎牙斗鱼YY轮播只用内置台标() {
        val urls = listOf(
            "https://sub.ottiptv.cc/huyayqk.m3u",
            "https://sub.ottiptv.cc/douyuyqk.m3u",
            "https://sub.ottiptv.cc/yylunbo.m3u",
            // 大小写 / 空白 / 带端口 / 带 query 都不影响判定
            "HTTPS://SUB.OTTIPTV.CC/HUYAYQK.M3U",
            "  https://sub.ottiptv.cc/yylunbo.m3u  ",
            "http://sub.ottiptv.cc:8080/douyuyqk.m3u?x=1",
        )
        urls.forEach { url ->
            assertEquals(
                "「$url」应为 BUILTIN_ONLY",
                SourceLogoPolicy.Mode.BUILTIN_ONLY,
                SourceLogoPolicy.modeOf(url),
            )
        }
    }

    @Test
    fun 潮汕节目回放不显示台标() {
        val urls = listOf(
            "https://gitee.com/nid123/chaoshan-tv/raw/master/data/chaoshan.txt",
            "https://raw.githubusercontent.com/nid123/chaoshan-tv/main/chaoshan.txt",
            "https://example.com/chaoshan.txt",
        )
        urls.forEach { url ->
            assertEquals(
                "「$url」应为 NONE",
                SourceLogoPolicy.Mode.NONE,
                SourceLogoPolicy.modeOf(url),
            )
        }
    }

    @Test
    fun 其他源走默认三级回退() {
        val urls = listOf(
            "https://tvlive.nide.qzz.io",
            "https://chaoju.nide.qzz.io/chaoju.txt",
            "https://nide.qzz.io/chaoju/chaoju.txt",
            "http://epg.example.com/live.m3u",
            null,
            "",
            "   ",
        )
        urls.forEach { url ->
            assertEquals(
                "「$url」应为 DEFAULT",
                SourceLogoPolicy.Mode.DEFAULT,
                SourceLogoPolicy.modeOf(url),
            )
        }
    }
}
