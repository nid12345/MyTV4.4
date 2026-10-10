package top.yogiczy.mytv.legacy.data

import android.content.Context
import android.os.Environment
import android.util.Log
import okhttp3.Request
import org.json.JSONObject
import top.yogiczy.mytv.legacy.BuildConfigCompat
import top.yogiczy.mytv.legacy.net.Http
import top.yogiczy.mytv.legacy.player.PlayerController
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 应用升级通道（v1.6 把此前推迟的 M11/M12 补齐）。
 *
 * ---------------------------------------------------------------------------
 * 通道设计
 * ---------------------------------------------------------------------------
 * 分发走 **GitHub**：
 *  - 版本清单：仓库里的 `update/version.json`（raw / jsdelivr / ghproxy 三条线路可切）；
 *  - 安装包：GitHub Releases 上的 `MyTV4.4-v*.apk`。
 *
 * 清单格式：
 * ```json
 * {
 *   "versionCode": 11,
 *   "versionName": "1.6-legacy.11",
 *   "url": "https://github.com/nid12345/MyTV4.4/releases/download/v1.6/MyTV4.4-v1.6-legacy11.apk",
 *   "size": 6900000,
 *   "md5": "……（可空）",
 *   "notes": "更新说明，可多行"
 * }
 * ```
 *
 * 之所以用「静态 JSON + Release 附件」而不是 GitHub API：API 有匿名调用频次限制，
 * 而电视机顶盒常年不关、会反复检查更新，用静 API 更稳（原版 1.7 也是走静态清单）。
 *
 * 本文件所有方法都是**同步阻塞**的，必须在后台线程调用（本工程不引协程）。
 * ---------------------------------------------------------------------------
 */
object UpdateRepository {

    private const val TAG = "检查更新"

    private const val OWNER = "nid12345"
    private const val REPO = "MyTV4.4"
    private const val BRANCH = "main"
    private const val MANIFEST_PATH = "update/version.json"

    /**
     * v1.8：更新线路**只有一条「自动」**。
     *
     * 之前设置页里有「下载线路 auto / ghproxy / jsdelivr」三档让用户选，
     * 用户反馈"更新通道怎么有三个？" —— 那三档本来就是**同一条链路上不同顺序的候选**，
     * 交给用户选没有意义：[check] 与 [download] 本来就会**按序逐个试，谁通走谁**。
     * 现在固定用 AUTO，设置项删除，行为不变（依然是"自动挑能用的那个"）。
     */
    const val LINE_AUTO = "auto"

    /**
     * ghproxy 类加速前缀。
     *
     * v1.11（用户需求 3b「让国内网络能下载到」）**实测重排**。
     * 本机 2MB range 实测（2026-10-10，含 302 跟随）：
     * ```
     *   gh.llkk.cc            2.5 MB/s   ✅ 留
     *   gh-proxy.com          2.3 MB/s   ✅ 留
     *   ghfast.top            1.8 MB/s   ✅ 留
     *   edgeone.gh-proxy.com  1.5 MB/s   ✅ 留（Cloudflare EdgeOne，延迟稳）
     *   ghproxy.net           0.26 MB/s  ✅ 留（慢但通）
     *   gitproxy.click / github.moeyy.xyz / ghproxy.cc / ghp.ci / gh.xxooo.cf /
     *   gh.jasonzeng.dev / hub.gitmirror.com                            ❌ 已下线，移除
     * ```
     * 注意：这里**只影响失败后的候选顺序**，真正决定用哪条的是 [firstAlive] 的并发探测。
     */
    private val GH_PROXIES = listOf(
        "https://gh.llkk.cc/",
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://edgeone.gh-proxy.com/",
        "https://ghproxy.net/",
    )

    /** 探测单条线路：只取前 1KB。国内直连 GitHub 会在这 9 秒内被快速判死，不再让用户干等。 */
    private const val PROBE_CONNECT_MS = 4_000L
    private const val PROBE_READ_MS = 5_000L

