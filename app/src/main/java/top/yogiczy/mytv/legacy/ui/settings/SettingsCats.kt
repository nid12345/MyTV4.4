package top.yogiczy.mytv.legacy.ui.settings

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import top.yogiczy.mytv.legacy.core.UiLog
import top.yogiczy.mytv.legacy.data.Prefs
import top.yogiczy.mytv.legacy.player.DisplayMode

/**
 * 13 个分类的右栏内容。
 *
 * 文案逐字取自原版 `SettingsCategoryXxx.kt`（MyTV 1.7）。
 * 每个函数返回该分类的设置项列表；值由 [refresh] 每秒刷新。
 */
object SettingsCats {

    /** 7 档显示模式的中文名，顺序与 `DisplayMode.ALL` 一致。 */
    private val MODE_CN = listOf("原始", "16:9", "4:3", "填充", "裁剪", "2.35:1", "3:4")

    /** 构造一行；[build] 内部自行决定是开关、循环值还是动作。 */
    private fun row(ctx: Context, headline: String, supporting: String? = null): SettingsItemView =
        SettingsItemView(ctx).apply { setText(headline, supporting) }

    // ---------- 1 关于 ----------
    fun about(ctx: Context): List<SettingsItemView> {
        val ver = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrDefault("unknown")
        return listOf(
            row(ctx, "应用名称", null).apply { setValue("MyTV-Legacy") },
            row(ctx, "应用版本", null).apply { setValue("$ver (API 19)") },
            row(ctx, "使用说明", "基于 MyTV 1.7 移植到 Android 4.4 的版本").apply {
                onClick = { }
            },
        )
    }

    /**
     * 2 应用（v1.8：原「更新」分类整体并入这里 —— 用户要求「更新入口放到应用里」）。
     *
     * 「更新通道 / 下载线路」两项已删除：本工程只有一条发布通道，线路改由
     * [top.yogiczy.mytv.legacy.data.UpdateRepository] **自动探测**（raw → 镜像，谁通走谁），
     * 不再让用户手动选（用户原话："更新通道怎么有三个？一个就好"）。
     *
     * 「开机自启」已删除：本工程没有实现开机广播接收，留着就是纯摆设。
     */
    fun app(ctx: Context, onReset: () -> Unit, onCheckUpdate: () -> Unit): List<SettingsItemView> {
        val check = row(ctx, "检查更新", "从 GitHub Releases 获取最新版本并下载安装")
        check.onClick = onCheckUpdate
        val remind = row(ctx, "升级提醒", "启动后自动检查一次更新")
        remind.setSwitch(Prefs.updateRemindEnable(ctx)) { Prefs.setUpdateRemindEnable(ctx, it) }
        val reset = row(ctx, "恢复初始化", "短按立即清空全部设置")
        reset.onClick = onReset
        return listOf(check, remind, reset)
    }

