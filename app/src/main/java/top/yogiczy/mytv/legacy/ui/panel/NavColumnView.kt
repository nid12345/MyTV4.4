package top.yogiczy.mytv.legacy.ui.panel

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import top.yogiczy.mytv.legacy.R
import top.yogiczy.mytv.legacy.core.UiLog
import top.yogiczy.mytv.legacy.ui.ChannelLogoStore
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit

/**
 * 分组栏（三段式的第一列）。
 *
 * M10 从 `MainActivity` 搬来（M6 遗留的拆分项），**行为一字不改**，只搬家。
 *
 * ⚠️ 图标那段曾是**启动闪退的根因**：源里存在「🕘️更新时间」这类既不匹配台标规则、
 * 又没有内置图标的普通分组，`logoName` 与 `iconRes` 会同时为 0，而
 * `AppCompatResources.getDrawable(ctx, 0)` 会抛 `Resources.NotFoundException`。
 * 现在走 `else -> ic_apps` 回落 + `runCatching`。
 */
class NavColumnView(context: Context) : LinearLayout(context) {

    init {
        orientation = VERTICAL
    }

    /** 导航项模型。 */
    data class Entry(
        val label: String,
        val logoName: String? = null,
        val iconRes: Int = 0,
    )

    // 尺寸对齐原版 1.7 分组栏（`ClassicChannelGroupItemList`：列宽 140dp、
    // 内边距 8dp、项间距 4dp；文字用 tv-material 的 labelLarge 档）。
    private var iconSize = UiKit.dp(context, 22f)
    private var pad = UiKit.dp(context, 12f)

    /** 当前条目（[select] 只改样式、不重建时要用它重刷图标）。 */
    private var items: List<Entry> = emptyList()

    /** 当前选中下标。 */
    private var selectedIdx = 0

    /**
     * 「焦点已离开一级菜单」时为 true。
     *
     * 对齐原版 `ListItemDefaults.colors(...)`：分组项有两种态 ——
     * **聚焦态**（`focusedContainerColor`，实心白底）与**选中态**
     * （`selectedContainerColor = surfaceVariant.copy(alpha = 0.5f)`，半透明底）。
     * 光标移进二级（频道列/订阅列）时，一级里那一项仍是"当前分组"，但不再聚焦，
     * 于是白框变成半透明 —— 这就是用户要的「一级菜单白框透明度降低一半」。
     */
    private var dimmed = false

    /** 焦点离开一级菜单 → 选中项白框半透明；回到一级 → 恢复实心。 */
    fun setDimmed(value: Boolean) {
        if (dimmed == value) return
        dimmed = value
        for (i in 0 until childCount) {
            val row = getChildAt(i) as? LinearLayout ?: continue
            styleItem(row, items.getOrNull(i), i == selectedIdx)
        }
        // 无 adb 的真机靠这行确认「焦点是否已进二级」（问题 1）
        UiLog.i(
            "界面", "一级菜单选中框透明度 → ${if (value) "50%（焦点在二级）" else "100%（焦点在一级）"}")
    }

    /**
     * **一级菜单里的行**获得焦点（真聚焦，不是重建期的伪聚焦）。
     *
     * 宿主用它把「面板光标记忆」置为"在一级"（问题 1：面板唤出时光标回到上次停留的位置）。
     */
    var onRowFocused: (() -> Unit)? = null

    /**
     * 重建期间为 true：忽略"焦点被系统移交"引起的 [onSelect]（判据 C1）。
     *
     * `removeAllViews()` 会移除正在聚焦的那一行，系统随即把焦点交给新行 —— 那是**伪聚焦**，
     * 但会同步回调 `onSelect` → `onNavSelected` → 中栏 `submit()`，而此刻中栏很可能正在
     * layout → 真机抛 `Cannot call this method while RecyclerView is computing a layout`。
     * 抑制后由宿主在 `render()` 返回后**显式**同步一次选中项（见 `MainActivity.renderNav`）。
     */
    private var suppressFocusSelect = false

    /**
     * 宿主在「面板刚由 GONE 变 VISIBLE」的窗口期内置 true（问题 1）。
     *
     * 面板一变可见，系统会把焦点自动交给窗口里第一个可聚焦视图 —— 也就是本列的第 1 项
     * 「换源」。那个**伪聚焦**会同步回调 `onSelect(0)` → 宿主 `onNavSelected(0)` →
     * 中栏被换成订阅源列表；同时把宿主的「上次光标停在二级」记忆冲成 false。
     * 结果就是用户看到的「每次点开菜单都从换源开始」。
     *
     * 与 [suppressFocusSelect] 分开记：那个是 [render] 内部的一过性开关，用 try/finally 包着，
     * 若共用会被 `render()` 的 finally 提前清掉。
     */
    private var hostSuppressFocusSelect = false

