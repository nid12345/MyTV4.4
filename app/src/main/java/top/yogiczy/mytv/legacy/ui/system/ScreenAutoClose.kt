package top.yogiczy.mytv.legacy.ui.system

import android.os.Handler
import android.os.Looper

/**
 * 界面自动关闭（超时无操作则关闭）
 *
 * ---------------------------------------------------------------------------
 * 对应原版：MyTV 1.7（源码 tag `custom-v1.7`）
 *           `tv/ui/screens/components/ScreenAutoClose.kt`
 *
 * 原版语义（逐条对齐，不能简化）：
 * ```
 * class ScreenAutoClose(timeout: Long, onTimeout: () -> Unit) {
 *     private val channel = Channel<Long>(CONFLATED)
 *     fun active() { channel.trySend(timeout) }          // 每次用户操作就"续期"
 *     suspend fun observe() {
 *         channel.consumeAsFlow().debounce { it }.collect { onTimeout() }
 *     }
 * }
 * // 用法：rememberScreenAutoCloseState(onTimeout = onClose) —— 创建时立即 active() 一次
 * ```
 * 关键行为有两点，本实现必须都保住：
 *  1. **CONFLATED + debounce**：频繁操作只保留最后一次，且**每次操作都会重新计时**
 *     （不是"到点就关、操作无效"）。
 *  2. **创建即 active()**：界面弹出后若一直没人操作，同样要超时关闭。
 *
 * 原版用协程；本工程不引协程（冻结决策），因此用 [Handler] + 可达性检查实现同一语义：
 * 续期 = removeCallbacks + postDelayed（正是 debounce 的行为）。
 *
 * 超时值取自 `Configs.uiScreenAutoCloseDelay`（默认 15s），原版注释里明确说
 * "原实现写死 15 秒常量，设置项形同虚设" —— 所以这里必须走 `Prefs.uiScreenAutoCloseDelay()`，
 * 不能写死。
 * ---------------------------------------------------------------------------
 */
class ScreenAutoClose(
    timeoutMs: Long,
    private val onTimeout: () -> Unit,
) {

    /**
     * 超时值（毫秒）。
     *
     * ⚠️ 判据 1：原来是构造时**一次性捕获**的 `val`，于是「超时自动关闭界面」这个设置项
     * 改了要等重启才生效（面板实例是 `by lazy` 建的）。改成可变属性，宿主每次
     * [active] 前用最新设置值覆盖即可立即生效。
     */
    var timeoutMs: Long = timeoutMs

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val runnable = Runnable {
        running = false
        onTimeout()
    }

    /** 续期：任何用户操作都应调用。创建后也应立即调用一次。 */
    fun active() {
        if (timeoutMs <= 0L) return          // 0 = 不自动关闭（与原版 IntRange(from = 0) 一致）
        handler.removeCallbacks(runnable)
        running = true
        handler.postDelayed(runnable, timeoutMs)
    }

    /** 停止计时（界面被手动关闭时必须调用，否则会对着已关闭的界面再触发一次回调）。 */
    fun cancel() {
        handler.removeCallbacks(runnable)
        running = false
    }

    /** 是否正在计时（诊断/日志用）。 */
    val isRunning: Boolean get() = running
}
