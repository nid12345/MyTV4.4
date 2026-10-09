package top.yogiczy.mytv.legacy.ui.epg

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
 * 三段式的**第三列**：节目竖排列表 + （跨天时才有的）日期列。
 *
 * 形态严格按《第二步计划》§8.1（已确认：竖排列表 + 日期列，**不做时间轴**）：
 * ```
 * Row {
 *     EpgProgrammeItemList(width = 聚焦时 340dp else 268dp)
 *     if (跨天) EpgDayItemList(width = 80dp)
 * }
 * ```
 *
 * ⚠️ **约束 1（M7 指令）**：匹配不到节目时必须显示「无节目信息」占位，**不能空白**。
 */
class EpgColumnView(context: Context) : LinearLayout(context) {

    private val tip = EpgTipView(context)
    private val placeholder = TextView(context).apply {
        text = "无节目信息"
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(Palette.onSurfaceVariant(context))
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = UiKit.dp(context, 12f) }
    }
    private val listWrap = LinearLayout(context).apply {
        orientation = VERTICAL
        visibility = View.GONE
    }
    private val recycler = RecyclerView(context)
    private val dayColumn = EpgDayColumnView(context)

    private val programmes = ArrayList<EpgProgramme>()
    private val adapter = ProgrammeAdapter()

    var onProgrammeClick: ((EpgProgramme) -> Unit)? = null
    var onTipClick: (() -> Unit)? = null

    private val dayFmt = SimpleDateFormat("E MM-dd", Locale.getDefault())

    init {
        orientation = HORIZONTAL
        setPadding(UiKit.dp(context, 6f), UiKit.dp(context, 6f), UiKit.dp(context, 6f), 0)

        recycler.layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
        recycler.setHasFixedSize(true)
        recycler.setItemViewCacheSize(20)
        recycler.isVerticalScrollBarEnabled = false
        recycler.overScrollMode = RecyclerView.OVER_SCROLL_NEVER
        recycler.isFocusable = false
        recycler.descendantFocusability = RecyclerView.FOCUS_AFTER_DESCENDANTS
        recycler.adapter = adapter

        listWrap.addView(recycler, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        addView(listWrap, LinearLayout.LayoutParams(
            UiKit.dp(context, 268f), ViewGroup.LayoutParams.MATCH_PARENT
        ))
        addView(dayColumn)

        tip.setOnClickListener { onTipClick?.invoke() }
        tip.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onTipClick?.invoke() }
        addView(tip, 0, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        addView(placeholder, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setFocused(false)
    }

    /**
     * 把焦点送进节目列表，成功返回 true。
     *
     * ⚠️ 不能靠 `FocusEngine.focusFirstIn(this)`：它只遍历**直接子 View**，
     * 而节目项在 `recycler`（`listWrap` 的子）里，永远遍历不到 ——
     * 结果是右键第三次时焦点进不了第三列、三段式卡死在状态 1（M7 实测踩到）。
     */
    fun focusProgrammeList(): Boolean {
        val visible = listWrap.visibility == View.VISIBLE
        android.util.Log.i(
            "MyTVLegacy", "节目单：取焦 listWrap可见=$visible 节目数=${programmes.size}"
        )
        if (!visible || programmes.isEmpty()) return false
        // 优先落在「当前正在播」那一项（原版初始位置 = liveIndex - 2）
        val liveIdx = programmes.indexOfFirst { it.isLive() }
        val target = if (liveIdx >= 0) (liveIdx - 2).coerceAtLeast(0) else 0
        recycler.scrollToPosition(target)
        val ok = tryFocus(target)
        android.util.Log.i(
            "MyTVLegacy",
            "节目单：取焦 target=$target childCount=${recycler.childCount} ok=$ok"
        )
        if (!ok) {
            // ⚠️ 刚 render 完 RecyclerView 还没 layout，此刻一个子 View 都没有。
            // 必须 post 到下一帧再取焦，否则焦点留在频道列、确认键被面板逻辑吞掉
            // （表现：状态到了 2，但按确认键只是"重新唤出面板" —— M7 实测踩到）。
            recycler.post {
                recycler.scrollToPosition(target)
                tryFocus(target)
            }
        }
        return true
    }

    private fun tryFocus(pos: Int): Boolean {
        // 先找正好是 pos 的那个子 View
        for (i in 0 until recycler.childCount) {
            val v = recycler.getChildAt(i) ?: continue
            if (v.isFocusable && recycler.getChildAdapterPosition(v) == pos && v.requestFocus()) return true
        }
        // 退而求其次：任一可聚焦子项
        for (i in 0 until recycler.childCount) {
            val v = recycler.getChildAt(i) ?: continue
            if (v.isFocusable && v.requestFocus()) return true
        }
        return false
    }

    /** 是否有可聚焦的节目（供外部判断"该不该进状态 2"）。 */
    fun hasProgrammes(): Boolean = programmes.isNotEmpty()

    /** 聚焦时列表加宽到 340dp（原版 `EpgProgrammeItemList` 的宽度切换）。 */
    fun setFocused(focused: Boolean) {
        listWrap.layoutParams = LinearLayout.LayoutParams(
            UiKit.dp(context, if (focused) 340f else 268f),
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    /** 只显示竖排提示条（尚未展开 / 没有节目单数据时）。 */
    fun showTipOnly() {
        android.util.Log.i("MyTVLegacy", "节目单：重置为提示条（listWrap → GONE）")
        tip.visibility = View.VISIBLE
        placeholder.visibility = View.GONE
        listWrap.visibility = View.GONE
        dayColumn.setCrossDay(false)
    }

    /**
     * 渲染某频道的节目单。
     *
     * @param epg 该频道的节目单；null 或空 → 显示占位「无节目信息」
     */
    fun render(epg: Epg?) {
        // 同 ChannelColumnView.submit：RecyclerView 正在 layout/滚动时 notifyDataSetChanged
        // 会抛 "Cannot call this method while RecyclerView is computing a layout or scrolling"。
        // 第三列的渲染会被"换台 → 焦点回调"这条链带进来，因此同样延后一帧处理。
        if (recycler.isComputingLayout || recycler.isAnimating) {
            android.util.Log.i("MyTVLegacy", "节目单：渲染延后一帧（RecyclerView 正在 layout）")
            recycler.post { render(epg) }
            return
        }
        val all = epg?.programmeList?.value.orEmpty()
        programmes.clear()
        programmes.addAll(all)

        if (programmes.isEmpty()) {
            // 约束 1：必须有兜底，不能留一片空白
            tip.visibility = View.VISIBLE
            placeholder.visibility = View.VISIBLE
            listWrap.visibility = View.GONE
            dayColumn.setCrossDay(false)
            adapter.notifyDataSetChanged()
            return
        }

        tip.visibility = View.GONE
        placeholder.visibility = View.GONE
        listWrap.visibility = View.VISIBLE
        adapter.notifyDataSetChanged()

        // 初始滚动到「当前节目 - 2」（原版 `liveIndex - 2`）
        val liveIdx = programmes.indexOfFirst { it.isLive() }
        if (liveIdx >= 0) recycler.scrollToPosition((liveIdx - 2).coerceAtLeast(0))

        buildDayColumn()
    }

    /** 跨天时才显示日期列；日期键 = `E MM-dd`（与原版一致）。 */
    private fun buildDayColumn() {
        val keys = LinkedHashMap<String, Int>()
        programmes.forEachIndexed { i, p ->
            val k = dayFmt.format(java.util.Date(p.startAt))
            if (!keys.containsKey(k)) keys[k] = i
        }
        val crossDay = keys.size > 1
        android.util.Log.i(
            "MyTVLegacy", "节目单：日期列 天数=${keys.size} 跨天=$crossDay 键=${keys.keys}"
        )
        dayColumn.setCrossDay(crossDay)
        if (crossDay) {
            val todayIdx = keys.values.indexOf(0)
            dayColumn.submit(keys.toList(), if (todayIdx >= 0) todayIdx else 0)
            dayColumn.onDaySelected = { startIdx ->
                recycler.scrollToPosition(startIdx.coerceIn(0, programmes.size - 1))
            }
        }
    }

    private inner class ProgrammeAdapter : RecyclerView.Adapter<ProgrammeVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ProgrammeVH(EpgProgrammeItemView(parent.context))

        override fun onBindViewHolder(holder: ProgrammeVH, position: Int) =
            holder.bind(programmes[position])

        override fun getItemCount(): Int = programmes.size
    }

    private inner class ProgrammeVH(private val item: EpgProgrammeItemView) :
        RecyclerView.ViewHolder(item) {
        fun bind(p: EpgProgramme) {
            item.bind(p, reserved = false, replaying = false)
            item.setOnClickListener { onProgrammeClick?.invoke(p) }
        }
    }
}
