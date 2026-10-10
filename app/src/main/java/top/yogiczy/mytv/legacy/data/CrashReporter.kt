package top.yogiczy.mytv.legacy.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.tencent.bugly.crashreport.CrashReport
import top.yogiczy.mytv.legacy.BuildConfigCompat

/**
 * 崩溃上报（v1.18 改版：国内免费、免 Token）。
 *
 * ===========================================================================
 * 为什么换掉原来的 GitHub 方案（用户原话）
 * ===========================================================================
 * 「当前问题：现有崩溃上报需要用户填写 GitHub Token，门槛高且国内网络不稳定。
 *   新需求：移除 Token 依赖，改为国内免费、免 Token、点击直接上传的方案。」
 *
 * 原实现要用户在电视上用遥控器/扫码填一个 fine-grained PAT，还要能连上 api.github.com ——
 * 门槛高、国内网络也常抽风。现在换成**腾讯 Bugly**：
 *
 *   · 国内直连、免费、有网页后台（bugly.qq.com → 崩溃分析），堆栈/机型/复现次数都有；
 *   · SDK **自动**采集 Java / Native / ANR 崩溃并上报 —— 崩了不用点任何按钮就已经在后台了；
 *   · 不需要 Token、不需要用户在任何界面填东西；
 *   · minSdk 15（读 AAR 内 AndroidManifest 实测），**兼容本工程的 API 19**；
 *   · POM 无传递依赖，不会把整套 AndroidX 拖进来。
 *
 * ===========================================================================
 * App ID 填在哪里（唯一的一处配置，且不用在电视上操作）
 * ===========================================================================
 * 见下面 [BUGLY_APP_ID] / [BUGLY_APP_KEY]。申请步骤：
 *   1. 打开 https://bugly.qq.com/v2/index 用 QQ 登录；
 *   2. 「产品管理 → 创建产品」→ 产品类型选 **Android** → 填应用名（如 MyTV4.4）、
 *      包名必须是 `top.yogiczy.mytv.legacy`（正式包）；
 *   3. 创建完成后页面会给一串 **App ID**（形如 `a1b2c3d4e5`），把它填到 [BUGLY_APP_ID]；
 *      同时会有一个 **App Key**，填进 [BUGLY_APP_KEY]（SDK 本身不用，留给将来接 HTTP 上报用）。
 * 填完重新编译安装即可，**电视端不需要任何操作**。
 *
 * ⚠️ 留空时本类**完全不初始化 SDK**：不会打错误日志、不会起后台线程，
 * App 内的"崩溃记录 + 一键反馈"按钮仍然可用（会提示如何配置 + 提供复制）。
 *
 * ===========================================================================
 * 隐私
 * ===========================================================================
 * Bugly 的 AAR **不自带任何权限声明**（实测其 AndroidManifest 只有 uses-sdk），
 * 因此不会往合并后的清单里塞权限 —— 无 READ_PHONE_STATE 时它取不到 IMEI，
 * 会自动退化成其它设备标识。本工程也不额外申请任何权限。
 */
object CrashReporter {

    /**
     * Bugly App ID（**唯一需要填的一处**）。留空 = 不启用 SDK。
     *
     * 例：`const val BUGLY_APP_ID = "a1b2c3d4e5"`
     */
    const val BUGLY_APP_ID = ""

    /**
     * Bugly App Key。SDK 本身初始化**不需要**它（只用 App ID），
     * 这里留出来是为了将来若要走 Bugly 的 HTTP 上报接口时随手可用。
     */
    const val BUGLY_APP_KEY = ""

    /**
     * 另一个（同样免 Token 的）通道：**免鉴权 HTTP 接口直接 POST**（用户给的备选方案）。
     *
     * 留空 = 不用。填上一个能收 POST 的国内地址（自己搭的、或任何免鉴权的收集服务）即可，
     * 请求体就是崩溃正文（`text/plain`），附两个头：`X-App-Version` / `X-Device-Model`。
     *
     * 优先级：**Bugly App ID 优先**；只有它留空时才走这里。
     * 两条都留空 → 一键反馈会给出"怎么配置"的说明，并提示先「复制记录」。
     */
    const val CRASH_UPLOAD_URL = ""

    private const val TAG = "崩溃上报"

