package top.yogiczy.mytv.legacy.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import top.yogiczy.mytv.legacy.data.Channel
import top.yogiczy.mytv.legacy.data.EpgProgramme
import top.yogiczy.mytv.legacy.data.EpgProgramme.Companion.progress
import top.yogiczy.mytv.legacy.data.EpgProgramme.Companion.remainingMinutes

/**
 * 频道信息块。
 *
 * ---------------------------------------------------------------------------
 * 对应原版 MyTV 1.7 `channel/components/ChannelInfo.kt`（结构逐条照搬）：
 * ```
 * Row(spacedBy(16.dp)) {
 *     台标（可选，height 94.dp / aspectRatio 16:9）        → showLogo
 *     Column(spacedBy(6.dp)) {
 *         标题行：频道名(headlineMedium) + 角标(线路 / 清晰度 / 立体声) + 网速
 *         当前节目行：时间段 + 进度条 + 剩余分钟 + 节目名
 *         下一节目行：时间段 + 节目名
 *     }
 * }
 * ```
 * 三处使用（与原版一致）：
 *  ① **右下角**（经典选台面板打开时）—— `showLogo = false`，底色 `surface@0.8` 圆角，
 *     占屏宽一半，距屏幕边缘 24dp（原版 `ClassicChannelScreen` 里那个 Modifier）。
 *  ② **左下角·换台临时信息**（原版 `ChannelTempScreen`）—— `showLogo = true`，
 *     底部竖向黑渐变底，1.5 秒后隐藏。
 *  ③ **快捷菜单里的信息块**（原版 `QuickOpScreenBottom`）—— `showLogo = true`，在按钮行上方。
 *
 * ⚠️ 与原版的一处差异：原版是用一个 `ChannelInfo` Composable 加不同 Modifier；
 * 这里是同一个 View 类被实例化三次（每次的台标开关不同）。语义完全一致。
 * ---------------------------------------------------------------------------
 */
