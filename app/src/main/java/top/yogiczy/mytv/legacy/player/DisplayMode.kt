package top.yogiczy.mytv.legacy.player

import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import top.yogiczy.mytv.legacy.data.Channel
import top.yogiczy.mytv.legacy.data.Prefs
import top.yogiczy.mytv.legacy.data.VideoPlayerDisplayMode

/**
 * 显示比例（7 种）
 *
 * ---------------------------------------------------------------------------
 * 对应原版：MyTV 1.7（源码 tag `custom-v1.7`）`VideoPlayerState.kt` 的
 * `VideoPlayerDisplayMode` 枚举（7 项，label/value 已由 M2 的 [Prefs] 逐条对齐），
 * 以及 `Configs.resolveVideoPlayerDisplayMode / rememberVideoPlayerDisplayMode /
 * forgetVideoPlayerDisplayMode` 三个按频道记忆的方法（M2 已移植到 [Prefs]）。
 *
 * 原版的画面布局走 media3-ui 的 `AspectRatioFrameLayout`：
 *   RESIZE_MODE_FIT(原始) / RESIZE_MODE_FILL(填充) / RESIZE_MODE_ZOOM(裁剪)。
 * 另外 4 种固定比例（4:3 / 16:9 / 2.35:1 / 3:4）是原版在 Compose 侧用
 * `Modifier.aspectRatio()` 把画面容器本身改成该比例实现的。
 *
 * 本工程没有 media3-ui / Compose，因此这里把**全部 7 种的几何算法**用纯函数实现
 * （[layoutRect]，不依赖任何 Android 类 → 可在 JVM 上单测），再用 [apply] 落到
 * `FrameLayout.LayoutParams` 上。**语义与原版的三种 resizeMode + 四种固定比例一致。**
 * ---------------------------------------------------------------------------
 */
object DisplayMode {

    /** 显示比例落盘几何的 logcat 标签（中文，R8 不影响字面量）。 */
    private const val DISPLAY_TAG = "显示比例"

