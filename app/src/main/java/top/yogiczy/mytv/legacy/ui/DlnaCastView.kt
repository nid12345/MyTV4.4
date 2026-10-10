package top.yogiczy.mytv.legacy.ui

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
import top.yogiczy.mytv.legacy.data.DlnaCastRepository
import top.yogiczy.mytv.legacy.data.DlnaDevice

/**
 * 「投送到设备」（DLNA）界面（问题 6 的投屏）。
 *
 * ---------------------------------------------------------------------------
 * 对应原版 MyTV 1.7 `tv/ui/screens/dlna/DlnaCastScreen.kt`：
 *   `Drawer(DrawerPosition.Bottom, header = "投送到设备")` + 设备列表 +
 *   末尾一个「重新搜索」项；选中设备 = 投送当前频道，再次选中同一台 = 断开。
 *
 * API 19 上没有 Compose，等价实现为：遮罩 + 底部深色抽屉 + 可聚焦行。
 * 设备列表与投送状态来自 [DlnaCastRepository]（同一个单例）。
 *
 * ---------------------------------------------------------------------------
 * v1.10（用户需求 1 / 3）修的三件事，都跟"点击序列被自己打断"有关：
 *
 * 1. **一次点击就能选中**。原先把关闭抽屉的动作放在宿主 `onScreenTap` 里，而那是手势
 *    检测阶段（早于 View 树派发），于是 ACTION_UP 一进来抽屉就被移除，`ViewGroup`
 *    的触摸目标链随之清空 → 被点的行**永远收不到 onClick**，用户看到的是"抽屉消失、
 *    可投屏根本没发生"。现在遮罩关闭由**本类根节点自己**处理，宿主只 `return`。
 *
 * 2. **重绘不再把正在点击的行从树上摘掉**。原先 [setBusy] 直接调 `render()`，
 *    而 `render()` 会 `removeAllViews()` 重建 —— 它恰好是在行的 onClick 回调里被调用的，
 *    等于在派发过程中销毁派发目标。现在 [setBusy] 只改提示文案；
 *    [render] 在设备集合未变时只做**增量刷新**，不重建视图。
 *
 * 3. **焦点直接落在设备行上**。根节点默认 `FOCUS_BEFORE_DESCENDANTS` 会截胡子节点的
 *    `requestFocus`，遥控器要先按一次方向键才进得了列表（与 LinePickerView /
 *    AgreementView 同一个坑），这里显式改为 `FOCUS_AFTER_DESCENDANTS`。
 * ---------------------------------------------------------------------------
 */
class DlnaCastView(context: Context) : FrameLayout(context) {

    var onClose: (() -> Unit)? = null

    /** 选中某台设备（已投送同一台 → 宿主负责断开）。 */
    var onSelectDevice: ((DlnaDevice) -> Unit)? = null

    /** 重新搜索。 */
    var onRescan: (() -> Unit)? = null

    /** 行视图与其文案控件的引用 —— 增量刷新用（避免整列重建丢焦点 / 断点击）。 */
    private class RowHolder(
        val deviceId: String,
        val row: LinearLayout,
        val title: TextView,
        val tip: TextView?,
    )

    private lateinit var panel: LinearLayout

    private val hintView = TextView(context)
    private val listColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var busy = false

    /** 已渲染的设备 id 序列 —— 与仓库当前序列一致时走增量刷新。 */
    private var renderedIds: List<String> = emptyList()
    private val holders = ArrayList<RowHolder>()
    private var rescanRow: LinearLayout? = null
    private var rescanLabel: TextView? = null

    init {
        setBackgroundColor(0xCC000000.toInt())
        isFocusable = true
        isFocusableInTouchMode = true
        // v1.10：见类注释第 3 条 —— 不设的话遥控器进不了列表。
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.rounded(Palette.surface(context), 14f, context)
            setPadding(s(20f), s(16f), s(20f), s(16f))
        }
        this.panel = panel
        panel.addView(TextView(context).apply {
            text = "投送到设备"
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
        })
        hintView.apply {
            setTextColor(Palette.onSurfaceVariant(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, s(4f), 0, s(10f))
        }
        panel.addView(hintView)

        panel.addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(listColumn, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        addView(panel, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.62f).toInt(),
            Gravity.BOTTOM
        ).apply {
            leftMargin = s(60f); rightMargin = s(60f); bottomMargin = s(48f)
        })

