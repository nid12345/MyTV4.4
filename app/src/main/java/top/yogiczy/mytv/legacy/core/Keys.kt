package top.yogiczy.mytv.legacy.core

import android.util.Log
import android.view.KeyEvent

/**
 * 遥控器按键路由（**最小内核**，M1）
 *
 * ## 对应原版
 * 移植自 MyTV 1.7（源码 tag `custom-v1.7`）：
 * `tv/src/main/java/top/yogiczy/mytv/tv/ui/utils/ModifierUtils.kt` 的
 * `Modifier.handleKeyEvents(onKeyTap, onKeyLongTap)` 与其两个便捷重载。
 *
 * ## 移植了原版的哪些语义（逐条）
 * 原版用 `Modifier.onPreviewKeyEvent` 实现，核心是三行：
 * ```kotlin
 * KeyEvent.ACTION_DOWN -> {
 *     if (repeatCount == 0)     keyDownMap[keyCode] = true          // ① 标记按下
 *     else if (repeatCount == 1) {
 *         keyDownMap.remove(keyCode)                                // ② 摘下标记
 *         onKeyLongTap[keyCode]?.invoke()                           // ③ 触发长按
 *     }
 * }
 * KeyEvent.ACTION_UP -> {
 *     if (keyDownMap[keyCode] == true) {                            // ④ 仍标记着
 *         keyDownMap.remove(keyCode)
 *         onKeyTap[keyCode]?.invoke()                               // ⑤ 触发短按
 *     }
 * }
 * ```
 * 本文件逐条照搬 ①～⑤。两个副作用也一并保留，因为它们就是原版的行为：
 *  - **长按会"吃掉"短按**：只要 repeatCount 走到 1，标记就被摘掉，
 *    松手时 ⑤ 不会触发。即"按住不放"不会产生换台等动作，必须一次次点。
 *  - **未注册长按回调的键也一样被吃掉**：原版 `onKeyLongTap` 是用
 *    `mapOf(KEYCODE_X to onLongX)` 构造的，即使 `onLongX` 为 null，
 *    **键仍在表里**，`?.invoke()` 什么也不做但标记已摘除。
 *
 * ## 与 Compose 版的一处**必要差异**（不是偷懒，是宿主环境决定的）
 * 原版 `onPreviewKeyEvent` 恒返回 `false`，即"只看不消费"——因为 Compose 在
 * 预览回调之后还会把事件继续派发给焦点系统，所以"切线路"与"移动焦点"可以共存。
 * **Android View 世界没有"观察而不消费"的等价物**：Activity 层返回 `true` 就
 * 终止派发。因此这里引入 [Handler] 的"是否消费"判定：
 *   **某个键只有在其对应回调非 null 时才被消费**，其余一律放行给焦点导航。
 * 这样既保住了原版"按界面按需绑定回调"的做法（原版也只在对应界面传该回调），
 * 也不会把方向键从列表焦点导航里抢走。
 *
 * ## M1 范围
 * 只做"映射表 + 短按/长按判定 + 路由"。**不做**任何业务动作：
 * MENU / SETTINGS / HELP / H 在 M1 只打印路由日志，功能菜单在 M8 接入。
 */
object Keys {

    /** 日志 tag。用字面量而非 `javaClass.simpleName`：R8 不会改写字符串常量。 */
    const val TAG = "MyTVKeys"

    /** 一个按键能触发哪一类动作。用枚举而不是直接存 lambda，便于日志与"是否有回调"判定。 */
    enum class Action {
        LEFT, LONG_LEFT, RIGHT, LONG_RIGHT,
        UP, LONG_UP, DOWN, LONG_DOWN,
        SELECT, LONG_SELECT, SETTINGS, NUMBER
    }

    /**
     * 单个按键的绑定。
     * 与原版两张表（`onKeyTap` / `onKeyLongTap`）一一对应。
     * 某键只出现在原版长按表里（如 `L`、`W/S/A/D`），这里就只填 [long]。
     */
    private class Binding(val tap: Action? = null, val long: Action? = null)

