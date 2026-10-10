package top.yogiczy.mytv.legacy.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import top.yogiczy.mytv.legacy.ui.qr.QrCodeView
import top.yogiczy.mytv.legacy.ui.qr.QrPushServer

/**
 * 应用风格对话框（自绘）。
 *
 * ---------------------------------------------------------------------------
 * 为什么不用 `AlertDialog`：
 * 系统 `AlertDialog` 走的是 AppCompat 默认主题（浅色 Material —— 白底、灰色输入框下划线、
 * 蓝色按钮），而本应用整体是深色半透明面板 + 聚焦整行反色。真机截图里"添加直播源"就是一张
 * 突兀的浅色对话框（用户原话："这风格没统一"）。
 *
 * 这里用与 `QuickOpView` / 三段式面板**同一套**配色与交互自己拼一个：
 *  - 全屏 55% 黑遮罩（点击遮罩不关闭，避免误触丢输入）；
 *  - 居中深色圆角面板（`Palette.surfaceContainerHigh`）；
 *  - 输入框为深色圆角块（`Palette.surface`），提示文字用 `onSurfaceVariant`；
 *  - 按钮为可聚焦文本行，**聚焦时整行反色**（与菜单项/面板行一致）。
 *
 * 不引入任何新依赖，也不改 M0 定稿文件。
 * ---------------------------------------------------------------------------
 */
object AppDialog {

    /** 构建结果：遮罩根视图 + 建议首先取焦的控件（宿主负责 add 到自己的根容器）。 */
    class Built(val root: View, val firstFocus: View?)

    /**
     * 「手机扫码隔空填写」的绑定声明（v1.14 需求 4）。
     *
     * 调用方**只需声明**"哪个输入框对应手机页上的哪个字段"，剩下的（起服务、生成二维码、
     * 把收到的内容写回输入框、关对话框时停服务）全部由本类接管 —— 这样"以后任何一个
     * 涉及输入的地方加扫码"都是一行声明的事。
     *
     * @param specs     字段声明，[FieldSpec.inputIndex] 是"第几个输入框"（0 起）
     * @param onReceive 内容写回输入框**之后**的回调（主线程），可选，用于打日志/联动
     */
    class QrPush(
        val specs: List<FieldSpec>,
        val onReceive: ((Map<String, String>) -> Unit)? = null,
    ) {
        class FieldSpec(val key: String, val label: String, val hint: String, val inputIndex: Int)
    }

    /** 双输入框对话框（"添加直播源"）。 */
    fun buildInput(
        ctx: Context,
        title: String,
        hint1: String,
        hint2: String,
        okLabel: String = "确定",
        qrPush: QrPush? = null,
        onCancel: () -> Unit,
        onOk: (String, String) -> Unit,
    ): Built {
        val overlay = overlay(ctx)
        val panel = panel(ctx)

        panel.addView(titleView(ctx, title))

        val input1 = input(ctx, hint1)
        val input2 = input(ctx, hint2)
        panel.addView(input1, lp(dp(ctx, 14f)))
        panel.addView(input2, lp(dp(ctx, 10f)))

        // v1.14 需求 4：扫码卡片放在输入框和按钮之间（打开时才占位）
        val qrCard = qrPush?.let { push ->
            buildQrCard(ctx, push, listOf(input1, input2), okLabel).also { panel.addView(it.card, lp(dp(ctx, 12f))) }
        }

        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        buttons.addView(button(ctx, "取消", onCancel))
        if (qrCard != null) {
            buttons.addView(qrCard.toggle, LinearLayout.LayoutParams(WRAP, WRAP)
                .apply { marginStart = dp(ctx, 8f) })
        }
        buttons.addView(button(ctx, okLabel) {
            onOk(input1.text.toString().trim(), input2.text.toString().trim())
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(ctx, 8f) })
        panel.addView(buttons, lp(dp(ctx, 16f)).apply { gravity = Gravity.END })

        overlay.addView(panel, FrameLayout.LayoutParams(panelWidth(ctx), WRAP)
            .apply { gravity = Gravity.CENTER })
        return Built(overlay, input1)
    }

