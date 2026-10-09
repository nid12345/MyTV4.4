package top.yogiczy.mytv.legacy.core

import android.util.Log
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap

/**
 * 焦点引擎（**最小内核**，M1）
 *
 * ## 对应原版
 * MyTV 1.7（源码 tag `custom-v1.7`）的 `tv/.../ui/utils/ModifierUtils.kt` 中：
 *  - `Modifier.focusOnLaunched()` / `focusOnLaunchedSaveable()` —— 进界面抢焦点
 *  - `Modifier.saveFocusRestorer(onRestoreFailed)` —— 焦点恢复，**整体 `runCatching` 兜底**
 *  - `FocusRequester.saveRequestFocus()` —— 每次 `requestFocus()` 都包一层 `runCatching`
 *
 * ## 为什么焦点恢复必须整体兜异常（原版踩过的坑）
 * 原版注释写得很明确：旧实现只把 `requestFocus()` 包进 `runCatching`，
 * 而 `onRestoreFailed()` 里"按下标取 FocusRequester"本身会抛
 * `IndexOutOfBoundsException`（列表被重建后 `indexOf` 返回 **-1**），
 * 于是异常一路冒到 Compose 崩溃 —— 用户看到的是「菜单快速下滑卡退」。
 *
 * 所以本文件的做法是：**按内容键（不是下标）定位**，并且
 * [restoreFocus] 把**整条恢复链**（取键 → 比较 → requestFocus）都放进一个 `runCatching`。
 *
 * ## M1 明确不做（留给 M10）
 *  - ❌ 列表重建后按内容重绑（`onChannelGroupListChanged` 那一套）
 *  - ❌ RecyclerView 稳定 ID / `setItemViewCacheSize` 配合
 *  - ❌ `focusRestorer` 的"记住整条链"语义（原版可以由系统恢复整条焦点路径）
 *  - ❌ 焦点在多个容器之间跨列收敛
 * 本文件只提供：首个可聚焦项、按内容键记忆与恢复、边界判定与"边界即动作"包装。
 */
object FocusEngine {

    /** 日志 tag（字面量，R8 不改写字符串常量） */
    const val TAG = "MyTVFocus"

    /**
     * View → 内容键。
     *
     * 用 [WeakHashMap] 而不是 `View.setTag()`：`setTag(Object)` 是单槽位，
     * 会被外部代码覆盖（v0.2/v0.3 就踩过 `setOnFocusChangeListener` 被外部覆盖的同类问题）。
     * WeakHashMap 不会持有 View 的强引用，列表回收后自动释放。
     */
    private val keyOfView = WeakHashMap<View, Any>()

    /** 容器 → 上一次聚焦项的内容键 */
    private val lastKeyOfContainer = WeakHashMap<ViewGroup, Any>()

    /**
     * 给可聚焦项登记**内容键**。
     *
     * 必须按"内容"取值（频道名 / 分组名 / 节目 hashCode），**不能按下标**——
     * 列表滚动或被整体替换后下标会漂移，这正是原版 -1 越界崩溃的根因。
     * 列表每次 bind 都要重新登记（M10 会把它接进 RecyclerView 的 onBindViewHolder）。
     */
    fun bind(view: View, key: Any) {
        keyOfView[view] = key
    }

    /** 取回登记过的内容键；没登记过返回 null。 */
    fun keyOf(view: View): Any? = keyOfView[view]

    /**
     * 记住"这个容器里上次聚焦的是哪一项"。
     *
     * 在子项的焦点变化回调里调用，例如：
     * ```
     * card.setOnFocusChangeListener { v, hasFocus ->
     *     if (hasFocus) FocusEngine.remember(navColumn, v)
     * }
     * ```
     */
    fun remember(container: ViewGroup?, view: View) {
        val c = container ?: (view.parent as? ViewGroup) ?: return
        val key = keyOfView[view] ?: return
        lastKeyOfContainer[c] = key
    }

    /** 清掉某个容器的记忆（换源、切换分组等场景由上层调用）。M1 暂未调用。 */
    fun forget(container: ViewGroup?) {
        container?.let { lastKeyOfContainer.remove(it) }
    }

