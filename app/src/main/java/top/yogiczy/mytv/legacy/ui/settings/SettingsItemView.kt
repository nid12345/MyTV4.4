package top.yogiczy.mytv.legacy.ui.settings

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit

/**
 * 设置项行组件。
 *
 * 对应原版 `SettingsListItem.kt` 的形态：
 * ```
 * headlineContent（主标题）
 * supportingContent（副标题）
 * trailingContent（右侧值 / 开关）
 * trailingIcon（16dp 尾图标）
 * + 锁图标（不可改） / 外链图标
 * ```
 */
class SettingsItemView(context: Context) : LinearLayout(context) {

    private val textCol = LinearLayout(context).apply { orientation = VERTICAL }
    private val headline = TextView(context)
    private val supporting = TextView(context)
    private val trailing = TextView(context)
    private val tailIcon = ImageView(context)
    private var switchView: SwitchCompat? = null

    /** 右栏值变化回调（点击循环切换时用）。 */
    var onValueChanged: ((String) -> Unit)? = null

    /** 整个行的点击回调。 */
    var onClick: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = UiKit.dp(context, 56f)
        setPadding(UiKit.dp(context, 16f), UiKit.dp(context, 10f),
            UiKit.dp(context, 16f), UiKit.dp(context, 10f))
        isFocusable = true
        isFocusableInTouchMode = true

        headline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        headline.setTextColor(Palette.onSurface(context))
        headline.maxLines = 1
        supporting.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        supporting.setTextColor(Palette.onSurfaceVariant(context))
        supporting.maxLines = 3
        supporting.visibility = GONE
        textCol.addView(headline)
        textCol.addView(supporting)
        addView(textCol, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        trailing.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        trailing.setTextColor(Palette.onSurfaceVariant(context))
        trailing.visibility = GONE
        addView(trailing, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = UiKit.dp(context, 8f) })

        val s = UiKit.dp(context, 16f)
        tailIcon.visibility = GONE
        addView(tailIcon, LayoutParams(s, s))

        setOnFocusChangeListener { _, hasFocus -> applyFocusStyle(hasFocus) }
        setOnClickListener {
            // 有开关时，整行点击 = 切换开关（遥控器只有确认键，焦点落在行上而不是开关内部）
            val sw = switchView
            if (sw != null && sw.visibility == VISIBLE) sw.isChecked = !sw.isChecked
            else onClick?.invoke()
        }
        applyFocusStyle(false)
    }

    private fun applyFocusStyle(focused: Boolean) {
        background = UiKit.rounded(
            if (focused) Palette.onSurface(context)
            else UiKit.withAlpha(Palette.surfaceContainer(context), 0.86f),
            8f, context
        )
        val fg = if (focused) 0xFF101014.toInt() else Palette.onSurface(context)
        val fg2 = if (focused) 0xFF303030.toInt() else Palette.onSurfaceVariant(context)
        headline.setTextColor(fg)
        supporting.setTextColor(fg2)
        trailing.setTextColor(fg2)
    }

    /** 主标题 + 可选副标题。 */
    fun setText(headlineText: String, supportingText: String? = null) {
        headline.text = headlineText
        if (supportingText.isNullOrBlank()) {
            supporting.visibility = GONE
        } else {
            supporting.text = supportingText
            supporting.visibility = VISIBLE
        }
    }

    /** 右侧纯文本值（只读或点击循环切换）。 */
    fun setValue(value: String?) {
        if (value == null) trailing.visibility = GONE else {
            trailing.text = value
            trailing.visibility = VISIBLE
        }
    }

    /**
     * 右侧开关。
     * @param onChange 返回新值；返回 null 表示拒绝修改（锁住）
     */
    fun setSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
        if (switchView == null) {
            switchView = SwitchCompat(context).apply {
                visibility = GONE
                setOnCheckedChangeListener { _, isChecked ->
                    switchGuard = true
                    onChange(isChecked)
                    switchGuard = false
                }
            }
            addView(switchView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        switchView?.apply {
            visibility = VISIBLE
            switchGuard = true
            isChecked = checked
            switchGuard = false
        }
    }

    /** 开关赋值时避免触发 onCheckedChanged。 */
    private var switchGuard = false

    /** 尾图标（16dp）：锁 / 外链。 */
    fun setTailIcon(resId: Int?) {
        if (resId == null) tailIcon.visibility = GONE else {
            tailIcon.setImageResource(resId)
            tailIcon.visibility = VISIBLE
        }
    }

    companion object {
        /** 供外部做「锁住不可改」的语义标记（原版有锁图标）。 */
        const val LOCKED = -1
    }
}
