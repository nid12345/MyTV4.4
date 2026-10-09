package top.yogiczy.mytv.legacy.ui.panel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.yogiczy.mytv.legacy.data.Channel
import top.yogiczy.mytv.legacy.core.FocusEngine
import top.yogiczy.mytv.legacy.core.UiLog
import top.yogiczy.mytv.legacy.ui.ChannelCardView
import top.yogiczy.mytv.legacy.ui.ChannelLogoStore

/**
 * 经典三段式的**中栏**：一个 RecyclerView。
 *
 * ---------------------------------------------------------------------------
 * 为什么必须是 RecyclerView：M6 之前中栏是 `LinearLayout` 全量建 View，
 * 1000 个频道就是 1000 个 `ChannelCardView`，首帧要几秒、滚动必卡。
 * 原版是 Compose 的 `LazyColumn`（天然只建可见项），View 侧的等价物就是 RecyclerView。
 *
 * 中栏不止装频道：换源面板 / 我的收藏 / 系统应用 也占同一列（原版
 * `ClassicChannelScreen` 就是按选中项整块替换中栏内容）。所以这里支持两种行：
 *   - [Row.ChannelRow] → [ChannelCardView]
 *   - [Row.TextRow]    → [PanelRowView]
 * ---------------------------------------------------------------------------
 */
class ChannelColumnView(context: Context) : RecyclerView(context) {

    /** 中栏的一行。 */
    sealed class Row {
        /**
         * @param programme 当前正在播出的节目名（对齐 1.7 `ChannelCard` 的第二行）
         * @param progress  该节目进度 0f..1f；< 0 = 不显示行底进度条
         */
        data class ChannelRow(
            val channel: Channel,
            val badge: String,
            val current: Boolean = false,
            val programme: String? = null,
            val progress: Float = -1f,
        ) : Row()

        data class TextRow(
            val title: String,
            val subtitle: String? = null,
            val icon: Drawable? = null,
            val onClick: () -> Unit,
            val onLongClick: (() -> Unit)? = null,
        ) : Row()
    }

    /** 频道行高（px，由调用方按 Density 换算好再传）。 */
    var channelRowHeight: Int = 0
    /** 台标边长（px）。 */
    var logoSize: Int = 0

    var onChannelClick: ((Channel, Int) -> Unit)? = null

    /**
     * 频道项**获得焦点**时的回调。
     *
     * ⚠️ 与 [onChannelClick] 语义不同（问题 2）：焦点移动**只预览**，不切换播放；
     * 只有按确认键（[onChannelClick]）才真正跳台。这与原版 1.7 一致 ——
     * `ClassicChannelItem` 里 `onFocusChanged → onChannelFocused` 只改 `focusedChannel`，
     * 换台走的是 `onSelect = onChannelSelected`。
     */
    var onChannelFocus: ((Channel, Int) -> Unit)? = null
    var onChannelLongClick: ((Channel) -> Unit)? = null

    /**
     * **中栏任意一行**获得焦点（频道行或文本行）—— 宿主用它把一级菜单的白框降透明度
     * （问题 1：光标进二级 → 一级选中白框半透明）。
     */
    var onRowFocused: (() -> Unit)? = null

    /** 当前已绑定（可见）的频道卡片，按频道名索引 —— 供远程台标回填。 */
    private val boundCards = HashMap<String, MutableList<ChannelCardView>>()

    private val rows = ArrayList<Row>()

    init {
        layoutManager = LinearLayoutManager(context, VERTICAL, false)
        setHasFixedSize(true)
        // 老设备上默认缓存偏小，快速滚动会频繁重新 bind；放大一点换流畅度
        setItemViewCacheSize(20)
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        // 焦点在行上，容器本身不抢
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        // M10：稳定 ID 是 **Adapter** 的方法（`RecyclerView` 只有 setHasFixedSize）。
        // 配合 getItemId 的内容键，让列表整体替换后能按内容重绑焦点。
        adapter = RowsAdapter().apply { setHasStableIds(true) }
    }

    /**
     * 换掉整列内容。列表被整体替换后由调用方负责把焦点恢复到目标项（按内容键）。
     *
     * ⚠️ **必须防"layout 中重入"**（真机 oppo R11s Plus / API 23 崩溃）：
     * `notifyDataSetChanged()` 在 RecyclerView 正在 layout/滚动时调用会抛
     * `IllegalStateException: Cannot call this method while RecyclerView is computing a layout
     * or scrolling`。这条链真实存在：分组栏 `NavColumnView.render()` 的 `removeAllViews()`
     * 会把焦点**同步**移交给新建的行 → 行的焦点回调 → `MainActivity.onNavSelected` →
     * `renderSourcePanel()` / `renderChannels()` → 这里 —— 此刻中栏可能正在 layout。
     * 处理：正在 layout/滚动就 post 到下一帧再换（内容最终一致，不会丢）。
     */
    fun submit(list: List<Row>) {
        if (isComputingLayout || isAnimating) {
            UiLog.i("界面", "中栏内容延后一帧替换（RecyclerView 正在 layout/滚动）")
            post { submit(list) }
            return
        }
        boundCards.clear()
        rows.clear()
        rows.addAll(list)
        adapter?.notifyDataSetChanged()
        scrollToPosition(0)
    }

