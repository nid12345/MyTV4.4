package top.yogiczy.mytv.legacy.ui.epg

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit

/**
 * 节目单的**日期列**（仅跨天时出现）。
 *
 * 对应原版 `EpgScreen.kt` 里的 `EpgDayItemList(width = 80.dp)`：
 * 分组键是 `SimpleDateFormat("E MM-dd", Locale.getDefault())`，默认选中今天。
 * 本工程（M7 形态确认：竖排列表 + 日期列，**不做时间轴**）只在节目跨天时才显示这一列。
 */
class EpgDayColumnView(context: Context) : LinearLayout(context) {

    /** 日期键 → 该天的节目在整份列表中的起始下标。 */
    private val days = ArrayList<Pair<String, Int>>()
    private var selectedIndex = 0
    var onDaySelected: ((Int) -> Unit)? = null

    init {
        orientation = VERTICAL
        setPadding(UiKit.dp(context, 4f), UiKit.dp(context, 6f), UiKit.dp(context, 4f), 0)
        layoutParams = LinearLayout.LayoutParams(
            UiKit.dp(context, 80f), ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    /** 传入日期键（已按时间排序）与该天起始下标。 */
    fun submit(list: List<Pair<String, Int>>, selected: Int) {
        days.clear()
        days.addAll(list)
        selectedIndex = selected.coerceIn(0, (days.size - 1).coerceAtLeast(0))
        rebuild()
    }

    private fun rebuild() {
        removeAllViews()
        days.forEachIndexed { i, (label, _) ->
            addView(TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(UiKit.dp(context, 6f), UiKit.dp(context, 8f),
                    UiKit.dp(context, 6f), UiKit.dp(context, 8f))
                background = UiKit.rounded(
                    if (i == selectedIndex) Palette.onSurface(context)
                    else UiKit.withAlpha(Palette.surfaceContainer(context), 0.92f),
                    8f, context
                )
                setTextColor(
                    if (i == selectedIndex) 0xFF101014.toInt() else Palette.onSurface(context)
                )
                setOnFocusChangeListener { _, hasFocus ->
                    background = UiKit.rounded(
                        if (hasFocus || i == selectedIndex) Palette.onSurface(context)
                        else UiKit.withAlpha(Palette.surfaceContainer(context), 0.92f),
                        8f, context
                    )
                    setTextColor(
                        if (hasFocus || i == selectedIndex) 0xFF101014.toInt()
                        else Palette.onSurface(context)
                    )
                }
                setOnClickListener {
                    selectedIndex = i
                    rebuild()
                    onDaySelected?.invoke(days[i].second)
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = UiKit.dp(context, 4f) })
        }
    }

    /** 跨天才显示（原版一致）。 */
    fun setCrossDay(crossDay: Boolean) {
        visibility = if (crossDay) View.VISIBLE else View.GONE
    }
}
