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

    // ---------- 2 应用 ----------
    fun app(ctx: Context, onReset: () -> Unit): List<SettingsItemView> {
        val boot = row(ctx, "开机自启", "请确保当前设备支持该功能")
        boot.setSwitch(Prefs.appBootLaunchEnable(ctx)) {
            Prefs.setAppBootLaunchEnable(ctx, it)
        }
        val upd = row(ctx, "应用更新", null).apply { setValue("M12 已推迟到 v1.1") }
        val reset = row(ctx, "恢复初始化", "短按立即清空全部设置")
        reset.onClick = onReset
        return listOf(boot, upd, reset)
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
        val hybrid = row(ctx, "混合模式")
        hybrid.setValue(Prefs.iptvHybridMode(ctx).name)
        hybrid.onClick = {
            val modes = top.yogiczy.mytv.legacy.data.IptvHybridMode.values()
            val next = modes[(modes.indexOf(Prefs.iptvHybridMode(ctx)) + 1) % modes.size]
            Prefs.setIptvHybridMode(ctx, next)
            hybrid.setValue(next.name)
        }
        l += listOf(noSelect, flip, cache, custom, group, allGroup, hybrid)
        return l
    }

    // ---------- 4 节目单 ----------
    fun epg(ctx: Context, onCustomEpg: () -> Unit): List<SettingsItemView> {
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
        // 问题 7：原来这一行**没有任何 onClick**（点了没反应，与「自定义直播源」同一类问题）。
        val custom = row(ctx, "自定义节目单", Prefs.epgSourceCurrent(ctx).url)
        custom.onClick = onCustomEpg
        custom.setTailIcon(top.yogiczy.mytv.legacy.R.drawable.ic_swap)
        return listOf(enable, threshold, custom)
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
        val logo = row(ctx, "台标显示")
        logo.setSwitch(Prefs.uiShowChannelLogo(ctx)) { Prefs.setUiShowChannelLogo(ctx, it) }
        val classic = row(ctx, "经典选台界面", "将选台界面替换为经典三段式结构")
        classic.setSwitch(Prefs.uiUseClassicPanelScreen(ctx)) {
            Prefs.setUiUseClassicPanelScreen(ctx, it)
            UiLog.i("设置", "经典选台界面 → $it")
        }
        val time = row(ctx, "时间显示")
        time.setValue(Prefs.uiTimeShowMode(ctx).name)
        time.onClick = {
            val modes = top.yogiczy.mytv.legacy.data.UiTimeShowMode.values()
            val next = modes[(modes.indexOf(Prefs.uiTimeShowMode(ctx)) + 1) % modes.size]
            Prefs.setUiTimeShowMode(ctx, next)
            time.setValue(next.name)
        }
        val close = row(ctx, "超时自动关闭界面", "影响选台界面，快捷操作等界面")
        close.setValue("${Prefs.uiScreenAutoCloseDelay(ctx) / 1000} 秒")
        close.onClick = {
            val cur = Prefs.uiScreenAutoCloseDelay(ctx)
            val next = when { cur < 10000L -> cur + 5000L; else -> 5000L }
            Prefs.setUiScreenAutoCloseDelay(ctx, next)
            close.setValue("${next / 1000} 秒")
        }
        val density = row(ctx, "界面整体缩放比例")
        density.setValue(Prefs.uiDensityScaleRatio(ctx).toString())
        density.onClick = {
            val cur = Prefs.uiDensityScaleRatio(ctx)
            val next = if (cur >= 0.3f) 0f else cur + 0.1f
            Prefs.setUiDensityScaleRatio(ctx, next)
            density.setValue(next.toString())
        }
        val font = row(ctx, "界面字体缩放比例")
        font.setValue(Prefs.uiFontScaleRatio(ctx).toString())
        font.onClick = {
            val cur = Prefs.uiFontScaleRatio(ctx)
            val next = if (cur >= 1.5f) 0.8f else cur + 0.1f
            Prefs.setUiFontScaleRatio(ctx, next)
            font.setValue(String.format("%.1f", next))
        }
        val focus = row(ctx, "焦点优化", "关闭后可解决触摸设备在部分场景下闪退")
        focus.setSwitch(Prefs.uiFocusOptimize(ctx)) { Prefs.setUiFocusOptimize(ctx, it) }
        l += listOf(prog, perm, logo, classic, time, close, density, font, focus)
        return l
    }

    // ---------- 7 收藏 ----------
    fun favorite(ctx: Context, count: Int, onClear: () -> Unit): List<SettingsItemView> {
        val enable = row(ctx, "收藏启用")
        enable.setSwitch(Prefs.iptvChannelFavoriteEnable(ctx)) {
            Prefs.setIptvChannelFavoriteEnable(ctx, it)
        }
        val cur = row(ctx, "当前已收藏", "跨订阅源保留，切换直播源后依然可以查看与播放")
            .apply { setValue("$count 个") }
        val clear = row(ctx, "清空全部收藏", "短按立即清空全部收藏")
        clear.onClick = onClear
        val boundary = row(ctx, "收藏换台边界跳出")
        boundary.setSwitch(Prefs.iptvChannelFavoriteChangeBoundaryJumpOut(ctx)) {
            Prefs.setIptvChannelFavoriteChangeBoundaryJumpOut(ctx, it)
        }
        return listOf(enable, cur, clear, boundary)
    }

    // ---------- 8 更新 ----------
    fun update(ctx: Context, onCheck: () -> Unit): List<SettingsItemView> {
        val check = row(ctx, "检查更新", "从 GitHub Releases 获取最新版本并安装")
        check.onClick = onCheck
        val remind = row(ctx, "升级提醒")
        remind.setSwitch(Prefs.updateRemindEnable(ctx)) { Prefs.setUpdateRemindEnable(ctx, it) }
        val force = row(ctx, "更新强提醒")
        force.setSwitch(Prefs.updateForceRemindEnable(ctx)) {
            Prefs.setUpdateForceRemindEnable(ctx, it)
        }
        val channel = row(ctx, "更新通道")
        channel.setValue(Prefs.updateChannel(ctx))
        channel.onClick = {
            val opts = listOf("stable", "beta", "alpha")
            val next = opts[(opts.indexOf(Prefs.updateChannel(ctx)) + 1) % opts.size]
            Prefs.setUpdateChannel(ctx, next)
            channel.setValue(next)
        }
        val line = row(ctx, "下载线路")
        line.setValue(Prefs.updateDownloadLine(ctx))
        line.onClick = {
            val opts = listOf("auto", "ghproxy", "jsdelivr")
            val next = opts[(opts.indexOf(Prefs.updateDownloadLine(ctx)) + 1) % opts.size]
            Prefs.setUpdateDownloadLine(ctx, next)
            line.setValue(next)
        }
        return listOf(check, remind, force, channel, line)
    }

    // ---------- 9 播放器 ----------
    fun videoPlayer(ctx: Context): List<SettingsItemView> {
        val l = ArrayList<SettingsItemView>()
        val render = row(ctx, "渲染方式")
        render.setValue(if (Prefs.videoPlayerRenderMode(ctx) == 0) "SurfaceView" else "TextureView")
        render.onClick = {
            val next = (Prefs.videoPlayerRenderMode(ctx) + 1) % 2
            Prefs.setVideoPlayerRenderMode(ctx, next)
            render.setValue(if (next == 0) "SurfaceView" else "TextureView")
        }
        val audioSw = row(ctx, "强制音频软解")
        audioSw.setSwitch(Prefs.videoPlayerForceAudioSoftwareDecode(ctx)) {
            Prefs.setVideoPlayerForceAudioSoftwareDecode(ctx, it)
        }
        val stopPrev = row(ctx, "停止上一媒体项")
        stopPrev.setSwitch(Prefs.videoPlayerStopPreviousMediaItem(ctx)) {
            Prefs.setVideoPlayerStopPreviousMediaItem(ctx, it)
        }
        val skipFrame = row(ctx, "跳过多帧渲染")
        skipFrame.setSwitch(Prefs.videoPlayerSkipMultiFrame(ctx)) {
            Prefs.setVideoPlayerSkipMultiFrame(ctx, it)
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
        val ua = row(ctx, "播放器自定义UA", Prefs.videoPlayerUserAgent(ctx))
        l += listOf(render, audioSw, stopPrev, skipFrame, mode, timeout, ua)
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
    fun debug(ctx: Context): List<SettingsItemView> {
        val fps = row(ctx, "显示FPS", "在屏幕左上角显示fps和柱状图")
        fps.setSwitch(Prefs.debugShowFps(ctx)) { Prefs.setDebugShowFps(ctx, it) }
        val info = row(ctx, "显示播放器信息", "显示播放器详细信息（编码、解码器、采样率等）")
        info.setSwitch(Prefs.debugShowPlayerInfo(ctx)) { Prefs.setDebugShowPlayerInfo(ctx, it) }
        val grid = row(ctx, "显示布局网格")
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
    fun push(ctx: Context, onPackages: () -> Unit): List<SettingsItemView> {
        val pkg = row(ctx, "安装包管理", "安装、删除推送收到的安装包；卸载已装应用")
        pkg.onClick = onPackages
        return listOf(pkg)
    }
}