    /** 远程台标到了：只更新**当前可见**的卡片（滚出去的会在 bind 时自己带上）。 */
    fun updateLogo(name: String, bitmap: Bitmap?) {
        if (bitmap == null) return
        boundCards[name]?.forEach { it.setLogo(bitmap) }
    }

    /**
     * 节目单到位后**就地**补上每个频道"当前节目名 + 进度"（键 = 频道名）。
     *
     * ⚠️ 不能靠 `submit()` 重建：那会丢焦点与滚动位置（M5 台标回填踩过同一个坑）。
     * 这里同时改行数据（新滚进来的项 bind 时就是对的）与已绑定卡片的显示。
     */
    fun updateProgrammes(map: Map<String, Pair<String?, Float>>) {
        if (map.isEmpty()) return
        for (k in rows.indices) {
            val r = rows[k]
            if (r is Row.ChannelRow) {
                val v = map[r.channel.name] ?: continue
                rows[k] = r.copy(programme = v.first, progress = v.second)
            }
        }
        for (i in 0 until childCount) {
            val vh = getChildViewHolder(getChildAt(i))
            if (vh is RowsAdapter.ChannelVH) vh.refreshProgramme()
        }
    }

    /** 把某一项滚进视野（换台 / 焦点恢复时用）。 */
    fun revealPosition(position: Int) {
        if (position < 0 || position >= rows.size) return
        val lm = layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (position < first || position > last) scrollToPosition(position)
    }

    private inner class RowsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.ChannelRow) 1 else 2

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 1) {
                val card = ChannelCardView(parent.context, channelRowHeight, logoSize)
                card.layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, channelRowHeight
                )
                ChannelVH(card)
            } else {
                TextVH(PanelRowView(parent.context))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.ChannelRow -> (holder as ChannelVH).bind(row, position)
                is Row.TextRow -> (holder as TextVH).bind(row)
            }
        }

        // M10：稳定 ID —— 用**内容键**（频道名 / 文本标题）而不是下标，
        // 列表整体替换（切源、测速重排）后才能按内容重绑焦点，而不是按位置乱跳。
        override fun getItemId(position: Int): Long = when (val r = rows[position]) {
            is Row.ChannelRow -> r.channel.name.hashCode().toLong()
            is Row.TextRow -> (r.title.hashCode().toLong() * 31 + (r.subtitle?.hashCode() ?: 0))
        }

        override fun getItemCount(): Int = rows.size

        inner class ChannelVH(private val card: ChannelCardView) :
            RecyclerView.ViewHolder(card) {

            private var boundName: String? = null

            fun bind(row: Row.ChannelRow, position: Int) {
                boundName?.let { boundCards[it]?.remove(card) }
                // 问题 1（本轮补）：登记**内容键**（频道名）。
                // 少了这一行，`FocusEngine.restoreFocus(channelColumn, "凤凰香港")` 会在
                // `keyOfView` 里匹配不到任何子项 → 静默走 `focusFirstIn()` 落到第 1 项，
                // 但仍返回 true —— 表现为"日志说回到上次频道、画面却停在第 1 项"。
                FocusEngine.bind(card, row.channel.name)
                card.bind(
                    row.channel,
                    row.badge,
                    row.current,
                    // 问题 5：**优先用直播源自带的 tvg-logo**（1.7 的解析器一定会给每个频道
                    // 一个 logo：`tvg-logo` 缺失时回落成台标仓库地址）。本工程此前只看频道名，
                    // 于是"换到带 tvg-logo 的订阅源"反而看不到台标。
                    ChannelLogoStore.bitmapFor(context, row.channel.name, row.channel.logo),
                    row.programme,
                    row.progress,
                )
                boundName = row.channel.name
                boundCards.getOrPut(row.channel.name) { ArrayList(1) }.add(card)

                card.setOnClickListener { onChannelClick?.invoke(row.channel, position) }
                card.onFocusChanged = { focused ->
                    if (focused) {
                        revealPosition(position)
                        onRowFocused?.invoke()
                        onChannelFocus?.invoke(row.channel, position)
                    }
                }
                card.setOnLongClickListener { onChannelLongClick?.invoke(row.channel); true }
            }

            /** 节目单到位后补刷这一张卡（行数据已被 [updateProgrammes] 更新）。 */
            fun refreshProgramme() {
                val name = boundName ?: return
                val r = rows.firstOrNull { it is Row.ChannelRow && it.channel.name == name } as? Row.ChannelRow
                    ?: return
                card.setProgramme(r.programme, r.progress, r.badge)
            }
        }

        private inner class TextVH(private val rowView: PanelRowView) :
            RecyclerView.ViewHolder(rowView) {
            fun bind(item: Row.TextRow) {
                rowView.bind(item.title, item.subtitle, item.icon, item.onClick, item.onLongClick)
                // 问题 1：订阅列表 / 应用列表也是"二级"，焦点进来同样要把一级白框降透明度
                rowView.onFocusChanged = { focused -> if (focused) onRowFocused?.invoke() }
                rowView.visibility = View.VISIBLE
            }
        }
    }
}
