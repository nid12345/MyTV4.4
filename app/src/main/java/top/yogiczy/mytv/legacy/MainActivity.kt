package top.yogiczy.mytv.legacy

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import top.yogiczy.mytv.legacy.ui.AppDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import okhttp3.Request
import top.yogiczy.mytv.legacy.core.FocusEngine
import top.yogiczy.mytv.legacy.core.Keys
import top.yogiczy.mytv.legacy.core.UiLog
import top.yogiczy.mytv.legacy.data.Channel
import top.yogiczy.mytv.legacy.data.ChannelGroup
import top.yogiczy.mytv.legacy.data.ChannelGroupList
import top.yogiczy.mytv.legacy.data.DlnaCastRepository
import top.yogiczy.mytv.legacy.data.DlnaDevice
import top.yogiczy.mytv.legacy.data.EpgList
import top.yogiczy.mytv.legacy.data.EpgProgramme
import top.yogiczy.mytv.legacy.data.EpgRepository
import top.yogiczy.mytv.legacy.data.EpgSource
// M2 把这几个扩展函数写在 companion object 里（逐字照搬原版），Kotlin 不会自动带进作用域，
// 必须显式 import —— 少了这两行会报 "Unresolved reference: isLive / match"。
import top.yogiczy.mytv.legacy.data.EpgProgramme.Companion.isLive
import top.yogiczy.mytv.legacy.data.EpgProgramme.Companion.progress
import top.yogiczy.mytv.legacy.data.EpgProgramme.Companion.remainingMinutes
import top.yogiczy.mytv.legacy.data.EpgList.Companion.match
import top.yogiczy.mytv.legacy.data.ChannelParser
import top.yogiczy.mytv.legacy.data.IptvSource
import top.yogiczy.mytv.legacy.data.Prefs
import top.yogiczy.mytv.legacy.data.UiTimeShowMode
import top.yogiczy.mytv.legacy.data.UpdateRepository
import top.yogiczy.mytv.legacy.net.Http
import top.yogiczy.mytv.legacy.player.DisplayMode
import top.yogiczy.mytv.legacy.player.PlayerController
import top.yogiczy.mytv.legacy.ui.ChannelCardView
import top.yogiczy.mytv.legacy.ui.ChannelInfoView
import top.yogiczy.mytv.legacy.ui.ChannelLogoStore
import top.yogiczy.mytv.legacy.ui.DatetimeView
import top.yogiczy.mytv.legacy.ui.DlnaCastView
import top.yogiczy.mytv.legacy.ui.panel.AppLauncherView
import top.yogiczy.mytv.legacy.ui.panel.ChannelColumnView
import top.yogiczy.mytv.legacy.ui.panel.NavColumnView
import top.yogiczy.mytv.legacy.ui.panel.PanelHost
import top.yogiczy.mytv.legacy.ui.panel.SourcePanelView
import top.yogiczy.mytv.legacy.ui.epg.EpgColumnView
import top.yogiczy.mytv.legacy.ui.settings.SettingsView
import top.yogiczy.mytv.legacy.ui.settings.GroupManageView
import top.yogiczy.mytv.legacy.ui.epg.EpgPanelView
import top.yogiczy.mytv.legacy.ui.epg.EpgProgressView
import top.yogiczy.mytv.legacy.ui.Density
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit
import top.yogiczy.mytv.legacy.ui.quickop.QuickOpView
import top.yogiczy.mytv.legacy.ui.system.ScreenAutoClose
import top.yogiczy.mytv.legacy.ui.system.Snackbar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 经典三段式选台界面，按 nid12345/mytv-android 的实际界面复刻。
 *
 * 对齐原版 ClassicChannelScreen：
 *   Row { 分组栏 · 频道列 · 节目单列 }，第三列靠整体平移滑入，一屏两段。
 *   面板外有 24dp 留白与圆角（原版 ClassicChannelScreenWrapper）。
 * 左栏底部的「换源 / 系统应用」是伪分组（ClassicPanelScreen*Group）。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val DEFAULT_SOURCE = "https://tvlive.nide.qzz.io"

        // 问题 5：演示源也带上 `tvg-logo`，与真实订阅源（虎牙/斗鱼/YY 的 m3u）写法一致 ——
        // 解析器与台标链路对"源自带台标"的处理就是靠这个字段。
        private val DEMO_M3U = """
            #EXTM3U
            #EXTINF:-1 tvg-logo="https://cdn.jsdelivr.net/gh/feiyang666999/testvideo/tg.jpg" group-title="演示频道",测试流·HLS
            https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8
        """.trimIndent()

        const val NAV_SWITCH_SOURCE = "换源"
        const val NAV_FAVORITE = "我的收藏"
        const val NAV_UPDATE_TIME = "更新时间"
        const val NAV_APPS = "系统应用"

        /**
         * 换台后"临时频道界面"显示时长。
         *
         * 对齐原版 `Constants.UI_TEMP_CHANNEL_SCREEN_SHOW_DURATION = 1500L`（1.5 秒）。
         */
        private const val TEMP_CHANNEL_SCREEN_SHOW_MS = 1_500L

        /**
         * 「再按一次退出」的确认窗口（毫秒）。
         *
         * 对齐原版 `rememberDoubleBackPressedExitState(resetSeconds = 2)`。
         */
        private const val EXIT_CONFIRM_WINDOW_MS = 2_000L
    }

    private lateinit var player: PlayerController
    private lateinit var surface: SurfaceView
    /** M10：`ui/panel/PanelHost`，三段式容器 + 三态状态机。 */
    private lateinit var panel: PanelHost
    /** M10：`ui/panel/NavColumnView`，分组栏（继承 LinearLayout）。 */
    private lateinit var navColumn: NavColumnView
    // M6：中栏由 LinearLayout 全量建 View 改为 RecyclerView（1000+ 频道才不卡）。
    private lateinit var channelColumn: ChannelColumnView
    // M7：第三列（节目竖排列表 + 跨天时的日期列）；无数据时内部显示「无节目信息」占位
    private lateinit var epgColumn: EpgColumnView
    /** 独立 EPG 弹层（长按左键唤出）。 */
    private lateinit var epgPanel: EpgPanelView
    /** 常驻节目进度（开关默认关）。 */
    private lateinit var epgProgress: EpgProgressView

    private lateinit var topNumber: TextView
    private lateinit var topDate: TextView
    private lateinit var topTime: TextView
    private lateinit var topLogo: ImageView

    /** 右上角整个信息块（频道号 ∥ 日期+时间 ∥ 台标）的容器 —— 一起显示/隐藏。 */
    private lateinit var topRight: View

    /**
     * 独立的"时间显示"图层（M5）。
     *
     * 对应原版 `DatetimeScreen` —— **只显示时间**，由 `UI_TIME_SHOW_MODE` 控制显隐，
     * 且只在"没有别的界面挡着"时才轮到它（见 [refreshTopRight]）。
     */
    private lateinit var datetime: DatetimeView

    private lateinit var bottomTitle: TextView
    private lateinit var bottomTags: LinearLayout
    private lateinit var tagLine: TextView
    private lateinit var tagRes: TextView
    private lateinit var tagAudio: TextView
    private lateinit var tagSpeed: TextView
    /** 底栏"节目信息区"整体（状态消息显示时整块让位）。 */
    private lateinit var bottomProgBlock: LinearLayout
    private lateinit var bottomCurTime: TextView
    private lateinit var bottomCurRemain: TextView
    private lateinit var bottomCurTitle: TextView
    private lateinit var bottomNextTime: TextView
    private lateinit var bottomNextTitle: TextView
    private lateinit var bottomProgressTrack: LinearLayout
    private lateinit var bottomFill: View
    private lateinit var bottomRest: View
    /** 状态消息行（载入中/解析为空/播放失败…）。 */
    private lateinit var bottomStatusText: TextView
    /** 底栏当前展示的"正在播出"节目（每秒刷新进度条与剩余分钟要用）。 */
    private var bottomEpgCur: EpgProgramme? = null

    private var groupList = ChannelGroupList()
    private var navIdx = 0
    private var channelIdx = 0
    private var currentChannel: Channel? = null
    /** 判据 5：启动后是否已恢复过"上次频道"（只恢复一次，用户手动换源不恢复）。 */
    private var lastChannelRestored = false

    /** 判据 1：右下角信息栏容器（不留引用就没法隐藏）。 */
    private lateinit var bottomBar: View

    /**
     * 问题 3/4：左下角**带台标**的频道信息块（原版 `ChannelInfo` 的另一处用法）。
     *
     * 两处共用同一个 [ChannelInfoView] 实现：
     *  · [tempInfo] —— 换台 / 换线路后 1.5 秒的临时信息（原版 `ChannelTempScreen`，屏幕左下角）；
     *  · [menuInfo] —— 快捷功能菜单打开时，显示在按钮行上方（原版 `QuickOpScreenBottom`）。
     */
    private lateinit var tempInfoBar: View
    private lateinit var tempInfo: ChannelInfoView
    private lateinit var menuInfo: ChannelInfoView
    /** 内容视图根（自绘对话框要往上挂）。 */
    private lateinit var rootView: FrameLayout
    /** 临时状态消息（载入中/解析为空/播放失败…）的显示截止时刻；0 = 无。 */
    private var bottomStatusUntil = 0L
    /** 粘性状态（播放失败）：一直显示到下次换台。 */
    private var bottomStatusSticky = false
    /** 临时状态消息显示时长（与「超时自动关闭界面」的 5 秒默认值同量级）。 */
    private val BOTTOM_STATUS_MS = 1_500L
    /** 三段式当前状态：0=分组栏+频道列，1=频道列+节目单列，2=节目单列为主。 */
    private var panelState = 0
    /** 动画与布局稳定后补一次节目单取焦（API 19 上 withEndAction 不可靠，用定时）。 */
    private val epgFocusRetry = Runnable {
        if (epgFocused && panelState == 2) epgColumn.focusProgrammeList()
    }

    private var panelAnimating = false

    private val animUnlock = Runnable {
        panelAnimating = false
        blockOffscreenFocus()
    }
    private var clockModePanel = true

    /**
     * 快捷功能菜单（M8）。
     *
     * ⚠️ 与 M1～M7 里那个 4 项的自制快捷条**不是同一个东西**：
     * 原版 `QuickOpBtnList.kt` 是**固定 7 项、固定顺序、无图标**，且遮罩全屏半透明。
     * 之前那个 4 项条（收藏/换线路/节目单/切换源）既不齐也不符顺序，已整体替换。
     */
    private lateinit var quickOp: QuickOpView

    /**
     * 面板自动关闭计时（M5 起改用 [ScreenAutoClose]，超时值取设置项）。
     *
     * 之前是写死 10 秒的 `handler.postDelayed`；原版的超时值是
     * `Configs.uiScreenAutoCloseDelay`（默认 15 秒，见 `Constants.UI_SCREEN_AUTO_CLOSE_DELAY`），
     * 所以必须走 [Prefs.uiScreenAutoCloseDelay] 而不是常量。
     */
    private val panelAutoClose: ScreenAutoClose by lazy {
        ScreenAutoClose(Prefs.uiScreenAutoCloseDelay(this)) { hidePanel() }
    }

    /**
     * 临时频道界面是否可见（对齐原版 `MainContentState.isTempChannelScreenVisible`）。
     *
     * 原版语义：**换台瞬间置 true**，1.5 秒后（`Constants.UI_TEMP_CHANNEL_SCREEN_SHOW_DURATION`）
     * 自动置 false；期间如果视频分辨率变了（画面出来了）也会立刻置 false。
     */
    private var tempChannelVisible = false

    private val tempChannelHide = Runnable {
        tempChannelVisible = false
        refreshTopRight()
        refreshChannelInfoBlock()   // 问题 3：1.5 秒后收起左下角信息块
    }

    /** 问题 3：换台 / 换线路后显示 1.5 秒的左下角信息块。 */
    private fun showTempChannelInfo() {
        tempChannelVisible = true
        handler.removeCallbacks(tempChannelHide)
        handler.postDelayed(tempChannelHide, TEMP_CHANNEL_SCREEN_SHOW_MS)
        refreshTopRight()
        refreshChannelInfoBlock()
    }

    /**
     * 当前屏幕上"频道名 → 卡片"的登记表（M5）。
     *
     * 远程台标是后台线程拉取的，回来后经 `ChannelLogoStore.onLogoLoaded` 通知；
     * 此时必须能**就地换图**而不是整列重建（重建会丢焦点和滚动位置）。
     * 每次重绘某一列时清空重填。
     */
    private val cardRegistry = HashMap<String, MutableList<ChannelCardView>>()

    /** 数字选台当前已输入的数字串（原版 `ChannelNumberSelectState._channelNumber`）。 */
    private var numberBuffer = ""

    /**
     * 问题 1（用户）：面板光标的**记忆**。
     *
     *  · [lastFocusInChannelColumn] —— 上次光标停在二级（频道列）还是 一级（分组栏）
     *  · [lastFocusedChannelName]   —— 上次停在哪个频道（按名字记忆，列表重建也不丢）
     */
    private var lastFocusInChannelColumn = false
    private var lastFocusedChannelName: String? = null

    /**
     * 退出应用二次确认（v1.6，对齐原版 1.7 `DoubleBackPressedExitState(resetSeconds = 2)`）。
     *
     * 根界面上第一次按返回键只弹「再按一次退出」，[EXIT_CONFIRM_WINDOW_MS] 内再按才真的退出；
     * 超时由 [resetAllowExit] 复位。用户要求「避免误按」，原版就是这个语义。
     */
    private var allowExit = false
    private val resetAllowExit = Runnable { allowExit = false }

    /**
     * 问题 1：**唤出面板那一刻**的「上次光标在哪」快照。
     *
     * 为什么必须快照：`panel.visibility = VISIBLE` 之后系统会把焦点自动交给分组栏第一项
     * 「换源」，那一发**伪聚焦**会同时（a）把 [lastFocusInChannelColumn] 冲成 false、
     * （b）触发 `onNavSelected(0)` 把中栏换成订阅源列表（实测：重开面板永远是"换源"）。
     * 所以要在改可见性**之前**把意图记下来，恢复时只看快照，不看被污染的字段。
     */
    private var wakeFocusInChannel = false
    private var wakeFocusChannelName: String? = null

    private val navEntries = ArrayList<NavEntry>()

    private data class NavEntry(val label: String, val iconRes: Int, val logoName: String?, val group: ChannelGroup?)

    private val handler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            if (::quickOp.isInitialized) quickOp.tick()   // 快捷菜单顶栏的「日期 + HH:mm:ss」
            if (::datetime.isInitialized) refreshDatetime()   // 原版 DatetimeScreen（每秒轮询）
            // 判据 4：底栏的网速角标与当前节目进度条每秒刷新（原版也是每秒轮询）
            if (::player.isInitialized && ::tagSpeed.isInitialized) {
                tagSpeed.text = "${player.speedKBps}KB/s"
                updateBottomProgress()
            }
            // 问题 3/4：信息块可见时每秒刷新（网速 / 进度都是每秒变的）
            val infoVisible = (::tempInfoBar.isInitialized && tempInfoBar.visibility == View.VISIBLE) ||
                (::quickOp.isInitialized && quickOp.isOpen)
            if (infoVisible) refreshChannelInfoBlock()
            handler.postDelayed(this, 1000L)
        }
    }

    /**
     * 布局不再按屏宽同比缩放。
     *
     * 早先用 (屏宽/1100) 缩放布局尺寸、却保留字号的 sp，两者脱节：
     * 导航列被压到约 70dp，而「我的收藏」按 13sp 要 101dp，
     * 于是全被截断（见实机截图）。现在布局直接给适配小屏的绝对值，字号同步收紧。
     */
    private fun s(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()

    /**
     * M5：把整界面切到"按 960dp 基准等比缩放"的 Context 上。
     *
     * 对齐原版 `App.kt`：
     * ```
     * LocalDensity provides Density(
     *     density   = LocalDensity.current.density * (uiDensityScaleRatio ?: screenWidthDp / 960f),
     *     fontScale = LocalDensity.current.fontScale * uiFontScaleRatio)
     * ```
     * 因为改的是 `Configuration.densityDpi`，所以 dp（布局）与 sp（字号）**同源缩放** ——
     * 这正是 v0.5「布局缩了、字号没缩 → 导航列文字被截断」那个坑的正解。
     *
     * 生效后本文件里的 `s(dp)` 与 `setTextSize(COMPLEX_UNIT_SP, …)` **自动**跟着缩放，
     * 不需要逐处乘比例；相反，**不能**再用 `Density.dp()/sp()/applyTextSize()`（会二次缩放）。
     *
     * 实测环境 1280×720@320dpi → 屏宽 640dp → 倍率 640/960 = 0.667（与原版同机同值）。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(
            Density.wrap(
                newBase,
                Density.currentRatio(newBase),
                Prefs.uiFontScaleRatio(newBase),
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterFullscreen()
        Snackbar.with(this)                      // M5：提示条需要宿主才能挂载
        if (showCrashReportIfAny()) return
        buildUi()

        // M5：远程台标（后台线程）加载完成后按频道名就地回填，不重建列表
        ChannelLogoStore.onLogoLoaded = { name -> handler.post { applyLogosLoaded(name) } }

        player = PlayerController(this)
        player.attach(surface)
        player.onVideoSize = { w, h ->
            bottomTagsUpdate("${w}×${h}")
            // 原版：视频分辨率一变（画面出来了）就撤掉临时频道界面
            if (tempChannelVisible) {
                handler.removeCallbacks(tempChannelHide)
                tempChannelVisible = false
                refreshTopRight()
            }
        }
        player.onLineChanged = { idx, total, _ ->
            setLineTag(idx + 1, total)
            setTopNumberPlaceholder()
        }
        player.onError = { msg ->
            showBottomStatus("播放失败 — $msg", sticky = true)
        }

        UiLog.i("界面", "密度缩放：${Density.describe(this)}")

        handler.post(clockTick)
        loadSource(Prefs.currentSourceUrl(this))
        refreshTopRight()
    }

    override fun onDestroy() {
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(tempChannelHide)
        panelAutoClose.cancel()
        // 断开台标回调：Activity 已销毁，再回填会拿到一个死掉的 View 树
        ChannelLogoStore.onLogoLoaded = null
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    /**
     * 返回键分层关闭（对应原版各弹层的 `BackHandler`）：
     *  1. 快捷菜单开着 → 只关菜单（原版 `QuickOpScreen` 的 BackHandler）
     *  2. 三段式面板开着 → 只收面板
     *  3. 都没有 → 交给系统（退出应用）
     *
     * ⚠️ 这是 M8 实机验收⑦抓出来的真 bug：没有这层，菜单开着按 BACK
     * 会直接 `finish()` 整个应用（Keys 路由表里没有 BACK，事件落到 Activity 默认实现）。
     */
    /** M10：收起动画的收尾（替代 withEndAction，见 hidePanel）。 */
    private val hidePanelFinish = Runnable {
        UiLog.i("界面", "hidePanelFinish 执行 → 置 GONE")
        panel.visibility = View.GONE
        // 必须在 visibility=GONE 之后刷新：那时 refreshTopRight() 才会判定 showFull=false，
        // 否则"收起后右上只留 HH:mm"不生效（台标一直挂着）。
        refreshTopRight()
    }

    /** M9：设置页（13 分类）。 */
    private lateinit var settings: SettingsView

    /** M10：换源面板 / 系统应用面板（从 MainActivity 拆出的两个视图类）。 */
    private val sourcePanel by lazy { SourcePanelView(this) }
    private val appLauncher by lazy { AppLauncherView(this) }

    /** M9：设置改动后的应用（界面开关、台标、进度条等）。 */
    private fun applySettingsChanged() {
        runCatching {
            // 常驻底部节目进度
            epgProgress.visibility =
                if (Prefs.uiShowEpgProgrammePermanentProgress(this)) View.VISIBLE else View.GONE
            // 台标显示开关：重绘当前分组的中栏即可生效
            renderEpgForCurrent()
            refreshTopRight()
        }
    }

    override fun onBackPressed() {
        // 判据 11-2 修复：崩溃报告屏（buildUi 被跳过）下 panel 未初始化，
        // 原写法 `panel.visibility` 会在按返回键时同样抛 UninitializedPropertyAccessException。
        if (!::panel.isInitialized) {
            super.onBackPressed()
            return
        }
        // 自绘对话框优先：返回键只关对话框。
        if (dialogView != null) {
            cancelExitConfirm()
            closeAppDialog()
            return
        }
        // 问题 6：分组管理抽屉优先于面板
        if (groupManageView != null) {
            cancelExitConfirm()
            closeGroupManage()
            return
        }
        // 问题 6：投屏抽屉
        if (dlnaView != null) {
            cancelExitConfirm()
            closeDlnaCast()
            return
        }
        when {
            ::settings.isInitialized && settings.isOpen -> { cancelExitConfirm(); settings.handleBack() }
            ::epgPanel.isInitialized && epgPanel.isOpen -> { cancelExitConfirm(); closeEpgPanel() }
            ::quickOp.isInitialized && quickOp.isOpen -> { cancelExitConfirm(); quickOp.close() }
            panel.visibility == View.VISIBLE -> { cancelExitConfirm(); hidePanel() }
            // v1.6（用户要求，对齐原版 1.7 `DoubleBackPressedExitState`）：
            // 根界面上**连续按两次**返回键才退出，第一次弹提示防误按。
            allowExit -> {
                UiLog.i("界面", "返回键：第二次 → 退出应用")
                super.onBackPressed()
            }
            else -> {
                allowExit = true
                handler.removeCallbacks(resetAllowExit)
                handler.postDelayed(resetAllowExit, EXIT_CONFIRM_WINDOW_MS)
                UiLog.i("界面", "返回键：第一次 → 提示「再按一次退出」（${EXIT_CONFIRM_WINDOW_MS}ms 内再按才退出）")
                Snackbar.show("再按一次退出", this)
            }
        }
    }

    /** 任何"被面板/抽屉消费掉"的返回键都要清掉退出确认，避免"关面板 + 再按一次"就退出。 */
    private fun cancelExitConfirm() {
        allowExit = false
        handler.removeCallbacks(resetAllowExit)
    }

    // ---------------- 遥控器按键（M1：统一走 Keys 路由表） ----------------
    // 对应原版 `ModifierUtils.kt` 的 `Modifier.handleKeyEvents(...)`。
    //
    // ⚠️ **主界面的按键映射以原版 `MainContent.kt:120-152` 为准**（M5 逐条对齐）：
    // ```
    // onUp         -> iptvChannelChangeFlip ? 下一个频道 : 上一个频道
    // onDown       -> iptvChannelChangeFlip ? 上一个频道 : 下一个频道
    // onLeft       -> urlList.size > 1 时切上一条线路
    // onRight      -> urlList.size > 1 时切下一条线路
    // onSelect     -> 打开经典三段式面板（原版 isChannelScreenVisible = true）
    // onLongSelect -> 打开快捷功能菜单
    // onSettings   -> 打开快捷功能菜单
    // onLongLeft   -> 打开 EPG 弹层        （M7）
    // onLongRight  -> 打开多线路弹层       （M6/M7）
    // onLongDown   -> 打开播放控制弹层     （M8/M9）
    // onNumber     -> 数字选台（debounce 规则见 numberInput）
    // ```
    // 面板打开时**只留收起与菜单**，其余放行给焦点系统 —— 这样面板内的上下左右
    // 才能正常在列表间移动焦点（原版靠 Compose 的 `onPreviewKeyEvent` 只看不消费，
    // View 世界没有等价物，只能按"是否有回调"来区分，见 [Keys.dispatch] 的说明）。
    //
    // 注意 v0.5 那个"右键展开面板"的写法**已删除**：原版右键是切换线路，不是展开面板。

    /** 面板收起时 = 原版主界面的完整按键映射。 */
    private val keysPanelCollapsed: Keys.Handler by lazy {
        Keys.Handler(
            onUp = { changeChannelBy(if (Prefs.iptvChannelChangeFlip(this)) +1 else -1) },
            onDown = { changeChannelBy(if (Prefs.iptvChannelChangeFlip(this)) -1 else +1) },
            onLeft = { changeLineBy(-1) },
            onRight = { changeLineBy(+1) },
            onSelect = {
                Log.i(Keys.TAG, "确认键 → 唤出经典三段式面板")
                showPanel()
                // 问题 1（用户）：不再固定落在分组栏第一项 ——
                // 把光标搬回**上次停留的位置**（可能就在某个频道上），方便接着挑。
                restorePanelFocus()
            },
            onLongSelect = { onLongSelectPlaceholder() },
            onSettings = { onMenuKeyPlaceholder() },
            onLongLeft = { onLongLeftPlaceholder() },
            onLongRight = { onLongRightPlaceholder() },
            onLongDown = { onLongDownPlaceholder() },
            onNumber = { n -> numberInput(n) },
        )
    }

    /** 面板打开时：只接管「左键收起」与菜单键；方向键一律放行给焦点导航。 */
    private val keysPanelExpanded: Keys.Handler by lazy {
        Keys.Handler(
            onLeft = { onPanelLeft() },
            onRight = { onPanelRight() },
            onSettings = { onMenuKeyPlaceholder() },
            // ⚠️ 问题 4：这里**故意不绑 onLongSelect**。
            // 绑了的话，长按确认键会被 Keys 消费掉并唤出快捷功能菜单，而焦点项自己的
            // 长按（频道项 = 收藏）由 View 的 CheckForLongPress 独立触发 ——
            // 两者同时发生，用户看到的就是「长按收藏的同时又弹出设置菜单」。
            // 不绑 → Keys.dispatch 对这个键完全没有回调，直接放行给 View 树，
            // 短按 = 焦点项的 onClick（换台），长按 = 焦点项的长按（收藏），语义唯一。
            onLongLeft = { onLongLeftPlaceholder() },
            onLongRight = { onLongRightPlaceholder() },
            onLongDown = { onLongDownPlaceholder() },
            onNumber = { n -> numberInput(n) },
        )
    }

    /**
     * MENU / SETTINGS / HELP / H：唤出快捷功能菜单（M8 接通）。
     *
     * 原版按键语义（`MainContent.kt:147`）：这几个键都映射到 `onSettings`
     * → 打开快捷功能菜单。
     */
    private fun onMenuKeyPlaceholder() {
        Log.i(Keys.TAG, "MENU/SETTINGS/HELP/H → 快捷功能菜单")
        showQuickMenu()
    }

    /**
     * 长按确认键 → 快捷功能菜单（对应原版 `MainContent.kt:146` 的 `onLongSelect`）。
     *
     * 注意这里**只注册长按、不注册短按**（本 handler 里也绑了 onSelect 的情形除外）：
     * 按 [Keys.dispatch] 的消费规则，短按仍会放行给焦点项自己去 onClick ——
     * 正是"短按激活、长按唤出菜单"的原版效果。
     */
    private fun onLongSelectPlaceholder() {
        Log.i(Keys.TAG, "长按确认键 → 快捷功能菜单")
        showQuickMenu()
    }

    // ---- 以下三个是 M6/M7/M8 的入口占位：**必须存在**（原版有），本轮只提示不实现 ----

    /** 原版 `onLongLeft`：长按左键 → 独立 EPG 弹层（M7）。 */
    /** 长按左键 → 独立 EPG 弹层（原版 `EpgScreen` 的 Drawer 形态）。 */
    private fun onLongLeftPlaceholder() {
        Log.i(Keys.TAG, "长按左键 → EPG 弹层")
        val epg = epgList?.match(currentChannel ?: return)
        epgPanel.open(epg)
        resetAutoHide()
        // 判据 1：EPG 弹层此前**没有任何超时**，长按左键唤出后不手动按返回就一直挂着
        // （与"节目表 5 秒自动隐藏"直接冲突）。这里给它同一条超时链路。
        epgAutoClose.timeoutMs = Prefs.uiScreenAutoCloseDelay(this)
        epgAutoClose.active()
        refreshBottomVisibility()
    }

    /** 判据 1：关闭 EPG 弹层（所有关闭路径都要走这里，保证计时被取消）。 */
    private fun closeEpgPanel() {
        if (::epgPanel.isInitialized && epgPanel.isOpen) epgPanel.close()
        epgAutoClose.cancel()
        refreshTopRight()
    }

    /** 判据 1：EPG 弹层的自动关闭计时（超时值取「超时自动关闭界面」设置）。 */
    private val epgAutoClose: ScreenAutoClose by lazy {
        ScreenAutoClose(Prefs.uiScreenAutoCloseDelay(this)) { closeEpgPanel() }
    }

    // ======================= M7：节目单 =======================

    /** 全量节目单（后台线程拉取，主线程只读）。 */
    private var epgList: EpgList? = null

    private fun fetchEpg() {
        if (!Prefs.epgEnable(this)) {
            epgList = null
            renderEpgForCurrent()
            return
        }
        Thread {
            val names = groupList.flatChannels().map { it.name }
            try {
                val current = Prefs.epgSourceCurrent(this)
                val fallbacks = Prefs.epgSourceList(this).filterNot { it.url == current.url }
                val list = EpgRepository(cacheDir, current, fallbacks)
                    .getEpgList(names, Prefs.epgRefreshTimeThreshold(this))
                epgList = list
                val programmes = list.value.sumOf { it.programmeList.value.size }
                // ★ 原版文案，逐字沿用（PROJECT-STATE 冻结决策）：无 adb 时按 tag「节目单」筛
                UiLog.i("节目单", "解析节目单完成，共${list.value.size}个频道，${programmes}个节目")
            } catch (e: Exception) {
                epgList = null
                UiLog.e("节目单", "节目单拉取失败：${e.message ?: e.javaClass.simpleName}", e)
            }
            handler.post { renderEpgForCurrent() }
        }.start()
    }

    /** 把当前频道的节目单渲染进第三列（并刷新底栏/常驻进度）。 */
    private fun renderEpgForCurrent() {
        val ch = currentChannel
        val epg = if (ch == null) null else epgList?.match(ch)
        Log.i(
            Keys.TAG,
            "节目单：渲染 频道=${ch?.name} epgList=${epgList?.size} 命中=${epg?.channel} " +
                "节目数=${epg?.programmeList?.value?.size}"
        )
        epgColumn.render(epg)

        // 判据 4（1.7 ChannelInfo）：当前节目（时间段 + 进度 + 剩余 + 名）+ 下一个节目（时间段 + 名）
        val list = epg?.programmeList?.value.orEmpty()
        val now = list.firstOrNull { it.isLive() }
        val next = if (now == null) null else list.firstOrNull { it.startAt >= now.endAt }
        showProgrammeInfo(now, next, ch)
        epgProgress.update(now)
        // ⚠️ `EpgProgressView.update()` 只负责填内容、会无条件置 VISIBLE；而"常驻底部节目进度"
        // 是个**设置项（默认关）**，可见性必须由设置项决定，否则面板收起后它一直挂在左下角。
        epgProgress.setEnabledByPref(
            now != null && Prefs.uiShowEpgProgrammePermanentProgress(this)
        )
        refreshChannelProgrammes()   // 判据 3：中栏每行底部的"当前节目名 + 进度"
        refreshBottomVisibility()   // 判据 1
    }

    /**
     * 判据 3：把**当前分组**每个频道的"正在播出节目 + 进度"补到中栏行上（1.7 的两行频道项）。
     *
     * 采取"就地刷新"而不是重建列表：重建会丢焦点与滚动位置（M5 台标回填踩过同一个坑）。
     * 只算当前分组（几十条），不遍历全量频道 —— `renderEpgForCurrent` 每次换台都会调用它。
     */
    private fun refreshChannelProgrammes() {
        val g = navEntries.getOrNull(navIdx)?.group ?: return
        channelColumn.updateProgrammes(programmesOf(g.channels))
    }

    /**
     * 问题 2：频道项**获得焦点**时的预览 —— **不切台、不动播放器**。
     *
     * 对齐原版 1.7：`ClassicChannelItem` 的 `onFocusChanged → onChannelFocused` 只更新
     * "光标所在频道"（`focusedChannel`），节目单列跟着它走；真正换台走的是
     * `onSelect = onChannelSelected`（本工程的 [selectChannel]）。
     * 用户反馈的「光标按到哪个就自动跳转」正是此前把 [selectChannel] 直接挂在了焦点回调上。
     */
    private fun onChannelPreview(ch: Channel, idx: Int) {
        channelIdx = idx
        // 问题 1（用户）：记下光标停留的频道，下次唤出面板时直接回到这里。
        // 同样只在面板可见时更新 —— 面板收起后的焦点事件不能污染这份记忆。
        if (::panel.isInitialized && panel.visibility == View.VISIBLE) {
            lastFocusedChannelName = ch.name
            lastFocusInChannelColumn = true
        }
        // 节目单列随光标预览（1.7 行为）；底部信息栏仍按**正在播放**的频道显示，不跟着动。
        epgColumn.render(epgList?.match(ch))
        UiLog.d("界面", "频道预览（未切台）→ ${ch.name}")
    }

    /**
     * 问题 1（用户）：把光标搬回**上次停留的位置**。
     *
     * 原来每次唤出面板都固定落在分组栏的当前项上；用户要的是"接着上次继续挑频道"。
     * 两条记忆：
     *  · [lastFocusInChannelColumn] —— 上次光标在二级（频道列）还是在一级（分组栏）；
     *  · [lastFocusedChannelName]  —— 上次停在哪个频道（按**名字**恢复，列表换对象也不丢）。
     */
    private fun restorePanelFocus() {
        // ⚠️ 必须**延迟**再搬焦点：面板刚从 GONE 变 VISIBLE 时，中栏 RecyclerView 还没
        // 走完 layout、子 View 尚不存在，此刻 restoreFocus 一定失败（实测踩到）。
        // 两次尝试：120ms（一般够）+ 380ms 兜底；第二次无论成败都解除伪聚焦屏蔽。
        handler.postDelayed({ tryRestorePanelFocus() }, 120L)
        handler.postDelayed({
            if (::panel.isInitialized && panel.visibility == View.VISIBLE &&
                !focusInside(channelColumn) && !focusInside(navColumn)
            ) tryRestorePanelFocus()
            if (::navColumn.isInitialized) navColumn.setFocusSelectSuppressed(false)
        }, 380L)
    }

    private fun tryRestorePanelFocus(): Boolean {
        if (!::panel.isInitialized || panel.visibility != View.VISIBLE) return false
        // 只看**唤出时**的快照（字段本身可能已被系统自动聚焦污染，见 wakeFocusInChannel 注释）
        if (wakeFocusInChannel) {
            val name = wakeFocusChannelName ?: currentChannel?.name
            if (name != null && FocusEngine.restoreFocus(channelColumn, name)) {
                // ⚠️ `restoreFocus` 匹配不到时会**静默回落**到第一项并同样返回 true，
                // 所以必须回查真实落点，否则日志会撒谎（本轮踩到）。
                val landed = channelColumn.findFocus()?.let { FocusEngine.keyOf(it) }
                if (landed == name) {
                    UiLog.i("界面", "面板唤出 → 光标回到上次频道「$name」")
                } else {
                    UiLog.i("界面", "面板唤出 → 频道列首项（上次的「$name」不在当前分组）")
                }
                return true
            }
            if (FocusEngine.focusFirstIn(channelColumn)) {
                UiLog.i("界面", "面板唤出 → 回到频道列第一项（上次的「$name」不在当前分组）")
                return true
            }
        }
        val row = navColumn.getChildAt(navIdx)
        if (row != null && row.requestFocus()) {
            UiLog.i("界面", "面板唤出 → 光标回到分组栏第 ${navIdx + 1} 项")
            return true
        }
        return FocusEngine.focusFirstIn(navColumn)
    }

    /** 频道列表 → (频道名 → 当前节目名 / 进度)。 */
    private fun programmesOf(channels: List<Channel>): Map<String, Pair<String?, Float>> {
        val map = HashMap<String, Pair<String?, Float>>()
        val list = epgList ?: return map
        channels.forEach { c ->
            val p = list.match(c)?.programmeList?.value?.firstOrNull { it.isLive() } ?: return@forEach
            map[c.name] = p.title to p.progress()
        }
        return map
    }

    /** 节目项确认键：已结束 → 回放；未开始 → 预约/取消预约（原版语义）。 */
    private fun onEpgProgrammeClick(p: EpgProgramme) {
        val now = System.currentTimeMillis()
        when {
            p.endAt < now -> {
                Log.i(Keys.TAG, "节目单：请求回放《${p.title}》")
                Snackbar.show("回放：${p.title}（回放链路属 M7 之外的范围）", this)
            }
              p.startAt > now -> {
                  // M10：预约真正落库（M9 遗留）
                  val chName = currentChannel?.name.orEmpty()
                  val added = if (chName.isBlank()) false
                  else Prefs.toggleReserve(this, chName)
                  Log.i(Keys.TAG, "节目单：${if (added) "已预约" else "已取消预约"} $chName《${p.title}》")
                  Snackbar.show(
                      if (chName.isBlank()) "预约需要当前频道"
                      else "${if (added) "已预约" else "已取消预约"}：$chName", this
                  )
              }
            else -> {
                Log.i(Keys.TAG, "节目单：点击正在播出的节目《${p.title}》")
            }
        }
    }

    /** 原版 `onLongRight`：长按右键 → 多线路弹层（M6/M7）。 */
    private fun onLongRightPlaceholder() {
        Log.i(Keys.TAG, "长按右键 → 多线路弹层（M6/M7）")
        Snackbar.show("多线路弹层将在 M6/M7 接入", this)
    }

    /** 原版 `onLongDown`：长按下键 → 播放控制弹层（M8/M9）。 */
    private fun onLongDownPlaceholder() {
        Log.i(Keys.TAG, "长按下键 → 播放控制弹层（M8/M9）")
        Snackbar.show("播放控制弹层将在 M8/M9 接入", this)
    }

    // ---------------- 换台 / 切线路 / 数字选台 ----------------

    /** 在当前源的全部频道里前后移动（原版 `changeCurrentChannelToNext/Prev`，跨分组循环）。 */
    private fun changeChannelBy(step: Int) {
        val all = groupList.flatChannels()
        if (all.isEmpty()) return
        val cur = currentChannel
        val i = if (cur == null) -1 else all.indexOfFirst { it.name == cur.name }
        var n = if (i < 0) 0 else i + step
        if (n < 0) n = all.lastIndex
        if (n > all.lastIndex) n = 0
        jumpToChannel(all[n])
    }

    /** 切换线路（原版：只有多线路时才切，单线路左右键无动作）。 */
    private fun changeLineBy(step: Int) {
        if (!::player.isInitialized) return
        if (player.lineCount <= 1) return
        val next = (player.currentLineIdx + step).coerceIn(0, player.lineCount - 1)
        if (next == player.currentLineIdx) return
        player.switchLine(next)
        // 问题 3（用户）：左右切换同名源的线路时，同样弹 1.5 秒的频道信息块
        showTempChannelInfo()
    }

    /**
     * 数字选台（原版 `ChannelNumberSelectState`）。
     *
     * ⚠️ 原版的 debounce 规则**不是固定的 1.5 秒**，而是
     * `debounce { (4 - 数字位数) * 1000L }`：
     * ```
     * 按 1 位 → 等 3 秒确认；2 位 → 2 秒；3 位 → 1 秒；4 位 → 立刻确认
     * ```
     * 所以"1""2"在 3 秒内按完就跳第 12 台 —— 《第二步计划》§M5 验收写的"1.5 秒内"
     * 是简化表述，实际窗口更宽，照原版实现即可通过。
     */
    private fun numberInput(no: Int) {
        numberBuffer += no.toString()
        Log.i(Keys.TAG, "数字选台输入=${numberBuffer}")
        topNumber.visibility = View.VISIBLE
        topNumber.text = numberBuffer
        topRight.visibility = View.VISIBLE
        handler.removeCallbacks(numberConfirm)
        handler.postDelayed(numberConfirm, (4 - numberBuffer.length).coerceAtLeast(0) * 1000L)
    }

    private val numberConfirm = Runnable {
        val n = numberBuffer.toIntOrNull()
        numberBuffer = ""
        if (n != null && n > 0) {
            val all = groupList.flatChannels()
            all.getOrNull(n - 1)?.let { jumpToChannel(it) }
        }
        setTopNumberPlaceholder()
        refreshTopRight()
    }

    /** 跳到某个频道（并把它所在分组同步到导航列）。 */
    private fun jumpToChannel(ch: Channel) {
        val g = groupList.groups.firstOrNull { grp -> grp.channels.any { it.name == ch.name } } ?: return
        val navI = navEntries.indexOfFirst { it.group?.name == g.name }
        if (navI >= 0 && navI != navIdx) {
            navIdx = navI
            renderNav()
        }
        val idxInGroup = g.channels.indexOfFirst { it.name == ch.name }
        selectChannel(ch, if (idxInGroup < 0) 0 else idxInGroup)
    }

    /**
     * 当前生效的按键路由。
     *
     * ⚠️ 快捷菜单打开时**必须返回一个什么都不绑的 Handler**：
     * 菜单里的 7 个按钮要靠方向键左右移动焦点，而面板收起时的 handler 绑了
     * `onRight`（展开面板）—— 若继续生效，RIGHT 会被面板抢走、菜单里根本移不了焦点。
     * 空 handler → `Keys.dispatch` 返回 false → 事件放行给 View 树自己处理。
     */
        private fun currentKeyHandler(): Keys.Handler =
            // ⚠️ 视图树还没建好时必须放行（判据 11-2 修复）：
            // `onCreate` 里 `if (showCrashReportIfAny()) return` 会**整体跳过 buildUi()**，
            // 此时 `panel` 从未初始化。而 `dispatchKeyEvent` 早于一切、任何按键都会走到这里
            // → 直接读 `panel.visibility` 就抛 UninitializedPropertyAccessException。
            // 崩溃报告屏上按任意键必崩，就是这么来的（真机 M17VA-ANSM0 实证）。
            if (!::panel.isInitialized) keysNone
            // 自绘对话框开着时必须放行给控件（EditText / 按钮要自己吃方向键与确认键）。
            else if (dialogView != null) keysNone
            // 问题 6：分组管理抽屉开着时同样放行给控件（确认键要能切换显隐）
            else if (groupManageView != null) keysNone
            // 问题 6：投屏抽屉开着时放行给控件（设备列表要能移动焦点 / 选中）
            else if (dlnaView != null) keysNone
            // ⚠️ 设置页打开时必须放行给焦点系统：否则方向键会被解释成"换台"，
            // 左栏焦点根本不动、右键也进不了右栏（M9 实测踩到）。
            else if (::quickOp.isInitialized && quickOp.isOpen) keysNone
            else if (::settings.isInitialized && settings.isOpen) keysNone
              // ⚠️ 判据必须是「面板可见」，不能是「面板已展开（state>0）」：
            // 面板刚唤出时 state=0，若此时走 keysPanelCollapsed，右键会被解释成
            // "切线路"，三态根本进不去（M6 实测踩到）。
            else if (panel.visibility == View.VISIBLE) keysPanelExpanded
            else keysPanelCollapsed

    /** 空路由：什么都不接管（放行给焦点系统）。 */
    private val keysNone = Keys.Handler()

    /**
     * 按键统一入口：**必须用 `dispatchKeyEvent`，不能用 `onKeyDown`**。
     *
     * 原因（M1 实测发现，原计划 §6.1 写的 `Activity.onKeyDown` 是错的）：
     * `Activity.onKeyDown` 是**最后兜底** —— 它只在"整个 View 树都没消费这个键"时才会被调用。
     * 而 `View.onKeyDown` 对方向键的默认实现是 `focusSearch()` + `requestFocus()`，
     * **一旦焦点落在面板里的某个可聚焦项上，方向键就被那个 View 吃掉了**，
     * Activity 层的 `onKeyDown` 永远收不到 → 面板左键收起会随机失效。
     *
     * `dispatchKeyEvent` 在事件进入 View 树**之前**就被调用，正好对应原版
     * `Modifier.onPreviewKeyEvent { }` 的"预览/拦截"语义 —— 这才是忠实的移植位置。
     *
     * 短按仍按原版在 ACTION_UP 触发，所以这里 DOWN/UP 都交给 [Keys.dispatch]。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 判据 1：EPG 弹层开着时任何按键都算"用户操作"，重新计时。
        if (::epgPanel.isInitialized && epgPanel.isOpen) epgAutoClose.active()
        if (::panel.isInitialized && panel.visibility == View.VISIBLE) resetAutoHide()
        return if (Keys.dispatch(event, currentKeyHandler())) true else super.dispatchKeyEvent(event)
    }

    // ---------------- 全屏 ----------------

    private fun enterFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyFullscreenFlags()
    }

    /** API 17 上没有 WindowInsetsControllerCompat 那套，直接用 systemUiVisibility。 */
    private fun applyFullscreenFlags() {
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            flags = flags or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreenFlags()
    }

    /**
     * 手势走 dispatchTouchEvent 旁路处理，**不能**用 root.setOnTouchListener。
     * 后者一旦返回 true 就把事件截住了，面板里的列表项和按钮会全部点不动。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 判据 11-2（真机 oppo R11s Plus 崩溃，触摸路径）：`showCrashReportIfAny()` 为真时
        // `onCreate` 会**整体跳过 `buildUi()`**，`panel` 等 lateinit 视图根本没建 ——
        // 而只要手指点一下屏幕，手势识别就会走到 `onScreenTap` 里读 `panel.visibility` →
        // `UninitializedPropertyAccessException`（真机堆栈：onScreenTap:936 ← onSingleTapUp:925 ← 这里）。
        // 视图树没建好就完全不走手势回调。
        if (::panel.isInitialized) gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ---------------- 面板显隐 ----------------
    // 对齐原版两处逻辑：
    //   1) ClassicChannelScreenWrapper 的 onTap = onClose  → 点空白处隐藏
    //   2) rememberScreenAutoCloseState 的 debounce 超时    → 无操作自动隐藏

    private var panelDismissing = false

    private fun showPanel() {
        if (panel.visibility == View.VISIBLE) {
            resetAutoHide()
            return
        }
        panelDismissing = false
        // 问题 1：改可见性**之前**快照「上次光标在哪」，并屏蔽打开窗口期内的伪聚焦 ——
        // 否则系统自动聚焦分组栏第一项「换源」，中栏被换成订阅源列表、记忆也被冲掉。
        wakeFocusInChannel = lastFocusInChannelColumn
        wakeFocusChannelName = lastFocusedChannelName
        if (::navColumn.isInitialized) navColumn.setFocusSelectSuppressed(true)
        panel.visibility = View.VISIBLE
        UiLog.i("界面", "showPanel 已置 VISIBLE=${panel.visibility}")
        // 问题 1：面板刚出来时焦点还没进二级，一级的选中白框应是实心的
        if (::navColumn.isInitialized) navColumn.setDimmed(false)
        panel.alpha = 0f
        panel.animate().alpha(1f).setDuration(150L).start()
        refreshTopRight()
        resetAutoHide()
        // 诊断：面板"看不见"时靠这一行分辨是 alpha 没动画完、还是尺寸/位移不对。
        handler.postDelayed({
            UiLog.i("界面", "面板显隐 可见=${panel.visibility} alpha=${panel.alpha} " +
                    "尺寸=${panel.width}x${panel.height} 位移=${panel.translationX} " +
                    "状态=${panel.currentState}")
        }, 400L)
    }

    private fun hidePanel() {
        // 诊断：面板"刚唤出就消失"时必须能看出是谁触发的（调用栈前三帧）。
        runCatching {
            val st = Throwable().stackTrace.drop(1).take(3)
                .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            UiLog.i("界面", "hidePanel 触发 可见=${panel.visibility} 来自 $st")
        }
        if (quickOp.isOpen) quickOp.close()
        if (panel.visibility != View.VISIBLE) {
            refreshTopRight()
            return
        }
          // ⚠️ M10：不用 withEndAction —— API 19 上该回调可能不来（M6 实测），
          // 一旦不来面板就永远收不掉、右上台标一直挂着。改定时兜底。
          // 问题 1（用户）：收起期间就算 alpha 还在淡出，右下角信息也必须**立即**消失
          // —— panelDismissing 让 refreshBottomVisibility() 不再把这段动画期算作"面板在屏"。
          panelDismissing = true
          panel.animate().alpha(0f).setDuration(150L).start()
          handler.removeCallbacks(hidePanelFinish)
          handler.postDelayed(hidePanelFinish, 170L)
          panelAutoClose.cancel()
          refreshBottomVisibility()
          refreshChannelInfoBlock()
    }

    /**
     * 任何交互都重置面板的超时（超时值取 `Prefs.uiScreenAutoCloseDelay`，默认 15 秒）。
     *
     * M5 起改用 [ScreenAutoClose]（语义与快捷菜单那一套一致，对应原版
     * `rememberScreenAutoCloseState`：CONFLATED + debounce，每次操作都重新计时）。
     *
     * 注意：快捷菜单（M8）**不在这里计时** —— 它有自己的 [ScreenAutoClose] 实例，
     * 两个界面各自计时、互不干扰；混用一个会互相打架。
     */
    private fun resetAutoHide() {
        // 判据 1：每次都读最新设置值 —— 面板实例是 by lazy 建的，固定住超时值会导致
        // 「超时自动关闭界面」改了要等重启才生效。
        panelAutoClose.timeoutMs = Prefs.uiScreenAutoCloseDelay(this)
        if (panel.visibility == View.VISIBLE) panelAutoClose.active() else panelAutoClose.cancel()
    }

    /**
     * 刷新右上角信息块。
     *
     * 三个层的优先级（对齐原版 `MainContent.kt` 的 `Visible{}` 条件）：
     *  1. **完整信息块**（大号频道号 ∥ 日期+时间 ∥ 台标）—— 面板打开 或 换台后 1.5 秒内
     *     （原版 `ChannelScreen` 自带的 TopRight / `ChannelTempScreen`）
     *  2. 都不可见时，交给 [datetime]（原版 `DatetimeScreen`，按 `UI_TIME_SHOW_MODE` 显隐，**默认隐藏**）
     *  3. `UI_TIME_SHOW_MODE = HIDDEN` 这一默认档，本工程保留 v0.5 的「只留大号 HH:mm」雏形
     *     —— 见 PROJECT-STATE「与原版有意差异」，这是《第二步计划》§M5 验收 3 明确要求的行为
     */
    // ---------------- 右下角信息栏（判据 1） ----------------

    /**
     * 显示一条**临时状态**（载入中 / 解析为空 / 播放失败 / 已收藏…）。
     *
     * 判据 1：底栏现在会跟着面板一起自动收起，但状态提示不能跟着消失 —— 所以这类消息
     * 单独给一个显示窗口（[BOTTOM_STATUS_MS]），窗口内即使面板不在屏也保持可见。
     */
    private fun showBottomStatus(msg: String, sticky: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { showBottomStatus(msg, sticky) }
            return
        }
        bottomStatusText.text = msg
        bottomStatusText.visibility = View.VISIBLE
        bottomProgBlock.visibility = View.GONE          // 状态消息优先，节目区让位
        bottomEpgCur = null
        bottomStatusSticky = sticky
        bottomStatusUntil = SystemClock.uptimeMillis() + BOTTOM_STATUS_MS
        refreshBottomVisibility()
    }

    /**
     * 右下角信息栏显隐（判据 1）。
     *
     * 原来 `buildBottomBar()` 的返回值内联 addView、没有字段引用，所以它**永远可见** ——
     * 真机与模拟器实测：面板收起后底栏（翡翠台 / 2/5 / 立体声 / 暂无正在播出的节目）
     * 30 秒采样里一直在屏。规则改为：
     *   面板在屏 ∨ 换台后 1.5 秒的临时界面在屏 ∨ 临时状态窗口内 → 显示，否则隐藏。
     */
    private fun refreshBottomVisibility() {
        if (!::bottomBar.isInitialized) return
        val mainThread = Looper.myLooper() == Looper.getMainLooper()
        if (!mainThread) {
            handler.post { refreshBottomVisibility() }
            return
        }
        val statusAlive = bottomStatusSticky || SystemClock.uptimeMillis() < bottomStatusUntil
        // 问题 1（用户）：收起动画期间不算"在屏"，右下角信息立即消失
        val panelVisible = ::panel.isInitialized &&
            panel.visibility == View.VISIBLE && !panelDismissing
        val show = panelVisible || statusAlive
        bottomBar.visibility = if (show) View.VISIBLE else View.GONE
        if (show && !bottomStatusSticky && bottomStatusUntil > 0L) {
            handler.removeCallbacks(bottomStatusExpire)
            handler.postDelayed(bottomStatusExpire, BOTTOM_STATUS_MS + 80L)
        }
    }

    private val bottomStatusExpire = Runnable {
        bottomStatusUntil = 0L
        refreshBottomVisibility()
    }

    /**
     * 写入**节目信息**（对齐 1.7 `ChannelInfo` 的两行节目）：
     * 当前节目 = `时间段 + 进度条 + 剩余 + 节目名`，下一节目 = `时间段 + 节目名`。
     *
     * ⚠️ 必须连粘性状态一起清：节目信息会顶掉「播放失败」那行字，若只清文字不清标记，
     * 底栏就会被一个已经看不见的粘性状态永久留在屏上（实测踩到）。
     */
    private fun showProgrammeInfo(cur: EpgProgramme?, next: EpgProgramme?, ch: Channel?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { showProgrammeInfo(cur, next, ch) }
            return
        }
        bottomStatusText.visibility = View.GONE
        bottomProgBlock.visibility = View.VISIBLE
        bottomStatusUntil = 0L
        bottomStatusSticky = false
        bottomEpgCur = cur

        if (cur != null) {
            bottomCurTime.text = "${hhmm(cur.startAt)} – ${hhmm(cur.endAt)}"
            bottomCurTitle.text = cur.title
            bottomCurRemain.text = "剩余 ${cur.remainingMinutes()} 分钟"
        } else {
            bottomCurTime.text = ""
            bottomCurTitle.text = if (ch != null) "无节目信息" else ""
            bottomCurRemain.text = ""
        }
        if (next != null) {
            bottomNextTime.text = "${hhmm(next.startAt)} – ${hhmm(next.endAt)}"
            bottomNextTitle.text = next.title
        } else {
            bottomNextTime.text = ""
            bottomNextTitle.text = ""
        }
        updateBottomProgress()
        refreshBottomVisibility()
    }

    /** 每秒刷新底栏当前节目的进度条（1.7 的细进度条）。 */
    private fun updateBottomProgress() {
        val cur = bottomEpgCur
        if (cur == null || !Prefs.uiShowEpgProgrammeProgress(this)) {
            bottomProgressTrack.visibility = View.INVISIBLE
            return
        }
        val p = cur.progress().coerceIn(0.001f, 0.999f)
        bottomFill.background = UiKit.rounded(Palette.primary(this), 1f, this)
        bottomRest.background = UiKit.rounded(UiKit.withAlpha(Palette.onSurfaceVariant(this), 0.45f), 1f, this)
        bottomFill.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, p)
        bottomRest.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - p)
        bottomProgressTrack.visibility = View.VISIBLE
        bottomCurRemain.text = "剩余 ${cur.remainingMinutes()} 分钟"
    }

    private fun hhmm(ms: Long): String =
        if (ms <= 0L) "--:--" else SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ms))

    private fun refreshTopRight() {
        val quickOpOpen = ::quickOp.isInitialized && quickOp.isOpen
        // 完整块（频道号 ∥ 日期+时间 ∥ 台标）：面板打开 或 换台后 1.5 秒内。
        val showFull = (panel.visibility == View.VISIBLE || tempChannelVisible) && !quickOpOpen

        // 快捷菜单自带顶栏（源名 + 频道号 + 日期时间），必须让位，否则两个顶栏叠字。
        // 对应原版 Visible{} 条件里的 `&& !mainContentState.isQuickOpScreenVisible`。
        topRight.visibility = if (quickOpOpen) View.GONE else View.VISIBLE

        clockModePanel = showFull
        val v = if (showFull) View.VISIBLE else View.GONE
        topNumber.visibility = v
        topDate.visibility = v
        topLogo.visibility = if (showFull && topLogo.drawable != null) View.VISIBLE else View.GONE
        // 不在快捷菜单里时，topTime 总在 —— 面板收起时它就代表"大号 HH:mm"
        topTime.visibility = if (quickOpOpen) View.GONE else View.VISIBLE

        updateClock()
        refreshDatetime()
        // 判据 1：右上角与右下角是同一套"界面在屏才显示"的语义，一起刷新。
        refreshBottomVisibility()
    }

    /**
     * 原版 `DatetimeScreen` 的显隐（每秒由 [clockTick] 调用）。
     *
     * `suppressed` 就是原版 `Visible{}` 的那些取反条件：只要面板 / 快捷菜单 / 临时频道界面
     * 有一个在显示，就轮到它"让位"。
     *
     * ⚠️ 本工程默认配置（`UI_TIME_SHOW_MODE = HIDDEN`）走的是上面那条「只留 HH:mm」的档
     * （《第二步计划》§M5 验收 3 明确要求，见 PROJECT-STATE「与原版有意差异」）。
     * 因此**只有用户把 `UI_TIME_SHOW_MODE` 改成非 HIDDEN 时**本图层才会真正接管右上角；
     * 接管时把 [topTime] 收起来，避免同一位置两个时间叠字。
     */
    private fun refreshDatetime() {
        if (!::datetime.isInitialized) return
        val overlayUp = panel.visibility == View.VISIBLE ||
                tempChannelVisible ||
                (::quickOp.isInitialized && quickOp.isOpen)
        val mode = runCatching { Prefs.uiTimeShowMode(this) }.getOrDefault(UiTimeShowMode.HIDDEN)
        val shown = datetime.tick(suppressed = overlayUp, mode = mode)
        topTime.visibility = if (shown) View.GONE else View.VISIBLE
    }

    /** 远程台标到位：按频道名就地换图（不重建列表，保住焦点与滚动位置）。 */
    /** 频道名 → 直播源自带的台标地址（`tvg-logo`）。问题 5：带源台标的源要走它。 */
    private fun channelLogoUrl(name: String): String? =
        groupList.flatChannels().firstOrNull { it.name == name }?.logo?.takeIf { it.isNotBlank() }

    private fun applyLogosLoaded(channelName: String) {
        // ⚠️ 只读缓存：带 tvg-logo 的源里，按名字重新解析出的候选与真正加载成功的 URL 不同，
        // 再走 bitmapFor 会又返回 null → 台标永远刷不出来（问题 5）。
        val bmp = ChannelLogoStore.cachedBitmapFor(this, channelName, channelLogoUrl(channelName))
            ?: return
        // 当前频道的话，右上角台标也一起换 —— 这步必须**先于**卡片判断：
        // 频道卡片可能还没滚到（未渲染、未注册），但 topLogo 一直在右上角等着。
        if (currentChannel?.name == channelName) {
            topLogo.setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, bmp))
            if (panel.visibility == View.VISIBLE || tempChannelVisible) topLogo.visibility = View.VISIBLE
            // 问题 3/4：左下角信息块（带台标）也要一起补上
            refreshChannelInfoBlock()
        }
        // M6：中栏改成 RecyclerView 后，只有**可见**的卡片存在；滚出去的会在 bind 时
        // 自己带上台标（ChannelColumnView 内部按频道名维护已绑定卡片）。
        channelColumn.updateLogo(channelName, bmp)
    }

    // cardRegistry / registerCard / clearCardRegistry 已随 M6 的 RecyclerView 化移除：
    // 现在由 ChannelColumnView 内部维护「已绑定的可见卡片」。

    // ---------------- 触摸 ----------------

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onScreenTap(e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                showQuickMenu()
            }
        })
    }

    private fun onScreenTap(x: Float, y: Float) {
        if (!::panel.isInitialized) return          // 判据 11-2：崩溃报告屏下视图树未建
        if (dialogView != null) return              // 自绘对话框开着时，画面点击不生效
        // 问题 6：分组管理抽屉开着时，点面板以外的地方 = 关闭抽屉
        if (groupManageView != null) {
            closeGroupManage()
            return
        }
        // 问题 6：投屏抽屉同理
        if (dlnaView != null) {
            closeDlnaCast()
            return
        }
        if (panel.visibility == View.VISIBLE) {
            if (hitPanel(x, y)) resetAutoHide() else hidePanel()
        } else if (x < resources.displayMetrics.widthPixels * 0.32f) {
            // 问题 1：触摸唤醒同样回到上次光标位置
            showPanel()
            restorePanelFocus()
        }
    }

    private fun hitPanel(x: Float, y: Float): Boolean =
        ::panel.isInitialized &&
            x >= panel.left && x <= panel.right && y >= panel.top && y <= panel.bottom

    /**
     * 快捷功能菜单（M8）：显示 / 隐藏。
     *
     * 对应原版 `MainContentState.isQuickOpScreenVisible` 的开关 + `QuickOpScreen`。
     * 触发方式（原版一致）：长按确认键 / MENU / SETTINGS / HELP / H 键；触屏长按亦可。
     */
    private fun showQuickMenu() {
        if (!::quickOp.isInitialized) return        // 判据 11-2：同上（长按屏幕走这条）
        if (quickOp.isOpen) {
            quickOp.close()
            return
        }
        // 频道号 = 全局下标 + 1（原版 QuickOpScreen 的 currentChannelNumberProvider）
        val n = currentChannelNumber()
        quickOp.open(
            channelNumber = if (n > 0) n.toString() else "",
            sourceName = currentSourceName(),
        )
        refreshTopRight()
        // 问题 4：菜单里的频道信息块（带台标）立刻填上内容
        refreshChannelInfoBlock()
    }

    /** 顶栏左侧显示的直播源名（原版 `QuickOpScreeIptvSource` 显示的就是它）。 */
    private fun currentSourceName(): String {
        val url = Prefs.currentSourceUrl(this)
        return Prefs.sources(this).firstOrNull { it.url == url }?.name ?: url
    }

    /**
     * 构造快捷功能菜单（M8）。
     *
     * 7 项、顺序固定、无图标（原版 `QuickOpBtnList.kt`），默认焦点在第一项「投屏」
     * （原版 `Modifier.focusOnLaunched()`）。自动关闭由 [QuickOpView] 内部的原版
     * `ScreenAutoClose` 负责（超时值取设置项，默认 15 秒）。
     */
    private fun buildQuickOp(): View {
        quickOp = QuickOpView(this)
        quickOp.onClosed = {
            refreshTopRight()
            refreshChannelInfoBlock()   // 问题 4：菜单关掉后，左下角那块交回给临时信息
        }
        quickOp.onAction = { action -> onQuickOpAction(action) }
        // 原版「当前订阅源」被选中 → 打开订阅源选择弹层（QuickOpScreen 的 onIptvSourceSelected）。
        // 本工程等价动作：关掉菜单，把面板切到「换源」面板。
        quickOp.onSourceSelect = {
            quickOp.close()
            if (panel.visibility != View.VISIBLE) showPanel()
            onNavSelected(navEntries.indexOfFirst { it.label == NAV_SWITCH_SOURCE }.coerceAtLeast(0))
        }
        // 问题 4：菜单打开时也显示频道信息块（带台标），位置在按钮行上方 ——
        // 对应原版 `QuickOpScreenBottom` 的 `Column { ChannelInfo(...); QuickOpBtnList(...) }`。
        menuInfo = ChannelInfoView(this, showLogo = true)
        quickOp.setInfoView(menuInfo)
        return quickOp
    }

    /**
     * 7 个按钮的动作。
     *
     * ⚠️ 如实标注实现状态：「投屏」属 M11、「更多设置」属 M9，本轮**未实现**，
     * 只弹提示条说明 —— 不假装已实现。其余 5 项已接真实行为。
     */
    private fun onQuickOpAction(action: QuickOpView.Action) {
        when (action) {
            QuickOpView.Action.DLNA -> {
                // 问题 6：真正接通 DLNA 投屏（此前只弹一句「将在 M11 接入」）
                showDlnaCast()
            }

            QuickOpView.Action.EPG -> {
                quickOp.close()
                if (panel.visibility != View.VISIBLE) showPanel()
                setPanelExpanded(true)
                Snackbar.show("节目单界面将在 M7 接入", this)
            }

            QuickOpView.Action.CHANNEL_URL -> {
                quickOp.close()
                if (panel.visibility != View.VISIBLE) showPanel()
                setPanelExpanded(true)
                Snackbar.show("多线路列表将在 M6/M7 接入", this)
            }

            QuickOpView.Action.CONTROLLER -> {
                // 原版「播放控制」是独立面板（倍速/软硬解/时移）。本轮先给**可观测的等价动作**：
                // 播放/暂停切换，并在提示里回显当前是否已切软解。
                if (::player.isInitialized) {
                    if (player.player.isPlaying) {
                        player.pause()
                        Snackbar.show("已暂停（软解=${player.isSoftDecode}）", this)
                    } else {
                        player.play()
                        Snackbar.show("继续播放（软解=${player.isSoftDecode}）", this)
                    }
                }
            }

            QuickOpView.Action.DISPLAY_MODE -> {
                val cur = Prefs.videoPlayerDisplayMode(this)
                val i = DisplayMode.ALL.indexOf(cur).let { if (it < 0) 0 else it }
                val next = DisplayMode.ALL[(i + 1) % DisplayMode.ALL.size]
                currentChannel?.let { DisplayMode.setGlobalDefault(this, it, next) }
                    ?: Prefs.setVideoPlayerDisplayMode(this, next)
                if (::player.isInitialized) player.setDisplayMode(next)
                Snackbar.show("显示模式：${next.label}", this)
            }

            QuickOpView.Action.CLEAR_CACHE -> {
                clearAppCache()
                Snackbar.show("缓存已清除，请重启应用", this)
            }

            QuickOpView.Action.MORE_SETTINGS -> {
                quickOp.close()
                settings.open()
            }
        }
    }

    /** 清缓存（原版「清除缓存」清的是订阅/节目单缓存，文案也是「缓存已清除，请重启应用」）。 */
    private fun clearAppCache() {
        runCatching {
            cacheDir.listFiles()?.forEach { f -> f.deleteRecursively() }
            ChannelLogoStore.clearCache()
        }
        Log.i(Keys.TAG, "已清除缓存：${cacheDir.absolutePath}")
    }

    // ---------------- 视图 ----------------

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.background(this@MainActivity)) }
        rootView = root

        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root.addView(buildTopRight(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.END; topMargin = s(18f); rightMargin = s(28f) })

        // 原版 `DatetimeScreen`：独立的"只显示时间"图层，位置与右上角信息块重合，
        // 但两者**互斥**（见 refreshDatetime）—— 默认 HIDDEN 时这一层根本不出现。
        datetime = DatetimeView(this)
        root.addView(datetime, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.END; topMargin = s(18f); rightMargin = s(28f) })

        // 判据 1：底栏必须留引用 —— 原来直接内联 addView，导致它永远无法被隐藏。
        bottomBar = buildBottomBar()
        // 问题 3：与原版一致，距屏幕边缘 24dp（左右下都要留）
        root.addView(bottomBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = s(24f); rightMargin = s(24f); leftMargin = s(24f)
        })

        // 问题 3/4：左下角带台标的信息块（换台临时信息；快捷菜单那块在 QuickOpView 里）
        tempInfoBar = buildTempInfoBar()
        root.addView(tempInfoBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.START })

        // 判据「菜单没必要左移」：三段式面板自身的左边距必须与 PanelHost 的位移计算共用同一个值，
        // 否则展开时分组栏会留一条 24dp 的"残边"贴在屏幕左边（真机截图就是这个现象）。
        root.addView(buildPanel(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START; topMargin = s(16f); leftMargin = s(panelLeftMarginDp) })

        // 快捷菜单是全屏半透明遮罩（原版 `QuickOpScreen` 的
        // `background.copy(alpha = 0.5f)` + `fillMaxSize`），因此必须是 MATCH_PARENT/MATCH_PARENT，
        // 而不是旧的「底部居中小条」。
          root.addView(buildQuickOp(), FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

          // M7：独立 EPG 弹层（长按左键唤出，原版 EpgScreen 的 Drawer 形态）
          epgPanel = EpgPanelView(this)
          root.addView(epgPanel, FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            // M9：设置页（13 分类，左 216dp + 右内容）
            settings = SettingsView(this).apply {
                onClose = { close() }
                // 问题 7：「自定义直播源」点了没反应 —— 原来只调 hidePanel()/showPanel()，
                // 而设置页是 MATCH_PARENT 且加在面板**之后**（盖在上面），所以看起来毫无变化。
                // 现在：先关设置页，再展开三段式面板并落到「换源」，最后把焦点送进订阅列表。
                onCustomSource = {
                    close()
                    if (panel.visibility != View.VISIBLE) showPanel()
                    navIdx = 0
                    if (navEntries.isNotEmpty()) {
                        navColumn.select(0)
                        onNavSelected(0)
                    }
                    handler.postDelayed({
                        navColumn.getChildAt(0)?.requestFocus()
                        FocusEngine.focusFirstIn(channelColumn)
                    }, 80L)
                    resetAutoHide()
                }
                // 问题 6：真正可用的分组管理（原来只弹一句 Snackbar）
                onGroupManage = { showGroupManage(counterpart = false) }
                onGroupManageAll = { showGroupManage(counterpart = true) }
                // v1.6：应用升级通道（此前「检查更新」只弹一句"已推迟"）
                onCheckUpdate = { checkForUpdate() }
                // 问题 7：自定义节目单（原来这一行没有任何 onClick）
                onCustomEpg = { customEpgDialog() }
                // ⚠️ v1.6：这里原本还有一行 `onCheckUpdate = { Snackbar.show("…M12 已推迟…") }`，
                // 它写在上面那行**之后**，把真正的检查更新覆盖掉了 —— 表现为"点了没反应/只弹一句提示"。
                // 已删除，只保留 v1.6 的 `checkForUpdate()`。
                onPackages = { Snackbar.show("安装包管理：暂未实现（本应用是侧载分发，无需包管理）", this@MainActivity) }
                onResetAll = {
                    Prefs.resetAll(this@MainActivity)
                    Snackbar.show("已恢复初始化，重启后生效", this@MainActivity)
                }
                onSettingChanged = { applySettingsChanged() }
            }
            root.addView(settings, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            // M7：常驻节目进度（开关 UI_SHOW_EPG_PROGRAMME_PERMANENT_PROGRESS，默认关）
            epgProgress = EpgProgressView(this)
          root.addView(epgProgress, FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
          ).apply { gravity = Gravity.BOTTOM or Gravity.START; bottomMargin = s(76f); leftMargin = s(28f) })

          setContentView(root)
    }

    private fun buildPanel(): PanelHost {
        // ⚠️ 面板**初始必须不可见**：原版 `MainContentState._isChannelScreenVisible` 初值就是 false，
        // 由确认键（`onSelect`）置 true 才展开。若不显式 GONE，启动瞬间会把三段式列表糊在画面上。
        // M10：容器改为 `ui/panel/PanelHost`（三态状态机搬家过去）。
        panel = PanelHost(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            panelMaxH = this@MainActivity.panelMaxH
            // 判据「菜单没必要左移」：面板自身左边距要计入位移，否则分组栏移不干净。
            offscreenExtraPx = s(panelLeftMarginDp)
        }

          // M10：分组栏改为 `ui/panel/NavColumnView`（继承 LinearLayout，接口不变）。
          navColumn = NavColumnView(this)
          // 问题 1：光标回到一级 → 记忆置为"在一级"
          // ⚠️ 只在面板**可见**时更新记忆：面板收起后系统会把焦点随手交给别的可聚焦视图，
          // 那些焦点事件会把记忆冲掉（实测：选完台重开面板时又回到了分组栏第 1 项）。
          navColumn.onRowFocused = {
              // ⚠️ 收起动画期间（panelDismissing）也不算：那段时间面板还是 VISIBLE，
              // 焦点可能被系统搬走并触发本回调，会把刚在 [selectChannel] 里写好的
              // 「记忆锚定到刚选中的频道」冲掉（实测踩到）。
              if (::panel.isInitialized && panel.visibility == View.VISIBLE && !panelDismissing) {
                  lastFocusInChannelColumn = false
              }
          }
          // 问题 1「菜单整体做大」：尺寸对齐原版 1.7 的经典选台界面
          //  分组栏 140dp / 中栏 280dp（带台标档）/ 节目单 340dp + 日期列 80dp
          //  频道项两行（频道名 + 当前节目），行高 62dp，台标 46dp
          // M6：中栏 = RecyclerView。
          channelColumn = ChannelColumnView(this).apply {
              channelRowHeight = s(62f)
              logoSize = s(46f)
              onChannelClick = { ch, i -> selectChannel(ch, i, fromPanel = true) }
              // 问题 2：焦点移动**只预览**，不切台；按确认键（onChannelClick）才跳转
              onChannelFocus = { ch, i -> onChannelPreview(ch, i) }
              // 问题 1：焦点进二级 → 一级选中白框透明度减半
              onRowFocused = {
                  if (::navColumn.isInitialized) navColumn.setDimmed(true)
                  lastFocusInChannelColumn = true
              }
            onChannelLongClick = { ch ->
                // 问题 4：长按 = 收藏（唯一语义）。此前 keysPanelExpanded 还绑了 onLongSelect，
                // 于是"长按收藏"的同时又弹出快捷功能菜单。已解绑，这里补一行日志便于无 adb 核对。
                // 问题 5：连线路一起快照，换订阅源后收藏依然可用。
                val added = Prefs.toggleFavorite(this@MainActivity, ch)
                UiLog.i("界面", "长按收藏：${ch.name} → ${if (added) "已收藏" else "已取消收藏"}")
                showBottomStatus(if (added) "已收藏：${ch.name}" else "已取消收藏：${ch.name}")
            }
          }
          epgColumn = EpgColumnView(this).apply {
              onTipClick = { setPanelExpanded(true) }
              onProgrammeClick = { p -> onEpgProgrammeClick(p) }
              setOnFocusChangeListener { _, hasFocus ->
                  if (hasFocus) {
                      epgVisible = true
                      epgFocused = true
                      refreshPanelState()
                  }
                  setFocused(hasFocus)   // 聚焦时列表 268dp → 340dp（原版 EpgProgrammeItemList）
              }
          }

            // M6：列宽必须**实测**后回填，不能拿 s(112f) 之类的设计值硬算位移 ——
          // 一旦列宽随 Density 变化，硬编码就会让三态位移错位（原版用 onSizeChanged 同理）。
          val navWrap = wrap(navColumn, s(140f)).apply { background = columnBg(true) }
          // ⚠️ 中栏**不能**再套 ScrollView：RecyclerView 自己管滚动，套两层会导致
          // 高度测量异常（表现为整列只显示一行）。直接按 panelMaxH 给高度。
          channelColumn.layoutParams = LinearLayout.LayoutParams(s(280f), panelMaxH)
          channelColumn.background = columnBg(false)
          val chWrap = channelColumn
        // M7：第三列内部自己滚动（含 RecyclerView），不再套 ScrollView（同中栏的理由）
        val epgWrap = epgColumn
        // 判据 2A：第三列此前**完全没有背景**（只给了 layoutParams），于是节目表悬在画面上
        // 没有面板底。原版第三列是独立的深色半透明面板底，这里与中栏用同一套 columnBg。
        epgColumn.background = columnBg(false)
        // 判据 1（用户）：初始 state=0（未展开）→ 第三列不显示（含竖提示）
        epgColumn.visibility = View.GONE
          // ⚠️ 宽度要按「节目列表 + 日期列」的总宽给：原版是 268/340dp + 80dp。
          // 若仍按 248dp，日期列会被挤出可视区（M7 实测：dump 里根本找不到它）。
          epgColumn.layoutParams = LinearLayout.LayoutParams(s(420f), panelMaxH)
          navWrapRef = navWrap
          chWrapRef = chWrap
          epgWrapRef = epgWrap
          // M10：三列引用交给 PanelHost，状态机内部的位移/焦点屏蔽由它完成
          panel.navRef = navWrap
          panel.channelRef = chWrap
          panel.epgRef = epgWrap
            panel.onStateChanged = { st ->
                epgColumn.setFocused(st == 2)
                // 判据 1（用户）：节目表**没点开时不要显示**第三列（连"向右查看节目单"竖提示一起），
                // 面板只占"分组栏 + 中栏"两列宽；点开（state ≥ 1）才出现。
                epgColumn.visibility = if (st == 0) View.GONE else View.VISIBLE
            }

          panel.addView(navWrap)
          panel.addView(chWrap)
          panel.addView(epgWrap)

          panel.viewTreeObserver.addOnGlobalLayoutListener(object :
              ViewTreeObserver.OnGlobalLayoutListener {
              override fun onGlobalLayout() {
                  measureColumns()
              }
          })
          return panel
      }

      /** 三列容器的引用（M6 三态位移要用它们的**实测**宽度）。 */
      private var navWrapRef: View? = null
      private var chWrapRef: View? = null
      private var epgWrapRef: View? = null

      /** 实测列宽；为 0 时退回设计值。 */
      private var groupW = 0
      private var channelW = 0

      private fun measureColumns() {
          val n = navWrapRef?.width ?: 0
          val c = chWrapRef?.width ?: 0
          if (n > 0) groupW = n
          if (c > 0) channelW = c
      }

    private fun columnBg(left: Boolean) = UiKit.rounded(
        UiKit.withAlpha(Palette.surface(this@MainActivity), 0.94f), if (left) 10f else 8f, this)

    /**
     * 面板最大高度（三列共用）。
     *
     * 问题 1「菜单整体做大」：原版 1.7 的三段式是**近乎满屏**的
     * （`Box(fillMaxSize).padding(24.dp)` 里的 `fillMaxHeight()`），
     * 本工程此前只给了 0.66，显得矮了一截。这里提到 0.80 —— 留出面板自身的
     * 16dp 上边距与下方空间（真机上 `displayMetrics.heightPixels` 含被隐藏的导航栏，
     * 取太大面板底部会被切掉）。
     */
    private val panelMaxH: Int by lazy {
        (resources.displayMetrics.heightPixels * 0.80f).toInt()
    }

    /** 三段式面板自身的左边距（dp）。位移计算与 LayoutParams 必须共用同一个值。 */
    private val panelLeftMarginDp = 24f

    private fun wrap(col: View, w: Int): ScrollView = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        layoutParams = LinearLayout.LayoutParams(w, panelMaxH)
        addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun buildTopRight(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topNumber = TextView(this).apply {
            setTextColor(Palette.onSurface(this@MainActivity))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            text = "--"
        }
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        topDate = TextView(this).apply {
            setTextColor(Palette.onSurfaceVariant(this@MainActivity))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.END
        }
        topTime = TextView(this).apply {
            setTextColor(Palette.onSurface(this@MainActivity))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.END
        }
        mid.addView(topDate)
        mid.addView(topTime)

        topLogo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.INVISIBLE
        }

        row.addView(topNumber)
        row.addView(mid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = s(6f); marginEnd = s(10f) })
        row.addView(topLogo, LinearLayout.LayoutParams(s(64f), s(36f)))
        topRight = row
        return row
    }

    /**
     * 右下角信息栏（对齐原版 1.7 `ChannelInfo`）。结构：
     * ```
     * [台标] 频道名   1/10  FHD  立体声  0KB/s
     *        15:25 – 16:13  ▓▓▓░░░  剩余 37 分钟  正阳门下(20)
     *        16:13 – 16:55                      正阳门下(21)
     * ```
     * 状态类消息（载入中/解析为空/播放失败…）走 [bottomStatusText] 单行，显示时节目区让位。
     */
    /**
     * 右下角信息栏（问题 3）。
     *
     * 结构逐条对齐原版 1.7 `ClassicChannelScreen.kt` 里包 `ChannelInfo` 的那个 Modifier：
     * ```
     * .align(Alignment.BottomEnd)          → 右下角
     * .fillMaxWidth(0.5f)                  → 占屏宽一半
     * .padding(24.dp)                      → 距屏幕边缘 24dp
     * .background(surface.copy(0.8f), medium)  → **半透明深色底 + 圆角**
     * .padding(horizontal = 20.dp, vertical = 10.dp)  → 内边距
     * ```
     * 之前这里既没有底色（所以字直接糊在画面上），尺寸也只有原版的一半左右。
     */
    private fun buildBottomBar(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            // 底色：与 PanelHost.columnBg 同源的深色，0.8 透明度（原版 surface.copy(0.8f)）
            background = UiKit.rounded(
                UiKit.withAlpha(Palette.surface(this@MainActivity), 0.8f), 10f, this@MainActivity
            )
            setPadding(s(20f), s(10f), s(20f), s(10f))
        }

        // ---- 第一行：频道名 + 角标（**不带台标**）----
        // 问题 2：用户要求去掉这里的台标图标，把宽度留给文字。
        // 这也与原版一致 —— `ClassicChannelScreen` 里那个右下角 ChannelInfo
        // **没有**传 `showChannelLogoProvider`（默认 false）；带台标的是换台临时信息块。
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        bottomTitle = TextView(this).apply {
            setTextColor(Palette.onSurface(this@MainActivity))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)   // 1.7 用 headlineMedium
            typeface = Typeface.DEFAULT_BOLD
            text = "—"
        }
        bottomTags = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // 四个角标用固定 View（每秒只改文本，不重建 —— 重建会把布局抖动带进来）
        tagLine = tag("")
        tagRes = tag("")
        tagAudio = tag("立体声")
        tagSpeed = tag("0KB/s")
        listOf(tagLine, tagRes, tagAudio, tagSpeed).forEach {
            bottomTags.addView(it, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = s(8f) })
        }
        head.addView(bottomTitle)
        head.addView(bottomTags)
        card.addView(head, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.END })

        // ---- 节目信息区（当前节目行 + 下一节目行）----
        bottomProgBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        val curRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        bottomCurTime = bottomInfoText(15f, Palette.onSurfaceVariant(this))
        bottomCurRemain = bottomInfoText(15f, Palette.onSurfaceVariant(this))
        bottomCurTitle = bottomInfoText(15f, Palette.onSurface(this))
        bottomFill = View(this)
        bottomRest = View(this)
        bottomProgressTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bottomFill, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(bottomRest, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            visibility = View.INVISIBLE
        }
        curRow.addView(bottomCurTime, lpWrap().apply { marginEnd = s(10f) })
        curRow.addView(bottomProgressTrack, LinearLayout.LayoutParams(s(150f), s(6f)).apply { marginEnd = s(10f) })
        curRow.addView(bottomCurRemain, lpWrap().apply { marginEnd = s(10f) })
        curRow.addView(bottomCurTitle, lpWrap())
        bottomProgBlock.addView(curRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.END; topMargin = s(6f) })

        val nextRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        bottomNextTime = bottomInfoText(15f, Palette.onSurfaceVariant(this))
        bottomNextTitle = bottomInfoText(15f, Palette.onSurface(this))
        nextRow.addView(bottomNextTime, lpWrap().apply { marginEnd = s(10f) })
        nextRow.addView(bottomNextTitle, lpWrap())
        bottomProgBlock.addView(nextRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.END; topMargin = s(2f) })

        card.addView(bottomProgBlock, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.END; topMargin = s(5f) })

        // ---- 状态行（载入中 / 解析为空 / 播放失败…）----
        bottomStatusText = bottomInfoText(15f, Palette.onSurfaceVariant(this))
        bottomStatusText.visibility = View.GONE
        card.addView(bottomStatusText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.END; topMargin = s(4f) })

        // 外层容器：整行铺开、内容右对齐；卡片宽度 = 屏宽的一半（原版 fillMaxWidth(0.5f)）
        val outer = FrameLayout(this)
        outer.addView(card, FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.5f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END
        ))
        return outer
    }

    /**
     * 左下角临时频道信息（问题 3）：底 = 底部向上的黑色渐变（对齐原版 `ChannelTempScreen`
     * 里给 `ChannelInfo` 加的那条 `Brush.linearGradient(0x00 → 0xB4 → 0xF3)`），
     * 内容在左下角、距屏幕边缘 24dp。
     */
    private fun buildTempInfoBar(): View {
        val bar = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF3000000.toInt(), 0xB4000000.toInt(), 0x00000000)
            )
            setPadding(s(24f), s(24f), s(24f), s(20f))
            visibility = View.GONE
        }
        tempInfo = ChannelInfoView(this, showLogo = true)
        bar.addView(tempInfo, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START))
        return bar
    }

    /**
     * 刷新两处「带台标」的信息块内容（问题 3/4）。
     *
     * ⚠️ 必须在主线程调用：`ChannelLogoStore.bitmapFor` 只做本地缓存/内置资源读取，
     * 但 `bind` 会改 View 树。
     */
    private fun refreshChannelInfoBlock() {
        if (!::tempInfo.isInitialized) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { refreshChannelInfoBlock() }
            return
        }
        val ch = currentChannel
        val idx = if (::player.isInitialized) player.currentLineIdx else 0
        val total = if (::player.isInitialized) player.lineCount else 0
        val speed = if (::player.isInitialized) player.speedKBps else 0
        val res = resolutionLabel()
        val prog = Prefs.uiShowEpgProgrammeProgress(this)

        val epg = ch?.let { epgList?.match(it) }
        val list = epg?.programmeList?.value.orEmpty()
        val now = list.firstOrNull { it.isLive() }
        val next = if (now == null) null else list.firstOrNull { it.startAt >= now.endAt }
        val bmp = ch?.let { ChannelLogoStore.bitmapFor(this, it.name, it.logo) }

        tempInfo.bind(ch, idx, total, res, speed, now, next, null, bmp, prog)
        if (::menuInfo.isInitialized) {
            menuInfo.bind(ch, idx, total, res, speed, now, next, null, bmp, prog)
        }

        // 换台后 1.5 秒内显示（快捷菜单打开时由菜单自己那块负责，避免两块叠在一起）
        val quickOpen = ::quickOp.isInitialized && quickOp.isOpen
        tempInfoBar.visibility =
            if (tempChannelVisible && !quickOpen) View.VISIBLE else View.GONE
        UiLog.d(
            "界面",
            "左下信息块：频道=${ch?.name} 线路=$idx/$total 清晰度=$res 网速=${speed}KB/s " +
                "当前=${now?.title} 下一=${next?.title} 显示=${tempInfoBar.visibility == View.VISIBLE}"
        )
    }

    private fun bottomInfoText(sp: Float, color: Int): TextView = TextView(this).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun lpWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    // ---------------- 数据 ----------------

    private fun loadSource(url: String) {
        showBottomStatus("正在载入直播源…")
        Thread {
            val fetched = fetchSourceBody(url)
            val body = fetched.first
            val err = fetched.second

            // 排查打点：区分「拿不到」与「拿到了但不是 m3u」。中文 tag，R8 不影响字面量。
            android.util.Log.i(
                "源加载",
                "url=$url 正文长度=${body.length} 错误=${err ?: "无"}"
            )

            val parsed = if (body.contains("#EXTINF") || body.contains("#genre#"))
                ChannelParser.parse(body) else emptyList()

            handler.post {
                if (parsed.isEmpty()) {
                    // ⚠️ v1.6 改动：**不再**静默把列表换成演示源。
                    // 原来这里无条件 renderAll(DEMO_M3U)，用户看到的现象就是
                    // 「点开别的订阅源 → 加载不出来 → 自己变成了测试源界面」，
                    // 而且当前正在看的频道列表被无声替换掉，完全没有可诊断的信息。
                    // 现在：解析失败就**保留原列表**，把真实原因挂在底栏（粘性，不自动消失）。
                    val reason = err ?: "内容不是有效的直播源格式（既无 #EXTINF 也无 #genre#）"
                    showBottomStatus("源加载失败：$reason", sticky = true)
                    android.util.Log.w("源加载", "加载失败，保留原列表：$reason")

                    // 只有「一个频道都没有」时才用演示源兜底，保证首次启动/断网时界面不是空的。
                    if (groupList.flatChannels().isEmpty()) {
                        UiLog.i("界面", "当前列表为空 → 用演示源兜底")
                        renderAll(ChannelParser.toGroupList(ChannelParser.parse(DEMO_M3U)))
                        fetchEpg()
                    }
                } else {
                    renderAll(ChannelParser.toGroupList(parsed))
                    // 问题 6：源就位后回到上次频道。**恢复成功才置位** ——
                    // 首次加载失败时不会把机会用掉，等真正加载到目标源时还能再试。
                    if (!lastChannelRestored && restoreLastChannel()) lastChannelRestored = true
                    // M7：源就位后才拉节目单（要用频道名做过滤）
                    fetchEpg()
                }
            }
        }.start()
    }

    /**
     * 抓取直播源正文。
     *
     * 两级回落（v1.6，针对真机反馈「其他订阅源加载不出来」）：
     *  ① **协议回落**：`https` 握手失败时，把协议换成 `http` 再试一次。
     *     IPTV 源大量同时监听 80/443，而旧设备（API 19）的 TLS 栈对现代 CDN 很不友好
     *     —— 换 http 往往直接就通了。
     *  ② 两级都会把「为什么失败」原样带回来给用户看，不再只是一句"解析为空"。
     *
     * @return (正文, 失败原因)；成功时原因为 null。
     */
    private fun fetchSourceBody(url: String): Pair<String, String?> {
        val first = fetchOnce(url)
        if (first.first.isNotBlank()) return first

        val httpUrl = when {
            url.startsWith("https://") -> "http://" + url.removePrefix("https://")
            else -> null
        }
        if (httpUrl != null) {
            android.util.Log.i("源加载", "https 失败（${first.second}）→ 回落 $httpUrl")
            val second = fetchOnce(httpUrl)
            if (second.first.isNotBlank()) return second
            return "" to "${first.second}；回落 http 也失败：${second.second}"
        }
        return first
    }

    /** 单次抓取。失败返回 (空串, 原因)。 */
    private fun fetchOnce(url: String): Pair<String, String?> {
        // 走 Http.open()：与台标/节目单同一条通路 —— 共用 TLS 配置，也共用
        // 「请求开始 / 已回包 / 协商成功（tls + 密码套件）」三点打点，
        // 排查「源加载不出来」时能直接看出卡在握手还是卡在服务端。
        return try {
            val body = Http.open(url, Http.client(userAgent = PlayerController.UA)) { resp ->
                resp.body()?.string().orEmpty()
            }
            body to null
        } catch (e: Exception) {
            "" to (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun renderAll(list: ChannelGroupList) {
        // 判据 3：去掉「🕘️更新时间」伪分组。
        // 有两处来源：① 直播源自身带的按"更新时间"归类的 genre 分组（老张源里就有一个，
        // 名字带 🕘️ 表情）；② 本工程 M10 之前自己往导航列尾部塞的那一项。
        // 这里在**数据层**统一剔除，导航列与换台链路就都看不见它了。
        // 问题 6：再按「频道分组管理 / 全部分组管理」里设定的隐藏名单过滤。
        val hidden = Prefs.iptvChannelGroupHiddenList(this)
        val filtered = list.groups.filterNot { it.name.contains("更新时间") || it.name in hidden }

        // 问题 6（全部分组管理）：记下**当前订阅源**出现过的分组名，
        // 供「全部分组管理」列出其它源的分组（原版 `Configs.iptvSourceGroupNamesMap` 同义）。
        runCatching {
            val url = Prefs.currentSourceUrl(this)
            if (url.isNotBlank()) {
                val names = list.groups.map { it.name }
                    .filterNot { it.contains("更新时间") }   // 伪分组不入名单
                val map = Prefs.iptvSourceGroupNamesMap(this)
                if (map[url] != names) Prefs.setIptvSourceGroupNamesMap(this, map + (url to names))
            }
        }

        groupList = ChannelGroupList(filtered)

        // 问题 5：把收藏快照对齐到本源的频道（补上/刷新线路地址）。
        // 对齐只影响"本源里出现过的"收藏，其它源的收藏原样保留。
        runCatching { Prefs.syncFavoriteChannels(this, groupList.flatChannels()) }

        navEntries.clear()

        navEntries += NavEntry(NAV_SWITCH_SOURCE, R.drawable.ic_swap, null, null)
        navEntries += NavEntry(NAV_FAVORITE, R.drawable.ic_star, null, null)
        filtered.forEach { g ->
            val logo = when {
                g.name.contains("央视") -> "CCTV1"
                g.name.contains("卫视") -> "湖南卫视"
                g.name.contains("港") || g.name.contains("港澳") -> "翡翠台"
                else -> null
            }
            navEntries += NavEntry(g.name, 0, logo, g)
        }
        navEntries += NavEntry(NAV_APPS, R.drawable.ic_apps, null, null)

        navIdx = navEntries.indexOfFirst { it.group != null }.coerceAtLeast(0)
        renderNav()
    }

    // M10：分组栏的条目构造与图标回落搬到了 `ui/panel/NavColumnView`，这里只做适配调用。
    private fun renderNav() {
        val hadNavFocus = navColumn.hasFocus()
        navColumn.render(
            navEntries.map { NavColumnView.Entry(it.label, it.logoName, it.iconRes) },
            navIdx,
        ) { i -> onNavSelected(i) }
        // 判据 C1：NavColumnView 重建期间**抑制**了"焦点被系统移交"引起的伪回调，
        // 中栏不再由那个副作用接手 —— 这里显式同步一次，顺序确定、且不会在 layout 中重入。
        if (navEntries.isNotEmpty()) onNavSelected(navIdx)
        // 判据 12：重建会销毁所有行（含正在聚焦的那一行），焦点搬回原项，否则会被丢回「换源」。
        if (hadNavFocus) navColumn.getChildAt(navIdx)?.requestFocus()
    }



    /** 上次崩溃过就把堆栈显示出来 —— 老设备接不了 adb，只能靠截图定位。 */
    private fun showCrashReportIfAny(): Boolean {
        val sp = getSharedPreferences(App.PREFS, MODE_PRIVATE)
        val report = sp.getString(App.KEY_LAST_CRASH, null) ?: return false

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF141416.toInt())
            setPadding(s(18f), s(18f), s(18f), s(18f))
        }
        col.addView(TextView(this).apply {
            text = "上次启动崩溃了 —— 把下面这段截图发我即可定位"
            setTextColor(0xFFFFB4AB.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(this).apply {
            text = report
            setTextColor(0xFFE3E3E3.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, s(10f), 0, s(10f))
        })
        col.addView(TextView(this).apply {
            text = "清除记录并重新加载"
            setTextColor(0xFF101014.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(s(16f), s(10f), s(16f), s(10f))
            isFocusable = true
            isFocusableInTouchMode = true
            background = UiKit.rounded(Palette.onSurface(this@MainActivity), 8f, this@MainActivity)
            setOnClickListener {
                sp.edit().remove(App.KEY_LAST_CRASH).commit()
                recreate()
            }
        })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF141416.toInt())
            addView(col, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        return true
    }

    /**
     * 判据 5：把当前频道按**全局序号**记下来（原版 `IPTV_LAST_CHANNEL_IDX` 语义）。
     *
     * 只由 [selectChannel] 在"真的换台"时调用 —— 那里已经 `if (currentChannel == ch) return`，
     * 所以焦点在频道列上移动时不会反复写盘。
     */
    /**
     * 问题 6（用户要求：设置里那个入口"没链接好"）：**记住上次频道改为始终生效**，
     * 不再看 `IPTV_LAST_CHANNEL_REMEMBER_ENABLE`（该开关已按要求从设置页删除）。
     *
     * 只由 [selectChannel] 在"真的换台"时调用 —— 那里已经 `if (currentChannel == ch) return`，
     * 所以焦点在频道列上移动时不会反复写盘。
     */
    private fun rememberLastChannel() {
        val gi = groupList.flatChannels().indexOfFirst { it.name == currentChannel?.name }
        if (gi >= 0) Prefs.setIptvLastChannelIdx(this, gi)
    }

    /**
     * 问题 6：启动时回到上次观看的频道。
     *
     * ⚠️ 本轮**修掉一个真实缺陷**：原来这里是无条件 `lastChannelRestored = true` ——
     * 只要"第一次源就位"发生过就把机会用掉了。而启动时第一次加载**可能失败**
     * （例如 API 19 上订阅源 TLS 握手失败 → 回落到演示源），此时 `groupList` 里
     * 根本没有目标频道，恢复静默失败，之后再也不会重试 → 用户看到的就是
     * 「明明开了记住上次频道，重开却没回到上次频道」。
     *
     * 现在：**恢复成功才置位**；手动换源时把序号重置为 0（与原版 `iptvLastChannelIdx = 0` 一致），
     * 于是"重试"不会跨源乱跳。
     *
     * @return 是否真的跳转过
     */
    private fun restoreLastChannel(): Boolean {
        val all = groupList.flatChannels()
        val gi = Prefs.iptvLastChannelIdx(this)
        if (all.isEmpty() || gi !in all.indices) {
            UiLog.i("界面", "恢复上次频道：跳过（共 ${all.size} 个频道，记住的序号 ${gi + 1}）")
            return false
        }
        jumpToChannel(all[gi])
        UiLog.i("界面", "回到上次频道：${all[gi].name}（全局序号 ${gi + 1}）")
        return true
    }

    /** 中栏按导航项切换内容 —— 原版就是这个逻辑：换源/系统应用会顶替掉频道列。 */
    private fun onNavSelected(i: Int) {
        if (navIdx != i) {
            navIdx = i
            // 判据 12 修复：**只改选中态，绝不重建navColumn**。
            // 原来这里调 renderNav() → NavColumnView.render() → removeAllViews()，会把
            // 正在聚焦的那一行销毁，焦点被系统丢回「换源」→ 真机现象就是
            // 「按上下键只能在 换源 / 我的收藏 之间来回，下不去」。
            navColumn.select(i)
        }
        val entry = navEntries.getOrNull(i) ?: return
        // 诊断：无 adb 的真机（G750-T20）也靠这行看分组栏焦点是否走得动（判据 12）。
        UiLog.i("界面", "导航项选中 → ${entry.label}（第 ${i + 1}/${navEntries.size} 项）")
        when (entry.label) {
            NAV_SWITCH_SOURCE -> renderSourcePanel()
            NAV_FAVORITE -> renderFavoritesPanel()
            NAV_APPS -> renderAppsPanel()
            else -> entry.group?.let {
                setPanelExpanded(false)
                renderChannels(it)
            }
        }
    }

    /** 通用列表面板项：源列表、应用列表复用同一套样式。 */
    private fun panelRow(
        title: String,
        subtitle: String?,
        icon: Drawable?,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(s(8f), 0, s(8f), 0)
        }
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, s(50f)
        ).apply { bottomMargin = s(4f) }

        val iconView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (icon != null) setImageDrawable(icon) else visibility = View.INVISIBLE
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(this).apply {
            text = title
            setTextColor(Palette.onSurface(this@MainActivity))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        texts.addView(titleView)
        if (subtitle != null) {
            texts.addView(TextView(this).apply {
                text = subtitle
                setTextColor(Palette.onSurfaceVariant(this@MainActivity))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }

        row.addView(iconView, LinearLayout.LayoutParams(s(34f), s(34f)).apply { marginEnd = s(8f) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val bgNormal = UiKit.rounded(UiKit.withAlpha(Palette.surfaceContainer(this@MainActivity), 0.92f), 8f, this)
        val bgFocus = UiKit.rounded(Palette.onSurface(this@MainActivity), 8f, this)
        row.background = bgNormal
        row.setOnFocusChangeListener { _, hasFocus ->
            row.background = if (hasFocus) bgFocus else bgNormal
            titleView.setTextColor(if (hasFocus) 0xFF101014.toInt() else Palette.onSurface(this@MainActivity))
            for (k in 1 until texts.childCount) {
                (texts.getChildAt(k) as? TextView)?.setTextColor(
                    if (hasFocus) 0xFF3C4043.toInt() else Palette.onSurfaceVariant(this@MainActivity)
                )
            }
        }
        row.setOnClickListener { onClick() }
        if (onLongClick != null) row.setOnLongClickListener { onLongClick(); true }
        return row
    }

    /** 换源面板：中栏换成订阅列表（对应原版 ClassicSourcePanel 的位置）。 */
    // M10：行模型搬到了 `ui/panel/SourcePanelView`
    private fun renderSourcePanel() {
        channelColumn.submit(
            sourcePanel.rows(
                Prefs.sources(this), Prefs.currentSourceUrl(this),
                onSwitch = { switchSource(it) },
                onRemove = { confirmRemoveSource(it) },
                onAdd = { addSourceDialog() },
            )
        )
    }

    private fun switchSource(src: IptvSource) {
        Prefs.setCurrentSource(this, src.url)
        currentChannel = null
        // 问题 6：手动换源时把"上次频道序号"重置为 0 —— 与原版
        // `IptvSourceScreen` 的 `iptvLastChannelIdx = 0` 一致；
        // 否则新源会拿旧源的序号去跳，跳到风马牛不相及的频道。
        Prefs.setIptvLastChannelIdx(this, 0)
        lastChannelRestored = false
        loadSource(src.url)
    }

    private fun confirmRemoveSource(src: IptvSource) {
        // 判据「这风格没统一」：原来是 AlertDialog（系统浅色 Material），已改为自绘应用风格对话框。
        val built = AppDialog.buildConfirm(
            this, "删除直播源", "${src.name}\n${src.url}", okLabel = "删除",
            onCancel = { closeAppDialog() },
            onOk = {
                Prefs.removeSource(this, src.url)
                closeAppDialog()
                renderSourcePanel()
            },
        )
        showAppDialog(built)
    }

    /**
     * 问题 7：自定义节目单。
     *
     * 原版 1.7 的这一项打开 `EpgSourceScreen`（一份可选的节目单来源列表 + 新增）。
     * 本工程按「等价的录入入口」实现：输入名称与 XMLTV 地址 → 记入用户自定义节目单列表
     * 并立即切为当前来源、重拉节目单。**差异已在报告中标注为「接受近似」**：
     * 暂未做 1.7 那种「多来源列表 + 逐条删除」的管理界面。
     */
    private fun customEpgDialog() {
        val built = AppDialog.buildInput(
            this, "自定义节目单", "名称，例如：备用节目单", "XMLTV / e.xml 地址",
            okLabel = "保存",
            onCancel = { closeAppDialog() },
            onOk = { name, url ->
                if (url.isBlank()) {
                    showBottomStatus("地址不能为空")
                } else {
                    val src = EpgSource(name.ifBlank { url }, url)
                    Prefs.setEpgSourceList(
                        this, (Prefs.epgSourceList(this) + src).distinctBy { it.url })
                    Prefs.setEpgSourceCurrent(this, src)
                    closeAppDialog()
                    runCatching { settings.refreshNow() }
                    fetchEpg()
                    Snackbar.show("节目单来源已切换：${src.name}", this)
                }
            },
        )
        showAppDialog(built)
    }

    private fun addSourceDialog() {
        // 判据「这风格没统一」（真机截图：白底灰框的系统对话框，与深色 UI 冲突）。
        val built = AppDialog.buildInput(
            this, "添加直播源", "名称，例如：备用源", "M3U / TXT 地址", okLabel = "添加",
            onCancel = { closeAppDialog() },
            onOk = { name, url ->
                if (url.isBlank()) {
                    showBottomStatus("地址不能为空")
                } else {
                    Prefs.addSource(this, IptvSource(name.ifBlank { url }, url))
                    closeAppDialog()
                    renderSourcePanel()
                }
            },
        )
        showAppDialog(built)
    }

    // ---------------- 应用升级通道（v1.6，补齐此前推迟的 M11/M12） ----------------

    /** 设置 → 更新 → 检查更新。 */
    private fun checkForUpdate() {
        showBottomStatus("正在检查更新…", sticky = true)
        val line = Prefs.updateDownloadLine(this)
        Thread {
            val (info, err) = UpdateRepository.check(line)
            handler.post {
                when {
                    err != null -> showBottomStatus("检查更新失败：$err", sticky = true)
                    info == null -> showBottomStatus("检查更新失败：版本清单为空", sticky = true)
                    !info.isNewerThanCurrent() ->
                        Snackbar.show("已是最新版本（${BuildConfigCompat.VERSION_NAME}）", this)
                    else -> showUpdateDialog(info)
                }
            }
        }.start()
    }

    /** 有新版本时的确认框（应用风格自绘，与「添加直播源」同一套）。 */
    private fun showUpdateDialog(info: UpdateRepository.UpdateInfo) {
        val msg = buildString {
            append("当前版本：").append(BuildConfigCompat.VERSION_NAME).append('\n')
            append("最新版本：").append(info.versionName).append('\n')
            append("安装包大小：").append(UpdateRepository.humanSize(info.sizeBytes)).append("\n\n")
            append("更新内容：\n").append(info.notes)
        }
        val built = AppDialog.buildMessage(
            this, "发现新版本", msg,
            listOf(
                "稍后" to { closeAppDialog() },
                "立即更新" to { closeAppDialog(); downloadUpdate(info) },
            ),
        )
        showAppDialog(built)
    }

    /** 下载安装包（后台线程 + 底栏进度），完成后拉起系统安装器。 */
    private fun downloadUpdate(info: UpdateRepository.UpdateInfo) {
        showBottomStatus("正在下载更新…", sticky = true)
        val line = Prefs.updateDownloadLine(this)
        Thread {
            var lastPercent = -1
            val (file, err) = UpdateRepository.download(this, info, line) { p ->
                if (p < 0f) return@download
                val percent = (p * 100).toInt()
                // 节流：只在整数百分比变化时回主线程刷新，避免每 64KB 刷一次底栏
                if (percent != lastPercent) {
                    lastPercent = percent
                    handler.post { showBottomStatus("正在下载更新… $percent%", sticky = true) }
                }
            }
            handler.post {
                if (file == null) {
                    showBottomStatus("更新下载失败：$err", sticky = true)
                } else {
                    showBottomStatus("更新已下载，正在拉起安装…")
                    installApk(file)
                }
            }
        }.start()
    }

    /**
     * 拉起系统安装器。
     *
     * API 24 起不允许把 `file://` 交给别的应用（`FileUriExposedException`），必须走
     * `FileProvider` 的 `content://`；API 19~23 用 `file://` 即可。两条路都保留。
     */
    private fun installApk(file: java.io.File) {
        val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } else {
            android.net.Uri.fromFile(file)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }.onFailure {
            UiLog.w("界面", "拉起安装器失败：${it.javaClass.simpleName}")
            showBottomStatus("无法拉起安装器：${it.javaClass.simpleName}", sticky = true)
        }
    }

    // ---------------- 分组管理（问题 6） ----------------

    /** 当前打开的分组管理抽屉（非 null 时按键放行给控件、返回键只关它）。 */
    private var groupManageView: GroupManageView? = null

    /**
     * 打开分组管理。
     *
     * @param counterpart false = 「频道分组管理」（只列**当前订阅源**的分组，对齐原版
     *        `ChannelGroupManageScreen`）；true = 「全部分组管理」（按订阅源分组列出，对齐
     *        `SourceGroupManageScreen`）。
     *
     * 显隐落在 `Prefs.iptvSourceGroupHiddenMap`（订阅源地址 → 隐藏分组名），
     * 与原版 `Configs.iptvSourceGroupHiddenMap` 一致：切源、重启后保持。
     */
    private fun showGroupManage(counterpart: Boolean) {
        closeGroupManage()
        val view = GroupManageView(this)
        groupManageView = view
        view.onClose = { closeGroupManage() }
        view.show(
            title = if (counterpart) "全部分组管理" else "频道分组管理",
            hint = if (counterpart) "统一管理所有订阅源的分组显示、隐藏（切换源、重启后保持）· 确认键切换"
            else "管理当前订阅源的分组显示、隐藏状态 · 确认键切换",
            provider = { if (counterpart) allSourceGroupRows() else currentSourceGroupRows() },
        )
        rootView.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.bringToFront()
        view.post { view.requestFocus() }
    }

    private fun closeGroupManage() {
        val v = groupManageView ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        groupManageView = null
        UiLog.i("设置", "分组管理已关闭")
    }

    /** 「频道分组管理」的行：只列当前订阅源的分组。 */
    private fun currentSourceGroupRows(): List<GroupManageView.Row> {
        val hidden = Prefs.iptvChannelGroupHiddenList(this)
        return groupList.groups.map { g ->
            GroupManageView.Row.Toggle(g.name, g.name in hidden) {
                Prefs.setIptvChannelGroupHiddenList(this, toggleName(hidden, g.name))
                groupManageView?.render()
                renderAll(groupList)          // 分组显隐立即生效
                UiLog.i("设置", "分组显隐：${g.name} → ${if (g.name in hidden) "显示" else "隐藏"}")
            }
        }
    }

    /** 「全部分组管理」的行：按订阅源分段。 */
    private fun allSourceGroupRows(): List<GroupManageView.Row> {
        val namesMap = Prefs.iptvSourceGroupNamesMap(this)
        val hiddenMap = Prefs.iptvSourceGroupHiddenMap(this)
        val rows = ArrayList<GroupManageView.Row>()
        Prefs.sources(this).forEach { src ->
            rows.add(GroupManageView.Row.Header("${src.name}　${src.url}"))
            val groups = namesMap[src.url].orEmpty()
            if (groups.isEmpty()) {
                rows.add(GroupManageView.Row.Toggle("（尚未加载过该订阅源，打开一次后可管理分组）", true) {})
            } else {
                val hidden = hiddenMap[src.url].orEmpty().toSet()
                groups.forEach { name ->
                    rows.add(GroupManageView.Row.Toggle(name, name in hidden) {
                        val now = toggleName(hidden, name)
                        Prefs.setIptvSourceGroupHiddenMap(
                            this, hiddenMap + (src.url to now.toList()))
                        groupManageView?.render()
                        if (src.url == Prefs.currentSourceUrl(this)) renderAll(groupList)
                    })
                }
            }
        }
        return rows
    }

    private fun toggleName(set: Set<String>, name: String): Set<String> =
        if (name in set) set - name else set + name

    // ---------------- 投屏（DLNA，问题 6） ----------------

    /** 当前打开的投屏抽屉。 */
    private var dlnaView: DlnaCastView? = null

    /** 打开「投送到设备」（对应原版 `DlnaCastScreen`，由快捷菜单第一项「投屏」进入）。 */
    private fun showDlnaCast() {
        if (dlnaView != null) return
        if (::quickOp.isInitialized && quickOp.isOpen) quickOp.close()
        val view = DlnaCastView(this)
        dlnaView = view
        view.onClose = { closeDlnaCast() }
        view.onRescan = { DlnaCastRepository.scan(this) }
        view.onSelectDevice = { device -> onDlnaDeviceSelected(device) }
        DlnaCastRepository.onChanged = { handler.post { dlnaView?.render() } }
        rootView.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.bringToFront()
        view.render()
        view.post { view.requestFocus() }
        // 打开即搜索（原版 `LaunchedEffect { DlnaCastRepository.scan(context) }`）
        DlnaCastRepository.scan(this)
    }

    private fun closeDlnaCast() {
        val v = dlnaView ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        dlnaView = null
        DlnaCastRepository.onChanged = null
    }

    /** 选中设备：已投送同一台 → 断开；否则投送当前频道。 */
    private fun onDlnaDeviceSelected(device: DlnaDevice) {
        val view = dlnaView ?: return
        view.setBusy(true)
        if (DlnaCastRepository.castingDeviceId == device.id) {
            DlnaCastRepository.stop(device) { ok ->
                view.setBusy(false)
                Snackbar.show(if (ok) "已停止投送" else "停止投送失败", this)
            }
            return
        }
        val ch = currentChannel
        if (ch == null) {
            view.setBusy(false)
            Snackbar.show("当前没有正在播放的频道", this)
            return
        }
        val idx = if (::player.isInitialized) player.currentLineIdx else 0
        DlnaCastRepository.cast(device, ch, idx) { result ->
            view.setBusy(false)
            view.render()
            result.onSuccess {
                closeDlnaCast()
                Snackbar.show("已投送到 ${device.displayName}", this)
            }.onFailure {
                Snackbar.show("投送失败：${it.message ?: "请重试"}", this)
            }
        }
    }

    // ---------------- 应用风格对话框的挂载/卸载 ----------------

    /** 当前自绘对话框（非 null 时：按键放行给控件、返回键只关它、画面点击不生效）。 */
    private var dialogView: View? = null

    private fun showAppDialog(built: AppDialog.Built) {
        closeAppDialog()
        dialogView = built.root
        rootView.addView(built.root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        built.root.bringToFront()
        // 对话框开着时不要让面板超时把背景收掉（输入过程中面板消失会很突兀）
        panelAutoClose.cancel()
        built.firstFocus?.post { built.firstFocus?.requestFocus() }
    }

    private fun closeAppDialog() {
        val v = dialogView ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        dialogView = null
        resetAutoHide()
    }

    private fun renderChannels(group: ChannelGroup) {
        setPanelState(0)
        val progs = programmesOf(group.channels)   // 判据 3：两行频道项的第二行 = 当前节目
        val rows = ArrayList<ChannelColumnView.Row>(group.channels.size)
        group.channels.forEach { ch ->
            val p = progs[ch.name]
            rows.add(ChannelColumnView.Row.ChannelRow(
                channel = ch,
                badge = if (ch.lineCount > 1) "${ch.lineCount} 条线路" else "直播",
                current = (ch == currentChannel),
                programme = p?.first,
                progress = p?.second ?: -1f,
            ))
        }
        channelColumn.submit(rows)
        // ⚠️ 不能调 renderEpgTip()（= showTipOnly）：它会把第三列重设为提示条、
        // 把刚渲染好的节目列表藏回 GONE，于是右键进第三列时取焦必然失败、焦点一直
        // 停在频道列卡片上（M7 实测：状态到了 2，focused 节点仍是频道列）。
        // 第三列始终跟随 currentChannel，这里直接按当前频道重渲。
        renderEpgForCurrent()
    }

    /** 我的收藏：跨分组汇总，长按取消。 */
    /**
     * 「我的收藏」面板。
     *
     * 问题 5（用户要求：切了订阅源后收藏仍在）：改成**以收藏快照为准**渲染 ——
     * 快照里带线路地址，所以换到别的订阅源后，收藏列表依旧完整、而且点开还能直接播。
     * 旧实现是 `Prefs.favorites()`（只有名字）+ 在当前源的频道里按名字找，
     * 换源后凡是不在新源里的收藏都会被 `mapNotNull` 静默丢掉 —— 真机现象就是"收藏没了"。
     */
    private fun renderFavoritesPanel() {
        setPanelState(0)
        renderEpgTip()
        val items = Prefs.iptvChannelFavoriteItems(this)
        val all = groupList.flatChannels()

        val rows = ArrayList<ChannelColumnView.Row>()
        if (items.isEmpty()) {
            rows.add(ChannelColumnView.Row.TextRow(
                "还没有收藏", "在频道上长按即可收藏 / 取消", null, onClick = {}))
        } else {
            items.forEach { fav ->
                // ① 当前订阅源里有同名频道 → 用当前源的（线路最新）
                // ② 没有 → 用收藏快照（跨源保留）
                val ch = all.firstOrNull { it.name == fav.name } ?: fav.toChannelOrNull()
                if (ch == null) {
                    rows.add(ChannelColumnView.Row.TextRow(
                        fav.name, "该收藏没有可用线路（旧版本只存了频道名，重新收藏一次即可）",
                        null, onClick = {}))
                } else {
                    rows.add(ChannelColumnView.Row.ChannelRow(
                        channel = ch,
                        badge = if (all.any { it.name == fav.name }) "长按取消收藏" else "来自其它订阅源 · 长按取消收藏",
                        current = (ch.name == currentChannel?.name),
                    ))
                }
            }
        }
        channelColumn.submit(rows)
        UiLog.i("界面", "收藏面板：共 ${items.size} 条快照，当前源命中 ${items.count { f -> all.any { it.name == f.name } }} 条")
    }

    /** 系统应用：装了什么列什么，点击跳转出去。 */
    // M10：应用查询与行模型搬到了 `ui/panel/AppLauncherView`
    private fun renderAppsPanel() {
        setPanelState(0)
        renderEpgTip()
        channelColumn.submit(appLauncher.loadingRows())

        Thread {
            val apps = appLauncher.queryApps(packageName)
            handler.post {
                if (isFinishing || isDestroyed) return@post
                channelColumn.submit(appLauncher.rows(apps) { launchApp(it) })
                showBottomStatus("共 ${apps.size} 个可启动应用")
            }
        }.start()
    }

    private fun launchApp(app: SourcePanelView.AppItem) {
        runCatching {
            packageManager.getLaunchIntentForPackage(app.pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(it)
            }
        }.onFailure { showBottomStatus("无法启动 ${app.label}") }
    }


    /**
     * 重置第三列（换源/换分组时）：回到竖排提示条，等节目单渲染。
     *
     * 原版未展开时就是那句竖排「向右查看节目单」；M7 之后节目单数据到位会被 [renderEpgForCurrent]
     * 换成节目列表。
     */
    private fun renderEpgTip() {
        epgColumn.showTipOnly()
    }

    private fun selectChannel(ch: Channel, idx: Int, fromPanel: Boolean = false) {
        channelIdx = idx
        if (currentChannel == ch) return
        currentChannel = ch
        rememberLastChannel()   // 判据 5：只在这里（真的换台）落库，避免每次焦点移动都写盘
        bottomTitle.text = ch.name
        // 问题 5：右上角 / 左下角台标也要优先用**源自带的 tvg-logo**，
        // 否则会出现"列表里有台标、右下角却是空的"这种不一致（同一个频道两套候选）。
        topLogo.setImageDrawable(
            ChannelLogoStore.bitmapFor(this, ch.name, ch.logo)
                ?.let { android.graphics.drawable.BitmapDrawable(resources, it) })
        // ⚠️ 可见性**不能在这里直接置 VISIBLE** —— 那样面板收起时台标仍在右上角，
        // 与「收起后只留大号 HH:mm」冲突（原版 setClockMode 行为）。
        // 上一轮没暴露是因为测试源「测试台01」没有台标；换成真实源（翡翠台）才看得见。
        // 统一交给 refreshTopRight() 按 showFull 决定。
        refreshTopRight()
        setTopNumberPlaceholder()
        // 问题 3/4：左下角信息块（带台标）随换台刷新
        refreshChannelInfoBlock()
        // 节目信息由 renderEpgForCurrent() 填；节目单还没到时给一句状态
        if (epgList == null) showBottomStatus("节目单加载中 · 共 ${ch.lineCount} 条线路")
        // M5：必须改走带 Channel 的重载 —— 只有这样 PlayerController.currentChannel 才有值，
        // 「按频道名记忆的显示比例」与"正在播出"进度才算得出来（旧写法传 urlList，那个字段恒为 null）。
        player.play(ch)
        // 问题 1（用户）：在菜单里选中频道后，**立即**收起菜单与右下角信息 ——
        // 不要再挂 1.5 秒的临时信息块（那是给"用上下键盲换台"看的）。
        if (fromPanel) {
            tempChannelVisible = false
            handler.removeCallbacks(tempChannelHide)
            // 问题 1：记忆锚定到刚选中的频道 —— 下次唤出面板时光标就落在这里
            lastFocusedChannelName = ch.name
            lastFocusInChannelColumn = true
            hidePanel()
            if (::bottomBar.isInitialized) bottomBar.visibility = View.GONE
            UiLog.i("界面", "面板内选台 → 立即收起菜单与右下角信息")
        } else {
            // 原版 `MainContentState.isTempChannelScreenVisible`：换台后显示 1.5 秒临时频道界面
            showTempChannelInfo()
        }
        resetAutoHide()
        // M7：换台后立刻刷新第三列与底栏节目信息
        renderEpgForCurrent()
    }

    /**
     * 三段式的**三态**平移（M6 核心）。
     *
     * 原版 `ClassicChannelScreen.kt`：
     * ```
     * offsetXPx = if (epgListVisible)
     *     if (epgListIsFocused) -groupWidth - channelListWidth else -groupWidth
     * else 0
     * ```
     * | 状态 | offsetX | 屏幕上看得见 |
     * |------|---------|-------------|
     * | 0    | 0                | 分组栏 + 频道列 |
     * | 1    | -groupWidth      | 频道列 + 节目单列 |
     * | 2    | -(group+channel) | 节目单列为主 |
     *
     * 三个必须处理的细节（原版由 Compose 布局系统天然规避，View 侧要手写）：
     * 1. 宽度**实测**回填 —— 见 [measureColumns]，不用设计值硬算；
     * 2. 位移后落在屏幕外的列要挡掉焦点（[blockOffscreenFocus]），
     *    否则方向键会把焦点移到看不见的列上；
     * 3. 动画期间不响应新的方向键 —— 连按会叠加多个 animate()（[panelAnimating]）。
     */
    /** M10：三态位移交给 `ui/panel/PanelHost`（逻辑原样搬过去了）。 */
    private fun setPanelState(state: Int) {
        panelState = state.coerceIn(0, 2)
        panel.setState(state)
    }

    /** M10：屏幕外的列不让抢焦点 —— 实现在 `PanelHost.blockOffscreenFocus()`。 */
    private fun blockOffscreenFocus() {
        panel.blockOffscreenFocus()
    }

    private fun setColumnFocusable(col: View?, enabled: Boolean) {
        // descendantFocusability 是 ViewGroup 的属性：列**容器**必须是 ViewGroup。
        // ScrollView / RecyclerView 都是；万一传进来的是普通 View 就跳过（不崩）。
        val vg = col as? ViewGroup ?: return
        vg.descendantFocusability =
            if (enabled) ViewGroup.FOCUS_AFTER_DESCENDANTS
            else ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun setPanelExpanded(expanded: Boolean) {
        epgVisible = expanded
        if (!expanded) epgFocused = false
        refreshPanelState()
    }

    /** 节目单列是否已展开（对应原版 `epgListVisible`）。 */
    private var epgVisible = false
    /** 焦点是否落在节目单列里（对应原版 `epgListIsFocused`）。 */
    private var epgFocused = false

    /** 由「是否展开 + 焦点在哪一列」推出三态，与原版 `offsetXPx` 的表达式同构。 */
      private fun refreshPanelState() {
          val st = if (!epgVisible) 0 else if (epgFocused) 2 else 1
          // 列表宽度随聚焦切换（268dp ↔ 340dp），要在位移之前改，避免动画期间布局跳动
          epgColumn.setFocused(st == 2)
          setPanelState(st)
      }

    /**
     * 面板里的**右键**：状态 0 → 1（展开节目单列，焦点仍在频道列）→ 再把焦点送进节目单列（→ 2）。
     *
     * 原版是 `focusProperties { exit = { FocusRequester.Cancel } }` 拦下"从频道列右移"这件事；
     * View 侧只能自己判断焦点在哪一列再决定是"移焦点"还是"切状态"。
     */
    private fun onPanelRight(): Boolean {
        // ⚠️ 焦点还在**分组栏**时必须放行：右键的语义是"把焦点移进频道列"，
        // 这里若拦截，焦点就永远进不了频道列（M7 实测：源加载后一台都没选中）。
        if (focusInside(navColumn)) {
            // 右键：把焦点送进频道列。
            // ⚠️ 必须**显式**做：中栏换成 RecyclerView 后，View 自带的焦点搜索
            // 从分组栏往右找不到可聚焦项（焦点停在 RecyclerView 容器上、子项拿不到焦点），
            // 于是"选中频道"永远不触发 —— M7 实测踩到。
            return FocusEngine.focusFirstIn(channelColumn)
                || FocusEngine.restoreFocus(channelColumn, currentChannel?.name)
        }

            if (!epgVisible) {
                // 判据「菜单没必要左移」（真机截图）：第三列**没有任何节目**、只有
                // "向右查看节目单"提示时，展开只是把分组栏白白推出屏幕 ——
                // 用户看到的就是"菜单被推左、右边却什么都没有"。没有节目就不展开。
                if (!epgColumn.hasProgrammes()) {
                    UiLog.i("界面", "第三列无节目 → 不展开（分组栏保持不动）")
                    return true
                }
                epgVisible = true
                refreshPanelState()
                return true
            }
          if (!epgFocused) {
              // ⚠️ 必须**先**把焦点送进第三列，再切状态：
              // setPanelState 会立刻 blockOffscreenFocus()，状态 2 下频道列不可聚焦，
              // 若此时焦点还在频道列会被系统剥夺（M7 实测：状态到了 2 但列表拿不到焦点）。
              // 另外 focusFirstIn 遍历不到 RecyclerView 里的节目项，必须走 EpgColumnView 自己的入口。
              epgColumn.focusProgrammeList()
              epgFocused = true
              refreshPanelState()
              // ⚠️ 还要在**动画结束 + RecyclerView layout 完成之后**再补一次：
              // 第一帧 recycler 通常还没有子 View，取焦必然失败（M7 实测：
              // 状态到了 2，focused 节点却仍是频道列卡片）。
              handler.removeCallbacks(epgFocusRetry)
              handler.postDelayed(epgFocusRetry, 260L)
              return true
          }
          return false   // 已在节目单列且已展开：放行给焦点系统
      }

    /** 焦点是否在某一列里（沿 parent 链往上找）。 */
    private fun focusInside(col: View?): Boolean {
        if (col == null) return false
        val f = currentFocus ?: return false
        var p: ViewParent? = f.parent
        while (p != null) {
            if (p === col) return true
            p = p.parent
        }
        return false
    }

    /** 面板里的左键：2 → 1 → 0（再左，且焦点已在分组栏，就收起整个面板）。 */
    private fun onPanelLeft(): Boolean {
        when {
            epgFocused -> {
                epgFocused = false
                refreshPanelState()
                FocusEngine.restoreFocus(channelColumn, currentChannel?.name)
                return true
            }
            epgVisible -> {
                epgVisible = false
                refreshPanelState()
                return true
            }
            focusInside(channelColumn) -> {
                // 判据 2（用户）：中栏是**第二级**，按左键必须退回第一级（分组栏）。
                // 系统自带的焦点搜索从 RecyclerView 子项往左找不到 ScrollView 里的分组项
                // （与 M7「从分组栏往右进不了中栏」同一个原因），所以必须显式搬焦点。
                val row = navColumn.getChildAt(navIdx)
                return if (row != null && row.requestFocus()) true else FocusEngine.focusFirstIn(navColumn)
            }
            else -> {
                hidePanel()
                return true
            }
        }
    }

    // ---------------- 底栏与顶部 ----------------

    private fun bottomTagsUpdate(resolution: String) {
        resolutionText = resolution
        refreshTags()
    }

    private var resolutionText = ""

    private fun refreshTags() {
        // 判据 4：角标顺序对齐 1.7 —— 线路 / 清晰度 / 立体声 / 网速（都是固定 View，只改文本）
        val hasPlayer = ::player.isInitialized
        val total = if (hasPlayer) player.lineCount else 0
        tagLine.text = if (hasPlayer) "${player.currentLineIdx + 1}/$total" else ""
        tagLine.visibility = if (total > 0) View.VISIBLE else View.GONE
        val res = resolutionLabel()
        tagRes.text = res ?: ""
        tagRes.visibility = if (res != null) View.VISIBLE else View.GONE
        tagAudio.visibility = View.VISIBLE
        tagSpeed.text = "${if (hasPlayer) player.speedKBps else 0}KB/s"
        tagSpeed.visibility = View.VISIBLE
    }

    /**
     * 清晰度角标（对齐 1.7：1920 以上显示 `FHD`）。
     * 取不到时返回 null（不显示该角标），而不是显示 `1280×0` 这种半截文本。
     */
    private fun resolutionLabel(): String? {
        val t = resolutionText
        if (t.isBlank() || t.contains("×0")) return null
        val w = t.substringBefore('×').toIntOrNull() ?: return t
        return when {
            w >= 3840 -> "4K"
            w >= 2560 -> "2K"
            w >= 1920 -> "FHD"
            w >= 1280 -> "HD"
            else -> t
        }
    }

    private fun setLineTag(idx: Int, total: Int) {
        refreshTags()
    }

    /**
     * 右上角的频道号。
     *
     * ⚠️ 原版用的是**当前频道在整个频道列表里的全局下标 + 1**
     * （`MainContent.kt:366`：`channelList.indexOf(currentChannel) + 1`），
     * 不是"当前分组内的下标"。v0.5 用的是分组内下标，换分组就会串号，M5 修正。
     */
    private fun currentChannelNumber(): Int {
        val cur = currentChannel ?: return 0
        val i = groupList.flatChannels().indexOfFirst { it.name == cur.name }
        return if (i < 0) 0 else i + 1
    }

    private fun setTopNumberPlaceholder() {
        if (numberBuffer.isNotEmpty()) return          // 正在数字选台时不覆盖
        val n = currentChannelNumber()
        topNumber.text = if (n > 0) String.format(Locale.CHINA, "%02d", n) else "--"
    }

    private fun loadLogoCaption(name: String) {
        val matched = ChannelLogoStore.match(this, name)
        showBottomStatus(if (matched != null) "台标：$matched" else "暂无台标")
    }

    private fun tag(text: String): TextView = UiKit.tag(
        this, text, UiKit.withAlpha(Palette.inverseSurface(this@MainActivity), 0.85f), Palette.surfaceAlt(this@MainActivity))
        .apply {
            // 问题 3：底栏角标按原版比例放大（`Tag` 默认 labelLarge ≈ 14sp）
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(s(8f), s(3f), s(8f), s(3f))
        }

    private fun updateClock() {
        val now = Date()
        if (clockModePanel) {
            topDate.text = SimpleDateFormat("MM/dd EEE", Locale.CHINA).format(now)
            topTime.text = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(now)
            topTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        } else {
            topTime.text = SimpleDateFormat("HH:mm", Locale.CHINA).format(now)
            topTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        }
    }
}
