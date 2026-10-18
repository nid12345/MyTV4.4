package top.yogiczy.mytv.legacy.data

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import top.yogiczy.mytv.legacy.net.Http
import java.io.StringReader
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI

/**
 * DLNA 设备发现（SSDP，问题 6 的投屏）。
 *
 * ---------------------------------------------------------------------------
 * 移植自 MyTV 1.7 `core/data/repositories/dlna/DlnaDiscovery.kt`：
 *   · 多播地址 / 端口、搜索目标（MediaRenderer 1/2/3）、报文格式、重发间隔、
 *     接收超时、网络接口挑选顺序、多播锁 —— **逐条保留**。
 *   · 原版用协程（`suspend fun discover` + `async/awaitAll`）；API 19 的工程里
 *     本工程不引协程，改为**同步阻塞 + 单线程**，由调用方（[DlnaCastRepository]）
 *     放到后台线程执行。行为等价：先收报文攒 LOCATION，再逐个抓设备描述。
 *   · 原版用 `Xml.newPullParser()` 解析描述 XML，这里完全一致（API 1 起可用）。
 * ---------------------------------------------------------------------------
 */
object DlnaDiscovery {

    private const val TAG = "投屏"

    private const val SSDP_HOST = "239.255.255.250"
    private const val SSDP_PORT = 1900

    /** 重复发送搜索报文的间隔（毫秒）。UDP 多播不可靠，单次发送容易漏包。 */
    private const val SEARCH_INTERVAL_MS = 700L

    /** 单次接收的超时时间（毫秒）。 */
    private const val RECEIVE_TIMEOUT_MS = 400

    /**
     * 搜索目标（v1.21 重写 —— 这是"搜不到小米电视"的根因所在）。
     *
     * 旧实现**只发 MediaRenderer:1/2/3**。局域网实测（2026-10-18，同一网段）：
     *   `ssdp:all`        → 1 台：客厅的小米电视
     *   `upnp:rootdevice` → 1 台：客厅的小米电视
     *   `MediaRenderer:1/2/3`、`AVTransport:1` → **0 台**
     * 那台电视的描述里写的是：
     *   deviceType = `urn:schemas-upnp-org:device:MiMediaRenderer:0.5`
     *   serviceType = `urn:schemas-upnp-org:service:MiAVTransport:0.5`
     * —— 小米用的是**私有设备类型**，对标准 `MediaRenderer:N` 的搜索**根本不回应**
     * （抖音等 App 的投屏 SDK 发的是 `ssdp:all`，所以它们搜得到）。
     *
     * 因此：`ssdp:all` 与 `upnp:rootdevice` **必须发**，标准 MediaRenderer 保留
     * （响应更快、报文更省）。设备"是不是渲染器"不再靠搜索目标判断，
     * 而是拉回描述 XML 后看有没有 AVTransport 服务 —— 见 [parseDeviceDescription]。
     */
    private val SEARCH_TARGETS = listOf(
        "ssdp:all",
        "upnp:rootdevice",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:device:MediaRenderer:2",
        "urn:schemas-upnp-org:device:MediaRenderer:3",
    )

    /**
     * 搜索局域网内的投屏设备（**同步阻塞**，必须在后台线程调用）。
     *
     * @param timeoutMs     搜索持续时间
     * @param onDeviceFound 每解析出一台设备即回调（后台线程），用于界面增量展示
     */
    fun discover(
        context: Context?,
        timeoutMs: Long = 8000L,
        onDeviceFound: (DlnaDevice) -> Unit = {},
    ): List<DlnaDevice> {
        val multicastLock = acquireMulticastLock(context)

        // v1.21：逐个网卡搜。旧实现只挑一张网卡，电视盒子同时插着网线又连着 WiFi 时，
        // 挑中的那张往往不是当前上网的那张 —— 多播发出去没有回应，表现为"搜不到"。
        val interfaces = candidateInterfaces()
        val targets = if (interfaces.isEmpty()) listOf(null) else interfaces
        val perInterface = (timeoutMs / targets.size).coerceAtLeast(2500L)

        val locations = LinkedHashMap<String, String>()
        try {
            targets.forEach { intf ->
                searchOnInterface(intf, perInterface, locations)
                // 已经搜到设备就不必再遍历其余网卡，省时间
                if (locations.isNotEmpty()) return@forEach
            }
        } finally {
            runCatching { multicastLock?.release() }
        }

        val devices = ArrayList<DlnaDevice>()
        locations.values.distinct().forEach { location ->
            fetchDevice(location)?.let { device ->
                onDeviceFound(device)
                devices.add(device)
            }
        }

        Log.i(TAG, "发现 ${devices.size} 台投屏设备（候选 LOCATION ${locations.size} 个）")
        return devices.sortedBy { it.displayName }
    }

