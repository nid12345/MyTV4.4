package top.yogiczy.mytv.legacy.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import top.yogiczy.mytv.legacy.data.Prefs
import top.yogiczy.mytv.legacy.data.UiTimeShowMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 独立的"时间显示"图层
 *
 * ---------------------------------------------------------------------------
 * 对应原版：MyTV 1.7（源码 tag `custom-v1.7`）
 *           `tv/ui/screens/datetime/DatetimeScreen.kt`
 *
 * ⚠️ **原版这个组件只显示"时间"，不显示日期**（`Text(text = timeText, ...)`，就一个 Text）。
 * 带日期的那份是 `datetime/components/DateTimeDetail.kt`，那是给**快捷菜单顶栏**用的
 * （见 `QuickOpView` 内联实现），两者不是同一个东西，不要合并。
 *
 * 显隐规则**逐条照搬**（`DatetimeScreen.kt` 的 `when`）：
 * ```
 * HIDDEN     -> 不显示                       （原版默认值，见 Configs.UiTimeShowMode）
 * ALWAYS     -> 一直显示，格式 "HH:mm"
 * EVERY_HOUR -> 整点前后 30 秒内显示，格式 "HH:mm:ss"
 *              条件：ts % 3600000 <= SHOW+1000 || ts % 3600000 >= 3600000 - SHOW
 * HALF_HOUR  -> 半点前后 30 秒内显示，格式 "HH:mm:ss"（同上，周期 1800000）
 * ```
 * `SHOW = Constants.UI_TIME_SCREEN_SHOW_DURATION = 1000L * 30`（原版注释：前后 30 秒）。
 *
 * ⚠️ 原版那个 `+1000` 不是笔误，是**每秒轮询带来的补偿**（轮询间隔 1 秒，
 * 若不加就会漏掉刚好错过的那一秒）。这里同样是每秒 tick，所以照抄，不要"优化"掉。
 *
 * **显示时机**（原版 `MainContent.kt` 的 `Visible { ... }` 条件，逐条对齐）：
 * 只有"临时频道界面 / 经典面板 / 设置页 / 快捷菜单 / EPG / 多线路 / 数字选台"
 * **全都没显示**时才轮到它 —— 换台后 1.5 秒内的临时频道界面会把它盖掉。
 * 这个条件由调用方算好，通过 [tick] 的 `suppressed` 参数传进来。
 * ---------------------------------------------------------------------------
 */
class DatetimeView(context: Context) : TextView(context) {

    companion object {
        /** 与 `Constants.UI_TIME_SCREEN_SHOW_DURATION` 一致：前后 30 秒。 */
        const val SHOW_DURATION_MS = 1_000L * 30

        private val TIME_ALWAYS = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val TIME_WITH_SECONDS = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }

    init {
        setTextColor(Palette.onSurface(context))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)      // typography.titleLarge
        gravity = Gravity.END
        visibility = GONE                                  // 默认 HIDDEN
    }

    /**
     * 每秒 tick 一次。
     *
     * @param suppressed 当前是否被别的界面盖住（见类注释的 `Visible{}` 条件）；
     *                   true 时一律不显示，且**不改变** [UiTimeShowMode] 的判定。
     * @return 本次是否可见（便于调用方打点）
     */
    fun tick(
        now: Date = Date(),
        suppressed: Boolean = false,
        mode: UiTimeShowMode = currentMode(),
    ): Boolean {
        if (suppressed) {
            if (visibility != GONE) visibility = GONE
            return false
        }

        val ts = now.time
        val visible = when (mode) {
            UiTimeShowMode.HIDDEN -> false
            UiTimeShowMode.ALWAYS -> true
            UiTimeShowMode.EVERY_HOUR -> nearBoundary(ts, 3_600_000L)
            UiTimeShowMode.HALF_HOUR -> nearBoundary(ts, 1_800_000L)
        }

        if (!visible) {
            if (visibility != GONE) visibility = GONE
            return false
        }

        text = if (mode == UiTimeShowMode.ALWAYS) TIME_ALWAYS.format(now)
        else TIME_WITH_SECONDS.format(now)
        if (visibility != VISIBLE) visibility = VISIBLE
        return true
    }

    /** 原版条件，逐字照抄。 */
    private fun nearBoundary(ts: Long, period: Long): Boolean {
        val phase = ts % period
        return phase <= SHOW_DURATION_MS + 1000 || phase >= period - SHOW_DURATION_MS
    }

    private fun currentMode(): UiTimeShowMode =
        runCatching { Prefs.uiTimeShowMode(context) }.getOrDefault(UiTimeShowMode.HIDDEN)
}
