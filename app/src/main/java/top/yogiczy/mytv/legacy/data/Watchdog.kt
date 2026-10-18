package top.yogiczy.mytv.legacy.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import top.yogiczy.mytv.legacy.App
import top.yogiczy.mytv.legacy.BuildConfigCompat
import top.yogiczy.mytv.legacy.core.UiLog

/**
 * 主线程卡死（ANR）看门狗 —— v1.20 需求 2。
 *
 * ---------------------------------------------------------------------------
 * 为什么要它
 * ---------------------------------------------------------------------------
 * 用户报障原话：「加载比较多节目源和在左侧菜单挑节目的时候出现偶尔卡死没反应闪退的情况
 * 复查下什么原因导致的修复好，另外这种卡死闪退的情况没有奔溃上报的入口」。
 *
 * 现状的盲区很清楚：`App.installCrashLogger` 装的是
 * `Thread.setDefaultUncaughtExceptionHandler` —— 它只看得见**抛出去的异常**。
 * 而"卡死"恰恰是**没抛异常**：主线程被一段重活堵住（或堵到系统弹出 ANR 对话框、
 * 用户强杀），进程死得无声无息，本地一条记录都没有，后台也就什么都看不到。
 * 于是这类问题永远只能靠用户复述，无法定位。
 *
 * ---------------------------------------------------------------------------
 * 原理（不引第三方 SDK，30 行内核）
 * ---------------------------------------------------------------------------
 *  · 往主线程 `Handler` 上挂一个每 2 秒一次的 tick，tick 只干一件事：
 *    把 [lastTickAt] 刷成当前 `uptimeMillis()`。
 *  · 看门狗线程每 2 秒醒一次，比一下 `now - lastTickAt`：
 *    超过 [BLOCK_MS] 就说明主线程这段时间**一次消息都没跑完** → 判定卡死。
 *  · 判定成立时**从看门狗线程**读 `mainLooper.thread.stackTrace` ——
 *    这一步能看到主线程正卡在哪一行（这是唯一能拿到"卡在哪"的办法，
 *    主线程自己已经被堵住、不可能自己上报）。
 *
 * ⚠️ 必须连续两次判定成立才报：单次长卡顿可能只是 GC 或首帧资源加载，
 *    一次误报就会把后台刷满、也污染同一指纹的计数。
 *
 * ---------------------------------------------------------------------------
 * 上报
 * ---------------------------------------------------------------------------
 * 与崩溃走同一条链路：写进 [CrashStore]（用户能在「设置 → 关于 → 崩溃记录」里看到、
 * 也能复制），同时**自动** POST 到自建 Worker（[CrashReporter]）。
 * 自动是刻意的 —— 卡死发生时用户往往正在按遥控器，指望他事后再去点「一键反馈」不现实。
 *
 * 频率限制：同一会话最少隔 [MIN_REPORT_INTERVAL_MS] 才报一次，避免一次长时间卡死
 * 被拆成十几条重复记录。
 */
object Watchdog {

    private const val TAG = "卡顿"

    /** tick 间隔：主线程正常时每 2 秒刷一次心跳。 */
    private const val TICK_MS = 2_000L

    /** 判死阈值：主线程 8 秒没跑完一轮消息 = 卡死（系统 ANR 是 5 秒，放宽一点避开误报）。 */
    private const val BLOCK_MS = 8_000L

    /** 两次上报的最小间隔。 */
    private const val MIN_REPORT_INTERVAL_MS = 10 * 60 * 1000L

    @Volatile
    private var lastTickAt = 0L

    @Volatile
    private var lastReportAt = 0L

    /** 置 false = 应用退到后台，此时主线程被系统挂起属于正常，不该判死。 */
    @Volatile
    private var enabled = false

    @Volatile
    private var installed = false

    private var appCtx: Context? = null

    /** 连续判定成立的次数（见类注释：要求连续两次）。 */
    @Volatile
    private var strikes = 0

    /**
     * 装看门狗。**只应在 `Application.onCreate` 里调一次**。
     *
     * @param ctx 用 `applicationContext`，避免持有 Activity。
     */
    fun install(ctx: Context) {
        if (installed) return
        installed = true
        appCtx = ctx.applicationContext
        lastTickAt = SystemClock.uptimeMillis()

        val main = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                lastTickAt = SystemClock.uptimeMillis()
                main.postDelayed(this, TICK_MS)
            }
        }
        main.post(tick)

        Thread({
            while (true) {
                try {
                    Thread.sleep(TICK_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                checkOnce()
            }
        }, "anr-watchdog").apply { isDaemon = true }.start()

        UiLog.i(TAG, "看门狗已启动（阈值 ${BLOCK_MS}ms）")
    }

    /** 前台/后台切换：退后台时停判定（[MainActivity.onResume] / [onPause] 调它）。 */
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        strikes = 0
        lastTickAt = SystemClock.uptimeMillis()
        UiLog.i(TAG, if (value) "看门狗恢复判定（回到前台）" else "看门狗暂停判定（退到后台）")
    }

    private fun checkOnce() {
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        val blocked = now - lastTickAt
        if (blocked < BLOCK_MS) {
            strikes = 0
            return
        }
        if (++strikes < 2) return                       // 连续两次才认定
        strikes = 0
        if (now - lastReportAt < MIN_REPORT_INTERVAL_MS) return
        lastReportAt = now
        report(blocked)
    }

    /**
     * 抓主线程堆栈并上报。
     *
     * ⚠️ 全程在**看门狗线程**执行，且**不能碰任何 UI 对象** ——
     * 此刻主线程正堵着，任何需要主线程参与的操作（如 `Handler.post` 后等结果）都会一起卡住。
     */
    private fun report(blockedMs: Long) {
        val ctx = appCtx ?: return
        runCatching {
            val mainThread = Looper.getMainLooper().thread
            // 「阻塞时长取整到秒」是刻意的：后台按正文做 SHA-256 指纹聚合，
            // 写毫秒的话每一次都是新指纹、列表会被同一处卡顿刷屏。
            val stack = buildString {
                append("java.lang.ANRError: 主线程无响应（ANR）— 阻塞 ${blockedMs / 1000}s\n")
                append("\t（以下为主线程被堵住时的实时堆栈）\n")
                mainThread.stackTrace.forEach { frame ->
                    append("\tat ").append(frame.toString()).append('\n')
                }
                append("\n---- 卡死前最近的操作 ----\n")
                UiLog.history.takeLast(12).forEach { append(UiLog.format(it)).append('\n') }
            }
            UiLog.w(TAG, "主线程卡死 ${blockedMs}ms → 已记录并上报")
            CrashStore.saveText(ctx, stack)
            // 自动上报：与崩溃同一条链路、同一个后台（mytvbug.nide.qzz.io）
            val result = CrashReporter.upload(ctx, CrashStore.read(ctx)?.text ?: stack)
            UiLog.i(TAG, if (result.ok) "卡顿记录已上报" else "卡顿记录上报失败：${result.message}")
        }.onFailure {
            UiLog.e(TAG, "卡顿记录写入失败：${it.javaClass.simpleName}")
        }
    }
}
