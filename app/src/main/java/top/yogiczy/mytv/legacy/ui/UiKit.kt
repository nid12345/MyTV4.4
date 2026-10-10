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

    /**
     * **四个角可以各不相同**的圆角背景（v1.13 需求 5）。
     *
     * 为什么需要它：三段式的三列要拼成"一个整体"，只有整体**最外侧**的四个角该是圆的，
     * 列与列相接处必须是**直角** —— 否则每列各带自己的圆角，接缝处会出现两段圆弧，
     * 看起来就是"两个框框贴在一起"（用户截图反馈的原话）。
     *
     * `GradientDrawable.cornerRadii` 的顺序是固定的
     * `[左上, 左上, 右上, 右上, 右下, 右下, 左下, 左下]`（每个角占两个 float：x/y 半径），
     * 这里按"左上/右上/右下/左下"四个 dp 值展开，避免调用点记错顺序。
     */
    fun roundedCorners(
        fill: Int,
        topLeftDp: Float,
        topRightDp: Float,
        bottomRightDp: Float,
        bottomLeftDp: Float,
        ctx: Context,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        val tl = dpF(ctx, topLeftDp)
        val tr = dpF(ctx, topRightDp)
        val br = dpF(ctx, bottomRightDp)
        val bl = dpF(ctx, bottomLeftDp)
        cornerRadii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
    }

    fun tag(ctx: Context, text: String, container: Int, content: Int): android.widget.TextView =
        android.widget.TextView(ctx).apply {
            this.text = text
            setTextColor(content)
            textSize = 10f
            background = rounded(container, 4f, ctx)
            val px = dp(ctx, 5f)
            setPadding(px, px / 2, px, px / 2)
            // ⚠️ v1.13 需求 4：角标**必须**单行。
            // 横向 LinearLayout 在总宽超出父容器时，会把**最后一个** WRAP_CONTENT 子 View
            // 按"剩余宽度"（可能只有几像素）去测量；没有 maxLines 限制的 TextView 就会
            // 一个字一行、竖着排成一条窄柱（信息框右端那条竖排乱码就是这么来的，实测复现）。
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    fun setAlpha(view: View, a: Float) {
        view.alpha = a
    }

    fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}
