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
 * ---------------------------------------------------------------------------
 */
class DlnaCastView(context: Context) : FrameLayout(context) {

    var onClose: (() -> Unit)? = null

    /** 选中某台设备（已投送同一台 → 宿主负责断开）。 */
    var onSelectDevice: ((DlnaDevice) -> Unit)? = null

    /** 重新搜索。 */
    var onRescan: (() -> Unit)? = null

    private val hintView = TextView(context)
    private val listColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var busy = false

    init {
        setBackgroundColor(0xCC000000.toInt())
        isFocusable = true
        isFocusableInTouchMode = true

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.rounded(Palette.surface(context), 14f, context)
            setPadding(s(20f), s(16f), s(20f), s(16f))
        }
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
    }

    /** 按仓库当前状态重绘（仓库每次变化都会由宿主调用它）。 */
    fun render() {
        val scanning = DlnaCastRepository.isScanning
        val devices = DlnaCastRepository.devices
        hintView.text = when {
            busy -> "正在投送…"
            scanning -> "正在搜索局域网内的投屏设备…请确认电视已开机并与本机连接同一网络"
            devices.isEmpty() -> "未发现投屏设备，请确认电视已开机并与本机连接同一网络"
            else -> "选中设备即把当前频道推送到它播放；再次选中同一台则断开"
        }

        listColumn.removeAllViews()
        var first: View? = null
        devices.forEach { device ->
            val v = deviceRow(device)
            if (first == null) first = v
            listColumn.addView(v)
        }
        val rescan = actionRow(if (scanning) "正在搜索…" else "重新搜索") { onRescan?.invoke() }
        if (first == null) first = rescan
        listColumn.addView(rescan)

        first?.post { first?.requestFocus() }
    }

    /** 投送中：屏蔽重复点击（对应原版 `isBusyProvider`）。 */
    fun setBusy(value: Boolean) {
        busy = value
        render()
    }

    private fun deviceRow(device: DlnaDevice): LinearLayout {
        val casting = DlnaCastRepository.castingDeviceId == device.id
        return row(
            title = device.displayName,
            tip = if (casting) "投送中" else device.displayInfo,
            highlight = casting,
        ) { onSelectDevice?.invoke(device) }
    }

    private fun actionRow(title: String, onClick: () -> Unit): LinearLayout =
        row(title, null, false, onClick)

    private fun row(
        title: String,
        tip: String?,
        highlight: Boolean,
        onClick: () -> Unit,
    ): LinearLayout {
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

        val titleColor = if (highlight) Palette.primary(context) else Palette.onSurface(context)
        val titleView = TextView(context).apply {
            text = title
            setTextColor(titleColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        item.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (!tip.isNullOrBlank()) {
            val tipColor = if (highlight) Palette.primary(context) else Palette.onSurfaceVariant(context)
            val tipView = TextView(context).apply {
                text = tip
                setTextColor(tipColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
            }
            item.addView(tipView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = s(12f) })
            item.setOnFocusChangeListener { _, hasFocus ->
                item.background = UiKit.rounded(
                    if (hasFocus) Palette.onSurface(context)
                    else UiKit.withAlpha(Palette.surfaceContainer(context), 0.9f),
                    8f, context)
                titleView.setTextColor(if (hasFocus) 0xFF101014.toInt() else titleColor)
                tipView.setTextColor(if (hasFocus) 0xFF3C4043.toInt() else tipColor)
            }
        } else {
            item.setOnFocusChangeListener { _, hasFocus ->
                item.background = UiKit.rounded(
                    if (hasFocus) Palette.onSurface(context)
                    else UiKit.withAlpha(Palette.surfaceContainer(context), 0.9f),
                    8f, context)
                titleView.setTextColor(if (hasFocus) 0xFF101014.toInt() else titleColor)
            }
        }
        item.setOnClickListener { onClick() }
        return item
    }

    private fun s(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()
}
