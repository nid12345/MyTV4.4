package top.yogiczy.mytv.legacy.ui.settings

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import top.yogiczy.mytv.legacy.R
import top.yogiczy.mytv.legacy.data.IptvSource
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit

/**
 * 「管理现有直播源」弹窗（v1.15 需求 2）。
 *
 * ---------------------------------------------------------------------------
 * 用户原话：
 *   「在总设置菜单里的直播源-自定义直播源，点开不要调到左侧菜单那了，
 *     在总设置菜单页的自定义直播源选项点开加个管理现有直播源的弹窗，
 *     包含添加和删除的选项，点添加的话调用可以隔空传的那个窗口出来」
 *
 * 与原版 / 本工程既有实现的关系：
 *   · 原版没有这一层；本工程 v1.14 及以前这里是"关设置页 → 展开左侧面板 → 跳到换源"
 *     （问题 7 的修法），用户明确说"不要调到左侧菜单那了"。
 *   · 结构与 `EpgSourceView`（节目单管理抽屉）刻意保持一致：同样是底部抽屉 +
 *     「点按切换 + 删除键/长按删除 + 末尾添加行」，用户两处操作习惯不用重新学。
 *
 * 与 `EpgSourceView` 的差别（需求 5 的预演）：
 *   · 删除前**必须弹确认框**（用户要求"跳出一个是否确认删除对应订阅源的弹窗选项，
 *     可以选择保留或是删除"）—— 直播源比节目单贵，删错没有"内置"兜底。
 *   · 有**保底源**：列表中第一个（默认自建源）不可删，见 [Row.removable]。
 * ---------------------------------------------------------------------------
 */
class SourceManageView(context: Context) : FrameLayout(context) {

    /** 列表里的一行。 */
    data class Row(
        val source: IptvSource,
        val isCurrent: Boolean,
        /** false = 保底源，不可删除（用户：「保底自建源这个除外，这个保底避免全部都删除了用不了」）。 */
        val removable: Boolean,
    )

    /** 返回键 / 点遮罩时关闭。 */
    var onClose: (() -> Unit)? = null
    /** 点按某一行 = 切换到该订阅源。 */
    var onSelect: ((IptvSource) -> Unit)? = null
    /** 长按 / 删除键 = 删除该订阅源（保底源不会触发，见 [sourceRow]）。 */
    var onDelete: ((IptvSource) -> Unit)? = null
    /** 点「添加」行 = 打开添加对话框（带扫码）。 */
    var onAdd: (() -> Unit)? = null

    private val listColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var provider: (() -> List<Row>)? = null

    init {
        setBackgroundColor(0xCC000000.toInt())
        isFocusable = true
        isFocusableInTouchMode = true
        // 与 EpgSourceView 同一约定：子视图优先取焦，否则列表行的 requestFocus 会被根节点截胡
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.rounded(Palette.surface(context), 14f, context)
            setPadding(s(20f), s(16f), s(20f), s(16f))
        }

        panel.addView(TextView(context).apply {
            text = "管理直播源"
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
        })
        panel.addView(TextView(context).apply {
            text = "点按切换订阅源 · 长按或按删除键移除（有确认提示）· 保底源不可删除"
            setTextColor(Palette.onSurfaceVariant(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, s(4f), 0, s(10f))
        })

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(listColumn, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        addView(panel, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            gravity = Gravity.BOTTOM
            leftMargin = s(60f); rightMargin = s(60f); bottomMargin = s(48f)
        })
        // 比节目单管理高一点：直播源通常更多，删除前还要留出确认框的空间
        (panel.layoutParams as LayoutParams).height =
            (resources.displayMetrics.heightPixels * 0.72f).toInt()

        // 与 EpgSourceView 同一条教训：点遮罩空白关闭必须由本类根节点处理，
        // 放在宿主的 onScreenTap 里会因 ACTION_UP 时序把行的点击一起吞掉。
        setOnClickListener { onClose?.invoke() }
        panel.setOnClickListener { /* 吃掉点击，避免点到面板本体时把抽屉一起关掉 */ }
    }

    fun show(provider: () -> List<Row>) {
        this.provider = provider
        render()
    }

    /** 重绘列表（切换/删除后由宿主调用）。同 GroupManageView：行集合没变时只改状态。 */
    fun render() {
        val rows = provider?.invoke().orEmpty()
        if (listColumn.childCount == rows.size + 1) {
            rows.forEachIndexed { i, row ->
                listColumn.getChildAt(i).findViewById<TextView>(R.id.src_manage_status)?.apply {
                    text = if (row.isCurrent) "使用中" else ""
                }
            }
            return
        }

        listColumn.removeAllViews()
        var first: View? = null
        rows.forEach { row ->
            val v = sourceRow(row)
            if (row.isCurrent || first == null) first = v
            listColumn.addView(v)
        }
        listColumn.addView(addRow())
        first?.post { first?.requestFocus() }
    }

    private fun sourceRow(row: Row): LinearLayout {
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(s(12f), s(10f), s(12f), s(10f))
            background = UiKit.rounded(
                UiKit.withAlpha(Palette.surfaceContainer(context), 0.9f), 8f, context)
        }
        item.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = s(6f) }

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = TextView(context).apply {
            text = row.source.name
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val status = TextView(context).apply {
            id = R.id.src_manage_status
            text = if (row.isCurrent) "使用中" else ""
            setTextColor(Palette.primary(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
        }
        top.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = s(12f) })

        val url = TextView(context).apply {
            text = row.source.url + if (!row.removable) " · 保底源（不可删除）" else ""
            setTextColor(UiKit.withAlpha(Palette.onSurfaceVariant(context), 0.85f))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        item.addView(top)
        item.addView(url)

        item.setOnFocusChangeListener { _, hasFocus ->
            item.background = UiKit.rounded(
                if (hasFocus) Palette.onSurface(context)
                else UiKit.withAlpha(Palette.surfaceContainer(context), 0.9f),
                8f, context
            )
            name.setTextColor(if (hasFocus) 0xFF101014.toInt() else Palette.onSurface(context))
            url.setTextColor(if (hasFocus) 0xFF3C4043.toInt()
            else UiKit.withAlpha(Palette.onSurfaceVariant(context), 0.85f))
            status.setTextColor(if (hasFocus) 0xFF1A73E8.toInt() else Palette.primary(context))
        }
        item.setOnClickListener { onSelect?.invoke(row.source) }
        if (row.removable) {
            item.setOnLongClickListener { onDelete?.invoke(row.source); true }
            item.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_UP &&
                    (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_MENU)
                ) {
                    onDelete?.invoke(row.source)
                    true
                } else false
            }
        }
        return item
    }

    private fun addRow(): TextView {
        val v = TextView(context).apply {
            text = "+ 添加直播源（支持手机扫码填写）"
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(s(12f), s(12f), s(12f), s(12f))
            background = UiKit.rounded(
                UiKit.withAlpha(Palette.surfaceContainer(context), 0.9f), 8f, context)
        }
        v.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = s(10f) }
        v.setOnFocusChangeListener { _, hasFocus ->
            v.background = UiKit.rounded(
                if (hasFocus) Palette.onSurface(context)
                else UiKit.withAlpha(Palette.surfaceContainer(context), 0.9f),
                8f, context
            )
            v.setTextColor(if (hasFocus) 0xFF101014.toInt() else Palette.onSurface(context))
        }
        v.setOnClickListener { onAdd?.invoke() }
        return v
    }

    private fun s(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()
}
