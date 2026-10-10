package top.yogiczy.mytv.legacy.ui.epg

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
import top.yogiczy.mytv.legacy.data.EpgSource
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit

/**
 * 「节目单管理」底部抽屉（v1.8）。
 *
 * ---------------------------------------------------------------------------
 * 对应原版 MyTV 1.7：
 *   `tv/ui/screens/epgsource/EpgSourceScreen.kt`
 *
 * 原版是 `Drawer(DrawerPosition.Bottom)` + `LazyColumn(height = 240.dp)`，
 * 列表 = `Constants.EPG_SOURCE_LIST + 用户自定义`，末项「添加自定义节目单」；
 * 每项可删除（自定义源）、点击即选中。
 *
 * 本工程按同一结构用原生 View 实现（API 19 没有 Compose / tv-material）：
 *   · 全屏半透明遮罩 + 底部深色面板（与「分组管理」同一套）；
 *   · 列表项：名称 + 地址，当前使用的那一项标「使用中」；
 *   · **点击 = 切到这个节目单**；
 *   · **长按（触屏）/ 删除键（遥控器）= 删除**（内置节目单不可删）；
 *   · 末项「添加自定义节目单」→ 走宿主的录入对话框。
 * ---------------------------------------------------------------------------
 */
class EpgSourceView(context: Context) : FrameLayout(context) {

    /** 列表里的一行。 */
    data class Row(
        val source: EpgSource,
        val isCurrent: Boolean,
        /** false = 内置节目单，不允许删除。 */
        val deletable: Boolean,
    )

    /** 返回键 / 点遮罩时关闭。 */
    var onClose: (() -> Unit)? = null
    /** 选中某个节目单（宿主负责落库并重拉节目单）。 */
    var onSelect: ((EpgSource) -> Unit)? = null
    /** 删除某个自定义节目单。 */
    var onDelete: ((EpgSource) -> Unit)? = null
    /** 添加自定义节目单。 */
    var onAdd: (() -> Unit)? = null

    private val listColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var provider: (() -> List<Row>)? = null

    init {
        setBackgroundColor(0xCC000000.toInt())
        isFocusable = true
        isFocusableInTouchMode = true
        // v1.8：子视图优先取焦 —— 否则下面 render() 里对列表行的 requestFocus() 会被本根节点
        // 截胡，遥控器要按一次方向键才进得了列表（与《使用须知》同一类问题）。
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.rounded(Palette.surface(context), 14f, context)
            setPadding(s(20f), s(16f), s(20f), s(16f))
        }

        panel.addView(TextView(context).apply {
            text = "节目单管理"
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
        })
        panel.addView(TextView(context).apply {
            text = "点击切换节目单 · 长按或按删除键移除自定义节目单 · 列表顶部为内置节目单"
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
        (panel.layoutParams as LayoutParams).height =
            (resources.displayMetrics.heightPixels * 0.66f).toInt()
    }

    fun show(provider: () -> List<Row>) {
        this.provider = provider
        render()
    }

    fun render() {
        val rows = provider?.invoke().orEmpty()
        val toRemove = ArrayList<View>()
        // 同 GroupManageView：能只更新状态就不要整列重建（重建会丢焦点）
        if (listColumn.childCount == rows.size + 1) {
            rows.forEachIndexed { i, row ->
                val v = listColumn.getChildAt(i)
                v.findViewById<TextView>(R.id.epg_src_status)?.apply {
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
            id = R.id.epg_src_status
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
            text = row.source.url + if (!row.deletable) " · 内置" else ""
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
        if (row.deletable) {
            item.setOnLongClickListener { onDelete?.invoke(row.source); true }
            item.setOnKeyListener { _, keyCode, event ->
                if (row.deletable && event.action == KeyEvent.ACTION_UP &&
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
            text = "+ 添加自定义节目单"
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