    /** 单条确认（"删除直播源"）。 */
    fun buildConfirm(
        ctx: Context,
        title: String,
        message: String,
        okLabel: String = "确定",
        /**
         * v1.15 需求 5：**删除类**确认框把默认焦点放到「取消」。
         *
         * 起因是实测踩到：删除订阅源时焦点默认落在「删除」上，按一下确认键就删了 ——
         * 用户想"先看看"也会误删（我在验证时也真把一个内置源删掉了）。
         * 危险动作给"取消"默认焦点是通行做法：多按一次方向键，换零误删。
         */
        focusOnCancel: Boolean = false,
        onCancel: () -> Unit,
        onOk: () -> Unit,
    ): Built {
        val overlay = overlay(ctx)
        val panel = panel(ctx)
        panel.addView(titleView(ctx, title))
        panel.addView(TextView(ctx).apply {
            text = message
            setTextColor(Palette.onSurfaceVariant(ctx))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(ctx, 10f), 0, 0)
        })
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val cancel = button(ctx, "取消", onCancel)
        val ok = button(ctx, okLabel, onOk)
        buttons.addView(cancel)
        buttons.addView(ok, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(ctx, 8f) })
        panel.addView(buttons, lp(dp(ctx, 18f)).apply { gravity = Gravity.END })

        overlay.addView(panel, FrameLayout.LayoutParams(panelWidth(ctx), WRAP)
            .apply { gravity = Gravity.CENTER })
        return Built(overlay, if (focusOnCancel) cancel else ok)
    }

    /**
     * 单输入框对话框（v1.8：设置页「播放器自定义UA」用它，
     * 之前那一行只能看不能改，等于摆设）。
     */
    fun buildSingleInput(
        ctx: Context,
        title: String,
        hint: String,
        initial: String = "",
        okLabel: String = "保存",
        qrPush: QrPush? = null,
        onCancel: () -> Unit,
        onOk: (String) -> Unit,
    ): Built {
        val overlay = overlay(ctx)
        val panel = panel(ctx)
        panel.addView(titleView(ctx, title))

        val input1 = input(ctx, hint).apply {
            setText(initial)
            setSelection(initial.length)
        }
        panel.addView(input1, lp(dp(ctx, 14f)))

        val qrCard = qrPush?.let { push ->
            buildQrCard(ctx, push, listOf(input1), okLabel).also { panel.addView(it.card, lp(dp(ctx, 12f))) }
        }

        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        buttons.addView(button(ctx, "取消", onCancel))
        if (qrCard != null) {
            buttons.addView(qrCard.toggle, LinearLayout.LayoutParams(WRAP, WRAP)
                .apply { marginStart = dp(ctx, 8f) })
        }
        buttons.addView(button(ctx, okLabel) {
            onOk(input1.text.toString().trim())
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(ctx, 8f) })
        panel.addView(buttons, lp(dp(ctx, 16f)).apply { gravity = Gravity.END })

        overlay.addView(panel, FrameLayout.LayoutParams(panelWidth(ctx), WRAP)
            .apply { gravity = Gravity.CENTER })
        return Built(overlay, input1)
    }

    /** 多按钮 + 长正文对话框（v1.6 「检查更新」用）。

     * 与 [buildConfirm] 的差别：正文可多行滚动、按钮数量与文案由调用方给（更新弹窗既要
     * 「立即更新 / 稍后」，下载完又要「安装 / 取消」，同一套壳复用）。
     */
    fun buildMessage(
        ctx: Context,
        title: String,
        message: String,
        buttons: List<Pair<String, () -> Unit>>,
    ): Built {
        val overlay = overlay(ctx)
        val panel = panel(ctx)
        panel.addView(titleView(ctx, title))

        val body = TextView(ctx).apply {
            text = message
            setTextColor(Palette.onSurfaceVariant(ctx))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(UiKit.dp(ctx, 4f).toFloat(), 1f)
        }
        // ⚠️ v1.8 修复「触屏上更新弹窗划不动、按钮点不到」：
        //   原来 ScrollView 高度是 WRAP —— 它会被内容撑到和正文一样高，
        //   于是"永远不需要滚动"，正文一长就把按钮顶到屏幕外，触屏够不着、划也划不动。
        //   现在给它一个**固定高度**（屏高 45%，上限 300dp），超出即滚动，
        //   按钮行始终留在屏幕内。
        val scroll = android.widget.ScrollView(ctx).apply {
            addView(body)
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
        }
        panel.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, bodyMaxHeight(ctx)
        ).apply { topMargin = dp(ctx, 10f) })

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        var first: View? = null
        buttons.forEachIndexed { i, (label, action) ->
            val b = button(ctx, label, action)
            if (first == null) first = b
            bar.addView(b, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                if (i > 0) marginStart = dp(ctx, 8f)
            })
        }
        panel.addView(bar, lp(dp(ctx, 18f)).apply { gravity = Gravity.END })

        overlay.addView(panel, FrameLayout.LayoutParams(panelWidth(ctx), WRAP)
            .apply { gravity = Gravity.CENTER })
        return Built(overlay, first)
    }

    // ---------------- 内部件 ----------------

    /** 扫码卡片：[card] 是展开后显示的二维码区，[toggle] 是按钮行里那个开关按钮。 */
    private class QrCard(val card: View, val toggle: TextView)

    /**
     * 「手机扫码隔空填写」卡片（v1.14 需求 4）。
     *
     * 结构（展开后）：左侧二维码，右侧三行说明 —— 标题 / 手机要访问的地址 / 状态。
     * 二维码**默认收起**：电视上九成用户还是用遥控器输入，一进来就占掉半屏会碍事；
     * 需要手机填时按一下按钮展开，同时才把局域网服务启起来（省电、省端口）。
     *
     * 生命周期：服务随卡片展开而启动、随卡片收起或**对话框被摘掉**而停止 ——
     * 后者注册在 attach 状态监听里，所以宿主怎么关对话框（取消/确定/被别的逻辑清场）
     * 都不用额外记得来收尾。
     */
    private fun buildQrCard(
        ctx: Context,
        push: QrPush,
        inputs: List<EditText>,
        okLabel: String,
    ): QrCard {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 12f), dp(ctx, 12f), dp(ctx, 12f), dp(ctx, 12f))
            background = UiKit.rounded(Palette.surface(ctx), 10f, ctx)
            visibility = View.GONE
        }

        val qr = QrCodeView(ctx).apply { targetSizePx = dp(ctx, 150f) }
        card.addView(qr, LinearLayout.LayoutParams(WRAP, WRAP))

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14f), 0, 0, 0)
        }
        val head = TextView(ctx).apply {
            text = "手机扫码填写"
            setTextColor(Palette.onSurface(ctx))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val addr = TextView(ctx).apply {
            setTextColor(Palette.primary(ctx))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
        }
        val status = TextView(ctx).apply {
            text = "手机需与本机连同一个无线网络"
            setTextColor(Palette.onSurfaceVariant(ctx))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(ctx, 3f).toFloat(), 1f)
        }
        col.addView(head)
        col.addView(addr, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(ctx, 6f) })
        col.addView(status, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(ctx, 6f) })
        card.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))

        // 服务句柄 + 主线程投递（服务端回调在后台线程，改 UI 必须回到主线程）
        var session: QrPushServer.Session? = null
        val main = Handler(Looper.getMainLooper())

        fun stopServer() {
            session?.stop()
            session = null
        }

        fun openServer() {
            if (session?.isRunning == true) return
            // 每次手机打开表单页都现取一次字段 → 手机页上显示的就是电视上此刻的内容
            val s = QrPushServer.start(
                fields = {
                    push.specs.map { sp ->
                        QrPushServer.Field(
                            key = sp.key,
                            label = sp.label,
                            hint = sp.hint,
                            value = inputs.getOrNull(sp.inputIndex)?.text?.toString().orEmpty(),
                        )
                    }
                },
                onSubmit = { values ->
                    main.post {
                        if (!card.isAttachedToWindow) return@post      // 对话框已关 → 丢弃
                        push.specs.forEach { sp ->
                            val v = values[sp.key] ?: return@forEach
                            inputs.getOrNull(sp.inputIndex)?.apply {
                                setText(v)
                                setSelection(v.length)
                            }
                        }
                        status.text = "已收到推送，核对后点「$okLabel」"
                        status.setTextColor(Palette.primary(ctx))
                        push.onReceive?.invoke(values)
                    }
                },
            )
            session = s
            if (s == null) {
                qr.visibility = View.GONE
                addr.text = ""
                status.text = "无法启动扫码服务，请确认本机已联网后重试"
                status.setTextColor(Palette.error(ctx))
                return
            }
            qr.visibility = View.VISIBLE
            addr.text = s.url
            val ok = qr.render(s.url, "扫码打开填写页")
            status.text = if (ok) {
                "手机需与本机连同一个无线网络\n扫不出时可手动输入上面的地址"
            } else {
                "二维码生成失败，可手动在手机浏览器输入上面的地址"
            }
            status.setTextColor(Palette.onSurfaceVariant(ctx))
        }

        lateinit var toggle: TextView
        toggle = button(ctx, "手机扫码填写") {
            val expand = card.visibility != View.VISIBLE
            card.visibility = if (expand) View.VISIBLE else View.GONE
            toggle.text = if (expand) "收起二维码" else "手机扫码填写"
            if (expand) openServer() else stopServer()
        }

        // 卡片被摘掉（= 对话框关闭）→ 停服务。放在这里宿主就不用管收尾。
        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = stopServer()
        })

        return QrCard(card, toggle)
    }

    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(ctx: Context, v: Float) = UiKit.dp(ctx, v)

    private fun lp(topMargin: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, WRAP
    ).apply { this.topMargin = topMargin }

    /** 全屏遮罩：半透明黑 + 吃掉所有触摸（避免穿透到画面去唤出面板）。 */
    private fun overlay(ctx: Context) = FrameLayout(ctx).apply {
        setBackgroundColor(UiKit.withAlpha(0xFF000000.toInt(), 0.55f))
        isClickable = true
        isFocusable = true
        // v1.8：让**子控件优先**取焦。默认的 FOCUS_BEFORE_DESCENDANTS 会让宿主
        // `built.firstFocus.requestFocus()` 的结果被遮罩根节点截胡，焦点停在遮罩上 ——
        // 遥控器要按一次方向键才进到按钮/输入框，触屏用户则完全看不到焦点在哪。
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    /** 居中面板：深色圆角。 */
    private fun panel(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 20f), dp(ctx, 18f), dp(ctx, 20f), dp(ctx, 14f))
        background = UiKit.rounded(Palette.surfaceContainerHigh(ctx), 12f, ctx)
        isClickable = true
    }

    private fun panelWidth(ctx: Context): Int {
        val screen = ctx.resources.displayMetrics.widthPixels
        return minOf((screen * 0.80f).toInt(), dp(ctx, 560f))
    }

    /** 正文最大高度：屏高 45%，上限 300dp —— 保证按钮行永远留在屏幕内（见 [buildMessage]）。 */
    private fun bodyMaxHeight(ctx: Context): Int {
        val screenH = ctx.resources.displayMetrics.heightPixels
        return minOf((screenH * 0.45f).toInt(), dp(ctx, 300f))
    }

    private fun titleView(ctx: Context, title: String) = TextView(ctx).apply {
        text = title
        setTextColor(Palette.onSurface(ctx))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun input(ctx: Context, hint: String) = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(UiKit.withAlpha(Palette.onSurfaceVariant(ctx), 0.75f))
        setTextColor(Palette.onSurface(ctx))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(ctx, 12f), dp(ctx, 11f), dp(ctx, 12f), dp(ctx, 11f))
        background = UiKit.rounded(Palette.surface(ctx), 8f, ctx)
        isSingleLine = true
        maxLines = 1
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /** 按钮：可聚焦文本行，聚焦时整行反色（与快捷菜单项一致）。 */
    private fun button(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        setPadding(dp(ctx, 18f), dp(ctx, 9f), dp(ctx, 18f), dp(ctx, 9f))
        applyButtonStyle(this, ctx, false)
        setOnFocusChangeListener { v, hasFocus -> applyButtonStyle(v as TextView, ctx, hasFocus) }
        setOnClickListener { onClick() }
    }

    private fun applyButtonStyle(v: TextView, ctx: Context, focused: Boolean) {
        v.background = UiKit.rounded(
            if (focused) Palette.onSurface(ctx) else Color.TRANSPARENT, 8f, ctx
        )
        v.setTextColor(if (focused) 0xFF101014.toInt() else Palette.onSurface(ctx))
    }
}