    /**
     * 把焦点给容器内第一个可聚焦且可见的子项。
     *
     * 比 v0.2 的实现多一个 `visibility == VISIBLE` 判断：不可见但仍然 `isFocusable` 的子项
     * 会把焦点"吞掉"，界面看起来像按了没反应。
     *
     * @return 是否成功把焦点移进去
     */
    fun focusFirstIn(container: ViewGroup?): Boolean {
        val c = container ?: return false
        return runCatching {
            for (i in 0 until c.childCount) {
                val child = c.getChildAt(i) ?: continue
                if (child.isFocusable && child.visibility == View.VISIBLE) {
                    if (child.requestFocus()) return@runCatching true
                }
            }
            false
        }.getOrElse { e ->
            Log.w(TAG, "focusFirstIn 失败（已兜底）：${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * 恢复焦点。
     *
     * 优先按**记住的内容键**找回原来那一项；找不到（列表被替换 / 该项被过滤掉 /
     * 从未记住过）则回落到 [focusFirstIn]。
     *
     * **整条链都在一个 `runCatching` 里**：取键、遍历、比较、`requestFocus` 任意一步抛异常
     * 都只记为一条日志，绝不让异常冒出去（原版就是漏掉了这一步才闪退）。
     *
     * @param anchor 可选。显式指定要恢复到的内容键；不传则用 [remember] 记住的那个。
     * @return 是否成功恢复
     */
    fun restoreFocus(container: ViewGroup?, anchor: Any? = null): Boolean {
        val c = container ?: return false
        return runCatching {
            val want = anchor ?: lastKeyOfContainer[c]
            if (want != null) {
                for (i in 0 until c.childCount) {
                    val child = c.getChildAt(i) ?: continue
                    if (keyOfView[child] == want &&
                        child.isFocusable &&
                        child.visibility == View.VISIBLE
                    ) {
                        if (child.requestFocus()) return@runCatching true
                    }
                }
            }
            // 没找到 → 明确回落到第一项，而不是"什么都不做"
            focusFirstIn(c)
        }.getOrElse { e ->
            Log.w(TAG, "restoreFocus 失败（已兜底，不会崩）：${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * 该方向上是否已经到头（右边/下边/…没有别的可聚焦项了）。
     *
     * `focusSearch` 在没有目标时会返回 null 或它自己，两种情况都算边界。
     */
    fun isAtBoundary(from: View?, direction: Int): Boolean {
        val v = from ?: return true
        return runCatching {
            val next = v.focusSearch(direction)
            next == null || next === v
        }.getOrElse { true }
    }

    /**
     * **焦点移动包装：能移就移，已在边界则执行动作。**
     *
     * 这是原版 `handleKeyEvents(isFocused, focusRequester)` 里
     * `if (isFocused()) it() else focusRequester.saveRequestFocus()` 那种
     * "焦点门"思路在 View 世界的对应物 —— 但那条语义（焦点不在容器里就先把焦点移进来）
     * 留给 M10；M1 只做"边界即动作"。
     *
     * 典型用法（M6 的频道列会用到）：
     * ```
     * Keys.Handler(onUp = {
     *     FocusEngine.moveOrAct(currentFocusView(), View.FOCUS_UP) { /* 已到顶，做别的 */ }
     * })
     * ```
     *
     * @return 恒为 true（表示事件已处理，调用方应消费掉）
     */
    fun moveOrAct(from: View?, direction: Int, onBoundary: () -> Unit): Boolean {
        if (from == null) {
            runCatching { onBoundary() }
            return true
        }
        return runCatching {
            val next = from.focusSearch(direction)
            if (next == null || next === from) {
                onBoundary()
            } else {
                next.requestFocus()
            }
            true
        }.getOrElse { e ->
            Log.w(TAG, "moveOrAct 失败（已兜底）：${e.javaClass.simpleName}: ${e.message}")
            runCatching { onBoundary() }
            true
        }
    }

    /** 当前焦点（Activity 级别）。`null` 表示没有焦点项。 */
    fun currentFocusOf(container: View?): View? = runCatching { container?.findFocus() }.getOrNull()
}
