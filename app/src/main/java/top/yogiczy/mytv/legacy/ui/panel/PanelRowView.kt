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
        addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        background = bgNormal
        setOnFocusChangeListener { _, hasFocus ->
            background = if (hasFocus) bgFocus else bgNormal
            titleView.setTextColor(
                if (hasFocus) 0xFF101014.toInt() else Palette.onSurface(context)
            )
            subtitleView?.setTextColor(
                if (hasFocus) 0xFF3C4043.toInt() else Palette.onSurfaceVariant(context)
            )
            onFocusChanged?.invoke(hasFocus)
        }
    }

    fun bind(
        title: String,
        subtitle: String?,
        icon: Drawable?,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null,
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
        if (icon != null) {
            iconView.setImageDrawable(icon)
            iconView.visibility = View.VISIBLE
        } else {
            iconView.visibility = View.INVISIBLE
        }
        setOnClickListener { onClick() }
        if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        else setOnLongClickListener(null)
    }

    private fun s(dp: Float): Int =
        (dp * resources.displayMetrics.density).toInt()
}
