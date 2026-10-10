package top.yogiczy.mytv.legacy.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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

        /**
         * 非交互说明行（v1.14 需求 6）：
         * 「正在获取该订阅源的分组…」/「暂时获取不到该订阅源的分组」这类占位。
         * 之前借用 [Toggle] 并把 hidden 硬编码成 true，会显示出一行自相矛盾的
         * 「正在获取…　已隐藏　[关]」，还占一个可聚焦位。
         */
        data class Note(val text: String) : Row()
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
        // v1.10：子视图优先取焦（见本 init 末尾的注释 ②）
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

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

        // ---------------------------------------------------------------------------
        // v1.10：两处补齐，都与「点一下行没反应 / 弹层莫名不见」有关。
        //
        // ① 点遮罩空白 = 关闭，**必须由本类根节点自己处理**。
        //    原先这个动作在宿主 `MainActivity.onScreenTap`（`if (groupManageView != null)
        //    closeGroupManage()`）。但 `dispatchTouchEvent` 是"先喂手势检测、再派发进 View 树"，
        //    点行时 ACTION_UP 一进来整个抽屉就被移除，ViewGroup 的触摸目标链随之清空
        //    → 行的 onClick 永远不触发，用户看到的就是"抽屉关了但什么都没发生"。
        //    现在宿主那边只 `return`，关闭交给这里。
        //
        // ② `descendantFocusability` 设成子视图优先 —— 与 EpgSourceView / LinePickerView
        //    同一条约定，否则行的 requestFocus() 会被本根节点截胡，遥控器要按一次方向键
        //    才进得了列表。
        // ---------------------------------------------------------------------------
        setOnClickListener { onClose?.invoke() }
        panel.setOnClickListener { /* 吃掉点击，避免点到面板本体时把抽屉一起关掉 */ }
    }

    /** 打开（或重绘）抽屉。[provider] 每次渲染都重新调用，切换后即可看到最新状态。 */
    fun show(title: String, hint: String, provider: () -> List<Row>) {
        titleView.text = title
        hintView.text = hint
        this.provider = provider
        render()
    }

    /**
     * 行视图与它对应的数据（用于**原地**更新状态，避免重建时焦点被系统搬走）。
     *
     * v1.14 需求 5：从「(行, 状态文字)」扩成 [(行, 行容器, 开关, 状态文字)] ——
     * 开关也要在"只改状态、不重建"这条路径上一并刷新，否则切换后文字变了、开关还停在旧位置。
     */
    private val boundRows = ArrayList<BoundRow>()

    /** 一行绑定到的视图与数据（v1.14 需求 5 新增）。 */
    private inner class BoundRow(
        var row: Row.Toggle,
        val item: LinearLayout,
        val switch: SwitchView,
        val status: TextView,
    ) {
        /** 按新数据刷新这一行的显示（状态文字 + 开关），**不重建视图**。 */
        fun apply(newRow: Row.Toggle) {
            row = newRow
            val hidden = newRow.hidden
            status.text = if (hidden) "已隐藏" else "显示中"
            status.setTextColor(
                if (item.isFocused) 0xFF3C4043.toInt() else statusColor(hidden)
            )
            switch.setState(checked = !hidden, onLightSurface = item.isFocused)
        }
    }

    /** 按当前数据重绘列表（切换显隐后由调用方触发）。 */
    fun render() {
        val rows = provider?.invoke().orEmpty()

        // ⚠️ **行集合没变时只改状态文本，不重建列表**：
        // `removeAllViews()` 会销毁正在聚焦的那一行，焦点瞬间被交给窗口里第一个可聚焦视图
        // （实测：焦点跑到设置页左栏的「关于」，把分类从「直播源」顶回「关于」）。
        // 只更新文本就没有这个问题。
        val toggles = rows.filterIsInstance<Row.Toggle>()
        if (toggles.size == boundRows.size &&
            toggles.indices.all { toggles[it].label == boundRows[it].row.label }
        ) {
            // v1.14 需求 5：这条路径原先只改 status 的文本与颜色，开关会停在旧状态上。
            toggles.forEachIndexed { i, row -> boundRows[i].apply(row) }
            return
        }

        boundRows.clear()
        listColumn.removeAllViews()
        var firstFocusable: View? = null
        rows.forEach { row ->
            when (row) {
                is Row.Header -> listColumn.addView(headerRow(row.text))
                is Row.Note -> listColumn.addView(noteRow(row.text))
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

    /** 非交互说明行（预取占位 / 失败提示）。不可聚焦，视觉上明显比正文弱。 */
    private fun noteRow(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(UiKit.withAlpha(Palette.onSurfaceVariant(context), 0.62f))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        maxLines = 2
        setPadding(s(12f), s(12f), s(12f), s(12f))
        background = UiKit.rounded(
            UiKit.withAlpha(Palette.surfaceContainer(context), 0.9f), 8f, context)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = s(6f) }
        isFocusable = false
        isClickable = false
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
        val status = TextView(context).apply {
            text = if (row.hidden) "已隐藏" else "显示中"
            setTextColor(statusColor(row.hidden))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
        }
        // v1.14 需求 5（用户）：行尾改用开关。语义「开 = 显示该分组 / 关 = 隐藏该分组」，
        // 只有这两个状态 —— 用户明确否掉了"切换三次会删除"的行为。
        val switch = SwitchView(context)

        item.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        item.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = s(12f) })
        item.addView(switch, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = s(12f) })

        val bound = BoundRow(row, item, switch, status)
        // ⚠️ 必须初始化开关：SwitchView 默认 checked=false，若这里不设初值，
        //    首次渲染时「显示中」的行也会画成关 —— 实测截图里开关与状态文字正好相反，
        //    直到这一行第一次获得焦点（focus 回调里 apply）才被纠正。
        switch.setState(checked = !row.hidden, onLightSurface = false)

        item.setOnFocusChangeListener { _, hasFocus ->
            item.background = UiKit.rounded(
                if (hasFocus) Palette.onSurface(context)
                else UiKit.withAlpha(Palette.surfaceContainer(context), 0.9f),
                8f, context
            )
            label.setTextColor(if (hasFocus) 0xFF101014.toInt() else Palette.onSurface(context))
            // 状态文字与开关的「浅底形态」一起刷新 —— 行反色成白底后，
            // 关态的浅灰滑块会看不见，必须换成深灰（见 SwitchView.onLight）。
            bound.apply(bound.row)
        }
        item.setOnClickListener { row.onClick() }
        boundRows.add(bound)
        return item
    }

    private fun statusColor(hidden: Boolean): Int =
        if (hidden) UiKit.withAlpha(Palette.onSurfaceVariant(context), 0.6f)
        else Palette.primary(context)

    /**
     * v1.14 需求 5（用户）：与整体风格一致的自绘开关。
     *
     * 用户原话：「这个改成开关按钮的形式比较明显」。
     *
     * 为什么不用 `android.widget.Switch`：它在 API 19 上是 Holo 风格的
     * 绿色/蓝色轨道 + 细长滑块，与本应用的深色圆角面板完全不是一套视觉语言 ——
     * 与 [top.yogiczy.mytv.legacy.ui.AppDialog] 里「不用系统 AlertDialog」同一条理由。
     *
     * 语义（用户指定）：**开 = 显示该分组，关 = 隐藏该分组**，只有这两个状态。
     */
    private class SwitchView(context: Context) : View(context) {

        private var checked = false

        /**
         * 是否落在"浅色底"上。
         *
         * 分组行获得焦点时整行反色成白底，此刻关态的滑块用的是 `onSurfaceVariant`（浅灰），
         * 在白底上几乎看不见 —— 所以聚焦时要换成深灰的 `surfaceVariant`。
         */
        private var onLight = false

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        /** 唯一的更新入口：状态与底色形态必须一起给，避免出现"白底浅滑块"。 */
        fun setState(checked: Boolean, onLightSurface: Boolean) {
            if (this.checked == checked && this.onLight == onLightSurface) return
            this.checked = checked
            this.onLight = onLightSurface
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(dp(46f), dp(26f))
        }

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            // 轨道（胶囊底）
            fill.color = when {
                checked -> Palette.primary(context)
                onLight -> UiKit.withAlpha(Palette.surfaceVariant(context), 0.85f)
                else -> Palette.surfaceVariant(context)
            }
            // ⚠️ 必须用 **RectF** 重载：`drawRoundRect(float…)` 那个重载是 API 21 才有的，
            //    在 API 19 上运行时会直接 NoSuchMethodError（实测模拟器当场崩，
            //    LevelBarView 的竖条就是这么崩的，已一并改掉）。
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, r, r, fill)
            // 滑块
            fill.color = when {
                checked -> Palette.surface(context)
                onLight -> Palette.surfaceVariant(context)
                else -> Palette.onSurfaceVariant(context)
            }
            canvas.drawCircle(if (checked) width - r else r, r, r - dpF(3f), fill)
        }

        private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()
        private fun dpF(v: Float) = v * resources.displayMetrics.density
    }

    private fun s(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()
}
