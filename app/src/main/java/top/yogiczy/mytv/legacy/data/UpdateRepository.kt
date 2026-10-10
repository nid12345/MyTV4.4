package top.yogiczy.mytv.legacy.data

import android.content.Context
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

    /** ghproxy 类加速前缀（按可用性排序；国内直连 raw.githubusercontent 常年不稳）。 */
    private val GH_PROXIES = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
    )

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
        for (url in manifestUrls(line)) {
            val text = runCatching { Http.getText(url, client(15_000L, 20_000L)) }.getOrElse {
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
        val dir = File(ctx.getExternalFilesDir(null), "update").apply { mkdirs() }
        val target = File(dir, "MyTV4.4-${info.versionName}.apk")
        // 已经下好且校验通过就直接复用，避免重复下载
        if (target.exists() && target.length() > 0 && matchesMd5(target, info.md5)) {
            Log.i(TAG, "已存在校验通过的安装包：${target.name}")
            return target to null
        }

        val errors = ArrayList<String>()
        var cancelled = false
        for (url in downloadUrls(info.url, line)) {
            val ok = runCatching {
                Http.client(connectTimeoutMs = 20_000L, readTimeoutMs = 120_000L, userAgent = PlayerController.UA)
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
            else -> listOf(raw) + proxied + listOf(jsdelivr)
        }
    }

    /** 安装包 URL 候选。jsdelivr 不代理 Release 附件，所以只做 raw 与 ghproxy。 */
    private fun downloadUrls(url: String, line: String): List<String> {
        if (url.isBlank()) return emptyList()
        val proxied = GH_PROXIES.map { it + url }
        return when (line) {
            "ghproxy" -> proxied + url
            else -> listOf(url) + proxied
        }
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
