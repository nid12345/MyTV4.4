package top.yogiczy.mytv.legacy.ui.epg

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import top.yogiczy.mytv.legacy.data.EpgProgramme
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit
import java.text.SimpleDateFormat
import java.util.Locale
import top.yogiczy.mytv.legacy.data.EpgProgramme.Companion.isLive

/**
 * 节目项：`HH:mm    节目名` + 尾标。
 *
 * ---------------------------------------------------------------------------
 * 对应原版 `tv/.../ui/screens/epg/components/EpgProgrammeItem.kt`：
 *   - 时间 `HH:mm`（原版用 `SimpleDateFormat("HH:mm")`）
 *   - 未聚焦 `maxLines = 1`，**聚焦时展开多行**（原版 `maxLines = if (selected) Int.MAX_VALUE else 1`）
 *   - 正在播：`selected = programme.isLive()` + 尾部 `PlayArrow` 图标
 *   - 尾标语义（原版）：正在播 → ▶；正在回放 → 「正在回放」；已结束且可回放 → 「回放」；
 *     未开始 → 「预约」/「已预约」
 * ---------------------------------------------------------------------------
 */
class EpgProgrammeItemView(context: Context) : LinearLayout(context) {

    private val timeView: TextView
    private val titleView: TextView
    private val tailView: TextView

    private val bgNormal = UiKit.rounded(
        UiKit.withAlpha(Palette.surfaceContainer(context), 0.92f), 8f, context
    )
    private val bgLive = UiKit.rounded(
        UiKit.withAlpha(Palette.primary(context), 0.22f), 8f, context
    )
    private val bgFocus = UiKit.rounded(Palette.onSurface(context), 8f, context)

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** 当前绑定的节目（供外层点击回调取用）。 */
    var programme: EpgProgramme? = null
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isFocusableInTouchMode = true
        setPadding(s(10f), s(6f), s(10f), s(6f))
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = s(4f) }

        timeView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
        }
        titleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
        }
        tailView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
        }

        addView(timeView, LayoutParams(s(52f), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(titleView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(tailView, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = s(6f) })

        background = bgNormal
        setOnFocusChangeListener { _, hasFocus -> applyStyle(hasFocus) }
    }

    /**
     * @param reserved 该节目是否已预约（外层按 Prefs 传入）
     * @param replaying 该节目是否正在回放
     */
    fun bind(p: EpgProgramme, reserved: Boolean, replaying: Boolean) {
        programme = p
        val live = p.isLive()
        val ended = p.endAt < System.currentTimeMillis()
        val notStarted = p.startAt > System.currentTimeMillis()

        timeView.text = timeFmt.format(java.util.Date(p.startAt))
        titleView.text = p.title

        tailView.text = when {
            replaying -> "正在回放"
            live -> "▶"
            ended -> "回放"
            notStarted -> if (reserved) "已预约" else "预约"
            else -> ""
        }

        bgLiveFlag = live
        applyStyle(isFocused)
    }

    private var bgLiveFlag = false

    private fun applyStyle(focused: Boolean) {
        background = if (focused) bgFocus else if (bgLiveFlag) bgLive else bgNormal
        // 未聚焦 maxLines=1，聚焦展开多行（原版语义）
        titleView.maxLines = if (focused) 3 else 1
        val on = if (focused) 0xFF101014.toInt() else Palette.onSurface(context)
        val sub = if (focused) 0xFF3C4043.toInt() else Palette.onSurfaceVariant(context)
        timeView.setTextColor(if (bgLiveFlag && !focused) Palette.primary(context) else sub)
        titleView.setTextColor(on)
        tailView.setTextColor(if (focused) sub else Palette.primary(context))
    }

    /** 供 RecyclerView 复用时清空焦点态残留。 */
    fun onRecycled() {
        programme = null
        bgLiveFlag = false
        background = bgNormal
        titleView.maxLines = 1
    }

    private fun s(dp: Float): Int = UiKit.dp(context, dp)
}
