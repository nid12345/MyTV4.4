package top.yogiczy.mytv.legacy.data

import android.util.Log
import top.yogiczy.mytv.legacy.net.Http

/**
 * DLNA 投送控制（UPnP AVTransport，问题 6 的投屏）。
 *
 * ---------------------------------------------------------------------------
 * 移植自 MyTV 1.7 `core/data/repositories/dlna/DlnaController.kt`：
 *   · `SetAVTransportURI`（带 DIDL-Lite 元数据）→ 失败退化到不带元数据重试一次 → `Play`
 *   · `Stop`
 *   · `GetTransportInfo`（查询播放状态）
 *   · `guessProtocolInfo`：按扩展名猜 MIME（m3u8/mp4/mkv/flv/ts）
 *   · `xmlEscape`：五个实体的转义
 *   —— 上述逻辑与报文格式**逐字保留**。
 *   · 原版用 OkHttp + 协程；本工程改为 [Http.postText] + 同步阻塞（调用方负责线程）。
 * ---------------------------------------------------------------------------
 */
object DlnaController {

    private const val TAG = "投屏"
    private const val INSTANCE_ID = "0"

    /**
     * 把直播源投送到设备播放。
     *
     * 先 `SetAVTransportURI` 设置播放地址与元数据，再 `Play` 开始播放。
     */
    fun cast(device: DlnaDevice, url: String, title: String): Result<Unit> = try {
        if (url.isBlank()) throw DlnaException("当前频道没有可用的播放地址")

        val metadata = buildDidlLite(url, title)

        val setUriSucceeded = try {
            invoke(
                device, "SetAVTransportURI",
                listOf(
                    "InstanceID" to INSTANCE_ID,
                    "CurrentURI" to url,
                    "CurrentURIMetaData" to metadata,
                ),
            )
            true
        } catch (ex: Exception) {
            // 部分设备不理解元数据，退化为不带元数据再试一次
            Log.w(TAG, "携带元数据投送失败，改为不带元数据重试: ${ex.message}")
            runCatching {
                invoke(
                    device, "SetAVTransportURI",
                    listOf(
                        "InstanceID" to INSTANCE_ID,
                        "CurrentURI" to url,
                        "CurrentURIMetaData" to "",
                    ),
                )
            }.isSuccess
        }

        if (!setUriSucceeded) throw DlnaException("设备拒绝播放该地址")

        // 部分设备设置播放地址后需要短暂处理才能接受播放指令
        runCatching { Thread.sleep(300L) }

        invoke(
            device, "Play",
            listOf("InstanceID" to INSTANCE_ID, "Speed" to "1"),
        )

        Log.i(TAG, "已投送到 ${device.displayName}: $title")
        Result.success(Unit)
    } catch (ex: Exception) {
        Log.e(TAG, "投送失败: ${ex.message}")
        Result.failure(ex)
    }

    /** 停止设备播放。 */
    fun stop(device: DlnaDevice): Boolean = try {
        invoke(device, "Stop", listOf("InstanceID" to INSTANCE_ID))
        true
    } catch (ex: Exception) {
        Log.w(TAG, "停止投送失败: ${ex.message}")
        false
    }

    /** 查询设备播放状态，如 PLAYING / STOPPED / NO_MEDIA_PRESENT。 */
    fun getTransportState(device: DlnaDevice): String? = runCatching {
        val response = invoke(device, "GetTransportInfo", listOf("InstanceID" to INSTANCE_ID))
        Regex("<CurrentTransportState>([^<]*)</CurrentTransportState>")
            .find(response)?.groupValues?.getOrNull(1)?.trim()
    }.getOrNull()

    private fun invoke(device: DlnaDevice, action: String, arguments: List<Pair<String, String>>): String {
        val serviceType = device.avTransportServiceType
            .ifBlank { "urn:schemas-upnp-org:service:AVTransport:1" }
        val controlUrl = device.avTransportControlUrl
        if (controlUrl.isBlank()) throw DlnaException("设备不支持 AVTransport 控制")

        val args = arguments.joinToString("\n") { (key, value) -> "<$key>${value.xmlEscape()}</$key>" }
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body>
            <u:$action xmlns:u="$serviceType">
            $args
            </u:$action>
            </s:Body>
            </s:Envelope>
        """.trimIndent()

        val (code, text) = try {
            Http.postRaw(
                url = controlUrl,
                body = body,
                extraHeaders = mapOf(
                    "SOAPAction" to "\"$serviceType#$action\"",
                    "Connection" to "close",
                ),
            )
        } catch (ex: Http.HttpError) {
            throw DlnaException(ex.message ?: "设备响应异常")
        }

        // 设备会把出错原因写在错误响应体里，优先用它（原版同逻辑）
        if (code !in 200..299 || text.contains(":Fault", ignoreCase = true)) {
            val description = Regex("<errorDescription>([^<]*)</errorDescription>")
                .find(text)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
            val errorCode = Regex("<errorCode>([^<]*)</errorCode>")
                .find(text)?.groupValues?.getOrNull(1)?.trim()
            throw DlnaException(
                description
                    ?: errorCode?.let { "设备返回错误码 $it" }
                    ?: "设备响应异常（HTTP $code）"
            )
        }
        return text
    }

    /**
     * 构造播放元数据。部分设备缺少元数据时会拒绝播放，需要提供标准的 DIDL-Lite 描述。
     */
    private fun buildDidlLite(url: String, title: String): String {
        val safeTitle = title.ifBlank { "直播" }
        return """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
            <item id="0" parentID="-1" restricted="1">
            <dc:title>${safeTitle.xmlEscape()}</dc:title>
            <upnp:class>object.item.videoItem</upnp:class>
            <res protocolInfo="${guessProtocolInfo(url)}">${url.xmlEscape()}</res>
            </item>
            </DIDL-Lite>
        """.trimIndent()
    }

    private fun guessProtocolInfo(url: String): String {
        val format = when {
            url.contains(".m3u8", ignoreCase = true) -> "application/vnd.apple.mpegurl"
            url.contains(".mp4", ignoreCase = true) -> "video/mp4"
            url.contains(".mkv", ignoreCase = true) -> "video/x-matroska"
            url.contains(".flv", ignoreCase = true) -> "video/x-flv"
            url.contains(".ts", ignoreCase = true) -> "video/mp2t"
            else -> "video/*"
        }
        return "http-get:*:$format:*"
    }

    private fun String.xmlEscape(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}

/** DLNA 投送异常。 */
class DlnaException(message: String) : Exception(message)