    // ---------- 3 直播源 ----------
    /**
     * 问题 8（用户要求删除的 5 个开关入口）：
     * 潮汕节目回放 / 线路测速排序 / 记住上次频道 / 自动连播 / 直播源快速切换。
     *
     * 说明：
     *  · 「潮汕节目回放」→ 已改为**内置订阅源**（问题 7），不再需要开关；
     *  · 「记住上次频道」→ 已改为**始终生效**（问题 6），不再需要开关；
     *  · 其余三个（线路测速排序 / 自动连播 / 直播源快速切换）本轮只**删入口**，
     *    行为仍按原默认值走（Prefs 里的键保留，将来要恢复入口随时可用）。
     */
    fun iptv(
        ctx: Context,
        onCustomSource: () -> Unit,
        onGroupManage: () -> Unit,
        onGroupManageAll: () -> Unit,
    ): List<SettingsItemView> {
        val l = ArrayList<SettingsItemView>()
        val noSelect = row(ctx, "数字选台", "通过数字选择频道")
        noSelect.setSwitch(Prefs.iptvChannelNoSelectEnable(ctx)) {
            Prefs.setIptvChannelNoSelectEnable(ctx, it)
        }
        val flip = row(ctx, "换台反转")
        flip.setSwitch(Prefs.iptvChannelChangeFlip(ctx)) {
            Prefs.setIptvChannelChangeFlip(ctx, it)
        }
        val cache = row(ctx, "直播源缓存时间")
        cache.setValue("${Prefs.iptvSourceCacheTime(ctx) / 1000} 秒")
        cache.onClick = {
            val cur = Prefs.iptvSourceCacheTime(ctx).toInt()
            val next = when { cur < 60000 -> cur + 30000; else -> 0 }
            Prefs.setIptvSourceCacheTime(ctx, next.toLong())
            cache.setValue("${next / 1000} 秒")
        }
        val custom = row(ctx, "自定义直播源", Prefs.currentSourceUrl(ctx))
        custom.onClick = onCustomSource
        // 外链图标：点了会跳到换源面板（原版 trailingIcon 的语义）
        custom.setTailIcon(top.yogiczy.mytv.legacy.R.drawable.ic_swap)
        val group = row(ctx, "频道分组管理", "管理频道分组可见、隐藏状态")
        group.onClick = onGroupManage
        group.setTailIcon(top.yogiczy.mytv.legacy.R.drawable.ic_swap)
        val allGroup = row(ctx, "全部分组管理", "统一管理所有订阅源的分组显示、隐藏（切换源、重启后保持）")
        allGroup.onClick = onGroupManageAll
        // v1.8 删除「混合模式」：它给每个频道追加 `hybrid-webview://` 地址（原版靠内置 WebView
        // 播放器播放），而本工程**没有**实现 hybrid-webview 播放链路（ChannelUtil 里只移植未接线）。
        // 留着它 = 把不可播的地址塞进线路列表，属于"看着有、实际没用"的摆设。
        l += listOf(noSelect, flip, cache, custom, group, allGroup)
        return l
    }

    // ---------- 4 节目单 ----------
    fun epg(
        ctx: Context,
        onCustomEpg: () -> Unit,
        onEpgSourceChanged: (top.yogiczy.mytv.legacy.data.EpgSource) -> Unit,
    ): List<SettingsItemView> {
        val enable = row(ctx, "节目单启用", "首次加载时可能会较为缓慢")
        enable.setSwitch(Prefs.epgEnable(ctx)) { Prefs.setEpgEnable(ctx, it) }
        val threshold = row(
            ctx, "节目单刷新时间阈值",
            "${Prefs.epgRefreshTimeThreshold(ctx)}:00 之前优先用已有缓存、不主动刷新"
        )
        threshold.setValue("${Prefs.epgRefreshTimeThreshold(ctx)} 小时")
        threshold.onClick = {
            val next = (Prefs.epgRefreshTimeThreshold(ctx) % 12) + 1
            Prefs.setEpgRefreshTimeThreshold(ctx, next)
            threshold.setValue("$next 小时")
        }

        // -------------------------------------------------------------------
        // v1.9（用户需求 1）：「内置节目源没法在设置里切换，点了不能切换到」。
        //
        // 原来这里**只有一行**「自定义节目单」，值显示的是当前节目单地址，
        // 点击只是弹出一个管理抽屉 —— **内置的 3 个节目单没有"切换"这个动作可点**，
        // 抽屉里能切但用户预期的是"在设置里点一下就换"，于是体感就是"点了没反应"。
        //
        // 现在拆成两行，职责分明：
        //   · 「节目单来源」= **点一下换下一个**（内置 3 个 + 自定义全部循环），
        //     与「时间显示」「超时自动关闭」同一套交互，切完立刻重拉节目单；
        //   · 「节目单管理」= 打开抽屉（新增 / 删除 / 指定切换）。
        // -------------------------------------------------------------------
        val allSources = ArrayList(top.yogiczy.mytv.legacy.data.Prefs.EPG_SOURCE_LIST)
        allSources += top.yogiczy.mytv.legacy.data.Prefs.epgSourceList(ctx)
        val distinct = allSources.distinctBy { it.url }
        val cur = Prefs.epgSourceCurrent(ctx)

        val source = row(
            ctx, "节目单来源",
            "点击切换到下一个（内置 ${top.yogiczy.mytv.legacy.data.Prefs.EPG_SOURCE_LIST.size} 个 + 自定义，共 ${distinct.size} 个）"
        )
        source.setValue(cur.name)
        source.onClick = pick@{
            val list = (top.yogiczy.mytv.legacy.data.Prefs.EPG_SOURCE_LIST +
                    top.yogiczy.mytv.legacy.data.Prefs.epgSourceList(ctx)).distinctBy { it.url }
            if (list.isEmpty()) return@pick
            val i = list.indexOfFirst { it.url == Prefs.epgSourceCurrent(ctx).url }
            val next = list[((if (i < 0) -1 else i) + 1) % list.size]
            Prefs.setEpgSourceCurrent(ctx, next)
            source.setValue(next.name)
            top.yogiczy.mytv.legacy.core.UiLog.i("节目单", "设置页切换节目单：${next.name}")
            onEpgSourceChanged(next)
        }

        // 问题 7：原来这一行**没有任何 onClick**（点了没反应，与「自定义直播源」同一类问题）。
        val custom = row(ctx, "节目单管理", "新增 / 删除节目单 · 当前：${cur.url}")
        custom.onClick = onCustomEpg
        custom.setTailIcon(top.yogiczy.mytv.legacy.R.drawable.ic_swap)
        return listOf(enable, threshold, source, custom)
    }

