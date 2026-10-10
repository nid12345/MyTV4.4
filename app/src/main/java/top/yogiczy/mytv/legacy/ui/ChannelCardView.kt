package top.yogiczy.mytv.legacy.ui

import android.content.Context
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
            onFocusChanged?.invoke(hasFocus)
        }
    }

    /**
     * @param programme 当前节目名（为 null 时第二行回落到 [subtitleText]，即「N 条线路 / 直播」）
     * @param progress  当前节目进度 0f..1f；< 0 表示不显示进度条
     */
    fun bind(
        c: Channel,
        subtitleText: String,
        current: Boolean,
        logoBitmap: Bitmap?,
        programme: String? = null,
        progress: Float = -1f,
    ) {
        channel = c
        isCurrent = current
        title.text = c.name
        subtitle.text = if (!programme.isNullOrBlank()) programme else subtitleText
        if (logoBitmap != null) {
            logo.setImageDrawable(BitmapDrawable(resources, logoBitmap))
            logo.visibility = VISIBLE
        } else {
            logo.setImageDrawable(null)
            logo.visibility = INVISIBLE
        }
        setProgress(progress)
        applyStyle(isFocused)
    }

    /**
     * 只更新"当前节目"文字与进度（节目单是**后台**拉到的，不能整列重建 —— 会丢焦点与滚动位置）。
     */
    fun setProgramme(programme: String?, progress: Float, subtitleFallback: String? = null) {
        if (!programme.isNullOrBlank()) subtitle.text = programme
        else if (subtitleFallback != null) subtitle.text = subtitleFallback
        setProgress(progress)
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
     * 只回填台标，不动文字与焦点态。
     *
     * 用途（M5）：[ChannelLogoStore] 的远程回退是**后台线程**完成的，
     * 加载成功后经 `onLogoLoaded` 回来；此时卡片必须能"就地换图"，
     * 而不能整列重建 —— 整列重建会把焦点和滚动位置一起丢掉。
     */
    fun setLogo(bitmap: Bitmap?) {
        if (bitmap == null) return
        logo.setImageDrawable(BitmapDrawable(resources, bitmap))
        logo.visibility = VISIBLE
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
