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

    /**
     * 源列表 → 行模型（原 `renderSourcePanel` 的 rows 构造）。
     *
     * v1.13 需求 3（用户）：**跟原版一样，不留头像的位置；直接显示直播源名字，网址不显示**。
     *
     * 改动前每行是「源名 + 网址」，且行首留着一个 40dp 的图标位（`PanelRowView.iconView`
     * 用 INVISIBLE 占位，还带 10dp 右边距）—— 于是源名被无谓地往右推了 50dp，
     * 用户截图里那块空白就是它。
     *
     * 现在：`noIcon = true` 把图标位**整个去掉**；副标题不再放网址；
     * 当前在用的源改用行尾「使用中」角标标识（原版就是用角标而不是副标题）。
     * 网址仍然可用 —— 长按行删除源时的确认弹窗里会带出来，不占列表版面。
     *
     * v1.15 需求 5（用户）：「点按一下就是切换对应的源，如果是长按ok键或是触碰长按对应
     * 订阅源的位置，跳出一个是否确认删除对应订阅源的弹窗选项…保底自建源这个除外，
     * 这个保底避免全部都删除了用不了，留这个自建源保底，这个长按就不用弹窗了」。
     *
     * 实现：`removable = 列表下标 != 0`（`Prefs.defaultSources()` 的第一项恒为
     * 「自建源」，见 [Prefs.defaultSource]）；保底源**长按不弹确认框**。
     *
     * ⚠️ 保底源这里给的是一个**什么都不做但返回 true** 的长按监听，而不是 `null`：
     * 实测（v1.15 在机验证）传 null 时事件会冒泡到宿主的手势识别，
     * 长按保底源反而**唤出了快捷功能菜单** —— 用户要的是"这个长按就不用弹窗了"，
     * 即彻底无反应。传空 lambda 只消费事件、不做任何事。
     */
    fun rows(
        sources: List<IptvSource>,
        currentUrl: String,
        onSwitch: (IptvSource) -> Unit,
        onRemove: (IptvSource) -> Unit,
        onAdd: () -> Unit,
    ): List<ChannelColumnView.Row> {
        val rows = ArrayList<ChannelColumnView.Row>()
        sources.forEachIndexed { index, src ->
            val removable = index != 0          // 第一个 = 保底源
            rows.add(
                ChannelColumnView.Row.TextRow(
                    title = src.name,
                    subtitle = null,
                    icon = null,
                    onClick = { onSwitch(src) },
                    onLongClick = if (removable) ({ onRemove(src) }) else ({ /* 保底源：吃掉长按 */ }),
                    noIcon = true,
                    trailing = if (src.url == currentUrl) "使用中" else null,
                )
            )
        }
        rows.add(
            ChannelColumnView.Row.TextRow(
                "＋ 添加直播源", null, null,
                onClick = { onAdd() },
                noIcon = true,
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
