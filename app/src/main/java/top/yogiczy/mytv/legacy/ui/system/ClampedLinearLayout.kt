package top.yogiczy.mytv.legacy.ui.system

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/**
 * 高度可**硬限**的纵向 LinearLayout。
 *
 * ---------------------------------------------------------------------------
 * v1.13 需求 4：右下角信息框「不要超过整体高度的五分之一」。
 *
 * 为什么不能用 `maxHeight`：`LinearLayout` 没有这个属性（只有 `ScrollView`/`ImageView`
 * 之类自带），`layoutParams.height` 又只能是定值或 WRAP_CONTENT ——
 * WRAP_CONTENT 下内容一多就会顶破上限。
 * 老平台也没有 `ConstraintLayout`（本项目冻结决策里明确禁用），
 * 所以只能自己在测量阶段夹一刀。
 *
 * 注意行为：**超限时内容会被裁掉**（不是滚动）。这里刻意不做成可滚动 ——
 * 需求要的是"信息框保持干净的小块"，可滚动的小块在电视遥控器上反而更难用。
 * 配套做法是把里面的文案字号调小，让正常情况根本到不了上限（见 MainActivity.buildBottomBar）。
 * ---------------------------------------------------------------------------
 */
class ClampedLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 高度上限（px）。<= 0 表示不限。 */
    var maxHeightPx: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val cap = maxHeightPx
        if (cap > 0 && measuredHeight > cap) {
            setMeasuredDimension(measuredWidth, cap)
        }
    }

    /** 供宿主日志/验证用：当前是否被夹住了。 */
    val isClamped: Boolean get() = maxHeightPx > 0 && measuredHeight >= maxHeightPx
}