    /**
     * 回调集合 —— 对应原版 `Modifier.handleKeyEvents(...)` 的具名参数。
     * 参数名与原版保持一致，便于日后与源码对照。
     */
    class Handler(
        val onLeft: (() -> Unit)? = null,
        val onLongLeft: (() -> Unit)? = null,
        val onRight: (() -> Unit)? = null,
        val onLongRight: (() -> Unit)? = null,
        val onUp: (() -> Unit)? = null,
        val onLongUp: (() -> Unit)? = null,
        val onDown: (() -> Unit)? = null,
        val onLongDown: (() -> Unit)? = null,
        val onSelect: (() -> Unit)? = null,
        val onLongSelect: (() -> Unit)? = null,
        val onSettings: (() -> Unit)? = null,
        val onNumber: ((Int) -> Unit)? = null,
    )

    /**
     * **一张映射表** —— 原版 `onKeyTap` + `onKeyLongTap` 的合并形式。
     *
     * 与原版 `ModifierUtils.kt` 的对照（左＝原版写法，右＝本表）：
     * ```
     * KEYCODE_DPAD_LEFT    to onLeft        -> tap  = LEFT
     * KEYCODE_DPAD_RIGHT   to onRight       -> tap  = RIGHT
     * KEYCODE_DPAD_UP      to onUp          -> tap  = UP
     * KEYCODE_CHANNEL_UP   to onUp          -> tap  = UP      （同一动作，两个键码）
     * KEYCODE_DPAD_DOWN    to onDown        -> tap  = DOWN
     * KEYCODE_CHANNEL_DOWN to onDown        -> tap  = DOWN
     * KEYCODE_DPAD_CENTER  to onSelect      -> tap  = SELECT
     * KEYCODE_ENTER        to onSelect      -> tap  = SELECT
     * KEYCODE_NUMPAD_ENTER to onSelect      -> tap  = SELECT
     * KEYCODE_MENU         to onSettings    -> tap  = SETTINGS
     * KEYCODE_SETTINGS     to onSettings    -> tap  = SETTINGS
     * KEYCODE_HELP         to onSettings    -> tap  = SETTINGS
     * KEYCODE_H            to onSettings    -> tap  = SETTINGS
     * KEYCODE_L            to onLongSelect  -> long = LONG_SELECT
     * KEYCODE_W            to onLongUp      -> long = LONG_UP
     * KEYCODE_S            to onLongDown    -> long = LONG_DOWN
     * KEYCODE_A            to onLongLeft    -> long = LONG_LEFT
     * KEYCODE_D            to onLongRight   -> long = LONG_RIGHT
     * KEYCODE_0..9         to onNumber(n)   -> tap  = NUMBER
     * ```
     * 长按表（原版 `onKeyLongTap`）只覆盖这几个键，本表同：
     * `DPAD_LEFT/RIGHT/UP/DOWN`、`CHANNEL_UP/DOWN`、`DPAD_CENTER/ENTER/NUMPAD_ENTER`。
     */
    private val BINDINGS: Map<Int, Binding> = buildMap {
        fun tap(code: Int, action: Action) = put(code, Binding(tap = action))
        fun long(code: Int, action: Action) {
            val old = get(code)
            put(code, Binding(tap = old?.tap, long = action))
        }

        // ---- 短按（对应原版 onKeyTap）----
        tap(KeyEvent.KEYCODE_DPAD_LEFT, Action.LEFT)
        tap(KeyEvent.KEYCODE_DPAD_RIGHT, Action.RIGHT)
        tap(KeyEvent.KEYCODE_DPAD_UP, Action.UP)
        tap(KeyEvent.KEYCODE_CHANNEL_UP, Action.UP)
        tap(KeyEvent.KEYCODE_DPAD_DOWN, Action.DOWN)
        tap(KeyEvent.KEYCODE_CHANNEL_DOWN, Action.DOWN)
        tap(KeyEvent.KEYCODE_DPAD_CENTER, Action.SELECT)
        tap(KeyEvent.KEYCODE_ENTER, Action.SELECT)
        tap(KeyEvent.KEYCODE_NUMPAD_ENTER, Action.SELECT)
        tap(KeyEvent.KEYCODE_MENU, Action.SETTINGS)
        tap(KeyEvent.KEYCODE_SETTINGS, Action.SETTINGS)
        tap(KeyEvent.KEYCODE_HELP, Action.SETTINGS)
        tap(KeyEvent.KEYCODE_H, Action.SETTINGS)
        for (n in 0..9) tap(KeyEvent.KEYCODE_0 + n, Action.NUMBER)

        // ---- 只有长按表的键（原版把它们放进 onKeyTap，值指向 onLongXxx）----
        tap(KeyEvent.KEYCODE_L, Action.LONG_SELECT)
        tap(KeyEvent.KEYCODE_W, Action.LONG_UP)
        tap(KeyEvent.KEYCODE_S, Action.LONG_DOWN)
        tap(KeyEvent.KEYCODE_A, Action.LONG_LEFT)
        tap(KeyEvent.KEYCODE_D, Action.LONG_RIGHT)

        // ---- 长按（对应原版 onKeyLongTap）----
        long(KeyEvent.KEYCODE_DPAD_LEFT, Action.LONG_LEFT)
        long(KeyEvent.KEYCODE_DPAD_RIGHT, Action.LONG_RIGHT)
        long(KeyEvent.KEYCODE_DPAD_UP, Action.LONG_UP)
        long(KeyEvent.KEYCODE_CHANNEL_UP, Action.LONG_UP)
        long(KeyEvent.KEYCODE_DPAD_DOWN, Action.LONG_DOWN)
        long(KeyEvent.KEYCODE_CHANNEL_DOWN, Action.LONG_DOWN)
        long(KeyEvent.KEYCODE_DPAD_CENTER, Action.LONG_SELECT)
        long(KeyEvent.KEYCODE_ENTER, Action.LONG_SELECT)
        long(KeyEvent.KEYCODE_NUMPAD_ENTER, Action.LONG_SELECT)
    }

