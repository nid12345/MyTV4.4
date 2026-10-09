package top.yogiczy.mytv.legacy.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View

// 主题色已迁到独立的 ui/Palette.kt，色值来源改为 res/values/colors.xml（M4）。

object UiKit {
    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).toInt()
    fun dpF(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    /** 圆角实心背景。API 17 无原生圆角容器，统一用 GradientDrawable。 */
    fun rounded(fill: Int, radiusDp: Float, ctx: Context, stroke: Int = 0, strokeDp: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dpF(ctx, radiusDp)
            if (stroke != 0) setStroke(dp(ctx, strokeDp).coerceAtLeast(1), stroke)
        }

    fun tag(ctx: Context, text: String, container: Int, content: Int): android.widget.TextView =
        android.widget.TextView(ctx).apply {
            this.text = text
            setTextColor(content)
            textSize = 10f
            background = rounded(container, 4f, ctx)
            val px = dp(ctx, 5f)
            setPadding(px, px / 2, px, px / 2)
        }

    fun setAlpha(view: View, a: Float) {
        view.alpha = a
    }

    fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}
