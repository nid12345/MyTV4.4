package top.yogiczy.mytv.legacy.ui.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit

/**
 * 二维码展示块（v1.14 需求 4）。
 *
 * 一块**白底**圆角卡，里面是二维码位图 + 一行说明文字。
 *
 * ⚠️ 必须是**白底黑块**：二维码的标准要求高对比正片，深色主题下若把白色换成
 * 主题的深色底，绝大多数扫码器都读不出来（很多实现只认"亮底暗块"）。
 * 所以这里的配色刻意**不用** Palette，只有二维码周边那圈留白跟着主题走。
 */
class QrCodeView(context: Context) : LinearLayout(context) {

    private val image = ImageView(context)
    private val caption = TextView(context)

    /** 显示尺寸（px）—— 上限，实际会按"每格整数像素"回缩到不超过它。 */
    var targetSizePx: Int = UiKit.dp(context, 190f)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(s(14f), s(14f), s(14f), s(12f))
        background = UiKit.rounded(0xFFFFFFFF.toInt(), 12f, context)

        image.scaleType = ImageView.ScaleType.FIT_CENTER
        caption.apply {
            setTextColor(0xFF444746.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT
            setPadding(0, s(8f), 0, 0)
        }
        addView(image, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(caption, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /**
     * 渲染二维码。
     *
     * @return 内容过长（超出编码器支持范围）时返回 false，此时 [caption] 会被写成失败原因。
     */
    fun render(text: String, captionText: String): Boolean {
        val qr = try {
            QrEncoder.encode(text)
        } catch (e: Exception) {
            image.setImageBitmap(null)
            caption.text = "无法生成二维码：${e.message ?: "内容过长"}"
            return false
        }
        image.setImageBitmap(toBitmap(qr))
        caption.text = captionText
        return true
    }

    /**
     * 矩阵 → 位图。
     *
     * 每格用**整数个像素**（`targetSize / 总格数` 向下取整），避免非整数缩放把模块边缘
     * 糊掉导致扫不出来。四周按标准留 4 格静默区（quiet zone）。
     */
    private fun toBitmap(qr: QrEncoder.Qr): Bitmap {
        val quiet = 4
        val total = qr.size + quiet * 2
        val cell = maxOf(1, targetSizePx / total)
        val dim = cell * total
        val pixels = IntArray(dim * dim)
        for (y in 0 until dim) {
            val gy = y / cell - quiet
            for (x in 0 until dim) {
                val gx = x / cell - quiet
                val dark = gx in 0 until qr.size && gy in 0 until qr.size && qr[gx, gy]
                pixels[y * dim + x] = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return Bitmap.createBitmap(pixels, dim, dim, Bitmap.Config.ARGB_8888)
    }

    private fun s(dp: Float): Int = UiKit.dp(context, dp)
}