    // ---------- 5 预约 ----------
    // 问题 8（用户要求）：整个「预约」分类已从设置页删除，这里的 reserveHeader 一并移除。

    // ---------- 6 界面 ----------
    fun ui(ctx: Context): List<SettingsItemView> {
        val l = ArrayList<SettingsItemView>()
        val prog = row(ctx, "节目进度", "在频道项底部显示当前节目进度条")
        prog.setSwitch(Prefs.uiShowEpgProgrammeProgress(ctx)) {
            Prefs.setUiShowEpgProgrammeProgress(ctx, it)
        }
        val perm = row(ctx, "常驻底部节目进度", "在播放器底部显示当前节目进度条")
        perm.setSwitch(Prefs.uiShowEpgProgrammePermanentProgress(ctx)) {
            Prefs.setUiShowEpgProgrammePermanentProgress(ctx, it)
        }
        // v1.8：「台标显示」已**接通**（见 ChannelLogoStore.bitmapFor：关闭时所有位置的
        // 台标都不再加载 —— 频道列表、右上台标、换台信息块统一生效）。
        val logo = row(ctx, "台标显示", "频道列表 / 右上台标 / 换台信息块")
        logo.setSwitch(Prefs.uiShowChannelLogo(ctx)) {
            Prefs.setUiShowChannelLogo(ctx, it)
            UiLog.i("设置", "台标显示 → $it")
        }
        // v1.8 删除「经典选台界面」：本工程只有经典三段式一种选台界面，没有第二种可切，
        // 留着就是点了没反应的摆设（用户要求"不要只是摆设按钮"）。

        // v1.8：「时间显示」确认**接通**（DatetimeView / MainActivity.refreshTopRight 按
        // HIDDEN / ALWAYS / EVERY_HOUR / HALF_HOUR 决定右上时间显隐），切换后立即重绘。
        val time = row(ctx, "时间显示", "控制右上角时间的显示时机")
        time.setValue(Prefs.uiTimeShowMode(ctx).label)
        time.onClick = {
            val modes = top.yogiczy.mytv.legacy.data.UiTimeShowMode.values()
            val next = modes[(modes.indexOf(Prefs.uiTimeShowMode(ctx)) + 1) % modes.size]
            Prefs.setUiTimeShowMode(ctx, next)
            time.setValue(next.label)
        }
        // v1.11（用户需求 1）：左侧三段式选台面板**不再**受这一项影响 ——
        // 它现在只在「选中频道 / 按返回键 / 点面板外空白」时收起（详见 MainActivity 里
        // panelAutoClose 处的注释）。这一项继续作用于快捷菜单、投屏、节目单来源、
        // 多线路选框、播放控制这五个弹层，所以文案随之收窄。
        val close = row(ctx, "超时自动关闭界面", "影响快捷菜单、投屏、节目单来源、多线路、播放控制等弹层（选台面板不受影响）")
        close.setValue("${Prefs.uiScreenAutoCloseDelay(ctx) / 1000} 秒")
        close.onClick = {
            val cur = Prefs.uiScreenAutoCloseDelay(ctx)
            val next = when { cur < 10000L -> cur + 5000L; else -> 5000L }
            Prefs.setUiScreenAutoCloseDelay(ctx, next)
            close.setValue("${next / 1000} 秒")
        }
        val density = row(ctx, "界面整体缩放比例")
        // 0f 是原版语义里的「未设置 → 按屏宽自动换算」，显示成 "0.0" 会让人以为坏了。
        density.setValue(if (Prefs.uiDensityScaleRatio(ctx) == 0f) "自动" else String.format("%.1f", Prefs.uiDensityScaleRatio(ctx)))
        density.onClick = {
            val cur = Prefs.uiDensityScaleRatio(ctx)
            val next = if (cur >= 0.3f) 0f else cur + 0.1f
            Prefs.setUiDensityScaleRatio(ctx, next)
            density.setValue(if (next == 0f) "自动" else String.format("%.1f", next))
        }
        val font = row(ctx, "界面字体缩放比例")
        font.setValue(String.format("%.1f", Prefs.uiFontScaleRatio(ctx)))
        font.onClick = {
            val cur = Prefs.uiFontScaleRatio(ctx)
            val next = if (cur >= 1.5f) 0.8f else cur + 0.1f
            Prefs.setUiFontScaleRatio(ctx, next)
            font.setValue(String.format("%.1f", next))
        }
        // v1.8 删除「焦点优化」：本工程没有原版那套 Compose focusProperties 机制，无对应实现。
        l += listOf(prog, perm, logo, time, close, density, font)
        return l
    }

