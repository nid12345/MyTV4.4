package top.yogiczy.mytv.legacy.ui.system

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
import android.widget.TextView
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit

/**
 * 应用内置的亮度 / 音量调节条（v1.14 需求 2）。
 *
 * ---------------------------------------------------------------------------
 * 用户原话：
 *   「屏幕最左边四分之一处上下滑动可以调节显示亮度，屏幕最右边四分之一处上下滑动可以
 *    调节声音大小；然后不要显示系统的调节显示条，该用应用内置的调节条在对应那边显示
 *    亮度或是声音的调节情况；按键调节也是用这个调节条；调节条风格要和整体 ui 风格和色调
 *    一致，简洁明了」
 *
 * 实现要点：
 *   · **在对应那边显示** → 亮度贴左边缘、音量贴右边缘（垂直居中），见 [show]
 *   · **不显示系统条** → 音量用 `setStreamVolume(..., flags = 0)`（不传 `FLAG_SHOW_UI`）
 *   · **风格一致** → 沿用面板的 `surfaceContainer @0.92` 圆角卡 + `primary` 填充色，
 *     不引任何新色值
 *   · **简洁明了** → 只有三个元素：标题、竖条、百分比
 *
 * 为什么自己画竖条而不用 `SeekBar`：API 19 的 SeekBar 是 Holo 风格（细轨道 + 大圆钮），
 * 颜色也取自系统主题，与本工程的深色圆角面板不是一套语言 ——
 * 与 AppDialog 不用系统 AlertDialog、GroupManageView 不用系统 Switch 同一条理由。
 * ---------------------------------------------------------------------------
 */
class LevelBarView(context: Context) : FrameLayout(context) {

    /** 调节对象。 */
    enum class Kind(val label: String) {
        /** 显示亮度（左边缘）。 */
        BRIGHTNESS("亮度"),

        /** 媒体音量（右边缘）。 */
        VOLUME("音量"),
    }

    private val nameView = TextView(context)
    private val percentView = TextView(context)
    private val track = TrackView(context)
    private val card = LinearLayout(context)

    init {
        // 不拦截触摸：手势本身就是靠 [MainActivity.dispatchTouchEvent] 的旁路处理的，
        // 这里若吃掉触摸，手指抬起时的 tap 判定会受影响。
        isClickable = false
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS

        card.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(s(14f), s(16f), s(14f), s(16f))
            background = UiKit.rounded(
                UiKit.withAlpha(Palette.surfaceContainer(context), 0.92f), 14f, context
            )
            // 卡片必须比最宽的文字宽：只有 12dp 的竖条时，LinearLayout 会把 WRAP 的文字
            // 挤到换行/截断（实测「音量」只剩一个「音」、「53%」只剩一个数字）。
            minimumWidth = s(72f)
        }
        nameView.apply {
            setTextColor(Palette.onSurfaceVariant(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setSingleLine(true)
        }
        percentView.apply {
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
        }
        card.addView(nameView)
        card.addView(track, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = s(10f)
            bottomMargin = s(10f)
        })
        card.addView(percentView)

        addView(card, LayoutParams(WRAP, WRAP))
        visibility = View.GONE
    }

    /**
     * 显示（或原地更新）调节条。
     *
     * @param value 0f..1f
     */
    fun show(kind: Kind, value: Float) {
        val v = value.coerceIn(0f, 1f)
        nameView.text = kind.label
        percentView.text = "${(v * 100).toInt()}%"
        track.value = v

        val lp = card.layoutParams as LayoutParams
        lp.gravity = Gravity.CENTER_VERTICAL or
            (if (kind == Kind.BRIGHTNESS) Gravity.START else Gravity.END)
        lp.marginStart = s(EDGE_MARGIN_DP)
        lp.marginEnd = s(EDGE_MARGIN_DP)
        card.layoutParams = lp

        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
    }

    private fun s(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()

    /**
     * 竖直进度条：底槽 + 自下而上的填充，圆头。
     */
    private class TrackView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        /** 0f..1f。 */
        var value: Float = 0f
            set(v) {
                field = v.coerceIn(0f, 1f)
                invalidate()
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(dp(12f), dp(168f))
        }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            // 底槽：低对比，避免在浅色画面上"太吵"
            paint.color = UiKit.withAlpha(Palette.onSurfaceVariant(context), 0.30f)
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            // ⚠️ 必须用 **RectF** 重载：`drawRoundRect(float…)` 那个重载是 API 21 才有的，
            //    在 API 19 上运行时会直接 NoSuchMethodError（实测模拟器当场崩）。
            canvas.drawRoundRect(rect, r, r, paint)
            // 填充：自下往上
            val fh = height * value
            if (fh > 1f) {
                paint.color = Palette.primary(context)
                rect.set(0f, height - fh, width.toFloat(), height.toFloat())
                canvas.drawRoundRect(rect, r, r, paint)
            }
        }

        private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()
    }

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** 与屏幕边缘的距离 —— 比全局 24dp 网格略大，避开画面边缘的手势死区。 */
        const val EDGE_MARGIN_DP = 28f
    }
}
