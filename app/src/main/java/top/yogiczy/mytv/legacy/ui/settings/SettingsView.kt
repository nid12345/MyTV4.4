package top.yogiczy.mytv.legacy.ui.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import top.yogiczy.mytv.legacy.core.UiLog
import top.yogiczy.mytv.legacy.data.Prefs
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit

/**
 * 设置页：`左栏 216dp（分类） + 右栏（内容）`，间距 52dp，外层左右留白 58dp。
 *
 * 对应原版 `SettingsScreen.kt`：
 * ```
 * Box(customBackground(), padding(horizontal = 58.dp)) {
 *     Row(spacedBy(52.dp)) {
 *         SettingsCategoryList(width = 216.dp)
 *         SettingsCategoryContent()
 *     }
 * }
 * ```
 *
 * 关键行为（原版实测）：
 * - 分类项**聚焦即选中**（onFocusChanged → onCategorySelected）
 * - 分类项按确认 / 右键 → `focusSearch(FOCUS_RIGHT)` 进右栏
 * - 设置页**每秒刷新一次**
 */
class SettingsView(context: Context) : LinearLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val catColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val contentColumn = LinearLayout(context).apply { orientation = VERTICAL }

    // 问题 8（用户要求）：整个「预约」分类已删除，原先在这里的 reserveView 一并移除。

    private var selected = 0
    private var lastSignature: String? = null

    /** 关闭设置页的回调（返回键 / 再次按 SETTINGS）。 */
    var onClose: (() -> Unit)? = null

    /** 需要宿主提供的动作（跳换源面板等）。 */
    var onCustomSource: (() -> Unit)? = null
    var onGroupManage: (() -> Unit)? = null

    /** 问题 6：「全部分组管理」（所有订阅源一起管）。 */
    var onGroupManageAll: (() -> Unit)? = null

    /** v1.8：「播放器自定义UA」点击后由宿主弹出录入框（原来只能看不能改）。 */
    var onEditUa: (() -> Unit)? = null

    /** 问题 7：「自定义节目单」（1.7 里打开 EpgSourceScreen；v1.8 改为节目单管理抽屉）。 */
    var onCustomEpg: (() -> Unit)? = null

    /** v1.9（用户需求 1）：设置页里直接切换了节目单来源 → 宿主需要按新源重拉节目单。 */
    var onEpgSourceChanged: ((top.yogiczy.mytv.legacy.data.EpgSource) -> Unit)? = null
    var onCheckUpdate: (() -> Unit)? = null
    var onPackages: (() -> Unit)? = null
    var onResetAll: (() -> Unit)? = null
    var onSettingChanged: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        setPadding(UiKit.dp(context, 58f), UiKit.dp(context, 24f),
            UiKit.dp(context, 58f), UiKit.dp(context, 24f))
        // v1.8（用户要求）：「更多设置点开的设置页没必要四角用圆角，直接全屏底色就好」。
        // 原来是 `UiKit.rounded(Palette.background(context), 12f, context)` —— 圆角 + 与
        // 背景同色的实心块，在真机上看就是一块带圆角的贴片。改为**纯色铺满**。
        setBackgroundColor(Palette.background(context))
        visibility = GONE

        val catWrap = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(catColumn, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(catWrap, LayoutParams(UiKit.dp(context, 216f), ViewGroup.LayoutParams.MATCH_PARENT)
            .apply { marginEnd = UiKit.dp(context, 52f) })

        val contentWrap = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(contentColumn, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(contentWrap, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        buildCategories()
    }

    // ---------------- 左栏 ----------------

    private fun buildCategories() {
        catColumn.removeAllViews()
        SettingsCategory.ALL.forEachIndexed { i, cat ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(UiKit.dp(context, 12f), UiKit.dp(context, 10f),
                    UiKit.dp(context, 12f), UiKit.dp(context, 10f))
                isFocusable = true
                isFocusableInTouchMode = true
            }
            val icon = ImageView(context).apply {
                setImageResource(cat.iconRes)
                val s = UiKit.dp(context, 20f)
                layoutParams = LayoutParams(s, s).apply { marginEnd = UiKit.dp(context, 10f) }
            }
            val label = TextView(context).apply {
                text = cat.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 1
            }
            row.addView(icon)
            row.addView(label)

            // 原版：onFocusChanged → onCategorySelected（聚焦即选中）
            // ⚠️ 判据 11-1 修复：[suppressCategoryFocus] 期间右栏正在重建，此刻的焦点变化
            // 是系统"把被移除行的焦点随手交给第一个可聚焦视图"造成的**伪聚焦**，
            // 必须忽略，否则设置页会自己跳到别的分类（实测：预约 → 关于）。
            row.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && !suppressCategoryFocus) selectCategory(i)
                applyCatStyle(row, label, i == selected)
            }
            row.setOnClickListener { enterContent() }
            row.setOnKeyListener { _, code, ev ->
                if (ev.action == android.view.KeyEvent.ACTION_DOWN &&
                    (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ||
                        code == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        code == android.view.KeyEvent.KEYCODE_ENTER)
                ) {
                    enterContent()
                    return@setOnKeyListener true
                }
                false
            }
            applyCatStyle(row, label, i == 0)
            catColumn.addView(row, LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = UiKit.dp(context, 10f) })
        }
    }

    private fun applyCatStyle(row: View, label: TextView, selectedNow: Boolean) {
        row.background = UiKit.rounded(
            if (selectedNow) Palette.onSurface(context)
            else UiKit.withAlpha(Palette.surfaceContainer(context), 0.86f),
            8f, context
        )
        label.setTextColor(
            if (selectedNow) 0xFF101014.toInt() else Palette.onSurface(context)
        )
    }

    private fun selectCategory(i: Int) {
        if (selected == i && contentColumn.childCount > 0) return
        selected = i
        for (k in 0 until catColumn.childCount) {
            val row = catColumn.getChildAt(k)
            val label = (row as? ViewGroup)?.getChildAt(1) as? TextView
            if (label != null) applyCatStyle(row, label, k == i)
        }
        renderContent()
        UiLog.i("设置", "分类选中 → ${SettingsCategory.ALL[i].title}")
    }

    /** 右键 / 确认 → 把焦点送进右栏（原版 focusSearch(FOCUS_RIGHT)）。 */
    private fun enterContent(): Boolean {
        val ok = run {
            val first = contentColumn.getChildAt(0)
            if (first != null && first.isFocusable) {
                first.requestFocus()
                return@run true
            }
            // 第一行不可聚焦时（例如纯文本行），退而求其次找下一个
            for (i in 0 until contentColumn.childCount) {
                val v = contentColumn.getChildAt(i)
                if (v.isFocusable && v.requestFocus()) return@run true
            }
            false
        }
        // 诊断：真机（尤其无 adb 的 G750-T20）靠这行判断"右键进不了右栏"是焦点没送出去、
        // 还是右栏压根没有可聚焦条目。
        UiLog.i(
            "设置",
            "进右栏：${if (ok) "成功" else "失败"}（右栏 ${contentColumn.childCount} 行，" +
                "首行可聚焦=${contentColumn.getChildAt(0)?.isFocusable}）"
        )
        return ok
    }

    // ---------------- 右栏 ----------------

    /**
     * 防重入（判据 11-1 修复）。
     *
     * `renderContent()` 里的 `contentColumn.removeAllViews()` 会移除**当前聚焦的右栏行**，
     * 系统随即 `clearChildFocus` → `checkFocus()` 把焦点交给窗口里第一个可聚焦视图
     * （设置页左栏的分类行）→ 该行 `onFocusChanged` → [selectCategory] → **同步重入
     * `renderContent()`**。重入那一次已经把某些**复用的成员视图**
     * addView 进了 `contentColumn`；外层循环回来后再次 addView 同一个 View，
     * 二次入树 → `IllegalStateException: The specified child already has a parent`（真机
     * M17VA-ANSM0 实证，堆栈落在本类 :226 的 addView）。
     *
     * 修法有三层：
     *  ① 重入时不递归执行，只记一笔 [renderPending]，由最外层循环再跑一遍；
     *  ② add 之前先把自己从旧父节点摘下来（[detachFromParent]），即使将来出现别的
     *     复用路径也不会再抛；
     *  ③ 重建期间 [suppressCategoryFocus]，忽略系统随手派发的伪聚焦，并在重建后把焦点
     *     按原下标搬回右栏 —— 否则每重建一次，设置页就会自己跳到「关于」。
     */
    private var renderingContent = false
    private var renderPending = false

    /** 右栏重建期间为 true：忽略左栏分类行因焦点移交而触发的 [selectCategory]。 */
    private var suppressCategoryFocus = false

    private fun renderContent() {
        if (renderingContent) {
            renderPending = true
            return
        }
        renderingContent = true
        try {
            // 上限 4 次：正常 1 次；只有"重入改了 selected"才会跑第 2 次。防死循环。
            var pass = 0
            do {
                renderPending = false
                renderContentOnce()
                pass++
            } while (renderPending && pass < 4)
        } finally {
            renderingContent = false
        }
    }

    /** 把 View 从它当前的父节点摘下来（复用视图 add 前必须调用）。 */
    private fun detachFromParent(v: View) {
        (v.parent as? ViewGroup)?.removeView(v)
    }

    /** 当前焦点落在右栏第几行（不在右栏返回 -1）。 */
    private fun focusedContentIndex(): Int {
        var f: View = contentColumn.findFocus() ?: return -1
        while (f.parent !== contentColumn) {
            f = f.parent as? View ?: return -1
        }
        return contentColumn.indexOfChild(f)
    }

    /** v1.9：上一次停留的右栏行号（打开子抽屉前记下，关掉抽屉后搬回去）。 */
    private var lastContentIdx = -1

    /**
     * v1.9（用户需求 1）：记下"当前这一行"。宿主在弹出子抽屉（节目单管理 / 分组管理）**之前**调用。
     *
     * 为什么需要它：子抽屉是加到 rootView 最上层的新 View，抽屉被移除时系统会把焦点交给
     * 窗口里第一个可聚焦视图 —— 也就是左栏第一项「关于」→ 设置页看起来"自己跳回关于了"，
     * 用户以为刚才那一下没生效。
     */
    fun rememberFocus() {
        val i = focusedContentIndex()
        if (i >= 0) lastContentIdx = i
    }

    /** 把焦点搬回 [rememberFocus] 记下的那一行（子抽屉关闭后调用）。 */
    fun restoreContentFocus() {
        val i = lastContentIdx
        if (i < 0 || i >= contentColumn.childCount) {
            catColumn.getChildAt(selected)?.requestFocus()
            return
        }
        if (!contentColumn.getChildAt(i).requestFocus()) {
            catColumn.getChildAt(selected)?.requestFocus()
        }
    }

    /** 按 [selected] 重建右栏（只由 [renderContent] 调用，保证不重入）。 */
    private fun renderContentOnce() {
        val keepFocusIdx = focusedContentIndex()
        suppressCategoryFocus = true
        try {
            contentColumn.removeAllViews()
        val ctx = context
        val rows = when (SettingsCategory.ALL[selected]) {
            SettingsCategory.ABOUT -> SettingsCats.about(ctx)
            SettingsCategory.APP -> SettingsCats.app(ctx,
                { onResetAll?.invoke() }, { onCheckUpdate?.invoke() })
            SettingsCategory.IPTV -> SettingsCats.iptv(ctx,
                { onCustomSource?.invoke() }, { onGroupManage?.invoke() },
                { onGroupManageAll?.invoke() })
            SettingsCategory.EPG -> SettingsCats.epg(
                ctx, { onCustomEpg?.invoke() }, { onEpgSourceChanged?.invoke(it) })
            // 问题 8（用户要求）：整个「预约」分类已删除（连同 EpgReserveView 接线）
            SettingsCategory.UI -> SettingsCats.ui(ctx)
            SettingsCategory.FAVORITE -> {
                val favs = Prefs.favorites(ctx)
                SettingsCats.favorite(ctx, favs.size) {
                    Prefs.setIptvChannelFavoriteItems(ctx, emptyList())
                    renderContent()
                }
            }
            SettingsCategory.VIDEO_PLAYER -> SettingsCats.videoPlayer(ctx) { onEditUa?.invoke() }
            SettingsCategory.HTTP -> SettingsCats.http(ctx)
            SettingsCategory.DEBUG -> SettingsCats.debug(ctx)
            SettingsCategory.LOG -> SettingsCats.log(ctx)
        }
        rows.forEach { v ->
            // 复用的成员视图先摘父，否则二次入树必抛
            // IllegalStateException: The specified child already has a parent.
            detachFromParent(v)
            contentColumn.addView(v, LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = UiKit.dp(ctx, 8f) })
        }
        } finally {
            suppressCategoryFocus = false
        }
        // 焦点搬回右栏原下标：原来的焦点行已被移除，系统把焦点随手交给了左栏第一行，
        // 不搬回来就会"内容还是这一栏、焦点却在左栏"。
        if (keepFocusIdx >= 0 && contentColumn.childCount > 0) {
            var i = keepFocusIdx.coerceAtMost(contentColumn.childCount - 1)
            while (i >= 0) {
                if (contentColumn.getChildAt(i).requestFocus()) break
                i--
            }
        }
    }

    // ---------------- 生命周期 ----------------

    /** 每秒刷新（原版行为）。 */
    private val tick = object : Runnable {
        override fun run() {
            if (visibility == VISIBLE) {
                // ⚠️ 不能无条件 removeAllViews + 重建：那样 UI 永远不 idle，
                // uiautomator dump 会一直报 "could not get idle state."（M9 实测踩到）。
                val sig = signature()
                if (sig != lastSignature) {
                    lastSignature = sig
                    renderContent()
                }
            }
            handler.postDelayed(this, 1000L)
        }
    }

    /** 当前右栏内容的签名；变了才重建。 */
    private fun signature(): String {
        val ctx = context
        return buildString {
            append(SettingsCategory.ALL[selected].name).append('|')
            append(Prefs.uiShowChannelLogo(ctx)).append(Prefs.epgEnable(ctx))
                .append(Prefs.uiUseClassicPanelScreen(ctx)).append(Prefs.uiTimeShowMode(ctx).name)
                .append(Prefs.uiScreenAutoCloseDelay(ctx)).append(Prefs.uiDensityScaleRatio(ctx))
                .append(Prefs.uiFontScaleRatio(ctx)).append(Prefs.uiFocusOptimize(ctx))
                .append(Prefs.uiShowEpgProgrammeProgress(ctx))
                .append(Prefs.uiShowEpgProgrammePermanentProgress(ctx))
            append(Prefs.iptvChannelFavoriteEnable(ctx)).append(Prefs.favorites(ctx).size)
            append(Prefs.httpRetryCount(ctx)).append(Prefs.httpRetryInterval(ctx))
            append(Prefs.debugShowFps(ctx)).append(Prefs.debugShowPlayerInfo(ctx))
                .append(Prefs.debugShowLayoutGrid(ctx))
            append(Prefs.videoPlayerRenderMode(ctx))
                .append(Prefs.videoPlayerDisplayMode(ctx).name)
                .append(Prefs.videoPlayerLoadTimeout(ctx))
            append(Prefs.updateRemindEnable(ctx)).append(Prefs.updateChannel(ctx))
                .append(Prefs.updateForceRemindEnable(ctx)).append(Prefs.updateDownloadLine(ctx))
            append(Prefs.appBootLaunchEnable(ctx)).append(Prefs.iptvAutoPlayNext(ctx))
                .append(Prefs.iptvSourceCacheTime(ctx)).append(Prefs.iptvHybridMode(ctx).name)
            append(Prefs.iptvChannelNoSelectEnable(ctx))
                .append(Prefs.iptvSourceQuickSwitchEnable(ctx))
                .append(Prefs.iptvLastChannelRememberEnable(ctx))
                .append(Prefs.iptvSourceLineSpeedSortEnable(ctx))
                .append(Prefs.iptvChaoshanSourceEnable(ctx))
                .append(Prefs.iptvChannelChangeFlip(ctx))
                .append(Prefs.iptvChannelFavoriteChangeBoundaryJumpOut(ctx))
            append(Prefs.epgRefreshTimeThreshold(ctx))
            // 问题 7：节目单来源变化也要触发重建（否则改完「自定义节目单」行上的 URL 不刷新）
            append(Prefs.epgSourceCurrent(ctx).url)
            // 日志分类：内容随日志增长变化
            if (SettingsCategory.ALL[selected] == SettingsCategory.LOG) {
                append(UiLog.history.lastOrNull()?.time ?: 0L).append(UiLog.history.size)
            }
        }
    }

    fun open() {
        visibility = VISIBLE
        lastSignature = null
        renderContent()
        lastSignature = signature()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000L)
        catColumn.getChildAt(selected)?.requestFocus()
        UiLog.i("设置", "打开设置页")
    }

    fun close() {
        visibility = GONE
        handler.removeCallbacks(tick)
        onSettingChanged?.invoke()
    }

    /** 立即重绘右栏（宿主在外部改了设置后调用，不必等每秒 tick）。 */
    fun refreshNow() {
        if (visibility != VISIBLE) return
        lastSignature = null
        renderContent()
        lastSignature = signature()
    }

    val isOpen: Boolean get() = visibility == VISIBLE

    /** 返回键：焦点在右栏则回到左栏，否则关闭。 */
    fun handleBack(): Boolean {
        if (!isOpen) return false
        for (i in 0 until contentColumn.childCount) {
            if (contentColumn.getChildAt(i).hasFocus()) {
                catColumn.getChildAt(selected)?.requestFocus()
                return true
            }
        }
        close()
        return true
    }
}