    // ---------- 7 收藏 ----------
    /**
     * v1.8：「收藏启用」已**接通**（关闭后导航列不再出现「我的收藏」，长按也不再能收藏）。
     * 「收藏换台边界跳出」已删除 —— 见下方说明。
     */
    fun favorite(ctx: Context, count: Int, onClear: () -> Unit): List<SettingsItemView> {
        val enable = row(ctx, "收藏启用", "关闭后不再显示「我的收藏」分组，也不能新增收藏")
        enable.setSwitch(Prefs.iptvChannelFavoriteEnable(ctx)) {
            Prefs.setIptvChannelFavoriteEnable(ctx, it)
            UiLog.i("设置", "收藏启用 → $it")
        }
        val cur = row(ctx, "当前已收藏", "跨订阅源保留，切换直播源后依然可以查看与播放")
            .apply { setValue("$count 个") }
        val clear = row(ctx, "清空全部收藏", "短按立即清空全部收藏")
        clear.onClick = onClear
        // v1.8 删除「收藏换台边界跳出」：本工程的收藏是**导航列里的伪分组**，
        // 上下键换台走的是「全部频道」这条链（changeChannelBy），不存在"在收藏里换到边界"
        // 这个场景 —— 没有可接的实现，留着就是摆设。
        return listOf(enable, cur, clear)
    }

    // ---------- 8 更新 ----------
    // v1.8（用户要求）：整个「更新」分类已删除，三项并入「应用」分类（见 [app]）。

    // ---------- 9 播放器 ----------
    fun videoPlayer(ctx: Context, onEditUa: (() -> Unit)? = null): List<SettingsItemView> {
        val l = ArrayList<SettingsItemView>()
        // ❶ v1.8 删除的三项（都是原版能力在本工程**不存在**对应实现，留着就是摆设）：
        //   · 「渲染方式」—— 本工程只有 SurfaceView（API 19 的 TextureView 更卡，没有做）；
        //   · 「强制音频软解」—— ExoPlayer 2.18 没有独立的"音频软解"开关；
        //   · 「跳过多帧渲染」—— 那是原版定制 media3 的反射开关，2.18 上没有。
        val stopPrev = row(ctx, "停止上一媒体项", "打开新频道前先停掉上一个（换台更干净，起播略慢）")
        stopPrev.setSwitch(Prefs.videoPlayerStopPreviousMediaItem(ctx)) {
            Prefs.setVideoPlayerStopPreviousMediaItem(ctx, it)
            UiLog.i("设置", "停止上一媒体项 → $it")
        }
        val mode = row(ctx, "全局显示模式")
        val modeLabel: (top.yogiczy.mytv.legacy.data.VideoPlayerDisplayMode) -> String = { m ->
            // 显示模式的中文名：DisplayMode 里没有现成表，按枚举序给出（与原版 7 档一致）
            DisplayMode.ALL.indexOf(m).let { idx ->
                if (idx >= 0 && idx < MODE_CN.size) MODE_CN[idx] else m.name
            }
        }
        mode.setValue(modeLabel(Prefs.videoPlayerDisplayMode(ctx)))
        mode.onClick = {
            val all = DisplayMode.ALL
            val cur = Prefs.videoPlayerDisplayMode(ctx)
            val next = all[(all.indexOf(cur) + 1) % all.size]
            Prefs.setVideoPlayerDisplayMode(ctx, next)
            mode.setValue(modeLabel(next))
        }
        val timeout = row(ctx, "播放器加载超时", "影响超时换源、断线重连")
        timeout.setValue("${Prefs.videoPlayerLoadTimeout(ctx)} ms")
        timeout.onClick = {
            val cur = Prefs.videoPlayerLoadTimeout(ctx)
            val next = when { cur < 30000L -> cur + 5000L; else -> 5000L }
            Prefs.setVideoPlayerLoadTimeout(ctx, next)
            timeout.setValue("$next ms")
        }
        // v1.8 接通：原来这一行只能看不能改（摆设），现在点开可输入。
        val ua = row(ctx, "播放器自定义UA", Prefs.videoPlayerUserAgent(ctx))
        ua.onClick = { onEditUa?.invoke() }
        ua.setTailIcon(top.yogiczy.mytv.legacy.R.drawable.ic_swap)
        l += listOf(stopPrev, mode, timeout, ua)
        return l
    }

