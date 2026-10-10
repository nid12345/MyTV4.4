package top.yogiczy.mytv.legacy.data

import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import top.yogiczy.mytv.legacy.BuildConfigCompat
import top.yogiczy.mytv.legacy.net.Http
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把崩溃记录一键提交到 GitHub（v1.17 需求）。
 *
 * ---------------------------------------------------------------------------
 * 用户原话
 * ---------------------------------------------------------------------------
 * 「这个崩溃页面添加一个把这个崩溃记录能一键反馈上传到 GitHub 的功能，
 *   这样下次有崩溃的话直接读取 GitHub 记录就可以直接修」。
 *
 * ---------------------------------------------------------------------------
 * 为什么用 REST API + 用户自备 Token，而不是把 Token 编进 APK
 * ---------------------------------------------------------------------------
 * 本仓库是**公开**的（`nid12345/MyTV4.4`），Release 里的 APK 谁都能下。
 * 只要把凭据写进代码/资源，下载 APK 的人解开就能拿到，等于把写权限交给全网 —— 不做。
 *
 * 所以：Token 由用户在电视上**填一次**（设置 → 关于 → 崩溃反馈设置，支持复用
 * 「手机扫码填写」把 Token 从手机贴过来，不用遥控器逐字敲），存在本机 SharedPreferences，
 * 只有真正点「反馈到 GitHub」时才用、只发往 `api.github.com`。
 * 建议用**细粒度 PAT**：只勾这一个仓库、只给 `Issues: Read and write`，丢了也不致命。
 *
 * ---------------------------------------------------------------------------
 * 去重
 * ---------------------------------------------------------------------------
 * 同一条崩溃（同一机型 / 同一版本 / 同一处堆栈）只开一个 issue：
 * 先按标题搜一次，命中就往那条 issue **追加一条评论**（天然记下"又复现了几次"），
 * 搜不到才新建。搜索失败时降级为"直接新建"，不让反馈整体失败。
 *
 * 本文件全部方法都是**同步阻塞**的，必须在后台线程调用（本工程不引协程）。
 */
object CrashReporter {

    private const val OWNER = "nid12345"
    private const val REPO = "MyTV4.4"
    private const val API = "https://api.github.com"

    /** Token 申请页（界面上直接给用户看）。 */
    const val TOKEN_HELP_URL = "https://github.com/settings/personal-access-tokens/new"

    /** issue 列表页（反馈成功后提示用户去哪看）。 */
    const val ISSUES_URL = "https://github.com/$OWNER/$REPO/issues"

    /** 上传结果。[ok] 为 false 时 [message] 是可直接展示给用户的中文原因。 */
    class Result(val ok: Boolean, val message: String)

    /**
     * issue 标题：`[崩溃] ClassCastException · 1.17-legacy.22`。
     *
     * 结构固定（"前缀 + 异常简单类名 + 版本"）是为了两件事：
     *  · 在 GitHub 上按 `[崩溃]` 一筛就是全部崩溃类 issue；
     *  · 搜索去重时能**精确按标题**命中同一条，不必解析正文。
     */
    fun issueTitle(report: String): String {
        val head = exceptionLine(report).substringBefore(':').trim()
        val simple = head.substringAfterLast('.').ifBlank { head }.ifBlank { "未知异常" }
        return "[崩溃] $simple · ${BuildConfigCompat.VERSION_NAME}"
    }

