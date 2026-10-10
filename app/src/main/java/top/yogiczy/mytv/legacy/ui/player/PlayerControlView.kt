package top.yogiczy.mytv.legacy.ui.player

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import top.yogiczy.mytv.legacy.R
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit

/**
 * 「播放控制」弹层（用户需求 5）。
 *
 * ---------------------------------------------------------------------------
 * 需求原文：「播放控制点开添加一个调整播放的进度条」。
 *
 * 取代原来那个「点一下 = 切播放/暂停 + 弹一句提示」的等价动作（见 MainActivity 的
 * `QuickOpView.Action.CONTROLLER`），现在是一个真正的控制面板：
 *
 *   ┌ 播放控制                                  线路 2/5 · 硬解 · 820KB/s ┐
 *   │ 01:23 ·━━━━━━━━━●───────────────· 45:00                        │
 *   │ [ ⏸ 暂停 ] [ ⏪ 快退 30 秒 ] [ ⏩ 快进 30 秒 ] [ 多线路 ]          │
 *   └──────────────────────────────────────────────────────────────────┘
 *
 * **进度条对直播与点播的行为不同**（这是刻意区分的，不是没做完）：
 *   · 点播（`durationMs > 0`）：可拖动。触屏拖动 + 遥控器左右键都能调，
 *     松手 / 停止按键 700ms 后落到实际位置（避免一路拨过去每 1 格都 seek）。
 *   · 直播（`durationMs <= 0`）：时长未知，**禁用拖动**，条上显示「直播中」。
 *     快进/快退按钮仍可用 —— HLS 直播的时移窗口内 seek 是有效的。
 *
 * 焦点：根节点 `FOCUS_AFTER_DESCENDANTS`，打开时聚焦进度条，
 * 这样遥控器一进来就能左右调；按上/下可到按钮行。
 * ---------------------------------------------------------------------------
 */
class PlayerControlView(context: Context) : FrameLayout(context) {

    /** 关闭（返回键 / 点遮罩）。 */
    var onClose: (() -> Unit)? = null

    /** 绝对定位（毫秒）。 */
    var onSeekTo: ((Long) -> Unit)? = null

    /** 相对快进/快退（毫秒，负数为后退）。 */
    var onSeekBy: ((Long) -> Unit)? = null

    /** 播放 / 暂停切换。 */
    var onTogglePlay: (() -> Unit)? = null

    /** 打开「多线路」选框。 */
    var onPickLine: (() -> Unit)? = null

    private val infoView = TextView(context)
    private val posView = TextView(context)
    private val durView = TextView(context)
    private val seekBar: SeekBar
    private val playBtn: TextView
    private val rewindBtn: TextView
    private val forwardBtn: TextView
    private val lineBtn: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSeekMs = 0L
    private var durationMs = 0L
    private var userAdjusting = false

    /** 贴底部居中的面板本体（[isInContent] 判定用）。 */
    private lateinit var panel: LinearLayout

    private val commitSeek = Runnable {
        userAdjusting = false
        if (durationMs > 0L) onSeekTo?.invoke(pendingSeekMs)
    }

    init {
        setBackgroundColor(0x99000000.toInt())
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.rounded(Palette.surface(context), 14f, context)
            setPadding(s(20f), s(16f), s(20f), s(16f))
        }
        this.panel = panel

