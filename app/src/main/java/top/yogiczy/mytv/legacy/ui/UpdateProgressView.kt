package top.yogiczy.mytv.legacy.ui

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 更新下载进度层（v1.8：用户反馈"点了更新没有任何提示下载进度之类的"）。
 *
 * 之前点「立即更新」后，进度只写在底栏那一行文字里（而且面板收起时常常看不见），
 * 用户看到的就是"点了没反应"。现在：
 *  · 点更新 → 弹出这个层：标题 + **进度条 + 百分比 + 已下载/总大小** + 状态文字；
 *  · 下载中按钮是「取消」（中止下载并关闭）；
 *  · 下载完成后按钮变成「安装」，点它才拉起系统安装器（不再"下完就直接跳走"）。
 *
 * 与 [AppDialog] 同一套配色与按钮样式。
 */
class UpdateProgressView(context: Context) : FrameLayout(context) {

    private val statusText: TextView
    private val percentText: TextView
    private val sizeText: TextView
    private val bar: ProgressBar
    private val actionBtn: TextView

    /** 「取消」点击（下载中）。 */
    var onCancel: (() -> Unit)? = null
    /** 「安装」点击（下载完成后）。 */
    var onInstall: (() -> Unit)? = null

    private var downloaded: Long = 0L
    private var total: Long = 0L

    init {
        setBackgroundColor(UiKit.withAlpha(0xFF000000.toInt(), 0.55f))
        isClickable = true
        isFocusable = true
        // v1.8：子控件优先取焦（否则下面的 actionBtn.requestFocus() 会被本根节点截胡）
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(context, 22f), UiKit.dp(context, 20f),
                UiKit.dp(context, 22f), UiKit.dp(context, 16f))
            background = UiKit.rounded(Palette.surfaceContainerHigh(context), 12f, context)
            isClickable = true
        }

        panel.addView(TextView(context).apply {
            text = "正在下载更新"
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        })

        statusText = TextView(context).apply {
            text = "正在连接…"
            setTextColor(Palette.onSurfaceVariant(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, UiKit.dp(context, 10f), 0, 0)
        }
        panel.addView(statusText)

        bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        panel.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = UiKit.dp(context, 12f) })

        percentText = TextView(context).apply {
            text = "0%"
            setTextColor(Palette.onSurface(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, UiKit.dp(context, 6f), 0, 0)
        }
        panel.addView(percentText)

        sizeText = TextView(context).apply {
            text = ""
            setTextColor(Palette.onSurfaceVariant(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        panel.addView(sizeText)

        actionBtn = TextView(context).apply {
            text = "取消"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            isFocusable = true
            setPadding(UiKit.dp(context, 20f), UiKit.dp(context, 10f),
                UiKit.dp(context, 20f), UiKit.dp(context, 10f))
            background = UiKit.rounded(Palette.onSurface(context), 8f, context)
            setTextColor(0xFF101014.toInt())
            setOnFocusChangeListener { v, hasFocus ->
                (v as TextView).background = if (hasFocus) {
                    UiKit.rounded(Palette.onSurface(context), 8f, context)
                } else {
                    UiKit.rounded(Color.TRANSPARENT, 8f, context)
                }
                setTextColor(if (hasFocus) 0xFF101014.toInt() else Palette.onSurface(context))
            }
            setOnClickListener {
                if (tag == "install") onInstall?.invoke() else onCancel?.invoke()
            }
        }
        panel.addView(actionBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.END
            topMargin = UiKit.dp(context, 16f)
        })

        val width = minOf((context.resources.displayMetrics.widthPixels * 0.80f).toInt(),
            UiKit.dp(context, 560f))
        addView(panel, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { gravity = Gravity.CENTER })

        // 遥控器上进来时把焦点落到按钮，方向键/确认键才可用（触屏不受影响）
        post { actionBtn.requestFocus() }
    }

    /** 宿主下载循环里调用（主线程）。 */
    fun setProgress(percent: Int, downloadedBytes: Long, totalBytes: Long) {
        downloaded = downloadedBytes
        total = totalBytes
        bar.isIndeterminate = percent < 0
        bar.progress = percent.coerceIn(0, 100)
        percentText.text = if (percent < 0) "—" else "$percent%"
        statusText.text = "正在下载更新…"
        sizeText.text = if (totalBytes > 0) {
            "${fmt(downloadedBytes)} / ${fmt(totalBytes)}"
        } else {
            fmt(downloadedBytes)
        }
    }

    /** 下载完成：状态改为完成，按钮变「安装」。 */
    fun setFinished(fileName: String) {
        bar.progress = 100
        percentText.text = "100%"
        statusText.text = "下载完成：$fileName"
        sizeText.text = ""
        actionBtn.text = "安装"
        actionBtn.tag = "install"
        actionBtn.background = UiKit.rounded(Palette.onSurface(context), 8f, context)
    }

    /** 下载失败：状态改为失败，按钮变「关闭」。 */
    fun setFailed(reason: String) {
        statusText.text = "下载失败：$reason"
        percentText.text = ""
        sizeText.text = ""
        actionBtn.text = "关闭"
        actionBtn.tag = "close"
    }

    private fun fmt(bytes: Long): String =
        if (bytes <= 0L) "0 KB" else "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
