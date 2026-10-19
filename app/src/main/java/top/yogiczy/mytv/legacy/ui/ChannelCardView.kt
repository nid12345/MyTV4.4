package top.yogiczy.mytv.legacy.ui

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import top.yogiczy.mytv.legacy.core.UiLog
import top.yogiczy.mytv.legacy.data.Channel
import top.yogiczy.mytv.legacy.data.Prefs

/**
 * 频道行：台标 + 频道名 + 当前节目名。
 * 焦点态照搬原版「白底 + 黑字」反色。
 */
class ChannelCardView(
    context: Context,
    private val rowHeightPx: Int,
    logoSizePx: Int,
) : FrameLayout(context) {

    private val logo = ImageView(context)
    private val title = TextView(context)
    private val subtitle = TextView(context)

    /**
     * 行底部的"当前节目进度条"（对齐 1.7 `ChannelCard` / 本工程设置项
     * `UI_SHOW_EPG_PROGRAMME_PROGRESS`「在频道项底部显示当前节目进度条」）。
     *
     * 用「已播段 + 剩余段」两个按 weight 分配宽度的子 View 拼出来 —— 这样不需要知道卡片宽度，
     * 也不会因为 RecyclerView 复用时宽度未定而算错。
     */
    private val fill = View(context)
    private val rest = View(context)
    private val track = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(fill, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        addView(rest, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        visibility = INVISIBLE
    }

    private val bgNormal: GradientDrawable =
        UiKit.rounded(UiKit.withAlpha(Palette.surfaceContainer(context), 0.92f), 8f, context)
    private val bgFocused: GradientDrawable = UiKit.rounded(Palette.onSurface(context), 8f, context)

    /**
     * 「正在播放」那一行的底色。
     *
     * ---------------------------------------------------------------------------
     * v1.14 需求 7（用户原话）：
     *   「这个当前播放的节目位置的透明度太透了，可以改成比没选中时候还加重 10% 的透明度」
     *
     * 原来这里是 `surfaceVariant @ 0.55f` —— **比未选中项的 `surfaceContainer @ 0.92f` 还透**，
     * 叠在视频画面上几乎看不出边界。用户截图里「湖南卫视」那一行就是这个状态：
     * 明明是当前正在播的台，观感却像"没被选中"。
     *
     * 现在按字面加重 10 个百分点：0.92 → 1.00（封顶），并换成更亮的 `surfaceContainerHigh`
     * 作底，这样它比普通行**更实也更亮**，一眼能看出"这台正在播"。
     *
     * ⚠️ 与焦点态（`bgFocused` = 纯白底 + 黑字反色）刻意保持区分：
     *    焦点是「你现在指着哪一行」，正在播放是「你正在看哪个台」，两者可以不是同一行。
     * ---------------------------------------------------------------------------
     */
    private val bgCurrent: GradientDrawable =
        UiKit.rounded(Palette.surfaceContainerHigh(context), 8f, context)

    var channel: Channel? = null
        private set
    private var isCurrent = false

    /** 外部焦点回调。内部样式更新不能被覆盖，所以单独开一个入口。 */
    var onFocusChanged: ((Boolean) -> Unit)? = null

    // ---------------------------------------------------------------------
    // v1.22 需求 3：标题跑马灯
    // ---------------------------------------------------------------------
    // 潮汕节目回放这类源的标题极长（去掉台标位后也常超宽），用户要求
    // 「标题太长的话，当光标在的位置，标题要横向循环跑马灯的形式展现」。
    //
    // 为什么不用 TextView 自带的 `ellipsize=marquee`：那套机制**要求 TextView 自身
    // 获得焦点**才会启动（`onFocusChanged` 里才 startMarquee），而我们的焦点在整张
    // 卡片上、TextView 只是子视图，实测起不来。所以这里自己用 ValueAnimator 做：
    //   · 只有「本行有焦点」且「文字真的超出可用宽度」时才滚；
    //   · 匀速滚动 + 首尾各停一拍（读完再走），循环用 RESTART，回到起点是瞬时的；
    //   · 失焦 / 重新绑定 / 文字变短 → 立刻取消并把 translationX 归零，
    //     否则 RecyclerView 复用时下一行会带着上一行的偏移渲染。
    // ---------------------------------------------------------------------

    /** 跑马灯动画句柄；null = 没在滚。 */
    private var marqueeAnim: ValueAnimator? = null

    /** 当前跑马灯已滚动过的距离（px）；同一段距离重复触发时不重启动画。 */
    private var marqueeDistance: Float = -1f

    /** 跑马灯的滚动速度（dp/秒）。 */
    private val marqueeSpeedDpPerSec = 26f

    /** 首尾停顿时长（毫秒）。 */
    private val marqueeHoldMs = 900L

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, rowHeightPx
        ).apply { bottomMargin = UiKit.dp(context, 4f) }

        background = bgNormal
        setPadding(UiKit.dp(context, 10f), 0, UiKit.dp(context, 10f), 0)

        title.apply {
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitle.apply {
            setTextColor(Palette.onSurfaceVariant(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        logo.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            // v1.15 需求 6：台标放大 40% 后（46→64dp）比行高（62dp）还高，
            // 内边距跟着收到 0 —— 否则 FIT_CENTER 里再被 2+2dp 挤一次，等于白放大。
            setPadding(0, 0, 0, 0)
        }

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(subtitle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = UiKit.dp(context, 2f) })
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(logo, LinearLayout.LayoutParams(
                logoSizePx, logoSizePx).apply { marginEnd = UiKit.dp(context, 8f) })
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 进度条：贴卡片底部（左右各留 10dp 与文字对齐），高 3dp（1.7 为 3.dp）
        val barH = UiKit.dp(context, 3f)
        addView(track, LayoutParams(LayoutParams.MATCH_PARENT, barH).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = UiKit.dp(context, 10f)
            rightMargin = UiKit.dp(context, 10f)
            bottomMargin = UiKit.dp(context, 4f)
        })

        setOnFocusChangeListener { _, hasFocus ->
            applyStyle(hasFocus)
            // v1.22 需求 3：光标进这一行才跑马灯，离开立刻停并把偏移归零
            if (hasFocus) updateMarquee() else stopMarquee()
            onFocusChanged?.invoke(hasFocus)
        }
    }

    /**
     * @param programme 当前节目名（为 null 时第二行回落到 [subtitleText]，即「N 条线路 / 直播」）
     * @param progress  当前节目进度 0f..1f；< 0 表示不显示进度条
     * @param showLogo  false = 不显示台标，并且**不留台标位**（v1.22 需求 3：把宽度让给标题）
     */
    fun bind(
        c: Channel,
        subtitleText: String,
        current: Boolean,
        logoBitmap: Bitmap?,
        programme: String? = null,
        progress: Float = -1f,
        showLogo: Boolean = true,
    ) {
        channel = c
        isCurrent = current
        logoAllowed = showLogo
        // 换绑到另一行：先把上一个标题的跑马灯停掉并把偏移归零
        stopMarquee()
        title.text = c.name
        subtitle.text = if (!programme.isNullOrBlank()) programme else subtitleText
        if (showLogo && logoBitmap != null) {
            logo.setImageDrawable(BitmapDrawable(resources, logoBitmap))
            logo.visibility = VISIBLE
        } else {
            logo.setImageDrawable(null)
            // ⚠️ v1.22 需求 3：这里必须是 **GONE** 而不是 INVISIBLE ——
            //    INVISIBLE 只让控件不画，布局仍会占着台标位的宽度；GONE 才会把
            //    `LinearLayout` 里 logo 的那一份（含 marginEnd）真正让出来给标题。
            //    "没有台标图"与"这个源不设台标位"是两回事，前者走 INVISIBLE。
            logo.visibility = if (showLogo) INVISIBLE else GONE
        }
        setProgress(progress)
        applyStyle(isFocused)
        //  RecyclerView 复用：这一行可能仍持着焦点（例如就地刷新），换了文字要重判跑马灯
        updateMarquee()
    }

    /**
     * 只更新"当前节目"文字与进度（节目单是**后台**拉到的，不能整列重建 —— 会丢焦点与滚动位置）。
     */
    fun setProgramme(programme: String?, progress: Float, subtitleFallback: String? = null) {
        if (!programme.isNullOrBlank()) subtitle.text = programme
        else if (subtitleFallback != null) subtitle.text = subtitleFallback
        setProgress(progress)
    }

    /**
     * 只回填台标，不动文字与焦点态。
     *
     * 用途（M5）：[ChannelLogoStore] 的远程回退是**后台线程**完成的，
     * 加载成功后经 `onLogoLoaded` 回来；此时卡片必须能"就地换图"，
     * 而不能整列重建 —— 整列重建会把焦点和滚动位置一起丢掉。
     *
     * ⚠️ v1.22 需求 3：本行若不设台标位（`showLogo = false`，如潮汕节目回放），
     * 远程图到了也**不能**把它显示出来 —— 用户要的就是那个位置让给标题。
     */
    fun setLogo(bitmap: Bitmap?) {
        if (bitmap == null) return
        if (!logoAllowed) return
        logo.setImageDrawable(BitmapDrawable(resources, bitmap))
        logo.visibility = VISIBLE
    }

    /** 本行是否允许显示台标（false = 台标位已让给标题）。 */
    private var logoAllowed: Boolean = true

    // ---------------- 跑马灯（v1.22 需求 3） ----------------

    /**
     * 焦点变化 / 文字变化后刷新跑马灯状态。
     *
     * 只有同时满足两条才滚：**本行有焦点**（"光标在的位置"）且**标题真的超出可用宽度**。
     * 标题测量要等布局完成才有宽度，所以 post 到下一帧再做。
     */
    private fun updateMarquee() {
        if (!isFocused) {
            stopMarquee()
            return
        }
        val text = title.text?.toString().orEmpty()
        if (text.isBlank()) {
            stopMarquee()
            return
        }
        title.post {
            if (!isFocused) return@post
            val avail = (title.width - title.paddingLeft - title.paddingRight).toFloat()
            val textWidth = title.paint.measureText(text)
            if (avail <= 0f || textWidth <= avail) {
                stopMarquee()
                return@post
            }
            startMarquee(textWidth - avail)
        }
    }

    /** 启动跑马灯：匀速滚过 [distance] 像素，首尾各停一拍，无限循环。 */
    private fun startMarquee(distance: Float) {
        // 已经在滚同一段距离就别重启（焦点事件可能连发）
        if (marqueeAnim?.isRunning == true && marqueeDistance == distance) return

        stopMarquee()
        marqueeDistance = distance
        val scrollMs = (distance / UiKit.dp(context, marqueeSpeedDpPerSec) * 1000f)
            .toLong().coerceIn(1500L, 20_000L)
        val total = scrollMs + marqueeHoldMs
        val holdRatio = marqueeHoldMs.toFloat() / total

        UiLog.d(
            "界面",
            "标题跑马灯启动：超出 ${distance.toInt()}px，匀速 ${marqueeSpeedDpPerSec}dp/s，" +
                    "一轮 ${total}ms（首尾各停 ${marqueeHoldMs}ms）",
        )

        marqueeAnim = ValueAnimator.ofFloat(0f, -distance).apply {
            duration = total
            // 首尾各停一拍：前 holdRatio 时间停在起点、后 holdRatio 时间停在终点，
            // 中间线性滚动。循环用 RESTART，回到起点是瞬时的（停在终点时正好读完尾巴）。
            interpolator = MarqueeInterpolator(holdRatio, holdRatio)
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { title.translationX = it.animatedValue as Float }
            start()
        }
    }

    /** 停掉跑马灯并把标题偏移归零（否则复用的卡片会带着旧偏移渲染）。 */
    private fun stopMarquee() {
        marqueeAnim?.cancel()
        marqueeAnim = null
        marqueeDistance = -1f
        title.translationX = 0f
    }

    /**
     * 跑马灯专用的时间插值器：首尾各停一拍，中间匀速。
     *
     * 为什么不用 [android.view.animation.LinearInterpolator]：匀速从头滚到尾再瞬移回起点，
     * 尾巴还没读完就跳走，长标题根本读不完；而停在终点那一拍正好是"读完最后几个字"的时间。
     *
     * 数学部分提成 [marqueeFraction] 这个纯函数，便于 JVM 单测（不依赖 Android）。
     */
    private class MarqueeInterpolator(
        private val holdStart: Float,
        private val holdEnd: Float,
    ) : android.view.animation.Interpolator {
        override fun getInterpolation(input: Float): Float =
            marqueeFraction(input, holdStart, holdEnd)
    }

    companion object {
        /**
         * 跑马灯的进度曲线（v1.22 需求 3）。
         *
         * @param input     归一化时间 0f..1f
         * @param holdStart 开头停顿时长占比
         * @param holdEnd   结尾停顿时长占比
         * @return 归一化滚动进度 0f（起点）..1f（终点）
         *
         * 边界：`holdStart + holdEnd >= 1`（两个停顿吃满整个周期）时退化成"几乎不动"，
         * 直接按 input 与 1 的关系给出端点，避免除零。
         */
        internal fun marqueeFraction(input: Float, holdStart: Float, holdEnd: Float): Float {
            if (input <= 0f) return 0f
            if (input >= 1f) return 1f
            val span = 1f - holdStart - holdEnd
            if (span <= 0f) return if (input < holdStart) 0f else 1f
            return when {
                input <= holdStart -> 0f
                input >= 1f - holdEnd -> 1f
                else -> (input - holdStart) / span
            }
        }
    }

    private fun setProgress(progress: Float) {
        val show = Prefs.uiShowEpgProgrammeProgress(context) && progress >= 0f
        if (!show) {
            track.visibility = INVISIBLE
            return
        }
        val p = progress.coerceIn(0.001f, 0.999f)
        fill.background = UiKit.rounded(Palette.primary(context), 1f, context)
        rest.background = UiKit.rounded(UiKit.withAlpha(Palette.onSurfaceVariant(context), 0.45f), 1f, context)
        fill.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, p)
        rest.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - p)
        track.visibility = VISIBLE
    }

    /**
     * 就地更新「正在播放」标记（v1.14 需求 7）。
     *
     * 面板收起期间用户可能用上下键 / 数字键换过台，重开面板时不能靠整列重建
     * （会丢焦点与滚动位置），所以由 [top.yogiczy.mytv.legacy.ui.panel.ChannelColumnView]
     * 对可见卡片逐个调这里。
     */
    fun setCurrent(current: Boolean) {
        if (isCurrent == current) return
        isCurrent = current
        applyStyle(isFocused)
    }

    private fun applyStyle(focused: Boolean) {
        background = when {
            focused -> bgFocused
            isCurrent -> bgCurrent
            else -> bgNormal
        }
        val on = if (focused) 0xFF101014.toInt() else Palette.onSurface(context)
        val sub = if (focused) 0xFF3C4043.toInt() else Palette.onSurfaceVariant(context)
        title.setTextColor(on)
        subtitle.setTextColor(sub)
    }
}