    /** 有没有一条可用通道（两条里有一条就行）。 */
    val configured: Boolean get() =
        BUGLY_APP_ID.isNotBlank() || CRASH_UPLOAD_URL.isNotBlank()

    /** 上报结果。[ok] 为 false 时 [message] 是可直接展示给用户的中文说明。 */
    class Result(val ok: Boolean, val message: String)

    /**
     * 初始化 SDK（由 `App.onCreate` 调用，必须早于任何业务代码 —— 崩溃可能发生在启动阶段）。
     *
     * 没配 App ID 时直接跳过：Bugly 未初始化时会往日志里刷"appId is empty"这类错误，
     * 而本工程的真机排障全靠 logcat，不能被这种噪音淹掉。
     */
    fun init(ctx: Context) {
        if (BUGLY_APP_ID.isBlank()) {
            Log.i(
                TAG,
                "未配置 Bugly App ID：跳过 SDK 初始化（崩溃仍会本地留档，可在应用内查看/复制）"
            )
            return
        }
        val debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        runCatching { CrashReport.initCrashReport(ctx, BUGLY_APP_ID, debuggable) }
            .onSuccess { Log.i(TAG, "Bugly 已初始化：AppID=$BUGLY_APP_ID debug=$debuggable 版本=${BuildConfigCompat.VERSION_NAME}") }
            .onFailure { Log.w(TAG, "Bugly 初始化失败：${it.javaClass.simpleName} ${it.message ?: ""}") }
    }

    /**
     * 「一键反馈」：把本地存下来的那条崩溃**当作一次已捕获异常**上报给 Bugly。
     *
     * 为什么用 `postCatchedException(Throwable)`：这是 Bugly 公开 API 里唯一
     * "把一段异常交上去"的入口（它走的是与真实崩溃同一条上报通道，后台能看到完整堆栈）。
     * 我们按崩溃记录里的 `at ...` 行**还原成 StackTraceElement**，于是后台看到的
     * 帧信息与真实崩溃一致（文件名 + 行号），而不是一串挤在 message 里的纯文本。
     *
     * 说明：这是**异步**上报 —— 调用返回只代表"已交给 SDK 队列"，不是"服务器已收到"。
     * 所以成功文案写的是"已提交/稍后可在后台看到"，不打包票。
     */
    fun upload(ctx: Context, recordText: String): Result {
        if (!configured) {
            return Result(
                false,
                "还没有配置崩溃上报服务（代码里两个常量都留着空：\n" +
                    "CrashReporter.BUGLY_APP_ID 或 CrashReporter.CRASH_UPLOAD_URL）。\n\n" +
                    "这是在电脑上做一次的事，电视端不需要任何操作：\n" +
                    "· 腾讯 Bugly（推荐，国内直连、有后台）：\n" +
                    "  1) bugly.qq.com 用 QQ 登录 → 创建产品 → Android\n" +
                    "  2) 包名填 top.yogiczy.mytv.legacy\n" +
                    "  3) 把 App ID 填进 CrashReporter.BUGLY_APP_ID\n" +
                    "· 或者填任意一个能收 POST 的国内地址到 CRASH_UPLOAD_URL\n\n" +
                    "在此之前可以先点「复制记录」，把这条堆栈直接发给开发者。",
            )
        }
        if (BUGLY_APP_ID.isNotBlank()) return uploadToBugly(recordText)
        return uploadByHttp(recordText)
    }

    /** 通道一：腾讯 Bugly。 */
    private fun uploadToBugly(recordText: String): Result = runCatching {
        CrashReport.postCatchedException(toThrowable(recordText))
        Result(
            true,
            "已提交到腾讯 Bugly。\n\n" +
                "查看位置：bugly.qq.com → 选「MyTV4.4」→ 崩溃分析\n" +
                "（上报是异步的，通常 1 分钟内出现；同一条崩溃会按「异常类型 + 版本」聚合，\n" +
                "能直接看到复现次数）\n\n版本：${BuildConfigCompat.VERSION_NAME}",
        )
    }.getOrElse {
        Result(
            false,
            "上报失败：${it.javaClass.simpleName}${it.message?.let { m -> "（$m）" } ?: ""}\n可先「复制记录」发给开发者。"
        )
    }

