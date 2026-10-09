package top.yogiczy.mytv.legacy.ui

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.util.TypedValue
import android.widget.TextView
import top.yogiczy.mytv.legacy.data.Prefs

/**
 * 密度 / 字号缩放体系
 *
 * ---------------------------------------------------------------------------
 * 对应原版：MyTV 1.7（源码 tag `custom-v1.7`）`App.kt` 里的密度缩放：
 * ```kotlin
 * density = LocalDensity.current.density * (settingsViewModel.uiDensityScaleRatio
 *           .takeIf { it != 0f } ?: (configuration.screenWidthDp / 960f))
 * ```
 * 即**设计基准 960dp 宽**：屏宽 960dp → 缩放 1.0；比它窄就整体缩小，比它宽就放大。
 * 关键点是它改的是 **density**，因此 `dp`（布局）与 `sp`（字号）**同源缩放** ——
 * 这正是 v0.5 踩过的坑：
 *
 * > v0.5 用 `s(dp) = dp × density`（原样 density，布局缩了）却保留未缩放的 `sp`（字号没缩），
 * > 两者脱节 → 导航列被压到约 70dp，而「我的收藏」按 13sp 要 101dp → **文字被截断**。
 *
 * 本文件把这条规则正式化，并提供 [Mode] 两种模式；**默认前者**（更接近原版观感）。
 *
 * ⚠️ **本模块只做"算法 + 应用工具"，尚未接线**（见 §遗留项）：
 * View 侧要真正生效必须让 Activity 的 Resources 用被放缩的 `Configuration`，
 * 做法是 `createConfigurationContext`（见 [wrap]）。调用点应在 `MainActivity` 里，
 * 而 M4 的改动清单不含 `MainActivity` 的该处 —— 因此**接线留给 M5**（M5 本来就要改主界面）。
 * ---------------------------------------------------------------------------
 */
object Density {

    /** 原版设计基准宽度。 */
    const val DESIGN_WIDTH_DP = 960f

    /**
     * 缩放模式。
     *
     * 两者都由 `Prefs.uiDensityScaleRatio`（原版 `UI_DENSITY_SCALE_RATIO`）表达，
     * **不新增配置键**：
     *  - 该值为 `0f`（原版默认）→ [SCALE_BY_DESIGN_WIDTH]
     *  - 该值非 `0f` → 用户显式给定的倍率（[ABSOLUTE] 即取 1.0f）
     */
    enum class Mode(val label: String) {
        /** 按 960 基准等比缩放（默认，对齐原版） */
        SCALE_BY_DESIGN_WIDTH("按 960 基准等比缩放"),

        /** 绝对值：不缩放（ratio = 1.0） */
        ABSOLUTE("绝对值"),
    }

    /** 由配置值推断模式：0f 视为"未设置"→ 走 960 基准。 */
    fun resolveMode(prefRatio: Float): Mode =
        if (prefRatio == 0f) Mode.SCALE_BY_DESIGN_WIDTH else Mode.ABSOLUTE

    /**
     * 计算最终缩放倍率。**纯函数**（无 Android 依赖）。
     *
     * @param prefRatio `Prefs.uiDensityScaleRatio`；`0f` = 未设置
     * @param screenWidthDp 当前屏宽（dp）
     */
    fun computeRatio(prefRatio: Float, screenWidthDp: Int, mode: Mode = resolveMode(prefRatio)): Float {
        val ratio = when (mode) {
            Mode.SCALE_BY_DESIGN_WIDTH -> {
                if (prefRatio != 0f) prefRatio
                else if (screenWidthDp > 0) screenWidthDp / DESIGN_WIDTH_DP
                else 1f
            }
            Mode.ABSOLUTE -> 1f
        }
        // 防御：倍率为 0 / 负数 / NaN 会让整个界面塌掉
        return if (ratio > 0f && !ratio.isNaN() && !ratio.isInfinite()) ratio else 1f
    }

    /** 被缩放后的 density。**纯函数**。 */
    fun scaledDensity(baseDensity: Float, ratio: Float): Float = baseDensity * ratio

