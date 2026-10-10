package top.yogiczy.mytv.legacy.ui.qr

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * 「手机扫码隔空填写」的局域网接收端（v1.14 需求 4）。
 *
 * ---------------------------------------------------------------------------
 * 为什么是"自建小 HTTP 服务"而不是别的方案：
 *
 *  1. **零依赖、零配对**。用户拿手机相机/微信扫一扫就能用，不需要装 App、不需要登录、
 *     不需要蓝牙配对、不需要同账号。手机上唯一要有的东西是浏览器 —— 电视和手机在同一个
 *     局域网就行。这是"隔空推送"里对老电视盒子最友好的一条路。
 *  2. 本工程构建完全离线，引不了任何二维码/推送 SDK；而一个只回一页表单的 HTTP 服务
 *     用 `ServerSocket` 三十行就能写完，比引依赖更可控。
 *  3. 服务**随手开、随手关**：对话框一关（视图被摘掉）立刻停，端口不留残余。
 *
 * ---------------------------------------------------------------------------
 * 协议（刻意做到最简，只服务这一个表单）：
 *   GET  /            → 返回手机表单页（字段从 [start] 的 fields 回调现取，保证与电视端同步）
 *   POST /submit      → 表单提交，值回传给 onReceive；回一页"已推送到电视"
 *   GET  /favicon.ico → 204（免得手机浏览器反复请求刷屏）
 *   其它              → 404
 *
 * ⚠️ 这是**局域网内的明文 HTTP**，没有鉴权。定位是"家里电视和手机之间传一段文字"，
 *    不做任何敏感数据；因此刻意不引入 token/加密，免得把简单事做复杂。
 *    想更稳可以加一次性 token（见 [Session.token]），默认不启用。
 * ---------------------------------------------------------------------------
 */
object QrPushServer {

    private const val TAG = "扫码推送"

    /** 表单正文上限（防御性，正常表单就几百字节）。 */
    private const val MAX_BODY = 64 * 1024

    /** 优先占用的端口（好记、好输）；全被占了就退到系统随机端口。 */
    private val PREFERRED_PORTS = intArrayOf(8080, 8081, 8082, 8083, 8088, 8090)

    /**
     * 一个待填字段。
     *
     * [key] 表单名（回传时用它区分字段）、[label] 手机页上的标题、[hint] 输入提示、
     * [value] 手机页的**初始值** —— 由宿主现取自电视端此刻输入框里的内容，
     * 这样用户在手机上看到的就是"电视上已经填了什么"，可以直接改。
     */
    class Field(
        val key: String,
        val label: String,
        val hint: String = "",
        val value: String = "",
    )

    /** 一个已启动的会话。宿主必须在对话框关闭时调 [stop]。 */
    class Session internal constructor(
        /** 手机上要访问的地址，也就是二维码内容。 */
        val url: String,
        /** 局域网 IPv4（拿不到时为 null，此时 [url] 用 127.0.0.1 仅本机可达）。 */
        val host: String?,
        val port: Int,
        private val server: ServerSocket,
        private val worker: Thread,
    ) {
        @Volatile private var stopped = false

        val isRunning: Boolean get() = !stopped && !server.isClosed

        fun stop() {
            if (stopped) return
            stopped = true
            // close() 会让阻塞中的 accept() 抛 SocketException，worker 由此退出
            runCatching { server.close() }
            runCatching { worker.interrupt() }
            Log.i(TAG, "推送服务已停止（端口 $port）")
        }
    }

