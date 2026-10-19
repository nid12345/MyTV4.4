package top.yogiczy.mytv.legacy.data

import android.content.Context
import android.util.Log
import top.yogiczy.mytv.legacy.BuildConfigCompat
import top.yogiczy.mytv.legacy.net.Http

/**
 * 崩溃上报（v1.19 改版：自建 Cloudflare Worker，v1.18 的腾讯 Bugly 方案已整份删除）。
 *
 * ===========================================================================
 * 为什么换掉 Bugly（用户原话）
 * ===========================================================================
 * 「你搞的这个腾讯 bugly 不喜欢用，把这部分的崩溃上传的代码删掉重新做新的」
 *
 * 新方案（用户指定）：
 *   · 后端是 **Cloudflare Worker**，绑用户自己的域名 **mytvbug.nide.qzz.io**；
 *   · 崩溃记录直接 POST 到 Worker，存在 Worker 侧（D1 / KV）；
 *   · 以后要修 bug，打开 https://mytvbug.nide.qzz.io 就能看全部崩溃记录。
 *
 * 好处：完全自持、没有第三方 SDK 依赖、APK 体积回到 Bugly 之前；
 * 免 Token（电视端零配置）、国内直连 Cloudflare 边缘。
 *
 * ===========================================================================
 * 配置（开发者一次，编译期常量，电视端不用操作）
 * ===========================================================================
 * 1) 部署 Worker：`wrangler deploy`（见工程根目录 `mytv-bug-report/`，含 README 与 schema）；
 * 2) 绑定自定义域 `mytvbug.nide.qzz.io`（`custom_domain = true`，CF 会自动建 CNAME 并签证书）；
 * 3) 把 [CRASH_UPLOAD_URL] 改成 `https://mytvbug.nide.qzz.io/report`；
 * 4) 后台查看地址 [ADMIN_URL] = `https://mytvbug.nide.qzz.io/admin`（Basic Auth，
 *    账号密码是 Worker secret，只有你自己知道）。
 *
 * [CRASH_UPLOAD_URL] 留空 = 不上报（本地崩溃记录仍然照常保留，可以复制）。
 *
 * ===========================================================================
 * 隐私与鉴权
 * ===========================================================================
 * 上报的只有 CrashStore 里那段纯文本：机型 / Android 版本 / App 版本 / 崩溃堆栈。
 * 不含 IMEI、不含账号、不含任何系统标识。请求带一个共享密钥头 [CRASH_UPLOAD_TOKEN]，
 * 用来挡扫描器与误触，不构成"用户凭证"。
 *
 * ⚠️ v1.22 修正一处**已不成立**的旧注释：本仓库 github.com/nid12345/MyTV4.4 是
 * **公开仓库**，所以 [CRASH_UPLOAD_TOKEN] 随源码一起公开，APK 里也能反编译出来。
 * 它只当"限流 / 挡扫描器"用，**不要**把它当鉴权手段，也不要往里放敏感信息。
 * 真要收紧，得改成设备级签名或服务端二次校验。
 */
object CrashReporter {

    /** 自建 Cloudflare Worker 的上报端点（v1.19：mytvbug.nide.qzz.io）。 */
    const val CRASH_UPLOAD_URL = "https://mytvbug.nide.qzz.io/report"

    /** 崩溃记录查看后台（同一个域名，Basic Auth）。 */
    const val ADMIN_URL = "https://mytvbug.nide.qzz.io/admin"

    /**
     * 共享密钥（与 Worker secret `REPORT_TOKEN` 一致）。
     *
     * 部署 Worker 时用 `wrangler secret put REPORT_TOKEN` 设置；
     * 两边一致即可，可随时在 CF 后台改后同步改这里重新出包。
     */
    const val CRASH_UPLOAD_TOKEN = "mytv-bug-report-2026"

    private const val TAG = "崩溃上报"

    /** 上报通道是否可用（URL 非空）。 */
    val configured: Boolean get() = CRASH_UPLOAD_URL.isNotBlank()

    /** 上报结果。[ok] 为 false 时 [message] 是可直接展示给用户的中文说明。 */
    class Result(val ok: Boolean, val message: String)

    /**
     * 「一键反馈」：把本地存下来的那条崩溃 POST 到自建 Worker。
     *
     * 与 v1.18 的 Bugly 不同，这里**没有 SDK 自动采集** —— 崩溃记录由
     * `App.kt` 里的本地 UncaughtExceptionHandler 落盘（照常工作），
     * 用户点一次「一键反馈」才上传；成功后本地标记 ack，不会重复打扰。
     * （启动时的自动补报见 `MainActivity.maybeShowCrashReport`。）
     *
     * 走 `net/Http.postRaw` —— 它统一带着 v1.17 补的现代根证书与强制 TLS 1.2，
     * Android 4.4 上打 Cloudflare 不会因为老 CA 库缺锚点而失败。
     */
    fun upload(ctx: Context, recordText: String): Result {
        if (!configured) {
            return Result(
                false,
                "还没有配置崩溃上报地址（CrashReporter.CRASH_UPLOAD_URL 为空）。\n\n" +
                    "在此之前可以先点「复制记录」，把这条堆栈发给开发者。"
            )
        }
        return runCatching {
            val (code, body) = Http.postRaw(
                url = CRASH_UPLOAD_URL,
                body = recordText,
                contentType = "text/plain; charset=utf-8",
                extraHeaders = mapOf(
                    "X-Crash-Token" to CRASH_UPLOAD_TOKEN,
                    "X-App-Version" to BuildConfigCompat.VERSION_NAME,
                    "X-Device-Model" to android.os.Build.MODEL,
                ),
            )
            if (code in 200..299) {
                Result(
                    true,
                    "已提交到崩溃记录后台。\n\n" +
                        "查看位置：$ADMIN_URL\n" +
                        "（同一条崩溃会按堆栈指纹合并，能直接看到复现次数）\n\n" +
                        "版本：${BuildConfigCompat.VERSION_NAME}"
                )
            } else {
                Result(false, "上报失败：服务端返回 HTTP $code\n${body.take(200)}\n可先「复制记录」发给开发者。")
            }
        }.getOrElse {
            Log.w(TAG, "上报异常：${it.javaClass.simpleName} ${it.message ?: ""}")
            Result(
                false,
                "上报失败：${it.javaClass.simpleName}${it.message?.let { m -> "（$m）" } ?: ""}\n可先「复制记录」发给开发者。"
            )
        }
    }
}
