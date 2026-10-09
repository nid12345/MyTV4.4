package top.yogiczy.mytv.legacy.ui.panel

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import top.yogiczy.mytv.legacy.data.IptvSource

/**
 * 换源面板（左导航「换源」时的中栏内容）。
 *
 * M10 从 `MainActivity` 搬来（M6 遗留的拆分项），**行为一字不改**，只搬家：
 * 列表行模型、添加源的输入框容器。真正的弹窗仍由宿主弹出（对话框需要 Activity）。
 */
class SourcePanelView(private val ctx: Context) {

    /** 源列表 → 行模型（原 `renderSourcePanel` 的 rows 构造）。 */
    fun rows(
        sources: List<IptvSource>,
        currentUrl: String,
        onSwitch: (IptvSource) -> Unit,
        onRemove: (IptvSource) -> Unit,
        onAdd: () -> Unit,
    ): List<ChannelColumnView.Row> {
        val rows = ArrayList<ChannelColumnView.Row>()
        sources.forEach { src ->
            rows.add(
                ChannelColumnView.Row.TextRow(
                    title = src.name,
                    subtitle = if (src.url == currentUrl) "当前使用 · ${src.url}" else src.url,
                    onClick = { onSwitch(src) },
                    onLongClick = { onRemove(src) },
                )
            )
        }
        rows.add(
            ChannelColumnView.Row.TextRow(
                "＋ 添加直播源", "点击输入地址 · 长按已有项可删除", null,
                onClick = { onAdd() },
            )
        )
        return rows
    }

    /** 添加源对话框的输入框容器（原 `addSourceDialog` 的 box）。 */
    fun inputBox(nameInput: EditText, urlInput: EditText): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
            addView(nameInput, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(urlInput, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    /** 系统应用面板用的应用条目。 */
    data class AppItem(val label: String, val pkg: String, val icon: Drawable?)
}