    /** 供宿主开关「面板打开窗口期」的伪选中屏蔽（问题 1）。 */
    fun setFocusSelectSuppressed(value: Boolean) {
        hostSuppressFocusSelect = value
    }

    /** 渲染全部导航项。 */
    fun render(
        entries: List<Entry>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
    ) {
        items = entries
        selectedIdx = selectedIndex
        suppressFocusSelect = true
        try {
            removeAllViews()
            entries.forEachIndexed { i, entry ->
                val item = navItem(entry, i == selectedIndex)
                item.setOnClickListener { onSelect(i) }
                item.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        // 焦点回到一级（按键或触摸）→ 白框恢复实心（问题 1）
                        setDimmed(false)
                        if (!suppressFocusSelect && !hostSuppressFocusSelect) {
                            onRowFocused?.invoke()
                            onSelect(i)
                        }
                    }
                }
                addView(item)
            }
        } finally {
            suppressFocusSelect = false
        }
    }

    /**
     * **只更新选中态，不重建**（判据 12 修复）。
     *
     * 原实现里宿主每次「选中项变了」都调 [render] → `removeAllViews()`。那会把**正在聚焦的
     * 那一行**销毁，焦点随即被系统交给窗口里第一个可聚焦视图（面板第一项「换源」），
     * 于是按一次下键就被弹回「换源」——真机现象正是「焦点只能到换源 / 我的收藏，下不去」。
     * 选中高亮本来就只是样式变化，没必要重建整列。
     */
    fun select(index: Int) {
        if (index == selectedIdx) return
        selectedIdx = index
        for (i in 0 until childCount) {
            val row = getChildAt(i) as? LinearLayout ?: continue
            styleItem(row, items.getOrNull(i), i == index)
        }
    }

    private fun navItem(entry: Entry, selected: Boolean): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(pad, UiKit.dp(context, 11f), UiKit.dp(context, 10f), UiKit.dp(context, 11f))
        }
        row.layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = UiKit.dp(context, 4f) }

        val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val label = TextView(context).apply {
            text = entry.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
        }
        row.addView(icon, LayoutParams(iconSize, iconSize).apply {
            marginEnd = UiKit.dp(context, 10f)
        })
        row.addView(label)

        styleItem(row, entry, selected)
        return row
    }

    /** 统一套用「选中 / 未选中」样式（[navItem]、[select]、[setDimmed] 共用）。 */
    private fun styleItem(row: LinearLayout, entry: Entry?, selected: Boolean) {
        val icon = row.getChildAt(0) as? ImageView
        val label = row.getChildAt(1) as? TextView
        if (entry != null && icon != null) applyNavIcon(icon, entry, selected)
        label?.setTextColor(if (selected) 0xFF101014.toInt() else Palette.onSurface(context))
        // 选中态：焦点在一级 → 实心白底；焦点已进二级 → 白底透明度减半（问题 1）
        val alpha = if (dimmed) 0.5f else 1f
        row.background = if (selected) {
            UiKit.rounded(UiKit.withAlpha(Palette.onSurface(context), alpha), 8f, context)
        } else {
            UiKit.rounded(Color.TRANSPARENT, 8f, context)
        }
    }

    /** 导航图标（台标优先 → 内置图标 → ic_apps 回落）。 */
    private fun applyNavIcon(iv: ImageView, entry: Entry, selected: Boolean) {
        val bmp = entry.logoName?.let { ChannelLogoStore.bitmapFor(context, it) }
        if (bmp != null) {
            iv.setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, bmp))
            return
        }
        val res = if (entry.iconRes != 0) entry.iconRes else R.drawable.ic_apps
        runCatching {
            iv.setImageDrawable(AppCompatResources.getDrawable(context, res))
            iv.setColorFilter(if (selected) 0xFF101014.toInt() else Palette.onSurface(context))
        }.onFailure {
            iv.setImageDrawable(null)
        }
    }

    /** 供宿主做「屏幕外的列不可聚焦」。 */
    fun setColumnFocusable(enabled: Boolean) {
        descendantFocusability =
            if (enabled) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    companion object {
        /** 预留：分组栏的可见性由宿主控制。 */
        fun gone(view: View) { view.visibility = View.GONE }
    }
}