    /**
     * 已按下但尚未释放的键 —— 原版 `keyDownMap`。
     * 键码 → true。注意 `remove` 的返回值就是"此前是否标记着"，
     * 所以不需要真的存 `true`，用 `HashSet` 语义即可；这里保留 Map 形式以便与原版对照。
     */
    private val keyDownMap = HashMap<Int, Boolean>()

    /**
     * 路由一个按键事件。
     *
     * @return `true` = 本键归我们管、事件已被消费（调用方应直接 return true）；
     *         `false` = 不干预（调用方应交给 `super`，让焦点导航等系统行为生效）。
     *
     * 应同时从 `onKeyDown` 与 `onKeyUp` 调用 —— 原版 ⑤ 的短按是在 ACTION_UP 触发的，
     * 只挂 `onKeyDown` 拿不到 ACTION_UP。
     */
    fun dispatch(event: KeyEvent, handler: Handler): Boolean {
        val code = event.keyCode
        val binding = BINDINGS[code] ?: return false

        // "是否有回调"决定是否干预：没有对应回调就完全不干预（焦点导航照常）
        val tapAction = binding.tap?.takeIf { hasCallback(handler, it) }
        val longAction = binding.long?.takeIf { hasCallback(handler, it) }
        if (tapAction == null && longAction == null) return false

        /*
         * 消费规则（V2，M1 实测后收紧）：
         *
         * · 该键有**短按**回调 → 整段按下（DOWN + UP）全部消费。
         *   理由：我们既然要在松手时动作，就必须把这一整次按键都拿到手，
         *   否则焦点项会在 UP 时也触发一次 onClick，出现"我们动作了、子 View 也动作了"的双击效应。
         *
         * · 该键**只有长按**回调 → 只在真正触发长按的那一次（repeatCount == 1）消费，
         *   DOWN(repeat 0) 与 UP 都放行给子 View。
         *   理由：这才能做到"短按激活焦点项、长按唤出我们的菜单"两者共存 ——
         *   若连短按也吃掉，焦点项就点不动了（DPAD_CENTER 上最容易踩）。
         */
        val ownWholePress = tapAction != null

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    // ① 首次按下 → 标记
                    keyDownMap[code] = true
                    return ownWholePress
                }
                if (event.repeatCount == 1) {
                    // ② 第一次重复 → 摘标记（原版行为：长按吃掉短按），③ 触发长按
                    keyDownMap.remove(code)
                    if (longAction != null) {
                        Log.i(TAG, "长按 key=${name(code)} action=${longAction.name}")
                        fire(handler, longAction, code)
                        return true
                    }
                    return ownWholePress
                }
                // 更靠后的重复（2、3…）：原版不处理，这里也不处理
                return ownWholePress
            }

            KeyEvent.ACTION_UP -> {
                // ④ 仍标记着才触发短按（被长按摘掉标记时，松手不会动作）
                if (keyDownMap.remove(code) == true && tapAction != null) {
                    Log.i(TAG, "短按 key=${name(code)} action=${tapAction.name}")
                    fire(handler, tapAction, code)
                    return true
                }
                return false
            }
        }
        return false
    }

    /** 该动作在给定 handler 上是否有回调。对应原版"该界面是否传了这个回调"。 */
    private fun hasCallback(handler: Handler, action: Action): Boolean = when (action) {
        Action.LEFT -> handler.onLeft != null
        Action.LONG_LEFT -> handler.onLongLeft != null
        Action.RIGHT -> handler.onRight != null
        Action.LONG_RIGHT -> handler.onLongRight != null
        Action.UP -> handler.onUp != null
        Action.LONG_UP -> handler.onLongUp != null
        Action.DOWN -> handler.onDown != null
        Action.LONG_DOWN -> handler.onLongDown != null
        Action.SELECT -> handler.onSelect != null
        Action.LONG_SELECT -> handler.onLongSelect != null
        Action.SETTINGS -> handler.onSettings != null
        Action.NUMBER -> handler.onNumber != null
    }

    /** 真正触发回调。NUMBER 的数字由键码推导（`KEYCODE_0 + n`）。 */
    private fun fire(handler: Handler, action: Action, code: Int) {
        when (action) {
            Action.LEFT -> handler.onLeft?.invoke()
            Action.LONG_LEFT -> handler.onLongLeft?.invoke()
            Action.RIGHT -> handler.onRight?.invoke()
            Action.LONG_RIGHT -> handler.onLongRight?.invoke()
            Action.UP -> handler.onUp?.invoke()
            Action.LONG_UP -> handler.onLongUp?.invoke()
            Action.DOWN -> handler.onDown?.invoke()
            Action.LONG_DOWN -> handler.onLongDown?.invoke()
            Action.SELECT -> handler.onSelect?.invoke()
            Action.LONG_SELECT -> handler.onLongSelect?.invoke()
            Action.SETTINGS -> handler.onSettings?.invoke()
            Action.NUMBER -> handler.onNumber?.invoke(code - KeyEvent.KEYCODE_0)
        }
    }

    /** 键名，只为日志可读。不依赖 `KeyEvent.keyCodeToString`（避免低版本行为差异）。 */
    private fun name(code: Int): String = when (code) {
        KeyEvent.KEYCODE_DPAD_LEFT -> "DPAD_LEFT"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "DPAD_RIGHT"
        KeyEvent.KEYCODE_DPAD_UP -> "DPAD_UP"
        KeyEvent.KEYCODE_DPAD_DOWN -> "DPAD_DOWN"
        KeyEvent.KEYCODE_CHANNEL_UP -> "CHANNEL_UP"
        KeyEvent.KEYCODE_CHANNEL_DOWN -> "CHANNEL_DOWN"
        KeyEvent.KEYCODE_DPAD_CENTER -> "DPAD_CENTER"
        KeyEvent.KEYCODE_ENTER -> "ENTER"
        KeyEvent.KEYCODE_NUMPAD_ENTER -> "NUMPAD_ENTER"
        KeyEvent.KEYCODE_MENU -> "MENU"
        KeyEvent.KEYCODE_SETTINGS -> "SETTINGS"
        KeyEvent.KEYCODE_HELP -> "HELP"
        KeyEvent.KEYCODE_H -> "H"
        KeyEvent.KEYCODE_L -> "L"
        KeyEvent.KEYCODE_W -> "W"
        KeyEvent.KEYCODE_S -> "S"
        KeyEvent.KEYCODE_A -> "A"
        KeyEvent.KEYCODE_D -> "D"
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> "DIGIT_${code - KeyEvent.KEYCODE_0}"
        else -> "key=$code"
    }

    /** 供排查用：清空按下标记（例如 Activity 失焦时）。M1 暂未调用，M10 会用到。 */
    fun reset() = keyDownMap.clear()
}
