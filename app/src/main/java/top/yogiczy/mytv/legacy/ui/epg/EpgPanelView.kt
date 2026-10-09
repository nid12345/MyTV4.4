package top.yogiczy.mytv.legacy.ui.epg

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.yogiczy.mytv.legacy.data.Epg
import top.yogiczy.mytv.legacy.data.EpgProgramme
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit
import java.text.SimpleDateFormat
import java.util.Locale
import top.yogiczy.mytv.legacy.data.EpgProgramme.Companion.isLive

/**
 * 独立 EPG 弹层（长按左键唤出）。
 *
 * 对应原版 `EpgScreen.kt` 的 Drawer 形态：右侧抽屉 + 半透明遮罩，
 * 内容是同一个节目竖排列表。本工程无 Compose Drawer，用 FrameLayout 覆盖实现。
 */
class EpgPanelView(context: Context) : FrameLayout(context) {

    private val dim = View(context).apply {
        setBackgroundColor(Color.parseColor("#80000000"))
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        setOnClickListener { close() }
    }

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(UiKit.dp(context, 16f), UiKit.dp(context, 16f),
            UiKit.dp(context, 16f), UiKit.dp(context, 16f))
        background = UiKit.rounded(
            UiKit.withAlpha(Palette.surface(context), 0.96f), 12f, context
        )
    }
    private val title = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(Palette.onSurface(context))
    }
    private val recycler = RecyclerView(context)
    private val items = ArrayList<EpgProgramme>()

    init {
        visibility = View.GONE
        addView(dim)
        panel.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = UiKit.dp(context, 10f) })
        recycler.layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
        recycler.isFocusable = false
        recycler.adapter = Adapter()
        panel.addView(recycler, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        addView(panel, FrameLayout.LayoutParams(
            UiKit.dp(context, 420f), ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.END })
    }

    fun open(epg: Epg?) {
        title.text = epg?.channel?.let { "$it · 节目单" } ?: "节目单"
        items.clear()
        items.addAll(epg?.programmeList?.value.orEmpty())
        (recycler.adapter as? Adapter)?.notifyDataSetChanged()
        val liveIdx = items.indexOfFirst { it.isLive() }
        if (liveIdx >= 0) recycler.scrollToPosition((liveIdx - 2).coerceAtLeast(0))
        visibility = View.VISIBLE
        panel.requestFocus()
    }

    fun close() {
        visibility = View.GONE
    }

    val isOpen: Boolean get() = visibility == View.VISIBLE

    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(EpgProgrammeItemView(parent.context))
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount(): Int = items.size
    }

    private inner class VH(private val item: EpgProgrammeItemView) : RecyclerView.ViewHolder(item) {
        fun bind(p: EpgProgramme) = item.bind(p, reserved = false, replaying = false)
    }
}
