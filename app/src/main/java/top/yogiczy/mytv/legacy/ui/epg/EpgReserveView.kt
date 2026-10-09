package top.yogiczy.mytv.legacy.ui.epg

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import top.yogiczy.mytv.legacy.data.EpgProgramme
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 预约列表。
 *
 * 对应原版 `EpgReverseScreen`（M9「预约」分类要用）。M7 先把容器与渲染做好，
 * 数据源由 M9 的「预约」设置项提供 —— **本模块内不新增持久化**（避免重复造键）。
 */
class EpgReserveView(context: Context) : LinearLayout(context) {

    private val empty = TextView(context).apply {
        text = "还没有预约"
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(Palette.onSurfaceVariant(context))
    }

    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    init {
        orientation = LinearLayout.VERTICAL
        setPadding(UiKit.dp(context, 12f), UiKit.dp(context, 12f),
            UiKit.dp(context, 12f), UiKit.dp(context, 12f))
    }

    /** @param onCancel 点了某一项 = 取消预约 */
    fun submit(list: List<Pair<String, EpgProgramme>>, onCancel: (EpgProgramme) -> Unit) {
        removeAllViews()
        if (list.isEmpty()) {
            addView(empty)
            return
        }
        list.forEach { (channelName, p) ->
            addView(TextView(context).apply {
                text = "$channelName · ${fmt.format(java.util.Date(p.startAt))} · ${p.title}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Palette.onSurface(context))
                maxLines = 1
                isFocusable = true
                background = UiKit.rounded(
                    UiKit.withAlpha(Palette.surfaceContainer(context), 0.92f), 8f, context
                )
                setPadding(UiKit.dp(context, 10f), UiKit.dp(context, 8f),
                    UiKit.dp(context, 10f), UiKit.dp(context, 8f))
                setOnClickListener { onCancel(p) }
            }, LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = UiKit.dp(context, 6f) })
        }
    }
}
