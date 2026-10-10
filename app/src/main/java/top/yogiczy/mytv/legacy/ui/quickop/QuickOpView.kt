package top.yogiczy.mytv.legacy.ui.quickop

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import top.yogiczy.mytv.legacy.data.Channel
import top.yogiczy.mytv.legacy.data.Prefs
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit
import top.yogiczy.mytv.legacy.ui.system.ScreenAutoClose

/**
 * 快捷功能菜单（全屏半透明遮罩）
 *
 * ---------------------------------------------------------------------------
 * 对应原版：MyTV 1.7（源码 tag `custom-v1.7`）
 *   · `tv/ui/screens/quickop/QuickOpScreen.kt`（316 行）—— 遮罩与上下两行结构
 *   · `tv/ui/screens/quickop/components/QuickOpBtnList.kt`（102 行）—— 7 项与顺序
 *
 * 逐条对齐的关键点（都不能"优化"）：
 *
 * 1. **7 项、固定顺序、无图标**（`QuickOpBtnList.kt` 的 item 顺序，逐字）：
 *      投屏 → 节目单 → 多线路 → 播放控制 → 显示模式 → 清除缓存 → 更多设置
 *    ⚠️ 注意「清除缓存」在「更多设置」**之前**。原版函数参数顺序是
 *    `MoreSettings` 在前、`ClearCache` 在后，容易照参数顺序写反而写错。
 *
 * 2. **默认焦点在第一项「投屏」**（原版 `Modifier.focusOnLaunched()` 挂在第一个 item 上）。
 *
 * 3. **背景是全屏 `background.copy(alpha = 0.5f)`** —— 半透明黑，不是全黑、不是模糊。
 *
 * 4. **结构**：顶部行 = 直播源名（左）＋ 频道号 + 白色竖线 + 日期时间（右）；
 *    底部（贴左下）= 频道信息块 ＋ 7 个按钮横排。
 *
 * 5. **点击遮罩空白处 / 返回键 → 关闭**（原版 `detectTapGestures { onClose() }`
 *    + `captureBackKey { onClose() }`）。
 *
 * 6. **任何用户操作都续期自动关闭**（原版把 `onUserAction` 透传给
 *    `screenAutoCloseState.active()`；本工程对应 [ScreenAutoClose.active]）。
 *    超时值取 `Prefs.uiScreenAutoCloseDelay()`（默认 15s），**不写死**。
 * ---------------------------------------------------------------------------
 */
class QuickOpView(context: Context) : FrameLayout(context) {

    /**
     * 动作项。顺序即 [ENTRIES] 的顺序。
     *
     * v1.15 需求 4（用户）：「在详情菜单里的投屏按钮前面添加一个收藏功能」——
     * 所以 [FAVORITE] 必须排在 [DLNA] 前面（[ENTRIES] 的第一项）。
     */
    enum class Action { FAVORITE, DLNA, EPG, CHANNEL_URL, CONTROLLER, DISPLAY_MODE, CLEAR_CACHE, MORE_SETTINGS }

    companion object {
        /**
         * 与原版 `QuickOpBtnList.kt` 的 7 个 `item {}` 逐条对应（含顺序），
         * 另在首位插入 v1.15 需求 4 的「收藏」。
         */
        val ENTRIES: List<Pair<String, Action>> = listOf(
            "收藏" to Action.FAVORITE,
            "投屏" to Action.DLNA,
            "节目单" to Action.EPG,
            "多线路" to Action.CHANNEL_URL,
            "播放控制" to Action.CONTROLLER,
            "显示模式" to Action.DISPLAY_MODE,
            "清除缓存" to Action.CLEAR_CACHE,
            "更多设置" to Action.MORE_SETTINGS,
        )

        /** 原版按钮横排间距 `Arrangement.spacedBy(8.dp)`。 */
        private const val BTN_GAP_DP = 8f

        /** 原版遮罩：`background.copy(alpha = 0.5f)`。 */
        private const val SCRIM_ALPHA = 0.5f
    }

    private val autoClose = ScreenAutoClose(
        timeoutMs = Prefs.uiScreenAutoCloseDelay(context),
    ) { close() }

    /**
     * 呼吸闪烁：对应原版 `Animatable(1f)` + `animateTo(0.2f, tween(1000)) / animateTo(1f, tween(1000))` 循环。
     *
     * ⚠️ 必须声明在 init 块之前：init 里 sourceLabel 的焦点回调（`breathe.attach/start/stop`）
     * 会读写它 —— Kotlin 按声明顺序初始化成员，放后面会在 <init> 里抛 NPE
     * （与 PlayerController 的 listener 同款教训，第二次踩）。
     */
    private val breathe = Breathe()

    private val sourceLabel: TextView
    private val iconHint: TextView
    private val numberView: TextView
    private val dateView: TextView
    private val timeView: TextView
    private val infoHolder: LinearLayout
    private val btnRow: LinearLayout
    private val buttons = ArrayList<TextView>()

    /** 由 MainActivity 注入：动作回调与被关闭回调。 */
    var onAction: ((Action) -> Unit)? = null
    var onClosed: (() -> Unit)? = null