    /** 在**一张**网卡上做完整的「发搜索 + 收响应」。 */
    private fun searchOnInterface(
        intf: NetworkInterface?,
        durationMs: Long,
        out: LinkedHashMap<String, String>,
    ) {
        val socket = createSocket(intf) ?: return
        try {
            val buffer = ByteArray(8192)
            val startedAt = System.currentTimeMillis()
            var lastSentAt = 0L

            while (System.currentTimeMillis() - startedAt < durationMs) {
                val now = System.currentTimeMillis()
                if (now - lastSentAt >= SEARCH_INTERVAL_MS) {
                    SEARCH_TARGETS.forEach { sendSearch(socket, it) }
                    lastSentAt = now
                }
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val location = response.headerValue("LOCATION") ?: continue
                    val key = response.headerValue("USN")
                        ?.substringBefore("::")
                        ?.ifBlank { null }
                        ?: location
                    if (out.put(key, location) == null) {
                        Log.i(TAG, "收到设备响应${intf?.let { "（${it.name}）" } ?: ""}: $location")
                    }
                } catch (_: SocketTimeoutException) {
                    // 本轮未收到报文，继续等待直到总超时
                } catch (ex: Exception) {
                    Log.w(TAG, "接收多播响应失败: ${ex.message}")
                }
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun createSocket(intf: NetworkInterface?): MulticastSocket? = try {
        MulticastSocket().apply {
            reuseAddress = true
            soTimeout = RECEIVE_TIMEOUT_MS
            timeToLive = 4
            intf?.let { runCatching { networkInterface = it } }
        }
    } catch (ex: Exception) {
        Log.e(TAG, "创建多播套接字失败: ${ex.message}")
        null
    }

    /**
     * 逐个尝试的多播网卡（wlan → eth → en → 其它 IPv4）。
     *
     * 设备同时在蜂窝网络与 WiFi 时，必须显式选局域网接口，否则多播发不出去。
     * 排除回环、未启用、不支持多播、无 IPv4 地址、以及 VPN 虚拟网卡（tun/ppp）。
     */
    private fun candidateInterfaces(): List<NetworkInterface> {
        val enumeration = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return emptyList()
        val candidates = ArrayList<NetworkInterface>()
        while (enumeration.hasMoreElements()) {
            val intf = enumeration.nextElement()
            val name = intf.name ?: continue
            if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("utun")) continue
            val hasIpv4 = runCatching {
                intf.interfaceAddresses.any { it.address is Inet4Address }
            }.getOrDefault(false)
            if (intf.isUp && !intf.isLoopback && intf.supportsMulticast() && hasIpv4) {
                candidates += intf
            }
        }
        val ordered = listOfNotNull(
            candidates.firstOrNull { it.name.startsWith("wlan") },
            candidates.firstOrNull { it.name.startsWith("eth") },
            candidates.firstOrNull { it.name.startsWith("en") },
        ).distinct()
        // 最多试 3 张，避免每张都等满超时
        return (ordered + candidates).distinct().take(3)
    }

    /**
     * WiFi 多播锁。Android 默认会在屏幕关闭后过滤多播报文，未持锁时 SSDP 响应会大量丢失。
     *
     * ⚠️ API 19 上 `WifiManager.createMulticastLock` 存在（API 4 起），无需版本分支；
     * 但 `CHANGE_WIFI_MULTICAST_STATE` 权限申请失败时只能降级（返回 null），
     * 此时发现率会下降但不会崩。
     */
    private fun acquireMulticastLock(context: Context?): WifiManager.MulticastLock? {
        val appContext = context?.applicationContext ?: return null
        return try {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.createMulticastLock("mytv-dlna")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (ex: Exception) {
            Log.w(TAG, "获取多播锁失败: ${ex.message}")
            null
        }
    }

    private fun sendSearch(socket: MulticastSocket, searchTarget: String) {
        // SSDP 属 HTTP 系协议，行分隔必须是 CRLF（部分固件会丢弃 LF 报文）。
        val message = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $SSDP_HOST:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            // v1.21：MX 2 → 3。设备被要求在 0~MX 秒内随机延迟后回应，MX 太小会让
            // 响应慢的设备（小米电视实测要 1 秒以上）直接错过窗口。
            append("MX: 3\r\n")
            append("ST: $searchTarget\r\n")
            append("USER-AGENT: Android/1.0 UPnP/1.1 MyTV/1.0\r\n")
            append("\r\n")
        }
        val bytes = message.toByteArray(Charsets.UTF_8)
        try {
            socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(SSDP_HOST), SSDP_PORT))
        } catch (ex: Exception) {
            Log.w(TAG, "发送搜索报文失败: ${ex.message}")
        }
    }

    private fun fetchDevice(location: String): DlnaDevice? = try {
        val xml = Http.getText(location)
        parseDeviceDescription(location, xml)
    } catch (ex: Exception) {
        Log.d(TAG, "获取设备描述失败: ${ex.message}")
        null
    }

    private fun parseDeviceDescription(location: String, xml: String): DlnaDevice? {
        var friendlyName = ""
        var modelName = ""
        var udn = ""
        var avTransportServiceType = ""
        var avTransportControlUrl = ""
        var inService = false
        var serviceType = ""
        var controlUrl = ""

        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        // 命名空间关闭后 name 可能是 "friendlyName" 或带前缀，两种都兜住
                        "friendlyName" -> if (friendlyName.isBlank()) friendlyName = parser.nextText().trim()
                        "modelName" -> if (modelName.isBlank()) modelName = parser.nextText().trim()
                        "UDN" -> if (udn.isBlank()) udn = parser.nextText().trim()
                        "service" -> {
                            inService = true
                            serviceType = ""
                            controlUrl = ""
                        }
                        "serviceType" -> if (inService) serviceType = parser.nextText().trim()
                        "controlURL" -> if (inService) controlUrl = parser.nextText().trim()
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "service" && inService) {
                        inService = false
                        if (serviceType.contains("AVTransport") && avTransportControlUrl.isBlank()) {
                            avTransportServiceType = serviceType
                            avTransportControlUrl = controlUrl
                        }
                    }
                }
                event = parser.next()
            }
        } catch (ex: Exception) {
            Log.d(TAG, "解析设备描述失败: ${ex.message}")
            return null
        }

        // 没有 AVTransport 服务说明设备不支持被推送播放（例如只提供 ContentDirectory 的 NAS）。
        // ⚠️ 判据用 `contains("AVTransport")` 而不是等于标准 URN —— 小米电视/盒子报的是
        //    `urn:schemas-upnp-org:service:MiAVTransport:0.5`，写死标准 URN 会把它误杀。
        if (avTransportControlUrl.isBlank()) {
            Log.i(TAG, "设备无 AVTransport 服务，跳过：$location（$friendlyName / $modelName）")
            return null
        }

        return DlnaDevice(
            friendlyName = friendlyName,
            modelName = modelName,
            udn = udn,
            location = location,
            host = runCatching { URI(location).host.orEmpty() }.getOrDefault(""),
            avTransportServiceType = avTransportServiceType
                .ifBlank { "urn:schemas-upnp-org:service:AVTransport:1" },
            avTransportControlUrl = resolveUrl(location, avTransportControlUrl),
        )
    }

    /** 控制地址可能是相对路径，需要与描述地址拼接。 */
    private fun resolveUrl(location: String, controlUrl: String): String =
        runCatching { URI(location).resolve(controlUrl).toString() }.getOrDefault(controlUrl)

    private fun String.headerValue(name: String): String? =
        lineSequence()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.ifBlank { null }
}
