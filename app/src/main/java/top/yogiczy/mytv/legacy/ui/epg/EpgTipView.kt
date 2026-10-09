package top.yogiczy.mytv.legacy.ui.epg

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit

/**
 * 未展开节目单时的竖排提示条。
 *
 * 对应原版 `ClassicEpgItemList.kt` 里那段：
 * ```kotlin
 * "向右查看节目单".map { Text(text = it.toString(), style = typography.labelSmall) }
 * ```
 * 原版是**逐字一个 Text**，所以是竖着排的 —— 这里同样逐字一个 TextView，不做合并。
 */
class EpgTipView(context: Context) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(s(8f), s(40f), s(8f), s(30f))
        isFocusable = true
        isFocusableInTouchMode = true
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        "向右查看节目单".forEach { c ->
            addView(TextView(context).apply {
                text = c.toString()
                setTextColor(Palette.onSurfaceVariant(context))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
        }
    }

    private fun s(dp: Float): Int = UiKit.dp(context, dp)
}
