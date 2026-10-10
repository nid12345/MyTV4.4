package top.yogiczy.mytv.legacy.ui.player

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import top.yogiczy.mytv.legacy.R
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit

/**
 * 「多线路」右侧选框（用户需求 5）。
 *
 * ---------------------------------------------------------------------------
 * 需求原文：「多线路的那个点开后在屏幕右侧跳出来选框，框内显示同个直播源的不同线路
 *          及详情可以选择对应直播源线路」。
 *
 * 形态：全屏半透明遮罩 + **贴右侧**的竖排面板（宽 420dp，高最多 80% 屏高）。
 * 每一行 = 序号（`线路 2/5`）+ 当前态标记 + 地址；确认键即切换。
 *
 * 与「分组管理 / 节目单管理」两个抽屉同一套交互约定：
 *   · 遮罩点击空白 / 返回键 → 关闭（由宿主调 [close]）；
 *   · 根节点 `FOCUS_AFTER_DESCENDANTS` —— 否则列表行的 requestFocus 会被根节点截胡，
 *     遥控器要按一次方向键才进得了列表（v1.8 在《使用须知》上踩过这个坑）；
 *   · 打开时把焦点放到**当前正在使用的那条线路**上，方便就近换。
 * ---------------------------------------------------------------------------
 */
class LinePickerView(context: Context) : FrameLayout(context) {

    /** 一行线路。 */
    data class Row(
        val index: Int,
        val url: String,
        val current: Boolean,
        /** 该线路的附加说明（如「当前使用 · 直播」），没有则留空。 */
        val note: String = "",
    )

    /** 返回键 / 点遮罩时关闭。 */
    var onClose: (() -> Unit)? = null

    /** 选中某条线路（0 基下标）。 */
    var onPick: ((Int) -> Unit)? = null

    /** 频道名，仅用于标题回显。 */
    var channelName: String = ""

    private val listColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val titleView = TextView(context)
    private val subtitleView = TextView(context)
    private var provider: (() -> List<Row>)? = null

    /** 贴右侧的竖排面板本体（[isInContent] 判定用）。 */
    private lateinit var panel: LinearLayout

    init {
        setBackgroundColor(0x99000000.toInt())
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.rounded(Palette.surface(context), 14f, context)
            setPadding(s(18f), s(16f), s(18f), s(16f))
        }
        this.panel = panel

        titleView.apply {
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        }
        subtitleView.apply {
            setTextColor(Palette.onSurfaceVariant(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, s(4f), 0, s(10f))
        }
        panel.addView(titleView)
        panel.addView(subtitleView)

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(listColumn, LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 贴右侧：宽 420dp，高最多 80% 屏高，垂直居中。
        val panelLp = LayoutParams(
            s(420f),
            (resources.displayMetrics.heightPixels * 0.8f).toInt()
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            rightMargin = s(24f)
        }
        addView(panel, panelLp)

        // 点遮罩空白 = 关闭；点面板本体要**吃掉**这次点击，否则会顺着事件冒泡到遮罩上
        // 把选框一起关掉（用户在列表里点一下结果框没了）。
        setOnClickListener { onClose?.invoke() }
        panel.setOnClickListener { /* 吃掉，避免误关 */ }
    }

    fun show(provider: () -> List<Row>) {
        this.provider = provider
        render()
    }

    /**
     * 触摸点是否落在选框**本体**内。
     *
     * 宿主 `MainActivity` 用它判断"点选框以外 = 关闭"。**不能**直接用 `panel.left/right`
     * （那是相对父容器的坐标），必须用 `getGlobalVisibleRect` 取真正的屏幕坐标。
     */
    fun isInContent(x: Float, y: Float): Boolean {
        if (!::panel.isInitialized) return false
        val r = android.graphics.Rect()
        return panel.getGlobalVisibleRect(r) && x >= r.left && x <= r.right && y >= r.top && y <= r.bottom
    }

    /**
     * 重绘列表。
     *
     * 行数没变时**只更新选中标记**，不整列重建 —— 重建会丢焦点（与 GroupManageView 同款约定）。
     */
    fun render() {
        val rows = provider?.invoke().orEmpty()
        titleView.text = if (channelName.isBlank()) "多线路" else "多线路 · $channelName"
        subtitleView.text = "共 ${rows.size} 条线路 · 确认键切换 · 返回键关闭"

        var focusTarget: View? = null
        if (listColumn.childCount == rows.size) {
            rows.forEachIndexed { i, row ->
                val v = listColumn.getChildAt(i)
                v.findViewById<TextView>(R.id.line_pick_note)?.apply {
                    text = if (row.current) "使用中" else row.note
                }
                if (row.current) focusTarget = v
            }
            focusTarget?.requestFocus()
            return
        }

        listColumn.removeAllViews()
        rows.forEach { row ->
            val v = rowView(row)
            listColumn.addView(v)
            if (row.current) focusTarget = v
        }
        val target = focusTarget ?: listColumn.getChildAt(0)
        target?.post { target?.requestFocus() }
    }

    private fun rowView(row: Row): LinearLayout {
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(s(14f), s(10f), s(14f), s(10f))
            background = UiKit.rounded(
                UiKit.withAlpha(Palette.surfaceContainer(context), 0.9f), 8f, context)
        }
        item.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = s(6f) }

        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val no = TextView(context).apply {
            text = "线路 ${row.index + 1}"
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        val note = TextView(context).apply {
            id = R.id.line_pick_note
            text = if (row.current) "使用中" else row.note
            setTextColor(Palette.primary(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
        }
        head.addView(no, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(note, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = s(10f) })

        val url = TextView(context).apply {
            text = row.url
            setTextColor(UiKit.withAlpha(Palette.onSurfaceVariant(context), 0.85f))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(0, s(2f), 0, 0)
        }

        item.addView(head)
        item.addView(url)

        item.setOnFocusChangeListener { _, hasFocus ->
            item.background = UiKit.rounded(
                if (hasFocus) Palette.onSurface(context)
                else UiKit.withAlpha(Palette.surfaceContainer(context), 0.9f),
                8f, context
            )
            no.setTextColor(if (hasFocus) 0xFF101014.toInt() else Palette.onSurface(context))
            note.setTextColor(if (hasFocus) 0xFF1A73E8.toInt() else Palette.primary(context))
            url.setTextColor(
                if (hasFocus) 0xFF3C4043.toInt()
                else UiKit.withAlpha(Palette.onSurfaceVariant(context), 0.85f))
        }
        item.setOnClickListener { onPick?.invoke(row.index) }
        return item
    }

    private fun s(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()
}