    /** 当前生效倍率（读配置 + 屏宽）。 */
    fun currentRatio(context: Context): Float {
        val dm = context.resources.displayMetrics
        val widthDp = (dm.widthPixels / dm.density).toInt()
        return computeRatio(Prefs.uiDensityScaleRatio(context), widthDp)
    }

    /**
     * 用被放缩的 Configuration 包一层 Context。
     *
     * 这是 View 世界里唯一能做到「dp 与 sp 同源缩放」的办法 ——
     * 直接改 `displayMetrics.density` 只影响布局换算，不会改 `scaledDensity` 与字体的换算基准。
     *
     * 接线方式（M5，已落地在 `MainActivity.attachBaseContext`）：
     * ```
     * override fun attachBaseContext(newBase: Context) {
     *     super.attachBaseContext(
     *         Density.wrap(newBase, Density.currentRatio(newBase), Prefs.uiFontScaleRatio(newBase)))
     * }
     * ```
     *
     * `fontRatio` 对应原版同一段代码里的 `fontScale = LocalDensity.current.fontScale *
     * uiFontScaleRatio`，默认 1f（`Prefs.uiFontScaleRatio` 的默认值），即不额外缩放。
     *
     * ⚠️ **一旦用了 [wrap]，就不要再调本对象的 [dp] / [sp] / [applyTextSize]**
     * —— 那两套是"手工缩放"，和 `wrap` 的"改 Configuration"叠加会变成二次缩放。
     * 两者只能选一种：本工程主界面走 [wrap]（`s(dp)` 与 `COMPLEX_UNIT_SP` 自动跟着缩放），
     * 手工那套留给**拿不到被包 Context** 的场景（例如后台线程里造 View）。
     */
    fun wrap(base: Context, ratio: Float, fontRatio: Float = 1f): Context {
        if (ratio == 1f && fontRatio == 1f) return base
        val dm = base.resources.displayMetrics
        val config = Configuration(base.resources.configuration)
        config.densityDpi = (dm.densityDpi * ratio).toInt().coerceAtLeast(1)
        config.fontScale = base.resources.configuration.fontScale *
                (if (fontRatio > 0f && !fontRatio.isNaN()) fontRatio else 1f)
        return base.createConfigurationContext(config)
    }

    /** 布局尺寸：`value(dp) × 缩放后的 density`。 */
    fun dp(context: Context, value: Float, ratio: Float = currentRatio(context)): Int =
        (value * scaledDensity(context.resources.displayMetrics.density, ratio)).toInt()

    /** 布局尺寸（Float 版）。 */
    fun dpF(context: Context, value: Float, ratio: Float = currentRatio(context)): Float =
        value * scaledDensity(context.resources.displayMetrics.density, ratio)

    /**
     * 字号：**与布局用同一个 ratio**，这是"不再截断"的关键。
     *
     * 返回 px 值，供 `setTextSize(TypedValue.COMPLEX_UNIT_PX, v)` 使用；
     * 这样字号既不受系统 fontScale 二次影响，也与布局同步缩放。
     */
    fun sp(context: Context, valueSp: Float, ratio: Float = currentRatio(context)): Float {
        val dm = context.resources.displayMetrics
        val layered = scaledDensity(dm.density, ratio)
        return valueSp * layered
    }

    /** 统一给 TextView 设字号（用 dimens.xml 里的 `ts_*_sp` 资源值）。 */
    fun applyTextSize(view: TextView, context: Context, sizeSp: Float, ratio: Float = currentRatio(context)) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(context, sizeSp, ratio))
    }

    /** 供排查：把当前换算结果打进日志（M5 接线后调用一次）。 */
    fun describe(context: Context): String {
        val dm: DisplayMetrics = context.resources.displayMetrics
        val widthDp = (dm.widthPixels / dm.density).toInt()
        val pref = Prefs.uiDensityScaleRatio(context)
        val mode = resolveMode(pref)
        val ratio = computeRatio(pref, widthDp, mode)
        return "屏宽 ${widthDp}dp / 基准 ${DESIGN_WIDTH_DP.toInt()}dp × 配置 ${pref} → 模式=${mode.label} 倍率=${"%.3f".format(ratio)} density ${dm.density} → ${scaledDensity(dm.density, ratio)}"
    }
}