    /**
     * 从正文里挑出"异常那一行"。
     *
     * 正文格式由 `App.installCrashLogger` 决定：3 行头部（机型 / Android / 版本）+ 空行 + 堆栈。
     * 这里不按下标取（将来头部行数改了会 silently 取错），而是**按内容找**：
     * 第一个含 `Exception` / `Error` 的行；都找不到就退化成第一行非空行。
     */
    private fun exceptionLine(report: String): String = report.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.contains("Exception") || it.contains("Error") }
        ?: report.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()

    /**
     * 上传（阻塞）。调用方必须在后台线程执行。
     *
     * @param token  用户配置的 GitHub Token（细粒度 PAT，Issues: Read and write）
     * @param report 崩溃正文（[CrashStore.Record.text]）
     */
    fun upload(token: String, report: String): Result {
        val tk = token.trim()
        if (tk.isEmpty()) return Result(false, "还没配置 GitHub Token（设置 → 关于 → 崩溃反馈设置）")

        val title = issueTitle(report)
        val body = buildBody(report)

        // ① 查同名 issue：查到就追加评论，避免同一条崩溃刷出一堆 issue。
        //    搜索失败**不影响**反馈本身（降级为新建），所以这里吞掉异常。
        val dupNumber = runCatching { findOpenIssueNumber(tk, title) }.getOrNull()

        return if (dupNumber != null) {
            commentIssue(tk, dupNumber, body)
        } else {
            createIssue(tk, title, body)
        }
    }

    /** 正文：把原始记录放进代码块（GitHub 上等宽显示，堆栈对齐不会乱）。 */
    private fun buildBody(report: String): String {
        val whenText = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        }.getOrDefault("-")
        return buildString {
            append("由电视端应用「一键反馈」自动提交（电视本地时间 ")
            append(whenText).append("）。\n\n")
            append("```\n")
            append(report.trim())
            append("\n```\n")
        }
    }

    // ---------------- GitHub REST ----------------

    /** 搜同名且仍打开的 issue，返回编号；没有则 null。 */
    private fun findOpenIssueNumber(token: String, title: String): Int? {
        val q = URLEncoder.encode("repo:$OWNER/$REPO in:title state:open \"$title\"", "UTF-8")
        val (code, resp) = call("GET", "$API/search/issues?q=$q", null, token)
        if (code != 200) return null
        val items = runCatching { JSONObject(resp).optJSONArray("items") }.getOrNull() ?: return null
        // 搜索是模糊匹配，这里再严格比一次标题（只认完全相同的标题）
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            if (o.optString("title") == title) return o.optInt("number", 0).takeIf { it > 0 }
        }
        return null
    }

    private fun createIssue(token: String, title: String, body: String): Result {
        val payload = JSONObject().put("title", title).put("body", body).toString()
        val (code, resp) = call("POST", "$API/repos/$OWNER/$REPO/issues", payload, token)
        if (code == 201) {
            val number = runCatching { JSONObject(resp).optInt("number", 0) }.getOrDefault(0)
            return Result(true, "已提交到 GitHub：issue #$number\n（$ISSUES_URL）")
        }
        return Result(false, explain(code, resp, "新建 issue"))
    }

    private fun commentIssue(token: String, number: Int, body: String): Result {
        val payload = JSONObject().put("body", body).toString()
        val (code, resp) = call("POST", "$API/repos/$OWNER/$REPO/issues/$number/comments", payload, token)
        if (code == 201) {
            return Result(true, "这条崩溃之前已经报过，已在 issue #$number 下追加一条记录")
        }
        return Result(false, explain(code, resp, "追加记录"))
    }

    /**
     * 一次 REST 调用。返回 `(状态码, 正文)`。
     *
     * 用 [Http.client] 而不是自己 new OkHttpClient —— 那里统一挂了 **TLS 1.2**
     * （API 19 必需，见 `net/Http.kt` 的长注释），另起一套必然漂移。
     * 老电视的系统时间可能不准，`api.github.com` 走的是普通 TLS 校验（不校验时间戳），
     * 所以时间不准不会挡住反馈。
     */
    private fun call(method: String, url: String, body: String?, token: String): Pair<Int, String> {
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "token $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        if (body != null) {
            builder.method(method, RequestBody.create(MediaType.parse(JSON_CONTENT_TYPE), body))
        } else {
            builder.method(method, null)
        }
        val client = Http.client(
            connectTimeoutMs = 10_000L,
            readTimeoutMs = 20_000L,
            userAgent = "MyTV-Legacy-CrashReporter",
        )
        val response = client.newCall(builder.build()).execute()
        response.use { resp ->
            return resp.code() to resp.body()?.string().orEmpty()
        }
    }

    /** 把 HTTP 状态码翻成用户能看懂的中文原因（老电视上没法看 logcat，文案必须自解释）。 */
    private fun explain(code: Int, body: String, action: String): String {
        val hint = when (code) {
            401 -> "Token 无效或已被吊销"
            403 -> "Token 权限不足（需要对该仓库开 Issues 的 Read and write），或已触发频率限制"
            404 -> "仓库不可见：Token 里没勾上 $OWNER/$REPO"
            422 -> "GitHub 拒绝了内容（可能标题/正文格式问题）"
            301, 302 -> "请求被重定向（Token 或网络异常）"
            in 500..599 -> "GitHub 服务暂时异常（HTTP $code）"
            else -> "HTTP $code"
        }
        val detail = runCatching { JSONObject(body).optString("message") }.getOrDefault("")
        return "反馈失败（$action）：$hint" + if (detail.isNotBlank()) "\n$detail" else ""
    }

    private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
}