class ChannelInfoView(
    context: Context,
    private val showLogo: Boolean,
) : LinearLayout(context) {

    /** 台标。原版 `height(94.dp).aspectRatio(16/9f)`；这里按本工程整体比例取 56dp 高、16:9 宽。 */
    private val logo = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
    }

    private val title = TextView(context)
    private val tagLine = tag()
    private val tagRes = tag()
    private val tagAudio = tag()
    private val speed = TextView(context)
    private val statusText = TextView(context)

    private val curTime = infoText()
    private val curRemain = infoText()
    private val curTitle = infoText(bold = false, primary = true)
    private val nextTime = infoText()
    private val nextTitle = infoText(bold = false, primary = true)

    /** 节目区（当前节目行 + 下一节目行）；状态消息出现时整块让位。 */
    private val progBlock: LinearLayout

    private val fill = View(context)
    private val rest = View(context)
    private val track = LinearLayout(context).apply {
        orientation = HORIZONTAL
        addView(fill, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(rest, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        visibility = INVISIBLE
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.BOTTOM
        if (showLogo) {
            // v1.15 需求 6（用户）：「台标logo可以再大40%」。
            // 100×56 → 140×78（= 100×1.4 / 56×1.4，保持 16:9 不变形）。
            // ⚠️ 与左侧菜单频道行的台标（46→64dp，见 MainActivity.buildPanel 的 logoSize）
            //    和右上角大台标（64×36 → 90×50）配套放大，三处一起改才不显乱。
            addView(logo, LayoutParams(dp(140f), dp(78f)).apply { marginEnd = dp(16f) })
        } else {
            logo.visibility = View.GONE
        }

        val col = LinearLayout(context).apply {
            orientation = VERTICAL
        }

        // ---- 标题行 ----
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.apply {
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        speed.apply {
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
        }
        head.addView(title)
        listOf(tagLine, tagRes, tagAudio).forEach {
            head.addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(8f) })
        }
        head.addView(speed, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(10f) })
        col.addView(head)

        // ---- 当前节目行 ----
        val curRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(curTime, wrap().apply { marginEnd = dp(10f) })
            addView(track, LayoutParams(dp(150f), dp(6f)).apply { marginEnd = dp(10f) })
            addView(curRemain, wrap().apply { marginEnd = dp(10f) })
            addView(curTitle, wrap())
        }

        // ---- 下一节目行 ----
        val nextRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(nextTime, wrap().apply { marginEnd = dp(10f) })
            addView(nextTitle, wrap())
        }

        progBlock = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(curRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(nextRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(2f) })
        }

        // ---- 状态行（载入中 / 播放失败…）----
        statusText.apply {
            setTextColor(Palette.onSurfaceVariant(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            visibility = GONE
        }
        col.addView(progBlock)
        col.addView(statusText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(4f) })

        addView(col, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    /**
     * 绑定频道信息。
     *
     * @param status 非 null 时**只显示这一行**（节目区让位），用于"正在载入直播源…""播放失败"等。
     */
    fun bind(
        ch: Channel?,
        lineIdx: Int,
        lineCount: Int,
        resolution: String?,
        speedKBps: Int,
        cur: EpgProgramme?,
        next: EpgProgramme?,
        status: String? = null,
        logoBitmap: android.graphics.Bitmap? = null,
        progressEnabled: Boolean = true,
    ) {
        title.text = ch?.name ?: "—"

        if (showLogo) {
            if (logoBitmap != null) {
                logo.setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, logoBitmap))
                logo.visibility = VISIBLE
            } else {
                logo.setImageDrawable(null)
                logo.visibility = GONE
            }
        }

        // 角标：线路 / 清晰度 / 立体声（原版顺序），再加网速
        val lineText = if (lineCount > 0) "${lineIdx + 1}/$lineCount" else null
        tagLine.text = lineText ?: ""
        tagLine.visibility = if (lineText != null) VISIBLE else GONE
        tagRes.text = resolution ?: ""
        tagRes.visibility = if (!resolution.isNullOrBlank()) VISIBLE else GONE
        tagAudio.text = "立体声"
        tagAudio.visibility = VISIBLE
        speed.text = "${speedKBps}KB/s"

        if (status != null) {
            statusText.text = status
            statusText.visibility = VISIBLE
            progBlock.visibility = GONE
            return
        }
        statusText.visibility = GONE
        progBlock.visibility = VISIBLE

        // 当前节目
        if (cur != null) {
            curTime.text = "${hhmm(cur.startAt)} — ${hhmm(cur.endAt)}"
            curTitle.text = cur.title
            curRemain.text = "剩余 ${cur.remainingMinutes()}分钟"
        } else {
            curTime.text = ""
            curTitle.text = if (ch != null) "无节目信息" else ""
            curRemain.text = ""
        }
        // 下一节目
        if (next != null) {
            nextTime.text = "${hhmm(next.startAt)} — ${hhmm(next.endAt)}"
            nextTitle.text = next.title
        } else {
            nextTime.text = ""
            nextTitle.text = ""
        }
        setProgress(if (cur != null && progressEnabled) cur.progress() else -1f)
    }

    private fun setProgress(p: Float) {
        if (p < 0f) {
            track.visibility = INVISIBLE
            return
        }
        val v = p.coerceIn(0.001f, 0.999f)
        fill.background = UiKit.rounded(Palette.primary(context), 1f, context)
        rest.background = UiKit.rounded(
            UiKit.withAlpha(Palette.onSurfaceVariant(context), 0.45f), 1f, context)
        fill.layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, v)
        rest.layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f - v)
        track.visibility = VISIBLE
    }

    private fun tag(): TextView = UiKit.tag(
        context, "", UiKit.withAlpha(Palette.inverseSurface(context), 0.85f),
        Palette.surfaceAlt(context)
    ).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
    }

    private fun infoText(bold: Boolean = false, primary: Boolean = false): TextView =
        TextView(context).apply {
            setTextColor(if (primary) Palette.onSurface(context) else Palette.onSurfaceVariant(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    private fun wrap() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun hhmm(ms: Long): String =
        if (ms <= 0L) "--:--"
        else java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))
}
