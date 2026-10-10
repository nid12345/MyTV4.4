package top.yogiczy.mytv.legacy.ui

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import top.yogiczy.mytv.legacy.data.Prefs

/**
 * 首次启动的《使用须知》全屏页（v1.7，用户要求"首次安装打开 app 弹出免责声明，
 * 文字和样式照搬原版"）。
 *
 * ---------------------------------------------------------------------------
 * 对应原版：MyTV 1.7（源码 tag `custom-v1.7`）
 *           `tv/ui/screens/agreement/AgreementScreen.kt`
 *
 * 与原版的对应关系：
 *  · 原版是 Compose 全屏 Screen（**不是对话框**）→ 这里同样做全屏覆盖层；
 *  · 标题「使用须知」headlineMedium 居中；
 *  · 正文 5 条 `bodyLarge`，列宽 556dp（720p TV 上约 77% 屏宽）→ 这里取屏宽 72%；
 *  · 按钮两枚「已阅读并同意 / 退出应用」，容器色 `onSurface α0.1`，居中排布；
 *  · 文案**逐字照抄原版**（`${Constants.APP_TITLE}` 换成本应用名 MyTV4.4）；
 *  · "已同意"落库语义对齐原版 `Configs.appAgreementAgreed`（默认 false，
 *    见 [Prefs.appAgreementAgreed]）。
 *
 * 原版按钮焦点逻辑（focusOnLaunched 落在「已阅读并同意」上）同样保留。
 * ---------------------------------------------------------------------------
 */
