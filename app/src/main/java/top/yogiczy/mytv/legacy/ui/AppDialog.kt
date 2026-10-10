package top.yogiczy.mytv.legacy.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

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

    /** 双输入框对话框（"添加直播源"）。 */
    fun buildInput(
        ctx: Context,
        title: String,
        hint1: String,
        hint2: String,
        okLabel: String = "确定",
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

        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        buttons.addView(button(ctx, "取消", onCancel))
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
        return Built(overlay, ok)
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

        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        buttons.addView(button(ctx, "取消", onCancel))
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