        // ---- 标题行：标题（左） + 线路/解码信息（右） ----
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "播放控制"
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        }
        infoView.apply {
            setTextColor(Palette.onSurfaceVariant(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.END
        }
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(infoView)
        panel.addView(head)

        // ---- 进度条行：当前（左） 条 总时长（右） ----
        val barRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, s(12f), 0, s(4f))
        }
        posView.apply {
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        durView.apply {
            setTextColor(Palette.onSurfaceVariant(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.END
        }

        seekBar = SeekBar(context).apply {
            id = R.id.player_seek_bar
            max = 100
            progress = 0
            isFocusable = true
            isFocusableInTouchMode = true
            // API 19 没有 progressTintList，只能整条换 drawable（LayerDrawable 约定见下）
            progressDrawable = buildTrack()
            thumb = buildThumb()
            // ⚠️ `splitTrack` **是 API 21 才加的方法**。这里 minSdk=19 / compileSdk=34，
            // 所以编译期完全不报错，但真机（API 19）一进这个构造函数就
            // `NoSuchMethodError: android.widget.SeekBar.setSplitTrack` → 直接闪退。
            // 我们要的只是"轨道不被滑块切断"；API 19 的 AbsSeekBar 默认就不切断，
            // 因此低版本**直接跳过**即可，不需要反射兜底。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) splitTrack = false
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                // ⚠️ 必须在"用户调过"期间挡住宿主的每秒刷新，否则刚拨到 30 分钟、
                // 下一秒就被 bind() 拉回真实播放位置，手感是"根本拨不动"。
                userAdjusting = true
                pendingSeekMs = progress.toLong() * 1000L
                posView.text = fmt(pendingSeekMs)
                // 遥控器左右键**不会**回调 onStopTrackingTouch，只能靠 debounce 落位
                handler.removeCallbacks(commitSeek)
                handler.postDelayed(commitSeek, 700L)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                userAdjusting = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                userAdjusting = false
                handler.removeCallbacks(commitSeek)
                pendingSeekMs = sb.progress.toLong() * 1000L
                if (durationMs > 0L) onSeekTo?.invoke(pendingSeekMs)
            }
        })
        // 确认键 = 立即落位（默认 SeekBar 对 DPAD_CENTER 无动作，这里补上）
        seekBar.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                handler.removeCallbacks(commitSeek)
                pendingSeekMs = seekBar.progress.toLong() * 1000L
                if (durationMs > 0L) onSeekTo?.invoke(pendingSeekMs)
                true
            } else false
        }
        seekBar.setOnFocusChangeListener { v, hasFocus ->
            v.background = if (hasFocus) {
                UiKit.rounded(UiKit.withAlpha(Palette.primary(context), 0.18f), 8f, context)
            } else null
        }

        barRow.addView(posView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        barRow.addView(seekBar, LinearLayout.LayoutParams(0, s(40f), 1f).apply {
            marginStart = s(10f); marginEnd = s(10f)
        })
        barRow.addView(durView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(barRow)

        // ---- 按钮行 ----
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, s(10f), 0, 0)
        }
        playBtn = makeButton("⏸ 暂停") { onTogglePlay?.invoke() }
        rewindBtn = makeButton("⏪ 快退 30 秒") { onSeekBy?.invoke(-30_000L) }
        forwardBtn = makeButton("⏩ 快进 30 秒") { onSeekBy?.invoke(+30_000L) }
        lineBtn = makeButton("多线路") { onPickLine?.invoke() }
        listOf(playBtn, rewindBtn, forwardBtn, lineBtn).forEachIndexed { i, b ->
            btnRow.addView(b, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (i > 0) marginStart = s(8f) })
        }
        panel.addView(btnRow)

        val panelLp = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            leftMargin = s(48f); rightMargin = s(48f); bottomMargin = s(32f)
        }
        addView(panel, panelLp)

        setOnClickListener { onClose?.invoke() }
        panel.setOnClickListener { /* 吃掉点击，避免点到面板内部时把弹层关掉 */ }
    }

    /** 水平进度条：轨道 + 已播放（LayerDrawable 的 id 约定是 SeekBar 的硬要求）。 */
    private fun buildTrack(): LayerDrawable {
        val track = UiKit.rounded(UiKit.withAlpha(Palette.onSurfaceVariant(context), 0.35f), 4f, context)
        val played = ClipDrawable(
            UiKit.rounded(Palette.primary(context), 4f, context),
            Gravity.START, ClipDrawable.HORIZONTAL
        )
        val layer = LayerDrawable(arrayOf(track, played))
        layer.setId(0, android.R.id.background)
        layer.setId(1, android.R.id.progress)
        return layer
    }

    private fun buildThumb(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Palette.primary(context))
        setSize(s(16f), s(16f))
    }

    private fun makeButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextColor(Palette.onSurface(context))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(s(14f), s(9f), s(14f), s(9f))
        isFocusable = true
        isFocusableInTouchMode = true
        background = UiKit.rounded(
            UiKit.withAlpha(Palette.surfaceContainerHigh(context), 0.96f), 8f, context)
        setOnFocusChangeListener { v, hasFocus ->
            v.background = UiKit.rounded(
                if (hasFocus) Palette.onSurface(context)
                else UiKit.withAlpha(Palette.surfaceContainerHigh(context), 0.96f),
                8f, context
            )
            (v as TextView).setTextColor(
                if (hasFocus) Palette.background(context) else Palette.onSurface(context))
        }
        setOnClickListener { onClick() }
        setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                onClick(); true
            } else false
        }
    }

    /** 打开时先给个焦点，随后 [bind] 会按"直播/点播"重新定位到该拿焦点的那个控件。 */
    fun open() {
        visibility = VISIBLE
        bringToFront()
        seekBar.post { if (!seekBar.requestFocus()) playBtn.requestFocus() }
    }

    val isOpen: Boolean get() = visibility == VISIBLE

    /**
     * 触摸点是否落在弹层**本体**内。
     *
     * 宿主 `MainActivity` 用它判断"点弹层以外 = 关闭"。**不能**直接用 `panel.left/right`
     * （那是相对父容器的坐标），必须用 `getGlobalVisibleRect` 取真正的屏幕坐标。
     */
    fun isInContent(x: Float, y: Float): Boolean {
        if (!::panel.isInitialized) return false
        val r = android.graphics.Rect()
        return panel.getGlobalVisibleRect(r) && x >= r.left && x <= r.right && y >= r.top && y <= r.bottom
    }

    /** 首次 [bind] 才算得清该聚焦谁（直播时进度条不可拖，焦点应落在「暂停」上）。 */
    private var focusDecided = false

    /** 每秒（或每 500ms）由宿主刷新。 */
    fun bind(
        playing: Boolean,
        positionMs: Long,
        durationMs: Long,
        lineIdx: Int,
        lineTotal: Int,
        softDecode: Boolean,
        speedKBps: Int,
    ) {
        if (visibility != VISIBLE) return
        this.durationMs = durationMs

        infoView.text = buildString {
            append("线路 ${lineIdx + 1}/$lineTotal")
            append(" · ").append(if (softDecode) "软解" else "硬解")
            if (speedKBps > 0) append(" · ${speedKBps}KB/s")
        }
        playBtn.text = if (playing) "⏸ 暂停" else "▶ 播放"

        if (durationMs > 0L) {
            // 点播：可拖动
            val secs = (durationMs / 1000L).coerceAtLeast(1L).toInt()
            if (seekBar.max != secs) seekBar.max = secs
            if (!userAdjusting) {
                val cur = (positionMs / 1000L).coerceIn(0L, secs.toLong()).toInt()
                seekBar.progress = cur
                pendingSeekMs = positionMs
                posView.text = fmt(positionMs)
            }
            durView.text = fmt(durationMs)
        } else {
            // 直播：时长未知 → 条走满、禁用拖动
            if (seekBar.max != 100) seekBar.max = 100
            if (!userAdjusting) {
                seekBar.progress = 100
                posView.text = "直播中"
            }
            durView.text = "时移窗口内可快进/快退"
        }
        seekBar.isEnabled = durationMs > 0L
        // ⚠️ 顺序要紧：`setEnabled(false)` 会把焦点能力一起收掉（API 19 实测），
        // 之后必须显式恢复 focusable，否则弹层打开后焦点无处可去、方向键全都不动。
        seekBar.isFocusable = true
        seekBar.isFocusableInTouchMode = true

        if (!focusDecided) {
            focusDecided = true
            if (durationMs > 0L) seekBar.requestFocus() else playBtn.requestFocus()
        }
    }

    private fun fmt(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val total = ms / 1000L
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    private fun s(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()
}
