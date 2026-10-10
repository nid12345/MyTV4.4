package top.yogiczy.mytv.legacy.ui.panel

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit

/**
 * 面板里的「一行」（订阅源 / 收藏 / 已装应用 / 添加按钮）。
 *
 * ---------------------------------------------------------------------------
 * 对应原版：MyTV 1.7 `ClassicChannelScreen` 中栏的 `SettingsListItem` 观感
 * （主标题 + 副标题 + 图标，聚焦时整行反色）。
 * 本文件是从 `MainActivity.panelRow()` **原样抽出来**的，行为一字未改 ——
 * 抽出来的原因是 M6 要把中栏换成 RecyclerView，行必须能脱离 Activity 独立构造。
 * ---------------------------------------------------------------------------
 */
class PanelRowView(context: Context) : LinearLayout(context) {

    private val iconView: ImageView
    private val texts: LinearLayout
    private val titleView: TextView
    private var subtitleView: TextView? = null

    /** 行尾角标（v1.13 需求 3：换源行的「使用中」）。 */
    private val trailingView: TextView = TextView(context).apply {
        setTextColor(Palette.onSurfaceVariant(context))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        maxLines = 1
        visibility = View.GONE
    }

    /** 外部焦点回调（内部样式更新不能被覆盖，单独开一个入口，同 `ChannelCardView`）。 */
    var onFocusChanged: ((Boolean) -> Unit)? = null

    private val bgNormal = UiKit.rounded(
        UiKit.withAlpha(Palette.surfaceContainer(context), 0.92f), 8f, context
    )
    private val bgFocus = UiKit.rounded(Palette.onSurface(context), 8f, context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isFocusableInTouchMode = true
        setPadding(s(10f), 0, s(10f), 0)
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, s(62f)
        ).apply { bottomMargin = s(4f) }

        iconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.INVISIBLE
        }
        texts = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView = TextView(context).apply {
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(titleView)

        addView(iconView, LayoutParams(s(40f), s(40f)).apply { marginEnd = s(10f) })
        // texts 权重 1 → 后面的 trailingView 自然被顶到行尾
        addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(trailingView, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = s(8f) })

        background = bgNormal
        setOnFocusChangeListener { _, hasFocus ->
            background = if (hasFocus) bgFocus else bgNormal
            titleView.setTextColor(
                if (hasFocus) 0xFF101014.toInt() else Palette.onSurface(context)
            )
            subtitleView?.setTextColor(
                if (hasFocus) 0xFF3C4043.toInt() else Palette.onSurfaceVariant(context)
            )
            trailingView.setTextColor(
                if (hasFocus) 0xFF3C4043.toInt() else Palette.onSurfaceVariant(context)
            )
            onFocusChanged?.invoke(hasFocus)
        }
    }

    /**
     * 绑定一行。
     *
     * @param noIcon   v1.13 需求 3：**完全不留图标位**。换源列表按原版就是"只有源名"，
     *                 此前 `iconView` 用 INVISIBLE 占着 40dp + 10dp 右边距，
     *                 源名被无谓地右推了 50dp（用户截图里那块空白就是它）。
     *                 ⚠️ 必须 GONE 且把 marginEnd 清零 —— 只置 GONE 时部分 ROM 的
     *                 LinearLayout 仍会计入 margin。
     * @param trailing 行尾角标（如「使用中」）。null = 不显示。
     */
    fun bind(
        title: String,
        subtitle: String?,
        icon: Drawable?,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null,
        noIcon: Boolean = false,
        trailing: String? = null,
    ) {
        titleView.text = title
        if (subtitle == null) {
            subtitleView?.let { texts.removeView(it) }
            subtitleView = null
        } else {
            val tv = subtitleView ?: TextView(context).apply {
                setTextColor(Palette.onSurfaceVariant(context))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                texts.addView(this)
            }.also { subtitleView = it }
            tv.text = subtitle
        }
        val iconLp = iconView.layoutParams as? LayoutParams
        if (noIcon) {
            iconView.setImageDrawable(null)
            iconView.visibility = View.GONE
            if (iconLp != null && iconLp.marginEnd != 0) {
                iconLp.marginEnd = 0
                iconView.layoutParams = iconLp
            }
        } else {
            if (iconLp != null && iconLp.marginEnd != s(10f)) {
                iconLp.marginEnd = s(10f)
                iconView.layoutParams = iconLp
            }
            if (icon != null) {
                iconView.setImageDrawable(icon)
                iconView.visibility = View.VISIBLE
            } else {
                iconView.setImageDrawable(null)
                iconView.visibility = View.INVISIBLE
            }
        }
        if (trailing.isNullOrBlank()) {
            trailingView.visibility = View.GONE
        } else {
            trailingView.text = trailing
            trailingView.visibility = View.VISIBLE
        }
        setOnClickListener { onClick() }
        if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        else setOnLongClickListener(null)
    }

    private fun s(dp: Float): Int =
        (dp * resources.displayMetrics.density).toInt()
}
