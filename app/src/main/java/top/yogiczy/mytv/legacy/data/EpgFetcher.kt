package top.yogiczy.mytv.legacy.data

import okhttp3.Response
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * 节目单数据获取
 *
 * ---------------------------------------------------------------------------
 * 来源：MyTV 1.7（源码 tag `custom-v1.7`，提交 7d1081f），合并自：
 *   core/data/repositories/epg/fetcher/EpgFetcher.kt        → [EpgFetcher]
 *   core/data/repositories/epg/fetcher/XmlEpgFetcher.kt     → [XmlEpgFetcher]
 *   core/data/repositories/epg/fetcher/XmlGzEpgFetcher.kt   → [XmlGzEpgFetcher]
 *   core/data/repositories/epg/fetcher/DefaultEpgFetcher.kt → [DefaultEpgFetcher]
 *
 * 移植方式：**判定逻辑与解压逻辑逐字保留**，只做两类必需改动：
 *   ① 去掉 `suspend` / `withContext(Dispatchers.IO)` / `kotlinx.coroutines` 依赖
 *      —— 本工程不引协程，改为**同步方法**，由调用方保证在后台线程执行
 *      （见 [EpgRepository] 的线程约定）。除签名外方法体一字未改。
 *   ② package 改为 `top.yogiczy.mytv.legacy.data`。
 *
 * 分派顺序与原版一致：`instances.first { it.isSupport(url) }`
 * → `.xml` 命中 [XmlEpgFetcher]；`.gz` 命中 [XmlGzEpgFetcher]；
 *   其余一律落到兜底的 [DefaultEpgFetcher]（它 `isSupport` 恒 true，必须排最后）。
 * ---------------------------------------------------------------------------
 */
interface EpgFetcher {
    /** 是否支持该格式 */
    fun isSupport(url: String): Boolean

    /** 获取节目单正文（同步；调用方需在后台线程调用） */
    fun fetch(response: Response): String

    companion object {
        val instances: List<EpgFetcher> = listOf(
            XmlEpgFetcher(),
            XmlGzEpgFetcher(),
            DefaultEpgFetcher(),
        )

        /** 按 url 后缀挑一个抓取器。对应原版 `instances.first { it.isSupport(url) }`。 */
        fun of(url: String): EpgFetcher = instances.first { it.isSupport(url) }
    }
}

/**
 * 节目单 xml 数据获取
 *
 * 来源：`fetcher/XmlEpgFetcher.kt`。
 */
class XmlEpgFetcher : EpgFetcher {
    override fun isSupport(url: String): Boolean {
        return url.endsWith(".xml")
    }

    override fun fetch(response: Response): String {
        // okhttp 3.x 的 Response.body 是 package-private 字段，必须调方法
        return response.body()!!.string()
    }
}

/**
 * 节目单 xml.gz 数据获取
 *
 * 来源：`fetcher/XmlGzEpgFetcher.kt`，解压与逐行读取逻辑逐字保留。
 *
 * ⚠️ 与 xml 版的行为差异（原版即如此，不是移植引入的）：这里按**行**读取并重新用
 * `\n` 拼接，因此原始文件的行尾符（CRLF）会被归一成 LF。对 XML 解析无影响。
 */
class XmlGzEpgFetcher : EpgFetcher {
    override fun isSupport(url: String): Boolean {
        return url.endsWith(".gz")
    }

    override fun fetch(response: Response): String {
        val gzData = response.body()!!.bytes()
        val stringBuilder = StringBuilder()
        GZIPInputStream(ByteArrayInputStream(gzData)).use { gzipInputStream ->
            BufferedReader(InputStreamReader(gzipInputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    stringBuilder.append(line).append("\n")
                }
            }
        }
        return stringBuilder.toString()
    }
}

/**
 * 缺省节目单数据获取
 *
 * 来源：`fetcher/DefaultEpgFetcher.kt`。
 * 它不读网络正文，只返回一个最小的 XML 头，解析后得到空节目单 ——
 * 这是「来源格式不认识」时的**兜底而非失败**，原版语义如此，逐字保留。
 */
class DefaultEpgFetcher : EpgFetcher {
    override fun isSupport(url: String): Boolean {
        return true
    }

    override fun fetch(response: Response): String {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
    }
}