    /**
     * 「当前订阅源」被选中（原版 `QuickOpScreeIptvSource` 的 onClick）。
     *
     * 原版行为：打开订阅源选择弹层。本工程把它交给 MainActivity
     * （关掉快捷菜单 → 展开面板 → 中栏换成订阅列表）。
     */
    var onSourceSelect: (() -> Unit)? = null

    /** 顶部行左侧显示的名字（M5 起接频道名；当前由 [setChannel] 填）。 */
    private var iptvSourceName: String = ""

    init {
        setBackgroundColor(UiKit.withAlpha(Palette.background(context), SCRIM_ALPHA))
        isFocusable = true
        isFocusableInTouchMode = true
        visibility = GONE
        // 原版：点遮罩空白即关闭。触屏点击不会与按键导航冲突，故直接监听。
        setOnClickListener { close() }

        // ---------------- 顶部行 ----------------
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24f), dp(20f), dp(24f), 0)
        }
        // SyncAlt 图标的文字占位（原版 Icons.Default.SyncAlt；图标库属新增依赖，M11 再定）。
        // ⚠️ 必须先建 iconHint 再建 sourceLabel：sourceLabel 的焦点回调里要读写它，
        //     Kotlin 按语句顺序初始化，反过来会报 "Variable must be initialized"。
        iconHint = TextView(context).apply {
            text = "⇄"
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            visibility = GONE
        }
        sourceLabel = TextView(context).apply {
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)      // titleLarge

            // -----------------------------------------------------------------
            // 原版 `QuickOpScreeIptvSource` 是**一个可聚焦的 Surface**（不只是文字）：
            //   · 聚焦时整体 alpha 在 0.2 ↔ 1.0 之间呼吸（tween 1000ms）
            //   · 聚焦时右侧多一个 SyncAlt 图标（本工程先用文字占位，见类注释）
            //   · 确认键 → 打开订阅源选择
            // 这是快捷菜单里唯一能从按钮行按左键走到的地方，必须可聚焦。
            // -----------------------------------------------------------------
            id = View.generateViewId()
            isFocusable = true
            isFocusableInTouchMode = true
            breathe.attach(this)
            setOnFocusChangeListener { _, hasFocus ->
                iconHint.visibility = if (hasFocus) View.VISIBLE else View.GONE
                if (hasFocus) breathe.start() else breathe.stop()
                autoClose.active()                                    // 原版 onUserAction
            }
            setOnClickListener {
                autoClose.active()
                onSourceSelect?.invoke()
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    autoClose.active()
                    onSourceSelect?.invoke()
                    true
                } else false
            }
        }
        numberView = TextView(context).apply {
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 45f)      // displayMedium
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        // 原版：ChannelNumber 与 DateTimeDetail 之间是一条 2dp x 30dp 的白色竖线
        val divider = View(context).apply {
            setBackgroundColor(Color.WHITE)
        }
        val dividerWrap = FrameLayout(context).apply {
            setPadding(dp(8f), 0, dp(8f), 0)
            addView(divider, LayoutParams(dp(2f), dp(30f), Gravity.CENTER))
        }
        val dtCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        dateView = TextView(context).apply {
            setTextColor(Palette.onSurfaceVariant(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)      // labelMedium
            gravity = Gravity.END
        }
        timeView = TextView(context).apply {
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)      // titleLarge
            gravity = Gravity.END
        }
        dtCol.addView(dateView)
        dtCol.addView(timeView)

        // SyncAlt 图标用文字占位后，源名与它并排一行（原版是 Row { Text(name); if(focused) Icon(...) }）
        val sourceRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sourceLabel)
            addView(iconHint, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(BTN_GAP_DP) })
        }
        topRow.addView(sourceRow, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(numberView)
        topRow.addView(dividerWrap)
        topRow.addView(dtCol)
        addView(topRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // ---------------- 底部（贴左下） ----------------
        val bottomCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(dp(24f), 0, dp(24f), dp(24f))
        }
        // 频道信息块（M5 的 BottomBarView 接上后会替换这里；M8 先给等宽占位，
        // 保证"7 个按钮贴左下"这个原版视觉特征成立）
        infoHolder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = GONE
        }
        btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ENTRIES.forEachIndexed { idx, (label, action) ->
            val btn = makeButton(label, action)
            buttons += btn
            if (idx == 0) {
                // 原版：从第一个按钮按左键能走到顶部的「当前订阅源」。
                // View 的焦点搜索按几何"束"算，源名与按钮 x 范围重叠，很可能算不出左方向，
                // 所以显式指定 nextFocusLeft，保证这条路一定通。
                btn.setNextFocusLeftId(sourceLabel.id)
                btnRow.addView(btn)
            } else {
                btnRow.addView(btn, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(BTN_GAP_DP) })
            }
        }
        bottomCol.addView(infoHolder)
        bottomCol.addView(btnRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20f) })

        addView(bottomCol, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START
        ))
    }

    private fun makeButton(label: String, action: Action): TextView = TextView(context).apply {
        text = label
        setTextColor(Palette.onSurface(context))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(16f), dp(9f), dp(16f), dp(9f))
        isFocusable = true
        isFocusableInTouchMode = true
        background = UiKit.rounded(UiKit.withAlpha(Palette.surfaceContainerHigh(context), 0.96f), 8f, context)
        setOnFocusChangeListener { v, hasFocus ->
            v.background = if (hasFocus) {
                UiKit.rounded(Palette.onSurface(context), 8f, context)      // 焦点态：白底
            } else {
                UiKit.rounded(UiKit.withAlpha(Palette.surfaceContainerHigh(context), 0.96f), 8f, context)
            }
            (v as TextView).setTextColor(
                if (hasFocus) Palette.background(context) else Palette.onSurface(context)
            )
            if (hasFocus) autoClose.active()                          // 原版 onUserAction
        }
        setOnClickListener {
            autoClose.active()
            onAction?.invoke(action)
        }
        setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                autoClose.active()
                onAction?.invoke(action)
                true
            } else false
        }
        if (action == Action.FAVORITE) favoriteBtn = this
    }

    /**
     * 「收藏」按钮（v1.15 需求 4）。建按钮时登记，[setFavoriteState] 用来改文案。
     * 默认 null —— 万一 ENTRIES 里没这一项也不会 NPE。
     */
    private var favoriteBtn: TextView? = null

    /**
     * 刷新「收藏」按钮的文案。
     *
     * v1.15 需求 4（用户原话）：「默认是显示收藏，点一下就显示已收藏，再点一下有变成收藏」
     * —— 所以这是个**状态可反的按钮**，文案必须跟着当前频道的收藏状态走；
     * 换台后要重新调一次，否则会停在上一台的「已收藏」上。
     *
     * 文案用纯「收藏 / 已收藏」（不带频道名）：按钮就那么宽，带上频道名会被截断，
     * 而且 Snackbar 已经给了「已收藏：XXX」的完整反馈。
     */
    fun setFavoriteState(favorited: Boolean) {
        favoriteBtn?.text = if (favorited) "已收藏" else "收藏"
    }
    /**
     * 打开：清空各按钮焦点并把焦点放到第一项（原版 focusOnLaunched）。
     *
     * v1.15 需求 4 起第一项是「收藏」，不再是「投屏」。
     */
    fun open(channelNumber: String = "", sourceName: String = "", favorited: Boolean = false) {
        iptvSourceName = sourceName
        sourceLabel.text = sourceName
        numberView.text = channelNumber.padStart(2, '0')
        setFavoriteState(favorited)
        visibility = VISIBLE
        bringToFront()
        autoClose.cancel()
        autoClose.active()                                            // 原版：弹出即开始计时
        buttons.firstOrNull()?.post { buttons.firstOrNull()?.requestFocus() }
        if (buttons.isNotEmpty()) buttons[0].requestFocus()
    }

    /** 呼吸闪烁实现（声明见类头部 [breathe]）。 */
    private class Breathe {
        private var view: View? = null
        private var animator: ValueAnimator? = null

        fun attach(v: View) {
            view = v
        }

        fun start() {
            stop()
            val v = view ?: return
            val a = ValueAnimator.ofFloat(1f, 0.2f).apply {
                duration = 1_000L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { anim -> v.alpha = anim.animatedValue as Float }
            }
            a.start()
            animator = a
        }

        fun stop() {
            animator?.cancel()
            animator = null
            view?.alpha = 1f
        }
    }

    /** 关闭：停止计时并回调（MainActivity 据此恢复按键路由）。 */
    fun close() {
        if (visibility != VISIBLE) return
        autoClose.cancel()
        breathe.stop()
        iconHint.visibility = GONE
        visibility = GONE
        onClosed?.invoke()
    }

    override fun onDetachedFromWindow() {
        autoClose.cancel()
        super.onDetachedFromWindow()
    }

    /** 顶部右侧「日期 + HH:mm:ss」每秒刷新（对应原版 QuickOpScreenTop 里的 DateTimeDetail）。 */
    fun tick(now: java.util.Date = java.util.Date()) {
        if (visibility != VISIBLE) return
        dateView.text = DATETIME_DATE.format(now)
        timeView.text = DATETIME_TIME.format(now)
    }

    /** 当前是否可见（MainActivity 的按键路由据此放行方向键）。 */
    val isOpen: Boolean get() = visibility == VISIBLE

    /** 7 个按钮的文案，供验收脚本/日志核对顺序。 */
    fun labels(): List<String> = ENTRIES.map { it.first }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private val DATETIME_DATE = java.text.SimpleDateFormat("MM/dd EEE", java.util.Locale.getDefault())
    private val DATETIME_TIME = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

    /** 供外部（M5 的 BottomBarView）替换频道信息块。 */
    fun setInfoView(v: View) {
        infoHolder.removeAllViews()
        infoHolder.addView(v)
        infoHolder.visibility = VISIBLE
    }

    /** 设置当前频道（用于「多线路」等按钮的回显；M8 阶段仅存引用）。 */
    var channel: Channel? = null
}