    /**
     * 通道二：免鉴权 HTTP 直传。
     *
     * 走的是 `net/Http.postRaw` —— 它统一带着 v1.17 补的那套现代根证书，
     * 在 Android 4.4 / 6.0 上不会因为老 CA 库缺锚点而报
     * `Trust anchor for certification path not found`。
     */
    private fun uploadByHttp(recordText: String): Result {
        val model = android.os.Build.MODEL
        return runCatching {
            val (code, body) = top.yogiczy.mytv.legacy.net.Http.postRaw(
                url = CRASH_UPLOAD_URL,
                body = recordText,
                contentType = "text/plain; charset=utf-8",
                extraHeaders = mapOf(
                    "X-App-Version" to BuildConfigCompat.VERSION_NAME,
                    "X-Device-Model" to model,
                ),
            )
            if (code in 200..299) {
                Result(true, "已提交（HTTP $code）。\n\n查看位置：你配置的那个收集服务。\n版本：${BuildConfigCompat.VERSION_NAME}")
            } else {
                Result(false, "上报失败：服务端返回 HTTP $code\n${body.take(200)}\n可先「复制记录」发给开发者。")
            }
        }.getOrElse {
            Result(
                false,
                "上报失败：${it.javaClass.simpleName}${it.message?.let { m -> "（$m）" } ?: ""}\n可先「复制记录」发给开发者。"
            )
        }
    }

    // ---------------- 记录 → Throwable ----------------

    /**
     * 把本地的崩溃正文还原成一个 [Throwable]。
     *
     * 正文格式（见 `CrashStore.save`）：
     * ```
     * 机型: MI TV4-ANSM0
     * Android: 6.0.1 (API 23)
     * 版本: 1.16-legacy.21
     *
     * java.lang.ClassCastException: xxx cannot be cast to yyy
     * 	at androidx.recyclerview.widget.RecyclerView.getChildViewHolderInt(RecyclerView.java:4989)
     * 	at top.yogiczy.mytv.legacy.MainActivity.handlePanelEdgeMove(MainActivity.kt:1655)
     * ```
     */
    private fun toThrowable(recordText: String): Throwable {
        val lines = recordText.trim().lines()
        val head = lines.firstOrNull { it.contains("Exception") || it.contains("Error") }
            ?: lines.firstOrNull().orEmpty()
        // message 里带上机型与版本：Bugly 后台的"异常详情 → 异常信息"里能直接看到，
        // 免得在两个页面之间来回对版本号。
        val model = lines.firstOrNull { it.startsWith("机型") }.orEmpty()
        val version = lines.firstOrNull { it.startsWith("版本") }.orEmpty()
        val throwable = Throwable("[手动反馈] ${head.take(300)}${if (model.isEmpty()) "" else "｜$model｜$version"}")

        val frames = ArrayList<StackTraceElement>()
        lines.forEach { raw ->
            val l = raw.trim()
            if (l.startsWith("at ")) frameOf(l.removePrefix("at "))?.let { frames.add(it) }
        }
        if (frames.isNotEmpty()) throwable.stackTrace = frames.toTypedArray()
        return throwable
    }

    /**
     * 解析一帧：`com.foo.Bar.baz(Bar.java:123)` / `(Native Method)` / `(Unknown Source)`。
     *
     * 解析不出来就返回 null（宁可少一帧，也不要造一个假的帧 —— 后台是按帧聚类定位的）。
     */
    private fun frameOf(s: String): StackTraceElement? {
        val open = s.lastIndexOf('(')
        val close = s.lastIndexOf(')')
        if (open <= 0 || close != s.length - 1) return null
        val decl = s.substring(0, open)
        val file = s.substring(open + 1, close)
        val dot = decl.lastIndexOf('.')
        if (dot <= 0) return null
        val cls = decl.substring(0, dot)
        val method = decl.substring(dot + 1)
        return when {
            file == "Native Method" -> StackTraceElement(cls, method, null, -2)
            file == "Unknown Source" -> StackTraceElement(cls, method, null, -1)
            else -> {
                val idx = file.substringAfterLast(':').toIntOrNull() ?: -1
                StackTraceElement(cls, method, file.substringBeforeLast(':'), idx)
            }
        }
    }
}