    // ---------- 10 网络 ----------
    fun http(ctx: Context): List<SettingsItemView> {
        val cnt = row(ctx, "HTTP请求重试次数", "影响直播源、节目单数据获取")
        cnt.setValue("${Prefs.httpRetryCount(ctx)} 次")
        cnt.onClick = {
            val next = (Prefs.httpRetryCount(ctx) % 5) + 1
            Prefs.setHttpRetryCount(ctx, next)
            cnt.setValue("$next 次")
        }
        val iv = row(ctx, "HTTP请求重试间隔时间", "影响直播源、节目单数据获取")
        iv.setValue("${Prefs.httpRetryInterval(ctx)} ms")
        iv.onClick = {
            val cur = Prefs.httpRetryInterval(ctx)
            val next = when { cur < 5000 -> cur + 1000; else -> 500 }
            Prefs.setHttpRetryInterval(ctx, next)
            iv.setValue("$next ms")
        }
        return listOf(cnt, iv)
    }

    // ---------- 11 调试 ----------
    /**
     * v1.8：三项全部**接通** —— 由 [top.yogiczy.mytv.legacy.ui.DebugOverlayView] 在画面
     * 左上角叠加显示（FPS / 播放器信息 / 布局网格），开关即时生效。
     */
    fun debug(ctx: Context): List<SettingsItemView> {
        val fps = row(ctx, "显示FPS", "在屏幕左上角显示fps")
        fps.setSwitch(Prefs.debugShowFps(ctx)) { Prefs.setDebugShowFps(ctx, it) }
        val info = row(ctx, "显示播放器信息", "显示解码器、分辨率、网速等")
        info.setSwitch(Prefs.debugShowPlayerInfo(ctx)) { Prefs.setDebugShowPlayerInfo(ctx, it) }
        val grid = row(ctx, "显示布局网格", "在画面上叠加对齐网格")
        grid.setSwitch(Prefs.debugShowLayoutGrid(ctx)) { Prefs.setDebugShowLayoutGrid(ctx, it) }
        return listOf(fps, info, grid)
    }

    // ---------- 12 日志 ----------
    /** 日志条目（约束 2：UiLog 在此展示）。 */
    fun log(ctx: Context): List<SettingsItemView> {
        val entries = UiLog.history
        if (entries.isEmpty()) {
            return listOf(row(ctx, "（暂无日志）", null))
        }
        return entries.takeLast(40).reversed().map { e ->
            row(ctx, UiLog.format(e)).apply {
                // 原版 headlineContent = "${level[0]} ${tag}"，message 走副标题
                setText("${e.level.toString()[0]} ${e.tag}", e.message)
            }
        }
    }

    // ---------- 13 推送 ----------
    // v1.8（用户要求）：「推送」分类已删除 —— 原版那一项只有"安装包管理"，
    // 本工程是侧载分发，本来就没有这东西，留着是个空入口。
}