        // v1.10：点遮罩空白 = 关闭抽屉（**兜底**）。
        // ⚠️ 实测：全屏沉浸式窗口下，本类根节点自身的 `setOnClickListener` **收不到点击**
        //    （同一屏里列表行能收到、根节点收不到），所以"点空白关闭"的主路径由宿主的
        //    `onScreenTap` 按 [isInContent] 几何判定完成，并经 `rootView.post` 延到下一轮再移除。
        //    这里保留监听只为非沉浸式窗口兜底，不承担主路径职责。
        setOnClickListener { onClose?.invoke() }
        // 点面板本体要"吃掉"这次点击，否则会冒泡到遮罩上把抽屉一起关掉
        // （用户点一下设备列表的空白处，结果整个抽屉没了）。
        panel.setOnClickListener { /* 吃掉，不关闭 */ }
    }

    /**
     * 按仓库当前状态重绘。
     *
     * ⚠️ 设备集合没变时**只做增量刷新**：整列重建会丢失行焦点，而且如果这次重绘正好发生在
     * 某一行的点击回调里（[setBusy] 的旧实现就是这样），会把正在派发的那个 View 从树上摘掉，
     * 点击序列直接断掉 —— 用户报的「点两次才选中」就是这么来的。
     */
    fun render() {
        val devices = DlnaCastRepository.devices
        val scanning = DlnaCastRepository.isScanning
        updateHint()
        rescanLabel?.text = if (scanning) "正在搜索…" else "重新搜索"

        val ids = devices.map { it.id }
        if (ids == renderedIds && holders.size == devices.size && rescanRow != null) {
            devices.forEachIndexed { i, d -> bindHolder(holders[i], d) }
            return
        }
        rebuild(devices, scanning)
    }

    /**
     * 投送中：只更新提示并屏蔽重复点击（对应原版 `isBusyProvider`）。
     *
     * ⚠️ **不要**在这里调 [render]：它是在行的 onClick 回调链里被调用的，
     * 重建列表会把正在派发点击的那个 View 摘掉（见类注释 2）。
     */
    fun setBusy(value: Boolean) {
        if (busy == value) return
        busy = value
        updateHint()
    }

    /** 选中第一台设备（没有设备时选中「重新搜索」）。打开抽屉后由宿主调用。 */
    fun focusFirst() {
        val target = holders.firstOrNull()?.row ?: rescanRow
        target?.requestFocus()
    }

    /**
     * 触摸点是否落在抽屉**本体（面板）**内。
     *
     * 宿主 `MainActivity` 用它判断"点空白 = 关抽屉"。手工算 `panel` 的屏幕坐标**不能**
     * 直接用 `panel.left/right`：那是相对父容器的坐标，父容器又不一定贴屏幕左上角。
     * `getGlobalVisibleRect` 拿的是真正的屏幕坐标，才是可靠的。
     */
    fun isInContent(x: Float, y: Float): Boolean {
        if (!::panel.isInitialized) return false
        val r = android.graphics.Rect()
        return panel.getGlobalVisibleRect(r) && x >= r.left && x <= r.right && y >= r.top && y <= r.bottom
    }

    // ---------------- 内部 ----------------

    private fun updateHint() {
        val scanning = DlnaCastRepository.isScanning
        val devices = DlnaCastRepository.devices
        hintView.text = when {
            busy -> "正在投送…"
            scanning -> "正在搜索局域网内的投屏设备…请确认电视已开机并与本机连接同一网络"
            devices.isEmpty() -> "未发现投屏设备，请确认电视已开机并与本机连接同一网络"
            else -> "选中设备即把当前频道推送到它播放；再次选中同一台则断开"
        }
    }

    /** 重建整个列表（只在设备集合变化时走这里）。 */
    private fun rebuild(devices: List<DlnaDevice>, scanning: Boolean) {
        renderedIds = devices.map { it.id }
        listColumn.removeAllViews()
        holders.clear()

        devices.forEach { d ->
            val h = buildDeviceRow(d)
            holders += h
            listColumn.addView(h.row)
        }

        val rescan = buildRescanRow()
        rescanRow = rescan
        listColumn.addView(rescan)
        rescanLabel?.text = if (scanning) "正在搜索…" else "重新搜索"

        val target = holders.firstOrNull()?.row ?: rescan
        target.post { runCatching { target.requestFocus() } }
    }

    private fun buildDeviceRow(device: DlnaDevice): RowHolder {
        val titleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val tipView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
        }

        val item = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(s(12f), s(12f), s(12f), s(12f))
        }
        item.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = s(6f) }
        item.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        item.addView(tipView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = s(12f) })

        val holder = RowHolder(device.id, item, titleView, tipView)
        item.setOnFocusChangeListener { _, hasFocus -> applyRowColors(holder, hasFocus) }
        item.setOnClickListener {
            // v1.10：投送中屏蔽重复点击（原版 isBusyProvider 的等价物）
            if (busy) return@setOnClickListener
            onSelectDevice?.invoke(device)
        }
        // 首次绑定文案与底色
        bindHolder(holder, device)
        return holder
    }

    /** 增量刷新：只改文案与配色，不动视图树。 */
    private fun bindHolder(h: RowHolder, device: DlnaDevice) {
        h.title.text = device.displayName
        h.tip?.text = if (DlnaCastRepository.castingDeviceId == device.id) "投送中" else device.displayInfo
        applyRowColors(h, h.row.isFocused)
    }

    private fun applyRowColors(h: RowHolder, focused: Boolean) {
        val casting = DlnaCastRepository.castingDeviceId == h.deviceId
        h.row.background = UiKit.rounded(
            if (focused) Palette.onSurface(context)
            else UiKit.withAlpha(Palette.surfaceContainer(context), 0.9f),
            8f, context)
        val baseTitle = if (casting) Palette.primary(context) else Palette.onSurface(context)
        val baseTip = if (casting) Palette.primary(context) else Palette.onSurfaceVariant(context)
        h.title.setTextColor(if (focused) FOCUS_ON_LIGHT else baseTitle)
        h.tip?.setTextColor(if (focused) FOCUS_DIM else baseTip)
    }

    private fun buildRescanRow(): LinearLayout {
        val label = TextView(context).apply {
            text = if (DlnaCastRepository.isScanning) "正在搜索…" else "重新搜索"
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        rescanLabel = label
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
        item.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        item.setOnFocusChangeListener { v, hasFocus ->
            v.background = UiKit.rounded(
                if (hasFocus) Palette.onSurface(context)
                else UiKit.withAlpha(Palette.surfaceContainer(context), 0.9f), 8f, context)
            label.setTextColor(if (hasFocus) FOCUS_ON_LIGHT else Palette.onSurface(context))
        }
        item.setOnClickListener { onRescan?.invoke() }
        return item
    }

    private fun s(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()

    private companion object {
        /** 焦点行是白底，文字用深色（与 LinePickerView / EpgSourceView 同一套配色）。 */
        const val FOCUS_ON_LIGHT = 0xFF101014.toInt()
        const val FOCUS_DIM = 0xFF3C4043.toInt()
    }
}
