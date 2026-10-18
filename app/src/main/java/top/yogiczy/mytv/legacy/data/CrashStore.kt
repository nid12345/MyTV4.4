package top.yogiczy.mytv.legacy.data

import android.content.Context
import top.yogiczy.mytv.legacy.App
import top.yogiczy.mytv.legacy.BuildConfigCompat
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest

/**
 * 崩溃记录的本地仓库（v1.17）。
 *
 * ---------------------------------------------------------------------------
 * 为什么需要它
 * ---------------------------------------------------------------------------
 * v1.6 起 `App` 里装了 `Thread.setDefaultUncaughtExceptionHandler`，崩溃时把堆栈写进
 * `App.PREFS / App.KEY_LAST_CRASH`；`MainActivity` 下次启动时把它整屏显示出来
 * （老电视接不了 adb，只能靠截图定位）。
 *
 * 但 v1.16 及以前那套实现有三个硬伤，小米电视（MI TV4-ANSM0 / Android 6.0.1）真机都撞上了：
 *
 *  1. **它把启动整个掐断**：`onCreate` 里 `if (showCrashReportIfAny()) return` ——
 *     后面的 `buildUi()` 一律不执行，于是屏幕上只有一个自绘按钮，
 *     而**没有显式 `requestFocus()`**，电视上方向键根本落不到它上面 →
 *     用户原话：「一遇到崩溃就什么都做不了，一个按钮能退出返回都没有」。
 *  2. **没有退出/返回**：返回键当时走 `onBackPressed` 的
 *     `if (!::panel.isInitialized) super.onBackPressed()` 分支，
 *     按下去直接把 Activity 结束（而不是关掉崩溃页）。
 *  3. **记录不清就永远拦着**：退出重进还是同一屏 →
 *     「只能卸载 app 重装才能用」。
 *
 * 现在把"记录"和"展示"彻底拆开：本文件只负责**存取**，
 * 展示交给 `MainActivity` 里一个**可关闭的弹窗**（[isAcked] 控制是否全屏弹出）。
 */
object CrashStore {

    /**
     * 一条崩溃记录。
     *
     * @param text        完整正文（机型 / Android 版本 / 应用版本 + 堆栈），给用户看和上传用
     * @param fingerprint 短指纹（正文 MD5 前 16 位），用来判断"是不是同一条"
     */
    class Record(val text: String, val fingerprint: String) {

        /** 首行异常描述（`java.lang.ClassCastException: ...`），列表/提示里用短文案。 */
        val headline: String
            get() = text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.contains("Exception") || it.contains("Error") }
                ?.take(120)
                .orEmpty()

        /**
         * 异常**简单类名**（`ClassCastException`）。
         *
         * 只给底部那一行提示用 —— 电视上离得远，`java.lang.ClassCastException: android.widget...`
         * 这种一行放不下，取到类名就够了。
         */
        val exceptionName: String
            get() {
                val head = headline.substringBefore(':').trim()
                val simple = head.substringAfterLast('.').ifBlank { head }
                return simple.ifBlank { "未知异常" }
            }
    }

    /** 读出上次崩溃记录；没有则 null。 */
    fun read(ctx: Context): Record? {
        // trim：磁盘读回来的值尾部可能带缩进空白（见 [fingerprint] 的注释）
        val text = prefs(ctx).getString(App.KEY_LAST_CRASH, null)?.trim()
        if (text.isNullOrBlank()) return null
        return Record(text, fingerprint(text))
    }

    /**
     * 把一次异常写入崩溃记录（供 `App` 的全局处理器与 `MainActivity.buildUiSafely` 共用）。
     *
     * 头部三行（机型 / Android 版本 / 应用版本）必须与"能定位问题"这个目的一致：
     * 电视上接不了 adb，用户只会把这段文字发过来 —— 少了版本号，堆栈对不上代码行。
     * 所以 [BuildConfigCompat.VERSION_NAME]（不是 `BuildConfig`，本工程关了 buildconfig 特性）
     * 而不是从 `PackageManager` 现查，保证与"关于页显示的版本"永远一致。
     */
    fun save(ctx: Context, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        saveText(ctx, sw.toString())
    }

    /**
     * 写一条记录（**正文不含头部三行**）。
     *
     * v1.20：[Watchdog] 的 ANR 记录没有 `Throwable` 可喂，只能直接给正文，
     * 所以把"拼头 + 落盘"这一段抽出来共用 —— 头部格式必须只有一处定义，
     * 否则崩溃与卡顿两条记录的机型/版本行会各自漂移。
     */
    fun saveText(ctx: Context, body: String) {
        val head = "机型: ${android.os.Build.MODEL}\n" +
                "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n" +
                "版本: ${BuildConfigCompat.VERSION_NAME}\n\n"
        prefs(ctx).edit()
            // ⚠️ 末尾必须 trim：SharedPreferences 的 XML 序列化会在**以换行结尾**的值后面
            // 补一段缩进空白（写成 `...</string>` 前的 "\n    "），重新读出来时就带上了。
            // 于是"同一段堆栈、进程内读"和"重启后从磁盘读"会得到两个不同的字符串
            // → 指纹不同 → 用户明明关掉过、下次启动又弹（v1.17 实测踩到）。
            .putString(App.KEY_LAST_CRASH, (head + body).trim())
            .remove(App.KEY_CRASH_ACK)      // 新的一条要重新提示（指纹会与旧的"已看过"标记不符）
            // 必须 commit：崩溃路径上进程马上要死，apply 可能写不完
            .commit()
    }

    /** 清掉崩溃记录与"已看过"标记（反馈成功 / 用户在设置里手动清除时调用）。 */
    fun clear(ctx: Context) {
        prefs(ctx).edit()
            .remove(App.KEY_LAST_CRASH)
            .remove(App.KEY_CRASH_ACK)
            .commit()
    }

    /**
     * 这一条是不是已经被用户看过/忽略过。
     *
     * `true` → 启动时**不再**弹全屏（只提一行"设置 → 关于 → 崩溃记录 可查看"），
     * 这样"退出重进还能正常用"就不会再被打断。
     */
    fun isAcked(ctx: Context, record: Record): Boolean =
        prefs(ctx).getString(App.KEY_CRASH_ACK, null) == record.fingerprint

    /** 标记"这条已经给用户看过了"（关闭弹窗时调用）。 */
    fun markAcked(ctx: Context, record: Record) {
        prefs(ctx).edit().putString(App.KEY_CRASH_ACK, record.fingerprint).commit()
    }

    /**
     * 正文指纹：正文（**去掉首尾空白后**）的 MD5 前 16 位。
     *
     * 用**正文内容**而不是时间戳：同一条崩溃（同一机型同一版本同一处堆栈）每次都是同一个指纹，
     * 于是"忽略过一次"能持续生效；而换了版本、换了崩溃点，指纹自然就变了 → 会重新弹出来。
     *
     * ⚠️ `trim()` 是必须的：SharedPreferences 存取一个以换行结尾的字符串时，
     * 读回来的值会在尾部多出缩进空白（见 [save] 的注释），不 trim 就会出现
     * "同一条崩溃两个指纹"，让"已忽略"失效。
     */
    private fun fingerprint(text: String): String = runCatching {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(text.trim().toByteArray(Charsets.UTF_8))
        digest.joinToString("") { "%02x".format(it) }.take(16)
    }.getOrDefault(text.trim().hashCode().toString())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(App.PREFS, Context.MODE_PRIVATE)
}