    /**
     * 启动服务。
     *
     * @param fields   每次手机打开表单页时现取一次字段（这样页面上显示的初始值 = 电视上此刻的值）
     * @param onSubmit 手机点「推送到电视」后回调，参数是 字段名 → 值（UTF-8 已解码）。
     *                 ⚠️ **在后台线程回调**，宿主需自己 post 回主线程再改 UI。
     * @return 启动失败（端口全占、无网络权限等）返回 null，调用方应降级为"不显示二维码"。
     */
    fun start(fields: () -> List<Field>, onSubmit: (Map<String, String>) -> Unit): Session? {
        val server = bind() ?: return null
        val port = server.localPort
        val host = detectLanIpv4()
        val url = "http://${host ?: "127.0.0.1"}:$port/"

        val worker = Thread({
            Log.i(TAG, "推送服务已启动：$url")
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    break                                   // 被 stop() 关掉 → 正常退出
                }
                try {
                    handle(client, fields, onSubmit)
                } catch (e: Exception) {
                    Log.w(TAG, "处理请求失败：${e.message}")
                } finally {
                    runCatching { client.close() }
                }
            }
        }, "qr-push-server")

        // 后台线程：不能阻塞 UI
        worker.isDaemon = true
        worker.start()

        return Session(url, host, port, server, worker)
    }

    // =========================================================================
    // 监听套接字
    // =========================================================================

    private fun bind(): ServerSocket? {
        for (p in PREFERRED_PORTS) {
            try {
                // 绑 0.0.0.0：手机要能从局域网连进来
                return ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p), 8) }
            } catch (e: Exception) {
                // 端口被占 —— 继续试下一个
            }
        }
        return try {
            // 全占 → 让系统随便给一个（URL 会不好记，但至少能用）
            ServerSocket(0)
        } catch (e: Exception) {
            Log.e(TAG, "无法启动推送服务：${e.message}")
            null
        }
    }

    /**
     * 取本机局域网 IPv4。
     *
     * 优先 wlan（手机/平板），其次 eth / en（盒子常见的有线网口就是 eth0）。
     * 过滤回环、IPv6 与 169.254 链路本地地址（那是"没拿到 DHCP"的兜底地址，手机连不上）。
     */
    private fun detectLanIpv4(): String? {
        var best: String? = null
        var fallback: String? = null
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement() ?: continue
                val up = runCatching { ni.isUp }.getOrDefault(false)
                if (!up || ni.isLoopback) continue
                val name = ni.name.orEmpty().lowercase()

                var addr: String? = null
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement() ?: continue
                    if (a.isLoopbackAddress) continue
                    val h = a.hostAddress ?: continue
                    if (h.contains(':')) continue                  // IPv6：二维码里放不下，也没必要
                    if (h.startsWith("169.254.")) continue         // 链路本地：手机到不了
                    addr = h
                    break
                }
                if (addr == null) continue

                if (name.contains("wlan") || name.contains("wifi") || name.contains("ap")) {
                    return addr                                     // 最理想，直接定
                }
                if (name.startsWith("eth") || name.startsWith("en")) {
                    best = best ?: addr
                }
                fallback = fallback ?: addr
            }
        } catch (e: Exception) {
            Log.w(TAG, "枚举网卡失败：${e.message}")
        }
        return best ?: fallback
    }

    // =========================================================================
    // 单条连接
    // =========================================================================

    private fun handle(client: Socket, fields: () -> List<Field>, onSubmit: (Map<String, String>) -> Unit) {
        runCatching { client.soTimeout = 8_000 }
        val input = client.getInputStream()
        val output = client.getOutputStream()

        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty().uppercase()
        val path = parts.getOrNull(1).orEmpty().substringBefore('?').ifBlank { "/" }

        // 读掉头部，顺便取 Content-Length
        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0 && line.substring(0, i).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(i + 1).trim().toIntOrNull() ?: 0
            }
        }

        val body = if (method == "POST" && contentLength > 0) {
            readFully(input, minOf(contentLength, MAX_BODY))
        } else {
            ByteArray(0)
        }

        when {
            method == "GET" && path == "/" -> {
                respond(output, "200 OK", formPage(hostOf(client), fields()))
            }

            method == "POST" && path == "/submit" -> {
                val values = parseForm(String(body, Charsets.UTF_8))
                Log.i(TAG, "收到推送：${values.entries.joinToString(" / ") { "${it.key}=${it.value}" }}")
                runCatching { onSubmit(values) }
                respond(output, "200 OK", donePage(values))
            }

            path == "/favicon.ico" -> respond(output, "204 No Content", "")

            else -> respond(output, "404 Not Found", errorPage())
        }
    }

    /** 表单页需要知道"用户是从哪个地址进来的"，好把回显地址写对（手机可能走 IP 也可能走主机名）。 */
    private fun hostOf(client: Socket): String =
        runCatching { client.localAddress.hostAddress.orEmpty() }.getOrDefault("")

    /** 读一行（以 \n 结束，容忍 \r\n）。返回 null = 对端已关闭。 */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder(96)
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 4096) return sb.toString()          // 防御超长行
        }
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(buf, off, length - off)
            if (n < 0) break
            off += n
        }
        return if (off == length) buf else buf.copyOf(off)
    }

    /** `a=1&b=%E4%B8%AD` → map（UTF-8 解码）。 */
    private fun parseForm(raw: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        if (raw.isBlank()) return map
        raw.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val i = pair.indexOf('=')
            val k = if (i < 0) pair else pair.substring(0, i)
            val v = if (i < 0) "" else pair.substring(i + 1)
            // 表单里 + 表示空格，URLDecoder 处理不了 ctx 为 null 的那种写法，统一先替换
            map[decode(k)] = decode(v.replace("+", " "))
        }
        return map
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    // =========================================================================
    // 响应
    // =========================================================================

    private fun respond(output: OutputStream, status: String, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store, must-revalidate\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        if (bytes.isNotEmpty()) output.write(bytes)
        output.flush()
    }

    // =========================================================================
    // 页面（配色对齐电视端深色 UI：surface #0E0E0E / container #1E1F20 / primary #A8C7FA）
    // =========================================================================

    /**
     * 手机页样式。配色**刻意对齐电视端**（surface #0E0E0E / container #1E1F20 /
     * primary #A8C7FA / onSurface #E3E3E3），扫完码从电视跳到手机视觉上是同一套 UI。
     */
    private val CSS = """
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;padding:18px;background:#0E0E0E;color:#E3E3E3;
 font:16px/1.6 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif}
.wrap{max-width:520px;margin:0 auto}
h1{margin:0 0 4px;font-size:19px;font-weight:600}
.sub{margin:0 0 18px;color:#C4C7C5;font-size:13px}
.card{background:#1E1F20;border-radius:14px;padding:16px;margin-bottom:14px}
label{display:block;font-size:13px;color:#C4C7C5;margin-bottom:6px}
input,textarea{width:100%;padding:12px;border-radius:10px;border:1px solid #444746;
 background:#0E0E0E;color:#E3E3E3;font-size:16px;font-family:inherit}
input:focus,textarea:focus{outline:none;border-color:#A8C7FA}
textarea{min-height:92px;resize:vertical;word-break:break-all}
button{width:100%;margin-top:16px;padding:15px;border:0;border-radius:10px;
 background:#A8C7FA;color:#101014;font-size:17px;font-weight:600;font-family:inherit}
button:active{opacity:.8}
.tip{margin-top:14px;color:#8E918F;font-size:12px;line-height:1.7;word-break:break-all}
.ok{display:flex;align-items:center;gap:10px;font-size:17px;font-weight:600}
.dot{width:10px;height:10px;border-radius:50%;background:#A8C7FA;flex:0 0 auto}
.echo{margin-top:10px;padding:10px 12px;border-radius:10px;background:#0E0E0E;
 color:#C4C7C5;font-size:13px;word-break:break-all}
""".trimIndent()

    private fun formPage(host: String, fields: List<Field>): String {
        val body = StringBuilder()
        fields.forEachIndexed { i, f ->
            body.append("<div class=\"card\">")
            body.append("<label for=\"f").append(i).append("\">").append(esc(f.label)).append("</label>")
            // 地址这类长文本用 textarea，手机上好粘贴、好看全
            val longText = f.hint.contains("地址") || f.hint.contains("URL") ||
                f.hint.contains("url") || f.value.length > 24
            if (longText) {
                body.append("<textarea id=\"f").append(i).append("\" name=\"").append(esc(f.key))
                    .append("\" placeholder=\"").append(esc(f.hint)).append("\" ")
                    .append("autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\">")
                    .append(esc(f.value)).append("</textarea>")
            } else {
                body.append("<input id=\"f").append(i).append("\" name=\"").append(esc(f.key))
                    .append("\" type=\"text\" value=\"").append(esc(f.value)).append("\" ")
                    .append("placeholder=\"").append(esc(f.hint)).append("\" ")
                    .append("autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\">")
            }
            body.append("</div>")
        }
        return page(
            title = "填写并推送到电视",
            inner = """
<h1>填写并推送到电视</h1>
<p class="sub">填好下面内容，点底部按钮即可隔空填到电视上</p>
<form method="post" action="/submit">
$body
<button type="submit">推送到电视</button>
</form>
<p class="tip">电视上「添加直播源 / 添加节目单」等输入框都会自动收到这里填的内容。<br>
页面地址：${esc(host)}</p>
"""
        )
    }

    private fun donePage(values: Map<String, String>): String {
        val echo = if (values.isEmpty()) {
            "（内容为空）"
        } else {
            values.entries.joinToString("<br>") { "${esc(it.key)}：${esc(it.value.ifBlank { "（空）" })}" }
        }
        return page(
            title = "已推送到电视",
            inner = """
<h1>已推送到电视</h1>
<div class="card">
  <div class="ok"><span class="dot"></span>推送成功</div>
  <div class="echo">$echo</div>
</div>
<p class="tip">回到电视上确认后点「确定 / 保存」即可。<br>
如果还要改，直接返回上一页重新填一次。</p>
"""
        )
    }

    private fun errorPage(): String = page(
        title = "页面不存在",
        inner = """
<h1>页面不存在</h1>
<p class="sub">请扫电视上的二维码重新进入。</p>
"""
    )

    private fun page(title: String, inner: String): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="format-detection" content="telephone=no">
<title>${esc(title)} · MyTV</title>
<style>$CSS</style>
</head>
<body><div class="wrap">$inner</div></body>
</html>
"""

    /** HTML 转义（表单值可能来自电视端的任意文本）。 */
    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
