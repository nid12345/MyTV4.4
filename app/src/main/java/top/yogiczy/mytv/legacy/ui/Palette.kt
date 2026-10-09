package top.yogiczy.mytv.legacy.ui

import android.content.Context
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import top.yogiczy.mytv.legacy.R

/**
 * 主题色访问器
 *
 * ---------------------------------------------------------------------------
 * 对应原版：MyTV 1.7（源码 tag `custom-v1.7`）
 *           `core/designsystem/theme/Color.kt` 的 `darkColors`（48 色）
 *
 * M4 之前这里是 9 个硬编码 `const val 0xFFxxxxxx.toInt()`；现在**全部改为读取
 * `res/values/colors.xml`**，色值的唯一来源是那个资源文件（它逐字抄自原版 darkColors）。
 *
 * ⚠️ **与原版的一处结构性差异**：原版在 Compose 里用
 * `CompositionLocalProvider(LocalColors provides darkColors)` 一次注入、全局可取，
 * 因此调用点是 `colors.onSurface` 这种**无参**写法。
 * View 世界没有等价机制：资源色值必须用 `Context` 解析。
 * 所以这里把 9 个常量改成 **9 个 `xxx(ctx)` 函数**；调用点由
 * `Palette.ON_SURFACE` 改为 `Palette.onSurface(this)`（M4 已同步改完全部 34 处）。
 *
 * 命名映射（旧常量 → 新函数 → colors.xml 资源）：
 *   BACKGROUND            → background()           → md_background
 *   SURFACE               → surface()              → md_surface
 *   SURFACE_CONTAINER     → surfaceContainer()     → md_surface_container
 *   SURFACE_CONTAINER_HIGH→ surfaceContainerHigh() → md_surface_container_high
 *   ON_SURFACE            → onSurface()            → md_on_surface
 *   ON_SURFACE_VARIANT    → onSurfaceVariant()     → md_on_surface_variant
 *   SURFACE_VARIANT       → surfaceVariant()       → md_surface_variant
 *   PRIMARY               → primary()              → md_primary
 *   OUTLINE_VARIANT       → outlineVariant()       → md_outline_variant
 *   INVERSE_SURFACE       → inverseSurface()       → md_inverse_surface
 *   SURFACE_ALT（= surface 的别名，v0.5 遗留）→ surfaceAlt() → md_surface
 * ---------------------------------------------------------------------------
 */
object Palette {

    private fun color(context: Context, @ColorRes id: Int): Int =
        ContextCompat.getColor(context, id)

    /** 背景（原版 `background`）：纯黑 `#000000` */
    fun background(context: Context): Int = color(context, R.color.md_background)

    /** 面板表面（原版 `surface`）：`#0E0E0E` */
    fun surface(context: Context): Int = color(context, R.color.md_surface)

    /** v0.5 遗留别名，语义等于 [surface]（原版无此名，保留以免调用点语义漂移）。 */
    fun surfaceAlt(context: Context): Int = surface(context)

    /** 表面容器（原版 `surfaceContainer`）：`#1E1F20` */
    fun surfaceContainer(context: Context): Int = color(context, R.color.md_surface_container)

    /** 高一级表面容器（原版 `surfaceContainerHigh`）：`#282A2C` */
    fun surfaceContainerHigh(context: Context): Int =
        color(context, R.color.md_surface_container_high)

    /** 表面上的内容色（原版 `onSurface`）：`#E3E3E3` —— 也是**焦点态的反色底** */
    fun onSurface(context: Context): Int = color(context, R.color.md_on_surface)

    /** 次级内容色（原版 `onSurfaceVariant`）：`#C4C7C5` */
    fun onSurfaceVariant(context: Context): Int = color(context, R.color.md_on_surface_variant)

    /** 表面变体（原版 `surfaceVariant`）：`#444746` */
    fun surfaceVariant(context: Context): Int = color(context, R.color.md_surface_variant)

    /** 主色（原版 `primary`）：`#A8C7FA` */
    fun primary(context: Context): Int = color(context, R.color.md_primary)

    /** 描边变体（原版 `outlineVariant`）：`#444746` */
    fun outlineVariant(context: Context): Int = color(context, R.color.md_outline_variant)

    /** 反色表面（原版 `inverseSurface`）：`#E3E3E3` */
    fun inverseSurface(context: Context): Int = color(context, R.color.md_inverse_surface)

    /** 错误色（原版 `error`）：`#F2B8B5` */
    fun error(context: Context): Int = color(context, R.color.md_error)

    /** 描边（原版 `outline`）：`#8E918F` */
    fun outline(context: Context): Int = color(context, R.color.md_outline)
}
