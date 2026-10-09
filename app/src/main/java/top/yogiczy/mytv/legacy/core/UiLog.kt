package top.yogiczy.mytv.legacy.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内日志环形缓冲
 *
 * ---------------------------------------------------------------------------
 * 来源：MyTV 1.7（源码 tag `custom-v1.7`）`core/data/utils/Logger.kt`
 *       （`Logger` + `HistoryItem` + `LevelType` 三件套）
 *
 * 移植方式：**语义等价重写**，改动点及理由：
 *   ① 原版 `HistoryItem` 是 `@Serializable`（kotlinx.serialization），本工程禁引该依赖
 *      → 改为普通 data class。持久化若需要，由 M9 用 org.json 另做。
 *   ② 原版 `Logger` 是「每处 `Logger.create(tag)` 一个实例」，且 tag 来自
 *      `javaClass.simpleName`。**这在 release 包上会失效**：R8 会把类名混淆成单字母，
 *      于是日志全变成 `a: ...`，按 tag 筛必然为空。
 *      → 本工程 [tag] 一律由调用方传**中文字面量**（如 "节目单"），R8 不会改写字符串常量。
 *      这是无 adb 时唯一可靠的排错通道。
 *   ③ 原版只保留 INFO/WARN/ERROR 三级进历史（DEBUG 不进），本文件保留该过滤规则。
 *
 * 容量：与 `Constants.LOG_HISTORY_MAX_SIZE = 50` 一致。
 *
 * 线程安全：日志会从网络线程写、从界面线程读，全部操作加锁。
 * ---------------------------------------------------------------------------
 */
object UiLog {

    /** 与 `Constants.LOG_HISTORY_MAX_SIZE` 一致。 */
    const val MAX_SIZE = 50

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val level: Level,
        val tag: String,
        val message: String,
        val cause: String? = null,
        val time: Long = System.currentTimeMillis(),
    )

    private val lock = Any()
    private var buffer = mutableListOf<Entry>()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    /** 历史日志（从旧到新）。返回快照，调用方随便遍历。 */
    val history: List<Entry>
        get() = synchronized(lock) { buffer.toList() }

    fun d(tag: String, message: String, cause: Throwable? = null) = write(Level.DEBUG, tag, message, cause)
    fun i(tag: String, message: String, cause: Throwable? = null) = write(Level.INFO, tag, message, cause)
    fun w(tag: String, message: String, cause: Throwable? = null) = write(Level.WARN, tag, message, cause)
    fun e(tag: String, message: String, cause: Throwable? = null) = write(Level.ERROR, tag, message, cause)

    fun clear() {
        synchronized(lock) { buffer = mutableListOf() }
    }

    /** 单行文本，给 M9 的「日志」分类直接显示。 */
    fun format(entry: Entry): String =
        "${timeFormat.format(Date(entry.time))} [${entry.level}] ${entry.tag}：${entry.message}" +
                (entry.cause?.let { "（$it）" } ?: "")

    private fun write(level: Level, tag: String, message: String, cause: Throwable?) {
        // 同时走系统日志：接得上 adb 时能直接 logcat 看（UILog 是给接不上 adb 的场景兜底的）
        when (level) {
            Level.DEBUG -> Log.d(SYSTEM_TAG, "$tag：$message", cause)
            Level.INFO -> Log.i(SYSTEM_TAG, "$tag：$message", cause)
            Level.WARN -> Log.w(SYSTEM_TAG, "$tag：$message", cause)
            Level.ERROR -> Log.e(SYSTEM_TAG, "$tag：$message", cause)
        }

        // 与原版一致：只有 INFO / WARN / ERROR 进历史，DEBUG 不进
        if (level == Level.DEBUG) return

        val entry = Entry(level, tag, message, cause?.message)
        synchronized(lock) {
            buffer.add(entry)
            if (buffer.size > MAX_SIZE) {
                buffer = buffer.takeLast(MAX_SIZE).toMutableList()
            }
        }
    }

    /** 系统 logcat 用的固定 tag（字面量，R8 不改写）。 */
    const val SYSTEM_TAG = "MyTVLegacy"
}