    /** 并发探测的总等待上限（按最慢的一条算：4s 连接 + 5s 读 + 余量）。 */
    private const val PROBE_TOTAL_WAIT_MS = 12_000L

    /** 真正下载：连接 10s；读 20s —— 只要还有字节在流动就不会触发（慢速下载不受影响）。 */
    private const val DL_CONNECT_MS = 10_000L
    private const val DL_READ_MS = 20_000L

    /** 远端版本信息。 */
    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val sizeBytes: Long,
        val md5: String?,
        val notes: String,
    ) {
        /** 相对当前安装版本是否更新。 */
        fun isNewerThanCurrent(): Boolean = versionCode > BuildConfigCompat.VERSION_CODE
    }

    /**
     * 检查更新。
     *
     * @param line 下载线路偏好（`auto` / `ghproxy` / `jsdelivr`，来自设置页）
     * @return (版本信息, 错误原因)。两者只会有一个非空。
     */
    fun check(line: String): Pair<UpdateInfo?, String?> {
        val errors = ArrayList<String>()
        // v1.11：清单同样先并发探测挑线路，避免国内网络先卡一次 raw.githubusercontent 的超时。
        for (url in orderByProbe(manifestUrls(line))) {
            val text = runCatching { Http.getText(url, client(8_000L, 12_000L)) }.getOrElse {
                errors.add("${hostOf(url)}：${it.message ?: it.javaClass.simpleName}")
                Log.w(TAG, "清单获取失败 $url -> ${it.message}")
                return@getOrElse ""
            }
            if (text.isBlank()) continue
            val parsed = runCatching { parseManifest(text) }.getOrElse {
                errors.add("${hostOf(url)}：清单格式错误（${it.javaClass.simpleName}）")
                return@getOrElse null
            }
            if (parsed != null) {
                Log.i(TAG, "清单就绪：远端 ${parsed.versionName}(${parsed.versionCode}) 本地 ${BuildConfigCompat.VERSION_NAME}(${BuildConfigCompat.VERSION_CODE})")
                return parsed to null
            }
        }
        return null to errors.joinToString("；").ifBlank { "所有更新线路都不可用" }
    }

    /**
     * 解析一个**真能写**的下载目录。
     *
     * ---------------------------------------------------------------------------
     * v1.12 需求 5（小米电视更新失败修复）
     * ---------------------------------------------------------------------------
     * 真机反馈：小米电视上点「立即更新」失败，底部提示
     * `无法创建下载目录：/storage/emulated/0/Android/data/top.yogiczy.mytv.legacy.debug/files/update`。
     *
     * 老代码（v1.8）只做了一次尝试：
     * ```
     * val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
     * val dir = File(base, "update")
     * if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return null to "无法创建下载目录：…"
     * ```
     * 于是在小米这类对 `Android/data/<pkg>/files` 有额外策略的 ROM 上，
     * `mkdirs()` 失败 → 更新通道**彻底不可用**，而且报错文案还是开发口径的绝对路径。
     *
     * 现在改成两件事：
     *   ① **按顺序试多个候选目录**，第一个"真的能写"的胜出（不再一失败就放弃）；
     *   ② 判据不是 `mkdirs()` 的返回值，而是**实打实写一个探针文件再删掉**——
     *      `mkdirs()` 会骗人（返回 true 或目录已存在，写入仍抛 EACCES），写入不会。
     *
     * 候选顺序（越靠前位置越"标准"）：
     *   1. `getExternalFilesDir(null)` → `/sdcard/Android/data/<pkg>/files/update`（标准位置，API 19 免权限）
     *   2. `Environment.getExternalStorageDirectory()` → 同上路径手工拼
     *      （少数 ROM 的 `getExternalFilesDir` 返回 null，但真实路径其实可写）
     *   3. `ctx.filesDir` → `/data/data/<pkg>/files/update`（内部私有，**必然可写**，兜底中的兜底）
     *   4. `ctx.externalCacheDir`、5. `ctx.cacheDir`（缓存目录兜底）
     *
     * ⚠️ 落到 **3/5（内部目录）** 时，API < 24 的 `file://` 安装需要文件可被安装器读取，
     * 这一步由 [top.yogiczy.mytv.legacy.MainActivity.installApk] 里的 `setReadable` 负责。
     *
     * @return (目录, 失败原因)。目录为 null 时，第二个值是**每个候选各自的失败原因**。
     */
    private fun resolveDownloadDir(ctx: Context): Pair<File?, String> {
        val candidates = LinkedHashMap<String, File>()
        runCatching { ctx.getExternalFilesDir(null) }.getOrNull()?.let {
            candidates["外部私有目录"] = File(it, "update")
        }
        runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let {
            candidates["外部存储根"] = File(it, "Android/data/${ctx.packageName}/files/update")
        }
        candidates["应用内部目录"] = File(ctx.filesDir, "update")
        runCatching { ctx.externalCacheDir }.getOrNull()?.let {
            candidates["外部缓存目录"] = File(it, "update")
        }
        candidates["内部缓存目录"] = File(ctx.cacheDir, "update")

        val errors = ArrayList<String>()
        for ((label, dir) in candidates) {
            val why = probeWritable(dir)
            if (why == null) {
                Log.i(TAG, "下载目录就绪（$label）：${dir.absolutePath}")
                return dir to ""
            }
            errors.add("$label(${dir.absolutePath})：$why")
            Log.w(TAG, "下载目录不可用（$label）${dir.absolutePath} -> $why")
        }
        return null to errors.joinToString("；")
    }

    /**
     * 探针：建目录 → 真写 1 个字节 → 删掉。返回 null 表示可用，否则返回失败原因。
     *
     * 之所以不能只看 `mkdirs()`：小米电视对 `Android/data/<pkg>/files` 的策略是
     * "目录建得出来、文件写不进去"，只看返回值会误判为可用，
     * 于是下载走到 `FileOutputStream` 才炸 —— 错误信息也更难懂。
     */
    private fun probeWritable(dir: File): String? {
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return "无法创建目录"
        if (!dir.isDirectory) return "同名路径不是目录"
        val probe = File(dir, ".wprobe")
        return try {
            FileOutputStream(probe).use { it.write(0) }
            probe.delete()
            null
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /**
     * 下载安装包。
     *
     * @param onProgress 形参为 0f..1f（-1 表示总长度未知），回调在**后台线程**；
     *        返回 `false` 表示用户取消 —— 立即中止下载，本函数返回 `(null, "已取消")`。
     * @return (文件, 错误原因)
     */
    fun download(
        ctx: Context,
        info: UpdateInfo,
        line: String,
        onProgress: (Float) -> Boolean,
    ): Pair<File?, String?> {
        // v1.8 修复（真机/模拟器实测）：老机顶盒经常没有可用的外部存储，
        // `getExternalFilesDir(null)` 返回 null，于是 `File(null, "update")` 退化成**相对路径**
        // `update`，最终解析成 `/update` —— mkdirs 必然失败，下载直接
        // `open failed: ENOENT (/update/MyTV4.4-x.apk.part)`。
        // 这会让「检查更新」能弹框、点「立即更新」却永远下载失败（用户反馈的"点了没反应"）。
        // v1.12 需求 5：单次尝试 → [resolveDownloadDir] 的多候选 + 写入探针。
        // ⚠️ 必须分两句写：`why` 若出现在**同一条**解构声明的 elvis 分支里，
        // Kotlin 还没把它声明出来，会报 "Unresolved reference: why"。
        val (dirOrNull, why) = resolveDownloadDir(ctx)
        val dir = dirOrNull ?: return null to "无法找到可写的下载目录（已尝试：$why）"
        val target = File(dir, "MyTV4.4-${info.versionName}.apk")
        // 已经下好且校验通过就直接复用，避免重复下载
        if (target.exists() && target.length() > 0 && matchesMd5(target, info.md5)) {
            Log.i(TAG, "已存在校验通过的安装包：${target.name}")
            return target to null
        }

        val errors = ArrayList<String>()
        var cancelled = false
        // v1.11（用户需求 3b）：先用并发探测挑出**当前网络下真正能用**的那条线路。
        // 国内直连 GitHub Releases 的 `objects.githubusercontent.com` 常年不通，
        // 老代码把直连排第一、连接超时 20s / 读超时 120s —— 用户点完「立即更新」
        // 要干等两分钟才等到一句失败，体感就是"下载不了"。探测把这段等待压到 1~2 秒。
        val candidates = orderByProbe(downloadUrls(info.url, line))
        for (url in candidates) {
            val ok = runCatching {
                Http.client(connectTimeoutMs = DL_CONNECT_MS, readTimeoutMs = DL_READ_MS, userAgent = PlayerController.UA)
                    .newCall(Request.Builder().url(url).header("User-Agent", PlayerController.UA).build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) throw Http.HttpError("HTTP ${resp.code()} ${resp.message()}")
                        val body = resp.body() ?: throw Http.HttpError("响应为空")
                        val total = body.contentLength()
                        val tmp = File(dir, target.name + ".part")
                        FileOutputStream(tmp).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var read = 0L
                            while (true) {
                                val n = body.byteStream().read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                read += n
                                if (!onProgress(if (total > 0) (read.toFloat() / total) else -1f)) {
                                    cancelled = true
                                    break
                                }
                            }
                        }
                        if (cancelled) {
                            tmp.delete()
                            throw Http.HttpError("已取消")
                        }
                        if (tmp.length() <= 0L) throw Http.HttpError("下载内容为空")
                        if (target.exists()) target.delete()
                        if (!tmp.renameTo(target)) throw Http.HttpError("写入安装包失败")
                    }
            }
            if (ok.isSuccess) {
                if (!matchesMd5(target, info.md5)) {
                    errors.add("${hostOf(url)}：校验失败（MD5 不匹配）")
                    target.delete()
                    continue
                }
                onProgress(1f)
                Log.i(TAG, "下载完成：${target.absolutePath} (${target.length()} 字节)")
                return target to null
            }
            if (cancelled) return null to "已取消"
            errors.add("${hostOf(url)}：${ok.exceptionOrNull()?.message ?: "下载失败"}")
            Log.w(TAG, "下载失败 $url -> ${ok.exceptionOrNull()?.message}")
        }
        return null to errors.joinToString("；").ifBlank { "所有下载线路都不可用" }
    }

    // ---------------- URL 组装 ----------------

    /** 清单 URL 候选（按下载线路偏好排序）。 */
    private fun manifestUrls(line: String): List<String> {
        val raw = "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/$MANIFEST_PATH"
        val jsdelivr = "https://cdn.jsdelivr.net/gh/$OWNER/$REPO@$BRANCH/$MANIFEST_PATH"
        val proxied = GH_PROXIES.map { it + raw }
        return when (line) {
            "jsdelivr" -> listOf(jsdelivr, raw) + proxied
            "ghproxy" -> proxied + listOf(raw, jsdelivr)
            // v1.11：AUTO 的顺序改为「镜像 → jsDelivr → 直连 raw」。
            // 国内 raw.githubusercontent.com 基本不通，原来把它排第一，
            // 每次检查更新都要先耗掉一次连接超时。
            else -> proxied + listOf(jsdelivr, raw)
        }
    }

    /**
     * 安装包 URL 候选。
     *
     * v1.11：镜像排在 GitHub 直连**前面**。jsdelivr 不代理 Release 附件，所以只有镜像与直连两类。
     * 真正决定用哪条的是 [firstAlive]（并发探测、先响应者胜），这里的顺序只是探测失败后的兜底顺序。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun downloadUrls(url: String, line: String): List<String> {
        if (url.isBlank()) return emptyList()
        // 已无「线路偏好」之分（设置页那三档在 v1.8 就删了）：镜像优先、直连兜底。
        return GH_PROXIES.map { it + url } + url
    }

    /**
     * 并发探测所有候选线路，返回**最先响应**的那条（都没有则 null）。
     *
     * 为什么要并发而不是逐个串行：串行时"某条线路不通"要等满 connect 超时才轮到下一条，
     * 5 条镜像最坏就是 5×4s=20s 的纯等待。并发后总耗时 = 最快那条的响应时间（通常 1~2s），
     * 而且天然挑中了当前网络下最快能连上的那条。
     *
     * 探测本身只请求 `Range: bytes=0-1023`，1KB 就够判断"这条路能不能走通"。
     */
    private fun firstAlive(urls: List<String>): String? {
        if (urls.isEmpty()) return null
        val done = java.util.concurrent.CountDownLatch(1)
        val winner = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val threads = urls.map { u ->
            Thread {
                // 已经有赢家了就不必再探（count 降到 0）
                if (done.count > 0L && probe(u) && winner.compareAndSet(null, u)) {
                    done.countDown()
                }
            }.apply { isDaemon = true }
        }
        threads.forEach { it.start() }
        runCatching { done.await(PROBE_TOTAL_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
        return winner.get()
    }

    /**
     * 单条线路探测：短超时取前 1KB，能拿到数据即算通。
     *
     * 403/404 也算"通"（说明链路通了、只是这个 URL 没内容），此时不该把它排到最前 —— 所以
     * 非 2xx 一律判失败，让别的线路顶上。
     */
    private fun probe(url: String): Boolean = runCatching {
        Http.client(PROBE_CONNECT_MS, PROBE_READ_MS, PlayerController.UA)
            .newCall(
                Request.Builder().url(url)
                    .header("User-Agent", PlayerController.UA)
                    .header("Range", "bytes=0-1023")
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) throw Http.HttpError("HTTP ${resp.code()}")
                val body = resp.body() ?: throw Http.HttpError("响应为空")
                val n = body.byteStream().read(ByteArray(16))
                if (n > 0) true else body.contentLength() > 0L
            }
    }.getOrElse {
        Log.w(TAG, "探测失败 ${hostOf(url)} -> ${it.message ?: it.javaClass.simpleName}")
        false
    }

    /**
     * 把探测到的最优线路排到最前，其余保持原顺序作为兜底。
     *
     * 探测**全军覆没**时原样返回：真正的下载循环超时更长（10s/20s），
     * 能接住"探测被误杀、实际能下"的情况，不至于直接判定失败。
     */
    private fun orderByProbe(urls: List<String>): List<String> {
        val best = firstAlive(urls) ?: return urls
        Log.i(TAG, "线路探测：优先使用 ${hostOf(best)}")
        return listOf(best) + urls.filter { it != best }
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

    /** 清单解析。字段缺失按"缺失即非法"处理，避免把半截文件当成新版本。 */
    private fun parseManifest(text: String): UpdateInfo? {
        val o = JSONObject(text)
        val code = o.optInt("versionCode", -1)
        val name = o.optString("versionName").ifBlank { "未知版本" }
        val url = o.optString("url")
        if (code <= 0 || url.isBlank()) return null
        return UpdateInfo(
            versionCode = code,
            versionName = name,
            url = url,
            sizeBytes = o.optLong("size", 0L),
            md5 = o.optString("md5").takeIf { it.isNotBlank() },
            notes = o.optString("notes").takeIf { it.isNotBlank() } ?: "（未提供更新说明）",
        )
    }

    private fun client(connectMs: Long, readMs: Long) = Http.client(
        connectTimeoutMs = connectMs,
        readTimeoutMs = readMs,
        userAgent = PlayerController.UA,
    )

    /** MD5 校验；[expected] 为空时直接通过。 */
    private fun matchesMd5(file: File, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return true
        return runCatching {
            val md = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
        }.getOrDefault(false)
    }

    /** 供 UI 显示「约 6.8 MB」。 */
    fun humanSize(bytes: Long): String =
        if (bytes <= 0L) "大小未知" else "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
