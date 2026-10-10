package top.yogiczy.mytv.legacy.ui.epg

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import top.yogiczy.mytv.legacy.data.EpgProgramme
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit
import java.text.SimpleDateFormat
import java.util.Locale
import top.yogiczy.mytv.legacy.data.EpgProgramme.Companion.progress
import top.yogiczy.mytv.legacy.data.EpgProgramme.Companion.remainingMinutes

/**
 * 常驻节目进度条。
 *
 * 对应原版 `EpgProgrammeProgressScreen`。
 *
 * ⚠️ v1.15 需求 3（用户）：「常驻底部节目进度」**设置项已删除**。原来它由
 * `UI_SHOW_EPG_PROGRAMME_PERMANENT_PROGRESS`（默认关）控制，现在改成
 * **有当前节目就显示、没有就自动收起**（`setEnabledByPref(now != null)`）——
 * 与「频道项底部进度条」那个开关各管一处，不再需要手动开关。
 */
class EpgProgressView(context: Context) : LinearLayout(context) {

    private val title = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(Palette.onSurface(context))
        maxLines = 1
    }
    private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val remain = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(Palette.onSurfaceVariant(context))
        maxLines = 1
    }

    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    init {
        orientation = VERTICAL
        gravity = Gravity.END
        setPadding(UiKit.dp(context, 12f), UiKit.dp(context, 8f),
            UiKit.dp(context, 12f), UiKit.dp(context, 8f))
        background = UiKit.rounded(
            UiKit.withAlpha(Palette.surface(context), 0.72f), 10f, context
        )
        addView(title, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        addView(bar, LayoutParams(UiKit.dp(context, 260f), UiKit.dp(context, 6f)).apply {
            topMargin = UiKit.dp(context, 4f)
        })
        addView(remain, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        visibility = GONE
    }

    /** 传入当前节目；null 时隐藏。 */
    fun update(p: EpgProgramme?) {
        if (p == null) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        title.text = "${fmt.format(java.util.Date(p.startAt))} ${p.title}"
        bar.progress = (p.progress() * 100).toInt().coerceIn(0, 100)
        bar.progressDrawable?.setColorFilter(
            Color.parseColor("#A8C7FA"), android.graphics.PorterDuff.Mode.SRC_IN
        )
        remain.text = "剩余 ${p.remainingMinutes()} 分钟"
    }

    /** 开关（默认关）。 */
    fun setEnabledByPref(enabled: Boolean) {
        visibility = if (enabled) VISIBLE else GONE
    }
}
