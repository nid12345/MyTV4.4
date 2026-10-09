package top.yogiczy.mytv.legacy.ui.panel

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import top.yogiczy.mytv.legacy.core.UiLog
import top.yogiczy.mytv.legacy.ui.UiKit

/**
 * 经典三段式的**容器与三态状态机**。
 *
 * M10 从 `MainActivity` 搬来（M6 遗留的拆分项），**行为一字不改**，只搬家。
 *
 * 原版 `ClassicChannelScreen.kt`：
 * ```
 * offsetXPx = if (epgListVisible)
 *     if (epgListIsFocused) -groupWidth - channelListWidth else -groupWidth
 * else 0
 * ```
 * | 状态 | offsetX | 屏幕上看得见 |
 * |------|---------|-------------|
 * | 0    | 0       | 分组栏 + 频道列 |
 * | 1    | -groupWidth | 频道列 + 节目单列 |
 * | 2    | -(groupWidth + channelListWidth) | 节目单列（+日期列）|
 */
class PanelHost(context: Context) : LinearLayout(context) {

    private val handler = Handler(Looper.getMainLooper())

    /** 三列（左→右）。 */
    var navRef: View? = null
    var channelRef: View? = null
    var epgRef: View? = null

    private var state = 0
    private var animating = false
    private var groupW = 0
    private var channelW = 0

    /** 状态变化回调（宿主用来刷新右上角信息等）。 */
    var onStateChanged: ((Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
    }

    /** 由 [epgVisible] / [epgFocused] 推状态（同构原版 offsetXPx 表达式）。 */
    fun refresh(epgVisible: Boolean, epgFocused: Boolean) {
        val want = if (!epgVisible) 0 else if (epgFocused) 2 else 1
        // 列表宽度随聚焦切换要在位移之前改，避免动画期间布局跳动
        setState(want)
    }

    fun setState(target0: Int) {
        val target = target0.coerceIn(0, 2)
        if (state == target) return
        if (animating) return           // 动画未结束，丢弃本次请求
        state = target

        val g = if (groupW > 0) groupW else navRef?.width ?: 0
        val c = if (channelW > 0) channelW else channelRef?.width ?: 0
        val dx: Float = when (target) {
            0 -> 0f
            1 -> -(g + offscreenExtraPx).toFloat()
            else -> -(g + c + offscreenExtraPx).toFloat()
        }

        animating = true
        animate()
            .translationX(dx)
            .setDuration(180L)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .start()
        // ⚠️ 解锁**不能靠 withEndAction**：API 19 上实测该回调有可能不来。
        handler.removeCallbacks(unlock)
        handler.postDelayed(unlock, 200L)
        blockOffscreenFocus()
        UiLog.i("界面", "三段式状态 → $target（位移 ${dx.toInt()}px）")
        onStateChanged?.invoke(target)
    }

    private val unlock = Runnable { animating = false }

    /** 屏幕外的列设为 FOCUS_BLOCK_DESCENDANTS。 */
    fun blockOffscreenFocus() {
        val navOff = state >= 1
        val channelOff = state >= 2
        val epgOff = state == 0
        setColumnFocusable(navRef, !navOff)
        setColumnFocusable(channelRef, !channelOff)
        setColumnFocusable(epgRef, !epgOff)
    }

    private fun setColumnFocusable(col: View?, enabled: Boolean) {
        val vg = col as? ViewGroup ?: return
        vg.descendantFocusability =
            if (enabled) ViewGroup.FOCUS_AFTER_DESCENDANTS
            else ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    /** 实测列宽回填（不用设计值硬算）。 */
    fun measureColumns() {
        groupW = navRef?.width ?: 0
        channelW = channelRef?.width ?: 0
    }

    val currentState: Int get() = state
    val isAnimating: Boolean get() = animating

    /** 列背景（原 `columnBg`）。 */
    fun columnBg(left: Boolean): android.graphics.drawable.GradientDrawable = UiKit.rounded(
        if (left) UiKit.withAlpha(0xFF1D1B20.toInt(), 0.96f)
        else UiKit.withAlpha(0xFF1D1B20.toInt(), 0.88f),
        10f, context
    )

    /** 三列的公共高度参数由宿主给（屏高的 60%）。 */
    var panelMaxH: Int = ViewGroup.LayoutParams.WRAP_CONTENT

    /**
     * 面板自身相对宿主左边的偏移（px，即 LayoutParams.leftMargin）。
     *
     * 判据「菜单没必要左移」：原位移量只按列宽算（`-groupWidth`），但面板本身还带着 24dp
     * 左边距，于是展开后分组栏会留一条 24dp 的"残边"贴在屏幕左边（真机截图实证）。
     * 把这段偏移计入位移，分组栏才会真正移出屏幕。
     */
    var offscreenExtraPx: Int = 0
}