    /** 画面矩形（左上右下，单位与入参一致）。用自定义类型而非 `android.graphics.Rect`，便于 JVM 单测。 */
    data class Frame(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** 7 种，顺序与 [VideoPlayerDisplayMode] 枚举一致。 */
    val ALL: List<VideoPlayerDisplayMode> = listOf(
        VideoPlayerDisplayMode.ORIGINAL,
        VideoPlayerDisplayMode.FILL,
        VideoPlayerDisplayMode.CROP,
        VideoPlayerDisplayMode.FOUR_THREE,
        VideoPlayerDisplayMode.SIXTEEN_NINE,
        VideoPlayerDisplayMode.WIDE,
        VideoPlayerDisplayMode.THREE_FOUR,
    )

    /** 固定比例模式的宽高比；其余模式返回 null。 */
    fun fixedAspect(mode: VideoPlayerDisplayMode): Float? = when (mode) {
        VideoPlayerDisplayMode.FOUR_THREE -> 4f / 3f
        VideoPlayerDisplayMode.SIXTEEN_NINE -> 16f / 9f
        VideoPlayerDisplayMode.WIDE -> 2.35f
        VideoPlayerDisplayMode.THREE_FOUR -> 3f / 4f
        else -> null
    }

    /**
     * 计算画面矩形。**纯函数**（无 Android 依赖），可 JVM 单测。
     *
     * ⚠️ 判据来自原版源码，不是我自己定的：
     * `tv/.../ui/screens/videoplayer/VideoPlayerScreen.kt:43-52`
     * ```
     * ORIGINAL     -> Modifier.aspectRatio(state.aspectRatio)      // 按视频比例放入
     * FILL         -> Modifier.fillMaxSize()                       // 铺满（拉伸）
     * CROP         -> Modifier.fillMaxWidth().aspectRatio(videoAR) // 宽度铺满，高按比例
     * FOUR_THREE   -> Modifier.aspectRatio(4f / 3)
     * SIXTEEN_NINE -> Modifier.aspectRatio(16f / 9)
     * WIDE         -> Modifier.aspectRatio(2.35f / 1)
     * THREE_FOUR   -> Modifier.aspectRatio(3f / 4)
     * ```
     * 画面容器是 `Box(align = Center)`。
     *
     * ⚠️ **CROP 最容易写错**：它**不是** media3 的 `ZOOM/cover`（那种会左右裁切）。
     * 原版是「宽度铺满 + 高度按视频比例」，于是：
     *   - 视频比屏幕**窄**（如 4:3 在 16:9 屏）→ 高度超出 → **上下被裁**
     *   - 视频比屏幕**宽**（如 21:9 在 16:9 屏）→ 高度不足 → **上下留黑边**（不是左右裁切）
     * 我曾按 cover 实现，宽片会左右裁掉画面，与 1.7 观感不同 —— 已按上面原版写法改正。
     *
     * 视频尺寸未知（`videoW <= 0 || videoH <= 0`）时，ORIGINAL / CROP 退化为铺满 ——
     * 因为此时无从计算比例，先铺满避免出现空画面。
     */
    fun layoutRect(
        mode: VideoPlayerDisplayMode,
        videoW: Int,
        videoH: Int,
        viewW: Int,
        viewH: Int,
    ): Frame {
        if (viewW <= 0 || viewH <= 0) return Frame(0, 0, 0, 0)

        val viewAspect = viewW.toFloat() / viewH.toFloat()

        // CROP：原版是「宽铺满 + 高按比例」，与 cover 不同，单独处理
        if (mode == VideoPlayerDisplayMode.CROP) {
            if (videoW <= 0 || videoH <= 0) return Frame(0, 0, viewW, viewH)
            val ar = videoW.toFloat() / videoH.toFloat()
            val w = viewW
            val h = (viewW / ar).toInt().coerceAtLeast(1)
            return Frame(0, (viewH - h) / 2, w, (viewH - h) / 2 + h)
        }

        // FILL：铺满容器（不保持比例）
        if (mode == VideoPlayerDisplayMode.FILL) return Frame(0, 0, viewW, viewH)

        // 其余（ORIGINAL + 4 种固定比例）：等比放入容器（contain）
        val targetAspect: Float = when (mode) {
            VideoPlayerDisplayMode.ORIGINAL -> {
                if (videoW <= 0 || videoH <= 0) return Frame(0, 0, viewW, viewH)
                videoW.toFloat() / videoH.toFloat()
            }
            else -> fixedAspect(mode) ?: return Frame(0, 0, viewW, viewH)
        }

        var w = viewW
        var h = viewH
        if (viewAspect > targetAspect) {
            h = viewH
            w = (h * targetAspect).toInt()
        } else {
            w = viewW
            h = (w / targetAspect).toInt()
        }

        w = w.coerceAtLeast(1)
        h = h.coerceAtLeast(1)

        val left = (viewW - w) / 2
        val top = (viewH - h) / 2
        return Frame(left, top, left + w, top + h)
    }

    /**
     * 把显示比例应用到实际 View 上。
     *
     * `surface` 的父容器在本工程里是 `FrameLayout`（`MainActivity.buildUi` 的 root）；
     * 万一父容器不是 FrameLayout，退化为显式 margin 定位，保证不会因为强转崩掉。
     */
    fun apply(surface: View, mode: VideoPlayerDisplayMode, videoW: Int, videoH: Int) {
        val parent = surface.parent as? ViewGroup ?: return
        val viewW = parent.width
        val viewH = parent.height
        if (viewW <= 0 || viewH <= 0) return

        val frame = layoutRect(mode, videoW, videoH, viewW, viewH)

        val lp: ViewGroup.LayoutParams = if (parent is FrameLayout) {
            FrameLayout.LayoutParams(frame.width, frame.height, Gravity.CENTER)
        } else {
            ViewGroup.MarginLayoutParams(frame.width, frame.height).apply {
                leftMargin = frame.left
                topMargin = frame.top
            }
        }

        // 打点记录实际落盘的几何：这是显示比例验收的判据来源。
        // 用中文 tag（R8 不改字面量），按 tag 显示比例 筛 logcat。
        Log.i(
            DISPLAY_TAG,
            "应用显示比例「${mode.label}」视频=${videoW}x${videoH} 容器=${viewW}x${viewH} " +
                    "画面=${frame.width}x${frame.height} 偏移=(${frame.left},${frame.top})"
        )

        surface.layoutParams = lp
        surface.requestLayout()
    }

    // ---------------- 按频道名记忆（全部委托 M2 的 Prefs，逻辑不重复实现） ----------------

    /** 当前频道实际生效的显示比例。 */
    fun resolve(ctx: Context, channel: Channel): VideoPlayerDisplayMode =
        Prefs.resolveVideoPlayerDisplayMode(ctx, channel)

    /** 记住某个频道单独的显示比例（只影响这个频道）。 */
    fun remember(ctx: Context, channel: Channel, mode: VideoPlayerDisplayMode) =
        Prefs.rememberVideoPlayerDisplayMode(ctx, mode, channel)

    /**
     * 设为**全局默认**：先写入全局值，再清掉本频道之前单独设置的那一条。
     *
     * 对应原版 `Configs.forgetVideoPlayerDisplayMode` 的语义 ——
     * 否则该频道会一直用旧的自定义值覆盖新默认值，用户会以为"设了没生效"。
     */
    fun setGlobalDefault(ctx: Context, channel: Channel, mode: VideoPlayerDisplayMode) {
        Prefs.setVideoPlayerDisplayMode(ctx, mode)
        Prefs.forgetVideoPlayerDisplayMode(ctx, channel)
    }

    /** 只清掉本频道的单独设置，回到全局默认。 */
    fun clearChannelOverrides(ctx: Context, channel: Channel) =
        Prefs.forgetVideoPlayerDisplayMode(ctx, channel)
}