class AgreementView(
    context: Context,
    private val onAgree: () -> Unit,
    private val onDisagree: () -> Unit,
) : LinearLayout(context) {

    /** 正文五条，逐字来自原版 `AgreementScreen.kt` 的 `messages`。 */
    private val messages = listOf(
        "欢迎使用MyTV4.4，请在使用前仔细阅读以下内容：",
        "1. 本软件为开源软件，仅供学习交流使用，禁止用于任何商业用途。",
        "2. 本软件不提供任何直播内容，所有直播内容均来自网络。",
        "3. 本软件完全基于您个人意愿使用，您应该对自己的使用行为和所有结果承担全部责任。",
        "4. 如果本软件存在侵犯您的合法权益的情况，请及时与作者联系，作者将会及时删除有关内容。",
        "如您继续使用本软件即代表您已完全理解并同意上述内容。",
    )

    /** 「已阅读并同意」按钮（默认焦点要落在它上面，见 [focusDefault]）。 */
    private lateinit var agreeBtn: TextView

    /** 「退出应用」按钮。 */
    private lateinit var exitBtn: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Palette.background(context))   // 原版 customBackground：全屏不透明
        isClickable = true          // 挡住底下视图的点击
        isFocusable = true
        // ⚠️ v1.8 关键修复：ViewGroup 默认是 `FOCUS_BEFORE_DESCENDANTS` —— 根节点自己也可聚焦时，
        //    子按钮 `requestFocus()` 的结果会被根节点"截胡"，焦点停在根覆盖层上。
        //    模拟器实测：dump 里 `focused` 是 `[0,0][1196,720]` 的根节点，确认键对着它毫无反应，
        //    没有触屏的盒子会被永久卡在这一屏。改成「子视图优先」后 [focusDefault] 才真正生效。
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        // ⚠️ 不设 isFocusableInTouchMode：设了会让触屏第一次点只拿焦点不触发按钮 onClick
        //    （模拟器实测踩到：点「已阅读并同意」无反应）。

        // 标题（原版 headlineMedium）
        addView(text("使用须知", 22f, true, Palette.onSurface(context)).apply {
            gravity = Gravity.CENTER
        }, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = UiKit.dp(context, 18f) })

        // 正文列（原版宽 556dp ≈ 屏宽 72%，行距 4dp）
        val body = LinearLayout(context).apply { orientation = VERTICAL }
        val pad = UiKit.dp(context, 4f)
        messages.forEach { m ->
            body.addView(text(m, 15f, false, Palette.onSurface(context)).apply {
                setLineSpacing(UiKit.dpF(context, 2f), 1f)
            }, LayoutParams(
                LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = pad })
        }
        val bodyWidth = (resources.displayMetrics.widthPixels * 0.72f).toInt()
        addView(body, LayoutParams(bodyWidth, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 按钮行（原版 top 48dp 居中，间距 12dp）
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        val agreeBtn = button("已阅读并同意") {
            Prefs.setAppAgreementAgreed(context, true)
            onAgree()
        }
        val exitBtn = button("退出应用") { onDisagree() }
        this.agreeBtn = agreeBtn
        this.exitBtn = exitBtn
        row.addView(agreeBtn)
        row.addView(exitBtn, rowLayoutParams().apply { leftMargin = UiKit.dp(context, 12f) })

        addView(row, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = UiKit.dp(context, 44f) })
    }

    /**
     * v1.8 修复：「默认焦点落在【已阅读并同意】」这件事必须在**入树之后**做。
     *
     * 原来只有 `init` 里的 `requestFocus()` —— 那时视图还没附着到窗口，调用是无效的；
     * 宿主 `addView` 之后又把焦点交给了**根覆盖层**（`gate.requestFocus()`），于是焦点停在
     * 根节点上：**没有触屏的老盒子按遥控器确认键毫无反应，用户被卡在这一屏**
     * （模拟器实测：dump 里 `focused` 在根覆盖层，KEYCODE_DPAD_CENTER 无任何效果）。
     *
     * 宿主改为 addView 后调用本方法（不要再 `gate.requestFocus()`）。
     */
    fun focusDefault() {
        if (::agreeBtn.isInitialized) agreeBtn.requestFocus()
    }

    /**
     * 键盘/遥控器兜底：若焦点还没落在任何一个按钮上（例如设备处于**触摸模式**时
     * `requestFocus()` 会被系统拒绝 —— 非 `focusableInTouchMode` 的视图在触摸模式下不能获焦），
     * 任意方向键/确认键到来时先把焦点补到「已阅读并同意」，避免"按了没反应"。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val nav = keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
        if (nav && ::agreeBtn.isInitialized) {
            val f = findFocus()
            if (f !== agreeBtn && !(::exitBtn.isInitialized && f === exitBtn)) {
                if (agreeBtn.requestFocus()) return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** 原版按钮容器色 `onSurface.copy(alpha = 0.1f)`，焦点态实底（与本工程 AppDialog 一致）。 */
    private fun button(label: String, onClick: () -> Unit): TextView {
        val ctx = context
        return TextView(ctx).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setPadding(UiKit.dp(ctx, 22f), UiKit.dp(ctx, 10f), UiKit.dp(ctx, 22f), UiKit.dp(ctx, 10f))
            setOnFocusChangeListener { v, hasFocus ->
                (v as TextView).background = if (hasFocus) {
                    UiKit.rounded(Palette.onSurface(ctx), 8f, ctx)
                } else {
                    UiKit.rounded((Palette.onSurface(ctx) and 0x00FFFFFF) or 0x1A000000, 8f, ctx)
                }
                setTextColor(if (hasFocus) 0xFF101014.toInt() else Palette.onSurface(ctx))
            }
            background = UiKit.rounded((Palette.onSurface(ctx) and 0x00FFFFFF) or 0x1A000000, 8f, ctx)
            setTextColor(Palette.onSurface(ctx))
            setOnClickListener {
                // 同意按钮：把"已同意"落库交给外层（onAgree 里做），这里只负责触发
                onClick()
            }
        }
    }

    private fun text(s: String, sp: Float, bold: Boolean, color: Int): TextView =
        TextView(context).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            paint.isFakeBoldText = bold
        }

    private fun rowLayoutParams() = LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
