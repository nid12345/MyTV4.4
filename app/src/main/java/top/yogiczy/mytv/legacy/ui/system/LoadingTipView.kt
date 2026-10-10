package top.yogiczy.mytv.legacy.ui.system

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit

/**
 * 加载直播源时的提示窗（v1.14 需求 1）。
 *
 * ---------------------------------------------------------------------------
 * 用户原话：
 *   「每次刚打开进入 app 的时候，右下角先不要有弹窗；但是中间底下会有个白色弹窗显示，
 *    正在加载直播源...然后有个加载的圈圈一直转；而且每次切换不同订阅直播源的时候
 *    全部其他框都不要显示，且黑屏，只会弹这个白色提示窗口；加载成功后就消失，
 *    换底部详细信息的框弹出，以显示当前节目详情；如果加载失败会在原来白色弹窗位置
 *    显示红色弹窗告知情况，并在三秒左右退回到原来的直播订阅链接正常播放那个订阅链接的内容」
 *
 * 对应关系：
 *   · 位置      → 屏幕**中下方**（底边上移屏高 20%，不是正中、也不贴底）
 *   · 白色提示  → [Kind.LOADING]：浅白圆角卡 + 旋转圆环 + 「正在加载直播源…」
 *   · 红色提示  → [Kind.ERROR]：同位置的浅红圆角卡 + 深红文字
 *   · 黑屏      → [setOpaque]：切换源时铺一层全屏不透明黑，盖住所有其他框
 *
 * 为什么自己画转圈而不用 `ProgressBar`：系统 indeterminate ProgressBar 在 API 19 上
 * 是 Holo 风格的细长横条，与本应用的深色圆角语言不符（与 AppDialog 不用系统
 * AlertDialog、GroupManageView 不用系统 Switch 是同一条理由）。
 * ---------------------------------------------------------------------------
 */
class LoadingTipView(context: Context) : FrameLayout(context) {

    /** 提示类型。 */
    enum class Kind {
        /** 加载中：白底 + 转圈。 */
        LOADING,

        /** 加载失败：红底 + 静态圆环。 */
        ERROR,
    }

    private val spinner = SpinnerView(context)
    private val label = TextView(context)
    private val card = LinearLayout(context)

    /**
     * 底边留白（px）—— v1.15 需求 1（用户）：
     * 「正在加载直播源那个框框显示位置太高，显示在和左侧菜单的最低边同个高度位置就好」。
     *
     * 左侧三段式面板的底边落在「屏高 − 24dp」（`MainActivity.panelMaxH = 屏高 − 2×24dp`
     * + `topMargin = 24dp`，v1.13 需求 4 用它同时对齐右下角信息框底边）。
     * 所以这里取同一个 24dp 网格值，提示窗底边就与面板底边齐平。
     *
     * 历史：v1.14 时是「屏高 × 20%」，用户反馈偏高 → 改成 24dp。
     */
    private val bottomGap: Int
        get() = s(EDGE_DP)

    init {
        // 吃掉点击与按键：加载期间不应该能点穿到底下任何东西
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS

        card.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(s(20f), s(14f), s(24f), s(14f))
        }
        label.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            // 必须给个宽度上限：卡片是 WRAP_CONTENT，若失败原因很长（URL + 异常原文），
            // 单行不折行会把卡片撑到屏幕外去，用户连"加载失败了"都看不到。
            maxWidth = (resources.displayMetrics.widthPixels * 0.62f).toInt()
        }
        card.addView(spinner, LinearLayout.LayoutParams(s(24f), s(24f)).apply { marginEnd = s(12f) })
        card.addView(label, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addView(card, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = bottomGap
        })

        visibility = View.GONE
    }

    /**
     * 是否铺全屏黑底。
     *
     * · 启动加载 → `false`：开机时画面本来就是黑的，铺黑反而多一层
     * · 切换订阅源 → `true`：用户明确要求"全部其他框都不要显示，且黑屏"
     */
    private var opaque = false

    fun setOpaque(value: Boolean) {
        opaque = value
        setBackgroundColor(if (value) 0xFF000000.toInt() else 0x00000000)
    }

    /** 显示提示（[text] 为空时用「正在加载直播源…」）。 */
    fun showTip(text: String, kind: Kind) {
        label.text = text
        val onError = kind == Kind.ERROR
        // 失败态放宽到 3 行：原因（URL / 异常原文）必须看得见，只留一行等于没说
        label.maxLines = if (onError) 3 else 1
        card.background = UiKit.rounded(
            if (onError) Palette.error(context) else SURFACE_LIGHT, 12f, context
        )
        label.setTextColor(if (onError) COLOR_ERROR_TEXT else COLOR_LIGHT_TEXT)
        // 失败态不再转圈 —— 圈还在转会让人以为"还在重试"
        spinner.setTint(if (onError) COLOR_ERROR_TEXT else COLOR_LIGHT_TEXT)
        spinner.spinning = !onError
        spinner.invalidate()
        visibility = View.VISIBLE
    }

    /** 收起提示并停掉动画（不停会在不可见时白烧 CPU）。 */
    fun hideTip() {
        visibility = View.GONE
        spinner.spinning = false
    }

    override fun onDetachedFromWindow() {
        spinner.spinning = false
        super.onDetachedFromWindow()
    }

    private fun s(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()

    /**
     * 自绘的加载转圈：一段 280° 的圆弧绕圈旋转。
     *
     * 只用一个 [ValueAnimator] 驱动角度，`infinite` 循环；`spinning = false` 时立刻
     * `cancel()`，避免提示收起后动画还在跑。
     */
    private class SpinnerView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 2.5f * resources.displayMetrics.density
        }

        private var angle = 0f
        private var tint = 0xFF101014.toInt()

        private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 850L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { angle = it.animatedValue as Float; invalidate() }
        }

        /** 是否正在转。置为 false 会立即停掉动画，不占 CPU。 */
        var spinning: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                if (value) animator.start() else animator.cancel()
            }

        fun setTint(color: Int) {
            tint = color
        }

        override fun onDraw(canvas: Canvas) {
            val inset = paint.strokeWidth / 2f
            val rect = RectF(inset, inset, width - inset, height - inset)
            // 底圈（淡）
            paint.color = UiKit.withAlpha(tint, 0.28f)
            canvas.drawArc(rect, 0f, 360f, false, paint)
            // 亮弧
            paint.color = tint
            canvas.drawArc(rect, angle, 280f, false, paint)
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }
    }

    private companion object {
        /** 加载中卡片的底色（浅白，压在视频画面上对比明显）。 */
        const val SURFACE_LIGHT = 0xFFF2F2F2.toInt()

        /** 浅色卡片上的文字色。 */
        const val COLOR_LIGHT_TEXT = 0xFF101014.toInt()

        /** 失败卡片的文字色（配 `Palette.error` 的浅红底）。 */
        const val COLOR_ERROR_TEXT = 0xFF410002.toInt()

        /**
         * 底边留白（dp）—— 与三段式面板 / 右下角信息框共用同一条 24dp 网格，
         * 见 [bottomGap] 的说明。改这里必须同时核对 `MainActivity.panelEdgeDp`。
         */
        const val EDGE_DP = 24f
    }
}
