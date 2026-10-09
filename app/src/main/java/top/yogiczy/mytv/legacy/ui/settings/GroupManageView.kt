package top.yogiczy.mytv.legacy.ui.settings

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit

/**
 * 「频道分组管理」/「全部分组管理」的底部抽屉（问题 6）。
 *
 * ---------------------------------------------------------------------------
 * 对应原版 MyTV 1.7：
 *   `tv/ui/screens/channelgroup/ChannelGroupManageScreen.kt`   —— 当前订阅源的分组显隐
 *   `tv/ui/screens/channelgroup/SourceGroupManageScreen.kt`    —— 所有订阅源一起管
 *
 * 原版用 Compose 的 `Drawer(DrawerPosition.Bottom)` + `LazyVerticalGrid`；
 * API 19 上两者都不存在，这里用等价的原生实现：
 *   · Drawer      → 全屏半透明遮罩 + 底部深色圆角面板
 *   · 网格/列表    → 可聚焦行（确认键 = 切换显隐，行尾显示「显示中 / 已隐藏」）
 *   · 顶部 header  → 标题 + 一句说明
 *
 * 显隐数据落在 `Prefs.iptvSourceGroupHiddenMap`（按订阅源地址分别记忆），
 * 与原版 `Configs.iptvSourceGroupHiddenMap` 语义一致 —— 切源、重启都保持。
 * ---------------------------------------------------------------------------
 */
class GroupManageView(context: Context) : FrameLayout(context) {

    /** 列表里的一行。 */
    sealed class Row {
        /** 分组标题（「全部分组管理」里用来分隔各订阅源）。 */
        data class Header(val text: String) : Row()

        /** 可切换的分组项。 */
        data class Toggle(
            val label: String,
            val hidden: Boolean,
            val onClick: () -> Unit,
        ) : Row()
    }

    /** 返回键 / 点遮罩时关闭。 */
    var onClose: (() -> Unit)? = null

    private val titleView = TextView(context)
    private val hintView = TextView(context)
    private val listColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private var provider: (() -> List<Row>)? = null

    init {
        setBackgroundColor(0xCC000000.toInt())
        isFocusable = true
        isFocusableInTouchMode = true

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.rounded(Palette.surface(context), 14f, context)
            setPadding(s(20f), s(16f), s(20f), s(16f))
        }

        titleView.apply {
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
        }
        hintView.apply {
            setTextColor(Palette.onSurfaceVariant(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, s(4f), 0, s(10f))
        }
        panel.addView(titleView)
        panel.addView(hintView)

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
        // 面板本身只占一部分高度，留出上方画面
        (panel.layoutParams as LayoutParams).height = (resources.displayMetrics.heightPixels * 0.72f).toInt()
    }

    /** 打开（或重绘）抽屉。[provider] 每次渲染都重新调用，切换后即可看到最新状态。 */
    fun show(title: String, hint: String, provider: () -> List<Row>) {
        titleView.text = title
        hintView.text = hint
        this.provider = provider
        render()
    }

    /** 行视图与它对应的数据（用于**原地**更新状态，避免重建时焦点被系统搬走）。 */
    private val boundRows = ArrayList<Pair<Row.Toggle, TextView>>()

    /** 按当前数据重绘列表（切换显隐后由调用方触发）。 */
    fun render() {
        val rows = provider?.invoke().orEmpty()

        // ⚠️ **行集合没变时只改状态文本，不重建列表**：
        // `removeAllViews()` 会销毁正在聚焦的那一行，焦点瞬间被交给窗口里第一个可聚焦视图
        // （实测：焦点跑到设置页左栏的「关于」，把分类从「直播源」顶回「关于」）。
        // 只更新文本就没有这个问题。
        val toggles = rows.filterIsInstance<Row.Toggle>()
        if (toggles.size == boundRows.size &&
            toggles.indices.all { toggles[it].label == boundRows[it].first.label }
        ) {
            toggles.forEachIndexed { i, row ->
                val (_, status) = boundRows[i]
                status.text = if (row.hidden) "已隐藏" else "显示中"
                status.setTextColor(statusColor(row.hidden))
                boundRows[i] = row to status
            }
            return
        }

        boundRows.clear()
        listColumn.removeAllViews()
        var firstFocusable: View? = null
        rows.forEach { row ->
            when (row) {
                is Row.Header -> listColumn.addView(headerRow(row.text))
                is Row.Toggle -> {
                    val v = toggleRow(row)
                    if (firstFocusable == null) firstFocusable = v
                    listColumn.addView(v)
                }
            }
        }
        // 焦点落在第一个可切换项上（对应原版 `focusOnLaunchedSaveable()`）
        firstFocusable?.post { firstFocusable?.requestFocus() }
    }

    private fun headerRow(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(UiKit.withAlpha(Palette.onSurfaceVariant(context), 0.9f))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        maxLines = 1
        setPadding(s(10f), s(14f), s(10f), s(6f))
    }

    private fun toggleRow(row: Row.Toggle): LinearLayout {
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(s(12f), s(12f), s(12f), s(12f))
            background = UiKit.rounded(
                UiKit.withAlpha(Palette.surfaceContainer(context), 0.9f), 8f, context)
        }
        item.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = s(6f) }

        val label = TextView(context).apply {
            text = row.label
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val statusColor = statusColor(row.hidden)
        val status = TextView(context).apply {
            text = if (row.hidden) "已隐藏" else "显示中"
            setTextColor(statusColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
        }
        item.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        item.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = s(12f) })

        item.setOnFocusChangeListener { _, hasFocus ->
            item.background = UiKit.rounded(
                if (hasFocus) Palette.onSurface(context)
                else UiKit.withAlpha(Palette.surfaceContainer(context), 0.9f),
                8f, context
            )
            label.setTextColor(if (hasFocus) 0xFF101014.toInt() else Palette.onSurface(context))
            status.setTextColor(if (hasFocus) 0xFF3C4043.toInt() else statusColor)
        }
        item.setOnClickListener { row.onClick() }
        boundRows.add(row to status)
        return item
    }

    private fun statusColor(hidden: Boolean): Int =
        if (hidden) UiKit.withAlpha(Palette.onSurfaceVariant(context), 0.6f)
        else Palette.primary(context)

    private fun s(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()
}
