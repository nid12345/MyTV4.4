package top.yogiczy.mytv.legacy.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import top.yogiczy.mytv.legacy.data.Prefs

/**
 * 调试叠加层（v1.8：把设置页「调试」分类的三个开关真正接通）。
 *
 * 之前 `显示FPS` / `显示播放器信息` / `显示布局网格` 三个开关只往 Prefs 里写值，
 * 没有任何地方读取 —— 用户反馈"纯粹是摆设按钮"。这里提供一个统一的左上角叠加层：
 *  - **FPS**：[Choreographer] 逐帧回调计数，每秒算一次（不是估值）；
 *  - **播放器信息**：由宿主通过 [setInfo] 喂进来（解码器 / 分辨率 / 网速）；
 *  - **布局网格**：[GridView] 自绘 8×N 网格线，用于对齐排版。
 *
 * 三个开关全关时整个视图 `GONE`，不参与绘制，不影响播放性能。
 */
class DebugOverlayView(context: Context) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())

    private val fpsText = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(0xFF00E676.toInt())
        setPadding(UiKit.dp(context, 8f), UiKit.dp(context, 4f), UiKit.dp(context, 8f), 0)
    }

    private val infoText = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(0xFFA8C7FA.toInt())
        setPadding(UiKit.dp(context, 8f), UiKit.dp(context, 2f), UiKit.dp(context, 8f), 0)
        visibility = GONE
    }

    private val grid = GridView(context)

    /** 当前要显示的播放器信息（宿主每秒刷新）。 */
    private var info: String = ""

    private var frames = 0
    private var fpsWindowStart = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frames++
            if (fpsWindowStart == 0L) fpsWindowStart = frameTimeNanos
            val elapsedNs = frameTimeNanos - fpsWindowStart
            if (elapsedNs >= 1_000_000_000L) {
                val fps = (frames * 1_000_000_000f / elapsedNs)
                fpsText.text = "FPS ${"%.1f".format(fps)}"
                frames = 0
                fpsWindowStart = frameTimeNanos
            }
            if (isAttachedToWindow) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        // 不吃触摸：调试层不能挡住画面手势（上滑换台 / 点击唤面板）
        isClickable = false
        isFocusable = false
        val column = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        column.addView(fpsText)
        column.addView(infoText)
        addView(column, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START))
        addView(grid, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        visibility = GONE
    }

    /** 由宿主（MainActivity）按设置项调用。 */
    fun applyPrefs(playerInfo: String) {
        val ctx = context
        val showFps = Prefs.debugShowFps(ctx)
        val showInfo = Prefs.debugShowPlayerInfo(ctx)
        val showGrid = Prefs.debugShowLayoutGrid(ctx)
        info = playerInfo

        fpsText.visibility = if (showFps) VISIBLE else GONE
        infoText.visibility = if (showInfo) VISIBLE else GONE
        infoText.text = playerInfo
        grid.visibility = if (showGrid) VISIBLE else GONE

        val any = showFps || showInfo || showGrid
        val wasVisible = visibility == VISIBLE
        visibility = if (any) VISIBLE else GONE
        if (any && !wasVisible) {
            Choreographer.getInstance().postFrameCallback(frameCallback)
            fpsWindowStart = 0L
            frames = 0
        }
    }

    /** 网格：竖线按 8 列等分，横线每 60dp 一条（近似的版面栅格）。 */
    private class GridView(context: Context) : View(context) {
        private val paint = Paint().apply {
            color = Color.argb(90, 255, 255, 255)
            strokeWidth = 1f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return
            val step = w / 8f
            for (i in 1 until 8) {
                val x = i * step
                canvas.drawLine(x, 0f, x, h, paint)
            }
            val vStep = UiKit.dpF(context, 60f)
            var y = vStep
            while (y < h) {
                canvas.drawLine(0f, y, w, y, paint)
                y += vStep
            }
        }
    }
}
