package top.yogiczy.mytv.legacy.ui.system

import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit

/**
 * 底部提示条（Snackbar）
 *
 * ---------------------------------------------------------------------------
 * 对应原版：MyTV 1.7（源码 tag `custom-v1.7`）`tv/ui/material/Snackbar.kt`
 *           + `SnackbarUIState.kt`。原版用法是一句全局调用：
 * ```
 * Snackbar.show("已设为全局默认（本频道不再单独记忆）")
 * ```
 * 本工程把它做成同样的「一句话调用」形式，但因为是 View 体系，需要一个宿主 Activity
 * 才能找到挂载点，所以签名多了一个 activity 参数（[with] 绑定宿主，[show] 用绑定后的实例）。
 *
 * 原版行为：显示在屏幕底部、数秒后自动消失。这里保持同样的观感：
 * 深色圆角底 + `onSurface` 文字 + 2.6 秒后淡出。
 *
 * ⚠️ 只允许在**主线程**调用：会直接操作 View 树。
 * ---------------------------------------------------------------------------
 */
object Snackbar {

    private const val TAG = "提示"
    private const val DURATION_MS = 2_600L

    private var host: AppCompatActivity? = null
    private var current: View? = null

    private val handler = Handler(Looper.getMainLooper())
    private val dismiss = Runnable { current?.let { remove(it) } }

    /** 绑定宿主 Activity（在 onCreate 里调用一次即可）。 */
    fun with(activity: AppCompatActivity) {
        host = activity
    }

    /** 显示一条提示。 [activity] 为空时用 [with] 绑定的宿主。 */
    fun show(message: String, activity: AppCompatActivity? = host) {
        val act = activity ?: return
        if (message.isBlank()) return

        val tv = TextView(act).apply {
            text = message
            setTextColor(Palette.onSurface(act))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = UiKit.rounded(UiKit.withAlpha(Palette.surfaceContainerHigh(act), 0.96f), 8f, act)
            setPadding(dp(act, 18f), dp(act, 10f), dp(act, 18f), dp(act, 10f))
            maxLines = 2
        }

        handler.removeCallbacks(dismiss)
        current?.let { remove(it) }

        val root = act.window?.decorView as? ViewGroup ?: return
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(act, 28f)
        }
        root.addView(tv, lp)
        tv.alpha = 0f
        tv.animate().alpha(1f).setDuration(160L).start()

        current = tv
        handler.postDelayed(dismiss, DURATION_MS)
    }

    private fun remove(v: View) {
        runCatching { (v.parent as? ViewGroup)?.removeView(v) }
        if (current === v) current = null
    }

    private fun dp(act: AppCompatActivity, v: Float): Int =
        (v * act.resources.displayMetrics.density).toInt()
}
