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
import top.yogiczy.mytv.legacy.ui.AgreementView
import top.yogiczy.mytv.legacy.ui.DebugOverlayView
import top.yogiczy.mytv.legacy.ui.UpdateProgressView
import top.yogiczy.mytv.legacy.ui.epg.EpgSourceView
import top.yogiczy.mytv.legacy.ui.ChannelLogoStore
import top.yogiczy.mytv.legacy.ui.DatetimeView
import top.yogiczy.mytv.legacy.ui.DlnaCastView
import top.yogiczy.mytv.legacy.ui.panel.AppLauncherView
import top.yogiczy.mytv.legacy.ui.panel.ChannelColumnView
import top.yogiczy.mytv.legacy.ui.panel.NavColumnView
import top.yogiczy.mytv.legacy.ui.panel.PanelHost
import top.yogiczy.mytv.legacy.ui.panel.SourcePanelView
import top.yogiczy.mytv.legacy.ui.player.LinePickerView
import top.yogiczy.mytv.legacy.ui.player.PlayerControlView
import top.yogiczy.mytv.legacy.ui.epg.EpgColumnView
import top.yogiczy.mytv.legacy.ui.settings.SettingsView
import top.yogiczy.mytv.legacy.ui.settings.GroupManageView
import top.yogiczy.mytv.legacy.ui.epg.EpgPanelView
import top.yogiczy.mytv.legacy.ui.epg.EpgProgressView
import top.yogiczy.mytv.legacy.ui.Density
import top.yogiczy.mytv.legacy.ui.Palette
import top.yogiczy.mytv.legacy.ui.UiKit
import top.yogiczy.mytv.legacy.ui.quickop.QuickOpView
import top.yogiczy.mytv.legacy.ui.system.ClampedLinearLayout
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
         * v1.7：换台/切线路后信息框的**兜底**显示时长 ——
         * 画面迟迟出不来时最多挂这么久就收（正常路径是"画面出来后 2 秒"收）。
         */
        private const val TEMP_INFO_MAX_SHOW_MS = 12_000L

        /** v1.7：画面开始播放（拿到视频分辨率）后信息框再保留的时长。 */
        private const val READY_HIDE_AFTER_MS = 2_000L

        /** v1.7：滑动手势的最短主轴位移（像素），太短的滑动不当作换台/切线路。 */
        private const val SWIPE_MIN_PX = 90f

        /**
         * 「再按一次退出」的确认窗口（毫秒）。
         *
         * 对齐原版 `rememberDoubleBackPressedExitState(resetSeconds = 2)`。
         */
        private const val EXIT_CONFIRM_WINDOW_MS = 2_000L

        /**
         * v1.13 需求 2：**三段式选台面板**的无操作超时（毫秒）。
         *
         * 用户裁决原话：「包含面板，但用更长的超时」—— 弹层统一 5 秒（走
         * `Prefs.uiScreenAutoCloseDelay`，默认 5 秒），面板单独给 15 秒。
         *
         * 为什么面板要更长：v1.11 用户曾明确要求"挑频道的时候别自己收掉"，
         * 挑频道时盯着一列频道名看十几秒是常态；但完全不收又会让面板一直压在画面上。
         * 15 秒是这两者之间的折中，且**任何按键 / 触摸都会重新计时**，
         * 也就是说只有在真正"人不在"的时候才会自己收。
         *
         * ⚠️ 刻意**不**读 `Prefs.uiScreenAutoCloseDelay`：那个设置项的语义是"弹层超时"
         * （默认 5 秒），若共用，把设置调到 30 秒会把面板一起拖长，
         * 而用户要的是"面板与弹层**不同**的超时"。
         */
        private const val PANEL_AUTO_CLOSE_MS = 15_000L

        /** v1.13 需求 5：面板整体的外圆角半径（dp）。列与列之间一律直角。 */
        private const val COLUMN_RADIUS_DP = 10f

        /** v1.13 需求 4：一级菜单背景不透明度（用户给的「90%」）。 */
        private const val PANEL_L1_ALPHA = 0.90f

        /** v1.13 需求 4：二级菜单（与三级）背景不透明度（用户给的「70%」）。 */
        private const val PANEL_L2_ALPHA = 0.70f
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

    // ⚠️ v1.13（用户需求 2）：面板**又**有了定时自动关闭，但超时值与弹层**不同**。
    //
    // 演变过程（两轮用户裁决叠在一起，注释留着免得下一轮又改回去）：
    //   · v1.11 用户要求「左侧菜单在挑选的时候就不要自动隐藏了」→ 这一处曾被**整体删除**；
    //   · v1.13 用户要求「除设置菜单外，所有在直播界面唤起的菜单和窗口都自动隐藏」，
    //     我回问"这里的『所有』是否包含面板"（因为与 v1.11 直接冲突），
    //     用户选择：**包含面板，但用更长的超时**。
    //
    // 于是现在的规则是：
    //   · 三段式面板  → [PANEL_AUTO_CLOSE_MS] = **15 秒**（本字段）
    //   · 其它弹层    → `Prefs.uiScreenAutoCloseDelay()` = **5 秒**（各自的 ScreenAutoClose）
    //   · 设置页      → **不自动关闭**（用户明确豁免；正在改设置时被收掉会很糟）
    // 全部都是「任何按键 / 触摸就重新计时」，见 [keepOverlaysAlive]。
    private val panelAutoClose: ScreenAutoClose by lazy {
        ScreenAutoClose(PANEL_AUTO_CLOSE_MS) { hidePanel() }
    }

    /**
     * v1.13 需求 2：三处**此前完全没有自动关闭**的弹层补上超时。
     *
     * 排查过：快捷菜单 / 多线路 / 播放控制 / EPG 弹层都有各自的 `ScreenAutoClose`，
     * 但这三个（投屏抽屉、节目单来源、分组管理）**一个都没有** —— 打开后不按返回键
     * 就一直挂在屏上，正是用户说的"需要五秒后自动隐藏"。
     */
    private val dlnaCastAutoClose: ScreenAutoClose by lazy {
        ScreenAutoClose(Prefs.uiScreenAutoCloseDelay(this)) { closeDlnaCast() }
    }

    private val epgSourceAutoClose: ScreenAutoClose by lazy {
        ScreenAutoClose(Prefs.uiScreenAutoCloseDelay(this)) { closeEpgSourceManage() }
    }

    private val groupManageAutoClose: ScreenAutoClose by lazy {
        ScreenAutoClose(Prefs.uiScreenAutoCloseDelay(this)) { closeGroupManage() }
    }

    /**
     * v1.13 需求 2：任何**按键或触摸**都给在屏的菜单/窗口续期。
     *
     * 集中在一处而不是散在各个 View 里，理由：`dispatchKeyEvent` / `dispatchTouchEvent`
     * 本来就是"事件进入视图树之前"的唯一喉舌（见它们各自的注释），
     * 在这里续期能保证「不管焦点落在哪一行、用的是遥控器还是手指」都算数。
     *
     * ⚠️ 刻意**不含** 更新下载进度层 与 自绘对话框：
     *   · 进度层是在下载中，收掉会让用户找不到「取消 / 安装」；而且它是从**设置页**进入的，
     *     不属于"在直播界面唤起的菜单"。
     *   · 对话框（添加源 / 删除确认）里有输入框，5 秒收掉会丢用户刚打的字。
     */
    private fun keepOverlaysAlive() {
        if (!::panel.isInitialized) return
        if (panel.visibility == View.VISIBLE) panelAutoClose.active()
        if (dlnaView != null) dlnaCastAutoClose.active()
        if (epgSourceView != null) epgSourceAutoClose.active()
        if (groupManageView != null) groupManageAutoClose.active()
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
        refreshChannelInfoBlock()   // 问题 3：收起左下角信息块
    }

    /**
     * v1.7（用户要求）：信息框**显示到画面开始播放后 2 秒**再自动隐藏。
     *
     * 之前是换台后固定 1.5 秒就收（画面常常还没出来），现在改为：
     * 换台/切线路时显示 → 等画面真的出来（onVideoSize）再挂 2 秒倒计时；
     * 如果直播一直出不了画面，最多挂 [TEMP_INFO_MAX_SHOW_MS] 兜底收起。
     */
    private val tempInfoMaxShow = Runnable {
        UiLog.i("界面", "信息块兜底收起（画面迟迟未出）")
        handler.post(tempChannelHide)
    }

    /** 问题 3：换台 / 换线路后显示左下角信息块（收起时机见 [tempInfoMaxShow]）。 */
    private fun showTempChannelInfo() {
        tempChannelVisible = true
        handler.removeCallbacks(tempChannelHide)
        handler.removeCallbacks(tempInfoMaxShow)
        handler.postDelayed(tempInfoMaxShow, TEMP_INFO_MAX_SHOW_MS)
        refreshTopRight()
        refreshChannelInfoBlock()
    }

    /**
     * v1.13 需求 6：收掉左下角的**临时频道信息块**，并取消它的两条计时。
     *
     * ⚠️ 只把 `tempInfoBar` 置 GONE 是不够的：`tempChannelVisible` 若仍是 true，
     * 下一次 [refreshChannelInfoBlock]（换线路、台标异步回填、每秒 tick 都会调）
     * 会立刻按"临时块该显示"把它又摆回来 —— 表现就是"关了又冒出来"。
     * 所以这里连状态位与计时一起清。
     */
    private fun hideTempInfoBlock() {
        tempChannelVisible = false
        handler.removeCallbacks(tempChannelHide)
        handler.removeCallbacks(tempInfoMaxShow)
        if (::tempInfoBar.isInitialized) tempInfoBar.visibility = View.GONE
        // 打日志：需求 6 的验收就是"日志里能证明先收了临时块、再出面板"，
        // 而 1.5 秒的自动收起会掩盖问题（截图根本追不上），所以必须留痕。
        UiLog.i("界面", "收掉左下临时信息块（面板唤起前）")
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
            // v1.9（用户需求 5）：播放控制弹层开着时，进度条每秒跟着走
            refreshPlayerControl()
            // 判据 4：底栏的网速角标与当前节目进度条每秒刷新（原版也是每秒轮询）
            if (::player.isInitialized && ::tagSpeed.isInitialized) {
                tagSpeed.text = "${player.speedKBps}KB/s"
                updateBottomProgress()
            }
            // v1.8：调试叠加层（三个开关都在「调试」分类里，关着时整层 GONE）
            if (::debugOverlay.isInitialized && ::player.isInitialized) {
                debugOverlay.applyPrefs(playerDebugText())
            }
            handler.postDelayed(this, 1000L)
        }
    }

    /**
     * v1.7（用户要求"网速要实时"）：信息块可见期间每 500ms 刷新一次 ——
     * [NetMeter] 的读数是真实下载速率，刷新越勤数字越"活"。
     * （时钟等其余 UI 仍走 1 秒的 [clockTick]。）
     */
    private val speedTick = object : Runnable {
        override fun run() {
            val infoVisible = (::tempInfoBar.isInitialized && tempInfoBar.visibility == View.VISIBLE) ||
                (::quickOp.isInitialized && quickOp.isOpen)
            if (infoVisible && ::player.isInitialized) {
                if (::tagSpeed.isInitialized) tagSpeed.text = "${player.speedKBps}KB/s"
                refreshChannelInfoBlock()
            }
            handler.postDelayed(this, 500L)
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

    /** v1.8：调试叠加层（FPS / 播放器信息 / 布局网格）。 */
    private lateinit var debugOverlay: DebugOverlayView

    /** v1.8：更新下载进度层（非 null 时按键与触摸都不穿透）。 */
    private var updateProgress: UpdateProgressView? = null

    /** v1.8：节目单管理抽屉。 */
    private var epgSourceView: EpgSourceView? = null

    /** v1.9：多线路右侧选框（用户需求 5）。 */
    private var linePicker: LinePickerView? = null

    /** v1.9：播放控制弹层（用户需求 5）。 */
    private var playerControl: PlayerControlView? = null

    /** v1.9：当前频道的起播时刻，用于"点播播完"的连播去抖（用户需求 3）。 */
    private var channelStartedAt = 0L

    /** v1.9：上一次自动连播的时刻，防止连续的 ENDED 事件连跳两集。 */
    private var lastAutoNextAt = 0L

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
            // v1.7（用户要求）：信息框显示到「画面开始播放后 2 秒」再自动隐藏，
            // 期间网速实时刷新（见 speedTick）。之前是画面一出来立刻撤。
            if (tempChannelVisible) {
                handler.removeCallbacks(tempChannelHide)
                handler.removeCallbacks(tempInfoMaxShow)
                handler.postDelayed(tempChannelHide, READY_HIDE_AFTER_MS)
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
        // v1.9（用户需求 3）：点播内容播完 → 自动接下一个（同分组下一个，组末接下一分组）
        player.onEnded = { onPlaybackEnded() }

        UiLog.i("界面", "密度缩放：${Density.describe(this)}")

        handler.post(clockTick)
        handler.post(speedTick)   // v1.7：信息块可见期间的实时网速刷新
        // v1.7（用户要求）：首次安装先弹《使用须知》（全屏覆盖层，照搬原版 1.7 文案与样式）。
        // 放在 loadSource 之后：底层继续加载，同意后直接就是可播状态。
        if (!Prefs.appAgreementAgreed(this)) showAgreementGate()
        loadSource(Prefs.currentSourceUrl(this))
        refreshTopRight()
        // v1.8 接通「升级提醒」：启动后自动静默检查一次更新（设置页里那一项原来只写 Prefs、无人读）。
        if (Prefs.updateRemindEnable(this)) checkStartupUpdate()
    }

    /**
     * v1.11（用户需求 4）：**是我把播放暂停的**（去了后台），回来时才由我恢复。
     *
     * 不能只看 `player.isPlaying` 就恢复 —— 用户可能自己按过播放控制的暂停键，
     * 那种情况切后台再回来必须**保持暂停**。用一个标志位记住"暂停是后台造成的"。
     */
    private var pausedByBackground = false

    override fun onPause() {
        super.onPause()
        // 用户需求 4：跳去其他应用（含系统安装器、权限弹窗、锁屏）前先暂停画面。
        // 好处有二：后台不再空跑解码与拉流；回来时不会发现画面已经落后一大截。
        if (!::player.isInitialized) return
        // 判据用 playWhenReady 而不是 isPlaying：流正在缓冲时 isPlaying 是 false，
        // 但用户并不是要暂停 —— 那一瞬间切走会漏掉暂停且回来不恢复。
        val wasPlaying = player.wantsToPlay
        pausedByBackground = wasPlaying
        if (wasPlaying) {
            player.pauseForBackground()
            UiLog.i("播放", "切到后台 → 自动暂停")
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户需求 4：回到播放界面 → 恢复播放。只恢复"后台造成的暂停"。
        if (!::player.isInitialized || !pausedByBackground) return
        pausedByBackground = false
        // 直播跳回直播沿再播；点播保持原进度（详见 PlayerController.resumeFromBackground）。
        player.resumeFromBackground()
        UiLog.i("播放", "回到前台 → 恢复播放")
    }

    override fun onDestroy() {
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(speedTick)
        handler.removeCallbacks(tempChannelHide)
        handler.removeCallbacks(tempInfoMaxShow)
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
        // v1.7：《使用须知》没同意前返回键不做事（必须显式选「已阅读并同意」或「退出应用」）
        if (agreementGate != null) return
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
        // v1.8：节目单管理抽屉（返回键关它，不要一路退到退出应用）
        if (epgSourceView != null) {
            cancelExitConfirm()
            closeEpgSourceManage()
            return
        }
        // v1.9（用户需求 5）：播放控制弹层 / 多线路选框优先于面板
        if (playerControl != null) {
            cancelExitConfirm()
            closePlayerControl()
            return
        }
        if (linePicker != null) {
            cancelExitConfirm()
            closeLinePicker()
            return
        }
        // v1.8：更新下载中，返回键 = 取消下载（与进度层上的「取消」同义），不退出应用
        if (updateProgress != null) {
            cancelExitConfirm()
            cancelUpdateDownload = true
            closeUpdateProgress()
            showBottomStatus("已取消更新下载")
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
    // onLongDown   -> 打开播放控制弹层     （M8/M9）⚠️ v1.12 起**仅面板收起时**，见下
    // onNumber     -> 数字选台（debounce 规则见 numberInput）
    // ```
    // 面板打开时**只留收起与菜单**，其余放行给焦点系统 —— 这样面板内的上下左右
    // 才能正常在列表间移动焦点（原版靠 Compose 的 `onPreviewKeyEvent` 只看不消费，
    // View 世界没有等价物，只能按"是否有回调"来区分，见 [Keys.dispatch] 的说明）。
    //
    // v1.13（用户需求 1）：面板打开时**长按下键不再是播放控制**，而是"按住快速往下挑频道"。
    // 实现方式就是下面 [keysPanelExpanded] 里不绑 `onLongDown`（见那边的长注释）。
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

    /** 面板打开时：只接管「左右键进出层级」与菜单键；方向键一律放行给焦点导航。 */
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
            // ⚠️ v1.13 需求 1：这里**故意不绑 onLongDown**（原来绑的是"长按下键 → 播放控制弹层"）。
            //
            // 用户原话：「在左侧菜单唤起的时候，长按下键的时候不要唤起控制菜单，
            //            左侧菜单唤起的时候长按下键目的是**快速往下挑选节目**」。
            //
            // 为什么"不绑"就等于"快速往下挑"：`Keys.dispatch` 的消费规则是
            // 「**某个键只有在其对应回调非 null 时才被消费**」，其余一律 return false 放行。
            // DPAD_DOWN 在这个 handler 里既没有短按回调（没绑 onDown）也没有长按回调（本行），
            // 于是 DOWN 的**每一次重复事件**都原样进 View 树 —— 系统对方向键的原生行为就是
            // 每个重复事件走一次焦点搜索，按住不放即连续下移，正是"快速往下挑选"。
            //
            // 对照：长按**上**键从来就没绑过，一直是这个行为 —— 这轮只是让"下"和"上"对称。
            // 长按下键的"播放控制"入口仍然保留在**面板收起时**（keysPanelCollapsed）。
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

    // ---- 原版三个"长按"入口 ----
    // 说明：这三个函数名里的 `Placeholder` 是历史遗留（M6 初期它们确实只是提示占位），
    // 现均已接真实行为，函数名保留是为了不动调用点。
    //   · 长按左键 → 独立 EPG 弹层
    //   · 长按右键 → 多线路选框
    //   · 长按下键 → 播放控制弹层

    /** 原版 `onLongLeft`：长按左键 → 独立 EPG 弹层（M7）。 */
    /** 长按左键 → 独立 EPG 弹层（原版 `EpgScreen` 的 Drawer 形态）。 */
    private fun onLongLeftPlaceholder() {
        Log.i(Keys.TAG, "长按左键 → EPG 弹层")
        val epg = epgList?.match(currentChannel ?: return)
        epgPanel.open(epg)
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
                // v1.8 修复：回退来源必须是「内置节目单 + 自定义节目单」（原来只取自定义，
                // 于是当前用的是内置源、而它失效时**没有任何回退**，直接判"所有来源都不可用"）。
                // 顺序：内置在前（与节目单管理列表同序），自定义在后；按 url 去重并排除当前源。
                val fallbacks = (Prefs.EPG_SOURCE_LIST + Prefs.epgSourceList(this))
                    .distinctBy { it.url }
                    .filterNot { it.url == current.url }
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
        // v1.12 需求 2：render 之后日期列的显隐可能变了（跨天 / 不跨天），列宽要跟着重算
        syncEpgColumnWidth()

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
                // v1.12 需求 3a：原来这里弹的是**开发话术**
                //   「回放：xxx（回放链路属 M7 之外的范围）」
                //
                // 实测结论（2026-10-14 对照实验，取用户自建源 6 个 tsfile/live 地址）：
                //   靠"猜参数"造回看是**不成立**的 —— `&starttime=&endtime=`、`&playseek=`、
                //   PLTV→tvod 三种写法服务器一律**忽略**，返回的 m3u8 与直播原址 md5 完全一致
                //   （连分段号都一样），也就是照样在播直播。做这种假回看只会更糟。
                //
                // 真正**可用**的回看信号只有一个：源里本来就带 tvod / playseek 回看线路
                // （上游 1.7 也只做到给这类地址打一个「回放」标签，并未实现回看本身）。
                // 所以这里：有回看线路就真播，没有就老实说一句 —— 不再暴露内部编号。
                val catchUp = currentChannel?.urlList?.firstOrNull { u ->
                    val l = u.lowercase()
                    l.contains("tvod") || l.contains("playseek")
                }
                if (catchUp != null && ::player.isInitialized) {
                    Log.i(Keys.TAG, "节目单：回看《${p.title}》（走源内的回看线路）")
                    UiLog.i("界面", "节目单回看《${p.title}》→ ${catchUp.take(80)}")
                    player.play(listOf(catchUp))
                    // 起播时刻复位，避免回看刚起播就被判成"只播了几秒"的异常线路
                    channelStartedAt = SystemClock.uptimeMillis()
                    Snackbar.show("回看：${p.title}", this)
                } else {
                    Log.i(Keys.TAG, "节目单：《${p.title}》已结束，当前频道没有回看线路")
                    Snackbar.show("《${p.title}》已结束 · 本频道不支持回看", this)
                }
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

    /** 原版 `onLongRight`：长按右键 → 多线路选框（v1.9 真正接通，用户需求 5）。 */
    private fun onLongRightPlaceholder() {
        Log.i(Keys.TAG, "长按右键 → 多线路选框")
        showLinePicker()
    }

    /**
     * 原版 `onLongDown`：长按下键 → 播放控制弹层（v1.9 真正接通，用户需求 5）。
     *
     * ⚠️ v1.13 需求 1：**只在面板收起时**有效。
     * 面板打开时 `keysPanelExpanded` 刻意不绑 `onLongDown` —— 那时长按下键的语义是
     * "快速往下挑选节目"（见那边的注释），不是唤出控制菜单。
     */
    private fun onLongDownPlaceholder() {
        Log.i(Keys.TAG, "长按下键 → 播放控制弹层")
        showPlayerControl()
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

    /**
     * 切换线路（v1.8：改为**循环**）。
     *
     * 用户要求：向右滑 → 第 2 条 → 第 3 条 → …… → 最后一条后再右滑回到第 1 条；
     * 向左滑完全相反。原来是 `coerceIn(0, last)`，到边界就停住（滑了没反应）。
     *
     * 遥控器左右键走的也是这个函数，所以两端行为一致。
     */
    private fun changeLineBy(step: Int) {
        if (!::player.isInitialized) return
        val count = player.lineCount
        if (count <= 1) return
        val cur = player.currentLineIdx
        val next = ((cur + step) % count + count) % count   // 负数取模：保证左滑也能轮回
        if (next == cur) return
        player.switchLine(next)
        // v1.9（用户需求 3）：换线路 = 重新起播，起播时刻一并复位
        channelStartedAt = SystemClock.uptimeMillis()
        // 问题 3（用户）：左右切换同名源的线路时，同样弹频道信息块
        showTempChannelInfo()
        UiLog.i("界面", "切线路 → ${next + 1}/${count}（循环）")
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
        // v1.8：设置页「数字选台」在这里真实生效 —— 关掉后数字键不再选台
        // （之前这个开关只写 Prefs、没有任何地方读，是摆设）。
        if (!Prefs.iptvChannelNoSelectEnable(this)) {
            UiLog.i("界面", "数字键忽略（数字选台已关闭）")
            return
        }
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
            // v1.7：《使用须知》在屏时放行给按钮焦点系统（DPAD 移动 + 确认键触发）
            else if (agreementGate != null) keysNone
            // 自绘对话框开着时必须放行给控件（EditText / 按钮要自己吃方向键与确认键）。
            else if (dialogView != null) keysNone
            // 问题 6：分组管理抽屉开着时同样放行给控件（确认键要能切换显隐）
            else if (groupManageView != null) keysNone
            // 问题 6：投屏抽屉开着时放行给控件（设备列表要能移动焦点 / 选中）
            else if (dlnaView != null) keysNone
            // v1.8：节目单管理抽屉开着时放行给控件（列表项要能吃确认键 / 删除键）
            else if (epgSourceView != null) keysNone
            // v1.9（用户需求 5）：多线路选框 / 播放控制弹层 —— 都放行给焦点系统，
            // 进度条要自己吃左右键（SeekBar 原生就支持），按钮要能吃确认键。
            else if (linePicker != null) keysNone
            else if (playerControl != null) keysNone
            // v1.8：更新进度层（按钮要能吃确认键）
            else if (updateProgress != null) keysNone
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
        // v1.13 需求 2：任何按键都给在屏的菜单/窗口续期（面板 15 秒、弹层 5 秒）。
        keepOverlaysAlive()
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
        if (::panel.isInitialized) {
            // v1.13 需求 2：手指一碰屏幕就给出自动关闭"续期"（与按键同一条链路）。
            if (ev.action == MotionEvent.ACTION_DOWN) keepOverlaysAlive()
            gestureDetector.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---------------- 面板显隐 ----------------
    // v1.11（用户需求 1）：面板的显式收起路径有三条：
    //   1) 在面板里选中频道
    //   2) 按返回键
    //   3) 点面板外的画面空白（ClassicChannelScreenWrapper 的 onTap = onClose）
    // v1.13（用户需求 2）：又加回了**定时收起**，但超时比弹层长（15 秒，见 panelAutoClose），
    // 且任何按键/触摸都会重新计时。

    private var panelDismissing = false

    private fun showPanel() {
        if (panel.visibility == View.VISIBLE) return
        // v1.10（用户需求 2）：三段式面板与快捷功能菜单**互斥**。
        // 两者都要占屏幕左侧/左下，同时出现就是截图 IMG_9161 那种互相叠字。
        // 约定：**谁后开谁负责把对方关掉**。这里是面板后开 → 关掉快捷菜单。
        if (::quickOp.isInitialized && quickOp.isOpen) quickOp.close()
        // v1.13 需求 6（用户）：**先隐藏底部信息框，再唤起左侧菜单**。
        //
        // 左下角那块"临时频道信息"（带台标、换台后出现）与面板右下角的信息框是**两套**东西：
        // 临时块的存活由 `tempChannelVisible` 控制（最长 12 秒兜底），
        // 面板信息框由"面板在屏"控制 —— 于是换台后马上唤出面板时**两块同时在屏**，
        // 底部左右各一份频道信息互相压字（用户截图 Clipboard_Screenshot-3.png 就是这个）。
        hideTempInfoBlock()
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
        // v1.13 需求 2：面板的无操作超时（15 秒）。任何按键 / 触摸都会续期，
        // 见 [keepOverlaysAlive]。
        panelAutoClose.cancel()
        panelAutoClose.active()
        // 诊断：面板"看不见"时靠这一行分辨是 alpha 没动画完、还是尺寸/位移不对。
        handler.postDelayed({
            UiLog.i("界面", "面板显隐 可见=${panel.visibility} alpha=${panel.alpha} " +
                    "尺寸=${panel.width}x${panel.height} 位移=${panel.translationX} " +
                    "状态=${panel.currentState}")
        }, 400L)
    }

    private fun hidePanel() {
        // v1.13 需求 2：面板收起时**必须**取消计时，否则 15 秒后会对着一个已经收起的面板
        // 再触发一次回调（hidePanel 自身有幂等保护，但会白刷日志、也会把
        // "下次唤出"的计时起点搞乱）。
        panelAutoClose.cancel()
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
          refreshBottomVisibility()
          refreshChannelInfoBlock()
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
    private fun showBottomStatus(msg: String, sticky: Boolean = false, durationMs: Long = BOTTOM_STATUS_MS) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { showBottomStatus(msg, sticky, durationMs) }
            return
        }
        bottomStatusText.text = msg
        bottomStatusText.visibility = View.VISIBLE
        bottomProgBlock.visibility = View.GONE          // 状态消息优先，节目区让位
        bottomEpgCur = null
        bottomStatusSticky = sticky
        bottomStatusUntil = SystemClock.uptimeMillis() + durationMs
        refreshBottomVisibility()
    }

    /**
     * v1.11（用户需求 3）：主动收掉底栏那条状态提示（尤其是 `sticky` 的）。
     *
     * 为什么单独加一个"清"：`sticky = true` 的提示**没有到期时间**，
     * `refreshBottomVisibility()` 会因为它一直把底栏留在屏上。之前「检查更新」用它显示
     * 「正在检查更新…」，而成功分支直接去弹「发现新版本」对话层、从没清过 —— 于是
     * 底部永久挂着「正在检查更新…」（用户看到的就是"底下多了一个转圈的加载中"），
     * 点「立即更新」后它还压在下载进度层下面同时显示。
     */
    private fun clearBottomStatus() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { clearBottomStatus() }
            return
        }
        if (!::bottomStatusText.isInitialized) return
        bottomStatusSticky = false
        bottomStatusUntil = 0L
        handler.removeCallbacks(bottomStatusExpire)
        bottomStatusText.visibility = View.GONE
        // 状态文字让位时节目区是 GONE，清完要还给节目区，否则底栏会变成一张空白卡片
        if (::bottomProgBlock.isInitialized) bottomProgBlock.visibility = View.VISIBLE
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
            // v1.11：按**剩余时间**续期（原来写死 BOTTOM_STATUS_MS+80，长提示会被提前收掉）
            val remain = bottomStatusUntil - SystemClock.uptimeMillis()
            if (remain > 0L) {
                handler.removeCallbacks(bottomStatusExpire)
                handler.postDelayed(bottomStatusExpire, remain + 80L)
            }
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
        // v1.9（用户需求 2）：**拼块里的时间只在"完整块"出现**。
        // 原来这里是「不在快捷菜单里就恒为 VISIBLE」—— 于是右上角那个时钟**永远在**，
        // 「界面 → 时间显示」这个设置项怎么切都看不出区别（用户报的正是这个）。
        // 现在：完整块（面板在屏 / 换台后 1.5 秒）里显示 HH:mm；
        // 收起状态下**一律交给 DatetimeView**，由它按 UiTimeShowMode 决定显示与否。
        topTime.visibility = v

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
     * v1.9（用户需求 2）：**这里不再去动 [topTime]**。原来两条链路互相覆盖
     * （本方法写 GONE/VISIBLE、[refreshTopRight] 又写一遍），结果是"设置项永远无效、
     * 右上角时间永远在"。现在职责单一：
     *   · 完整块在屏（面板/临时信息块）→ [refreshTopRight] 负责，显示 HH:mm；
     *   · 反之 → 本方法按 `UiTimeShowMode` 决定这个独立图层的显隐：
     *       HIDDEN     不显示（原版默认，`配置项` 真正生效」
     *       ALWAYS     一直显示 HH:mm
     *       EVERY_HOUR 整点前后 30 秒显示 HH:mm:ss
     *       HALF_HOUR  半点前后 30 秒显示 HH:mm:ss
     */
    private fun refreshDatetime() {
        if (!::datetime.isInitialized) return
        val overlayUp = panel.visibility == View.VISIBLE ||
                tempChannelVisible ||
                (::quickOp.isInitialized && quickOp.isOpen) ||
                playerControl != null ||
                linePicker != null ||
                epgSourceView != null ||
                (::settings.isInitialized && settings.isOpen)
        val mode = runCatching { Prefs.uiTimeShowMode(this) }.getOrDefault(UiTimeShowMode.HIDDEN)
        datetime.tick(suppressed = overlayUp, mode = mode)
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

            /** 一次触摸序列只认一次滑动（DOWN 时复位）。 */
            private var swipeConsumed = false

            override fun onDown(e: MotionEvent): Boolean {
                swipeConsumed = false
                return false
            }

            /**
             * 累计位移判定（v1.7 补）：`input swipe`／手指慢滑在 API 19 模拟器上
             * 可能不产生 fling 速度（onFling 不回调，实测踩到），所以滑动到阈值
             * 就立即认定，不等抬手。
             */
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (swipeConsumed || e1 == null) return false
                val adx = Math.abs(e2.x - e1.x)
                val ady = Math.abs(e2.y - e1.y)
                if (adx >= SWIPE_MIN_PX || ady >= SWIPE_MIN_PX) {
                    swipeConsumed = true
                    onScreenSwipe(e1.x, e1.y, e2.x - e1.x, e2.y - e1.y)
                }
                return false
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                onScreenSwipe(e1.x, e1.y, e2.x - e1.x, e2.y - e1.y)
                return true
            }
        })
    }

    /**
     * v1.7（用户要求）：全屏画面上的滑动手势。
     *  · **面板收起时**：上滑 = 下一频道 / 下滑 = 上一频道（跨分组循环）；
     *    左滑 = 下一条线路 / 右滑 = 上一条线路（多线路频道才有动作）。
     *  · **面板展开时**（v1.12 需求 4）：横向滑在三段式里进出层级 ——
     *    左滑深入（频道列 + 节目单列），右滑退回（最后一步收起面板）。
     *    面板内**不响应竖向滑**，留给列里的列表自己滚。
     *
     * v1.9（用户需求 4）：**左右滑的方向按用户反馈对调**。
     * 原来是「右滑 = 下一条 / 左滑 = 上一条」，用户反馈"不符合直觉"。
     * 现在按"翻页"的直觉：**手指向左划 = 往后翻（下一条），向右划 = 往前翻（上一条）**。
     * 遥控器左右键**没动**（右键 = 下一条仍是标准语义）—— 用户只提了滑动手势。
     * 面板内的层级切换沿用同一套直觉（左滑 = 往后翻 = 深入，右滑 = 往前翻 = 退回）。
     *
     * 用 onFling（要有初速度）而不是 onScroll：慢慢拖动 EPG 列表不会误触发换台。
     * 防抖只挡 500ms 内的重复触发（一次 fling 的 DOWN/UP 序列不会重复回调），
     * **不能**用 tempChannelVisible（12 秒兜底窗口）拦截 —— 那会让用户在换台后
     * 十几秒里觉得"手势失灵"（模拟器实测踩到）。
     */
    private var lastSwipeAt = 0L

    private fun onScreenSwipe(x: Float, y: Float, dx: Float, dy: Float) {
        if (!::panel.isInitialized) return          // 判据 11-2：视图树没建好不走手势
        if (dialogView != null) return              // 自绘对话框开着时手势不生效
        if (agreementGate != null) return           // 使用须知没同意前手势不生效
        if (groupManageView != null) return         // 分组管理抽屉开着时不生效
        if (dlnaView != null) return                // 投屏抽屉开着时不生效
        if (epgSourceView != null) return           // v1.8：节目单管理抽屉开着时不生效
        if (updateProgress != null) return          // v1.8：更新下载进度层在屏时不生效
        if (linePicker != null) return              // v1.9：多线路选框在屏时不生效
        if (playerControl != null) return           // v1.9：播放控制弹层在屏时不生效

        val now = android.os.SystemClock.uptimeMillis()

        // ------------------------------------------------------------------
        // v1.12 需求 4：**面板开着时**左右滑 = 在三段式里进出层级
        // ------------------------------------------------------------------
        // 老代码这里是一句光秃秃的 `if (panel.visibility == View.VISIBLE) return` ——
        // 面板一开，**任何**手势全部失效。于是触屏用户点开节目表之后
        // "滑不回一级菜单"，只能去够屏幕边缘的返回键（用户截图反馈的就是这个）。
        // 按键模式早就有等价实现（[onPanelRight] / [onPanelLeft]），手势这边只是缺一条分支。
        //
        // 方向沿用 v1.9 定下的"翻页"直觉：
        //   · **向左划 = 往后翻 = 深入一层**：分组栏 →（展开）频道列+节目单列 → 聚焦节目单列
        //   · **向右划 = 往前翻 = 退回一层**：节目单列 → 频道列+分组栏 → 收起整个面板
        //
        // ⚠️ 只认"横向"滑：竖向位移更大的手势要原样留给列里的 ScrollView / RecyclerView
        // 自己滚，所以判定顺序是**先看方向、再看防抖** —— 竖向拖动不会白白吃掉防抖窗口。
        // ⚠️ 还要避开动画重叠：面板位移要 180ms，期间再切状态会被 PanelHost 直接丢弃。
        if (panel.visibility == View.VISIBLE) {
            val adxP = Math.abs(dx)
            val adyP = Math.abs(dy)
            if (adxP < SWIPE_MIN_PX || adxP <= adyP) return
            if (now - lastSwipeAt < 500L) return
            if (panel.isAnimating) return
            lastSwipeAt = now
            if (dx < 0) {
                UiLog.i("手势", "面板内左滑 → 深入一层（当前状态 ${panel.currentState}）")
                enterPanelDeeper()
            } else {
                UiLog.i("手势", "面板内右滑 → 退回一层（当前状态 ${panel.currentState}）")
                leavePanelOneLevel()
            }
            return
        }

        if (now - lastSwipeAt < 500L) return        // 防抖：连续 fling 只认第一次
        lastSwipeAt = now

        val adx = Math.abs(dx)
        val ady = Math.abs(dy)
        if (adx < SWIPE_MIN_PX && ady < SWIPE_MIN_PX) return
        UiLog.i("手势", "滑动换台 dx=$dx dy=$dy → " + if (adx > ady) (if (dx > 0) "上一条线路" else "下一条线路") else (if (dy < 0) "下一频道" else "上一频道"))
        if (adx > ady) {
            // v1.9（用户需求 4）：水平主轴 → 切线路。左滑 = 下一条（-1 号参数给 changeLineBy 的 step
            // 是 +1）、右滑 = 上一条。与遥控器左右键**刻意相反**，因为用户明确只要改手势。
            changeLineBy(if (dx > 0) -1 else +1)
        } else {
            // 垂直主轴 → 切频道（用户指定：上滑 = 下一个，下滑 = 上一个）
            changeChannelBy(if (dy < 0) 1 else -1)
        }
    }

    private fun onScreenTap(x: Float, y: Float) {
        if (!::panel.isInitialized) return          // 判据 11-2：崩溃报告屏下视图树未建
        if (dialogView != null) return              // 自绘对话框开着时，画面点击不生效
        if (agreementGate != null) return           // v1.7：《使用须知》没同意前点击不穿透
        // v1.9：播放控制 / 多线路在屏时，画面点击一律不穿透给下层。
        // ⚠️ **不要在这里直接关掉它们**：`dispatchTouchEvent` 是先把事件喂给手势检测、
        // 之后才派发进 View 树，若这里就关，紧接着的 View 树派发会落在一个已被移除的
        // 视图上 —— 结果就是"点列表项没反应、框却没了"。
        //
        // v1.10：关闭改为**几何判定 + post 到下一轮**。原来指望覆盖层根节点的
        // `setOnClickListener` 来关，实测**根本不触发**：全屏沉浸式窗口下，覆盖层根节点
        // 自己的 onTouchEvent 收不到点击（同一屏里列表行倒是能收到，诊断日志
        // `顶层=LinePickerView[0,0,1280,720]` 证明它确实在最上层却仍不响应）。
        // 于是"点空白关掉"这条在 v1.9 是坏的。现在在手势阶段按坐标判断，
        // 不依赖任何视图的点击派发，再延到下一轮消息才真正移除视图。
        if (playerControl != null) {
            val v = playerControl
            if (v != null && !v.isInContent(x, y)) rootView.post { if (playerControl === v) closePlayerControl() }
            return
        }
        if (linePicker != null) {
            val v = linePicker
            if (v != null && !v.isInContent(x, y)) rootView.post { if (linePicker === v) closeLinePicker() }
            return
        }

        // ---------------------------------------------------------------------------
        // v1.10（用户需求 2 / 3）：**凡是有模态层在屏，这里一律只 return，不做任何动作。**
        //
        // 原来这三个抽屉在这里是直接 `closeXxx()` 关掉的，这就是用户报的两个现象的**同一个根因**：
        //   `dispatchTouchEvent` 是「先把事件喂给手势检测、再派发进 View 树」，
        //   于是点列表行时 ACTION_UP 一进来就把整个抽屉移除了，等 `super.dispatchTouchEvent`
        //   派发时 ViewGroup 的触摸目标链已被清空（`removeViewInternal` 会把该 child 从
        //   `mFirstTouchTarget` 摘掉）→ **行的 onClick 永远不触发**。
        //   现象①：抽屉消失但投屏没发生（用户原话「点了菜单就隐藏了…实际是没投成功的」）。
        //   现象②：紧接着再点一下，守卫不再命中、又落到下面的 `x < 32%` 分支 →
        //          把左侧三段式面板唤了出来（用户原话「投屏的时候点开会唤起左侧菜单」）。
        //
        // 现在关闭统一交给抽屉**自己的遮罩点击**（根节点都挂了 onClose），与
        // LinePickerView / PlayerControlView 同一套约定。
        // ---------------------------------------------------------------------------
        if (groupManageView != null) return
        if (dlnaView != null) {
            // v1.10（用户需求 3）：点抽屉**本体以外** = 关抽屉，但**必须延到下一轮消息**再关。
            // 这里还在手势检测阶段（早于 View 树派发），当场 removeView 会清空 ViewGroup 的
            // 触摸目标链，正在被点的设备行就收不到 onClick 了（用户报的「点了没反应」）。
            //
            // ⚠️ 用几何判定而不是靠抽屉根节点的 setOnClickListener：实测遮罩区域
            // （抽屉面板之外的任何位置）合成/触摸点都送不到根节点上，只有列表行能收到，
            // 所以根节点的 onClick 完全不可靠。这里在手势阶段判定，不受任何遮挡影响。
            val v = dlnaView
            if (v != null && !v.isInContent(x, y)) {
                rootView.post { if (dlnaView === v) closeDlnaCast() }
            }
            return
        }
        if (epgSourceView != null) return
        if (updateProgress != null) return

        // v1.10：设置页 / 快捷菜单在屏时同样不穿透。
        //  · 快捷菜单的 7 个按钮都贴左下角（x 很小），不挡的话点任意一个按钮都会顺手
        //    把三段式面板也打开 —— 这正是截图 IMG_9161「左侧菜单与长按菜单同时显示」的成因之一。
        //  · 设置页不挡的话，点设置项会在背后冒出一个三段式面板
        //    （模拟器实测日志：「面板唤出 → 光标回到分组栏第 3 项」）。
        // 两者的关闭同样由它们自己的遮罩点击负责（QuickOpView 根节点已挂 onClick = close）。
        if (::settings.isInitialized && settings.isOpen) return
        if (::quickOp.isInitialized && quickOp.isOpen) return

        if (panel.visibility == View.VISIBLE) {
            // v1.11（用户需求 1）：点在面板**内部**什么都不做（原来会 resetAutoHide 续期）。
            // 点在面板外的空白 → 收面板，这是面板的显式关闭路径之一。
            if (!hitPanel(x, y)) hidePanel()
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
        // v1.10（用户需求 2）：与 [showPanel] 互斥，谁后开谁关掉对方（见 showPanel 的注释）。
        // 原来这里什么都不做，于是在经典面板上按 MENU / 长按确认键，两个界面会同时挂在屏上
        // —— 用户截图 IMG_9161 就是这个。
        if (::panel.isInitialized && panel.visibility == View.VISIBLE) hidePanel()
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
     * 实现状态（v1.12 复核）：**7 项全部接真实行为**。
     * 曾经有 3 项只弹「将在 Mx 接入」的提示条（投屏 / 节目单 / 多线路 / 播放控制），
     * 现已分别由 v1.8 的 DLNA、v1.9 的线路选框与播放控制弹层、v1.12 的节目单列补齐。
     */
    private fun onQuickOpAction(action: QuickOpView.Action) {
        when (action) {
            QuickOpView.Action.DLNA -> {
                // 问题 6：真正接通 DLNA 投屏（此前只弹一句「将在 M11 接入」）
                showDlnaCast()
            }

            QuickOpView.Action.EPG -> {
                // v1.12 需求 3b：这里过去在展开面板之后还弹一句
                //   「节目单界面将在 M7 接入」
                // 但**节目单界面早就接入了** —— setPanelExpanded(true) 会把 epgVisible 置真，
                // refreshPanelState() 随即把三段式推到状态 1（频道列 + 节目单列），
                // 那句话说白了是过期的残留，用户看到只会以为功能没做。
                // 现在直接不弹：动作本身就是正确结果。
                quickOp.close()
                if (panel.visibility != View.VISIBLE) showPanel()
                setPanelExpanded(true)
                UiLog.i("界面", "快捷菜单「节目单」→ 展开节目单列（状态 1）")
            }

            QuickOpView.Action.CHANNEL_URL -> {
                // v1.9（用户需求 5）：原来只弹一句「多线路列表将在 M6/M7 接入」——
                // 现在真正弹出**屏幕右侧的线路选框**。
                quickOp.close()
                showLinePicker()
            }

            QuickOpView.Action.CONTROLLER -> {
                // v1.9（用户需求 5）：原来只是"播放/暂停 + 一句提示"，
                // 现在打开真正的播放控制弹层（含可调进度条、快进快退、多线路入口）。
                quickOp.close()
                showPlayerControl()
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
        ).apply { gravity = Gravity.TOP or Gravity.END; topMargin = s(panelEdgeDp); rightMargin = s(panelEdgeDp) })

        // 原版 `DatetimeScreen`：独立的"只显示时间"图层，位置与右上角信息块重合，
        // 但两者**互斥**（见 refreshDatetime）—— 默认 HIDDEN 时这一层根本不出现。
        datetime = DatetimeView(this)
        root.addView(datetime, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.END; topMargin = s(panelEdgeDp); rightMargin = s(panelEdgeDp) })

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
        ).apply { gravity = Gravity.TOP or Gravity.START; topMargin = s(panelEdgeDp); leftMargin = s(panelEdgeDp) })

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
                }
                // 问题 6：真正可用的分组管理（原来只弹一句 Snackbar）
                onGroupManage = { showGroupManage(counterpart = false) }
                onGroupManageAll = { showGroupManage(counterpart = true) }
                // v1.6：应用升级通道（v1.8 起入口从「更新」分类移到「应用」分类）
                onCheckUpdate = { checkForUpdate() }
                // v1.8：节目单管理（内置 + 自定义，可切换 / 删除 / 添加）
                onCustomEpg = { showEpgSourceManage() }
                // v1.9（用户需求 1）：设置页里直接切换节目单来源 → 立刻按新源重拉
                onEpgSourceChanged = { src ->
                    showBottomStatus("已切换节目单：${src.name}，正在重新加载…")
                    fetchEpg()
                }
                // v1.8：播放器 UA 录入（原来那一行只能看不能改）
                onEditUa = { editUaDialog() }
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
          // v1.12 需求 2：左边距并入 24dp 网格（原来 28dp，与面板/底栏的 24dp 差 4dp）。
          // bottomMargin 保持 76dp —— 它要压在底栏之上，不是网格值。
          ).apply { gravity = Gravity.BOTTOM or Gravity.START; bottomMargin = s(76f); leftMargin = s(panelEdgeDp) })

          // v1.8：调试叠加层（FPS / 播放器信息 / 布局网格，由设置页「调试」三项控制）
          debugOverlay = DebugOverlayView(this)
          root.addView(debugOverlay, FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

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
            offscreenExtraPx = s(panelEdgeDp)
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
                // 问题 4：长按 = 收藏（唯一语义）。
                // 问题 5：连线路一起快照，换订阅源后收藏依然可用。
                // v1.8：设置页「收藏启用」关掉时，长按不再收藏（否则开关形同虚设）。
                if (!Prefs.iptvChannelFavoriteEnable(this@MainActivity)) {
                    Snackbar.show("收藏功能已在设置里关闭", this@MainActivity)
                } else {
                    val added = Prefs.toggleFavorite(this@MainActivity, ch)
                    UiLog.i("界面", "长按收藏：${ch.name} → ${if (added) "已收藏" else "已取消收藏"}")
                    showBottomStatus(if (added) "已收藏：${ch.name}" else "已取消收藏：${ch.name}")
                }
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
          //
          // v1.13 需求 5：三列的背景**不再各自设置**，统一由 [syncColumnBackgrounds] 按
          // "当前看得见的整体"给角 —— 只给整体最外侧的四角圆角，列与列相接处一律直角。
          val navWrap = wrap(navColumn, s(140f))
          // ⚠️ 中栏**不能**再套 ScrollView：RecyclerView 自己管滚动，套两层会导致
          // 高度测量异常（表现为整列只显示一行）。直接按 panelMaxH 给高度。
          channelColumn.layoutParams = LinearLayout.LayoutParams(s(280f), panelMaxH)
          val chWrap = channelColumn
        // M7：第三列内部自己滚动（含 RecyclerView），不再套 ScrollView（同中栏的理由）
        val epgWrap = epgColumn
        // 判据 2A：第三列此前**完全没有背景**，于是节目表悬在画面上没有面板底。
        // v1.13 需求 5 起它跟另两列共用同一套"整体圆角"逻辑，见 syncColumnBackgrounds。
        // 判据 1（用户）：初始 state=0（未展开）→ 第三列不显示（含竖提示）
        epgColumn.visibility = View.GONE
          // ⚠️ 宽度要按「节目列表 + 日期列」的**实际内容**给。
          // v1.12 需求 2（整体视觉对齐）：此前写死 420dp（= 聚焦 340 + 日期列 80，最宽组合），
          // 不聚焦时内容只有 280dp，右边就多出一条 140dp 的**空面板底**。
          // 现在交给 EpgColumnView.desiredWidthPx() 按当前聚焦/跨天状态算，
          // 并在每次状态切换后重算（见 panel.onStateChanged 与 syncEpgColumnWidth）。
          epgColumn.layoutParams = LinearLayout.LayoutParams(epgColumn.desiredWidthPx(), panelMaxH)
          navWrapRef = navWrap
          chWrapRef = chWrap
          epgWrapRef = epgWrap
          // M10：三列引用交给 PanelHost，状态机内部的位移/焦点屏蔽由它完成
          panel.navRef = navWrap
          panel.channelRef = chWrap
          panel.epgRef = epgWrap
            panel.onStateChanged = { st ->
                epgColumn.setFocused(st == 2)
                // v1.12 需求 2：宽度随聚焦/跨天状态重算（必须在位移之前算好，避免动画中布局跳动）
                syncEpgColumnWidth()
                // v1.13 需求 5：圆角随"看得见的是哪几列"重算
                syncColumnBackgrounds(st)
                // 判据 1（用户）：节目表**没点开时不要显示**第三列（连"向右查看节目单"竖提示一起），
                // 面板只占"分组栏 + 中栏"两列宽；点开（state ≥ 1）才出现。
                epgColumn.visibility = if (st == 0) View.GONE else View.VISIBLE
            }
          // 初始（state 0）就把圆角摆好，否则首帧是"三个直角块"、下一次状态变化才跳成圆角
          syncColumnBackgrounds(0)

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

      /**
       * v1.12 需求 2（整体视觉对齐）：把第三列的宽度重新对齐到它的**实际内容**。
       *
       * 需要在两种时机各调一次：
       *   ① 三段式状态变化（268 ↔ 340 的列表宽度切换）；
       *   ② 节目单重新渲染之后 —— 跨天时日期列会显隐，列宽要跟着变。
       * 宽度本身由 `EpgColumnView.desiredWidthPx()` 决定（它知道自己的内边距与日期列状态）。
       */
      private fun syncEpgColumnWidth() {
          if (!::epgColumn.isInitialized) return
          val want = epgColumn.desiredWidthPx()
          val lp = epgColumn.layoutParams as? LinearLayout.LayoutParams
          if (lp != null && lp.width == want) return
          epgColumn.layoutParams = LinearLayout.LayoutParams(want, panelMaxH)
      }

    /**
     * 三列共用的面板底（v1.13 需求 4 / 5 重写）。
     *
     * 与前一版的两处关键差别：
     *  · **列与列相接的角一律直角**（传 0），只有整体最外侧的四角是 [COLUMN_RADIUS_DP]。
     *    以前每列各带一套 10dp 圆角，三列拼起来就是"两个框框贴在一起、中间两段圆弧"
     *    —— 正是用户截图圈出来的那个问题。
     *  · **不透明度按层级给**：一级菜单 90%、二级与三级 70%（用户给定值，见
     *    [PANEL_L1_ALPHA] / [PANEL_L2_ALPHA]）。三列不再强行统一，因为用户要的就是"一级更实、
     *    二级更透"。代价是两列交界处会有一条可见的色阶 —— 这是需求本身要的效果，不是缺陷。
     */
    private fun columnBg(alpha: Float, tlDp: Float, trDp: Float, brDp: Float, blDp: Float) =
        UiKit.roundedCorners(
            UiKit.withAlpha(Palette.surface(this@MainActivity), alpha),
            tlDp, trDp, brDp, blDp, this,
        )

    /**
     * v1.13 需求 5：按**当前看得见的是哪几列**，把三列拼成一个只有外圈带圆角的整体。
     *
     * 三种状态看得见的内容不同（PanelHost 的位移把左边的列推到了屏幕外）：
     *
     * | 状态 | 位移 | 屏幕上看得见 | 外圆角该落在谁身上 |
     * |---|---|---|---|
     * | 0 | 0 | 分组栏 + 频道列 | 分组栏左两角、频道列右两角 |
     * | 1 | -分组栏宽 | 频道列 + 节目单列 | 频道列左两角、节目单列右两角 |
     * | 2 | -(分组栏+频道列)宽 | 节目单列 | 节目单列四个角 |
     *
     * 由此推出：分组栏**永远是整体最左**（它只在状态 0 与频道列相接）→ 恒为"左圆右直"；
     * 节目单列只在状态 2 独占时四角都圆；频道列夹在中间，随状态在两种形态间切。
     */
    private fun syncColumnBackgrounds(state: Int) {
        val r = COLUMN_RADIUS_DP
        navWrapRef?.background =
            columnBg(PANEL_L1_ALPHA, tlDp = r, trDp = 0f, brDp = 0f, blDp = r)
        channelColumn.background = if (state <= 0) {
            // 状态 0：左边贴着分组栏 → 左直角；右边就是整体外缘 → 右圆角
            columnBg(PANEL_L2_ALPHA, tlDp = 0f, trDp = r, brDp = r, blDp = 0f)
        } else {
            // 状态 ≥1：分组栏已移出屏幕，频道列成了整体最左 → 左圆角；右侧接节目单列 → 右直角
            columnBg(PANEL_L2_ALPHA, tlDp = r, trDp = 0f, brDp = 0f, blDp = r)
        }
        epgColumn.background = if (state >= 2) {
            // 状态 2：节目单列独占整块 → 四角都圆
            columnBg(PANEL_L2_ALPHA, tlDp = r, trDp = r, brDp = r, blDp = r)
        } else {
            // 状态 1：左侧接频道列 → 左直角
            columnBg(PANEL_L2_ALPHA, tlDp = 0f, trDp = r, brDp = r, blDp = 0f)
        }
    }

    /**
     * 面板高度（三列共用）。
     *
     * v1.13 需求 4（用户）：**面板底边要与右下角信息框的底边对齐**。
     *
     * 对齐怎么来的：右下角信息框（[buildBottomBar] 的外层）是 `Gravity.BOTTOM` + `bottomMargin = 24dp`，
     * 它的底边落在 `屏高 - 24dp`；面板这里 `topMargin = 24dp`，所以只要高度取
     * 「屏高 - 上下各 24dp」，两条底边就正好落在同一条水平线上。
     *
     * 历史沿革（别改回去）：更早是 0.66 屏高（显得矮），M10 因「菜单整体做大」提到 0.80，
     * 但 0.80 与 24dp 网格无关 —— 面板底边落在一个"看哪儿都不对齐"的位置上。
     * 现在改成与网格一致的算式，既更高（大屏上约 0.95 屏高）又有明确的对齐靶子。
     */
    private val panelMaxH: Int by lazy {
        resources.displayMetrics.heightPixels - 2 * s(panelEdgeDp)
    }

    /**
     * 三段式面板的**外边距（dp）**：上下左右统一用它。
     *
     * v1.12 需求 2（整体视觉对齐）：此前左 24 / 上 16 —— 面板贴着屏幕顶部比左边更近，
     * 视觉上会显得"顶到了"。现在统一到一条 24dp 网格（右上角信息块、
     * 底部节目进度、快捷菜单的内边距也都按 24dp 对齐）。
     *
     * ⚠️ 位移计算与 LayoutParams 必须共用同一个值，所以保留这个常量而不是就地写死。
     */
    private val panelEdgeDp = 24f

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
        // v1.13 需求 4（用户）：**高度不能超过屏高的五分之一**。
        //
        // `LinearLayout` 没有 maxHeight，`ConstraintLayout` 又在本项目的冻结决策里被禁，
        // 所以用 [ClampedLinearLayout] 在测量阶段把高度夹住（见那个类的注释）。
        // 正常内容本来就到不了上限（见下面把字号调小后的高度估算），
        // 这条只是"内容异常长时兜住"的保险，避免信息框一路顶到屏幕上半部分。
        val card = ClampedLinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            maxHeightPx = resources.displayMetrics.heightPixels / 5
            // v1.13 需求 4：底色与三段式面板同源（见 columnBg），
            // 不透明度按用户给定值改为 **0.70**（原来是 0.8）。
            background = UiKit.rounded(
                UiKit.withAlpha(Palette.surface(this@MainActivity), PANEL_L2_ALPHA), 10f, this@MainActivity
            )
            setPadding(s(20f), s(8f), s(20f), s(8f))
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
            // ⚠️ v1.13 需求 4：频道名占满"角标之外"的剩余宽度（见下面 addView 的 weight=1），
            // 必须自己管住单行，否则长频道名会把整行撑爆、连累角标被压成一列。
            // 文本右对齐：视觉上仍与角标贴在一起（与改动前一致），长了就在尾部出省略号。
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
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
        // ⚠️ v1.13 需求 4：这一行**必须**给成定宽 + 频道名 weight=1。
        // 原来是「整行 WRAP_CONTENT、频道名与角标都 WRAP_CONTENT」：内容一旦超过卡片宽度，
        // 横向 LinearLayout 就会把最后一个角标按剩余宽度（可能是 10px 级）去测量，
        // 于是 "0KB/s" 被逐字换行、竖着排成一条窄柱（实测复现，见 UiKit.tag 的注释）。
        // 现在：标题吃掉剩余宽度并自己省略号，角标永远保持自然宽度。
        head.addView(bottomTitle, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(bottomTags)
        card.addView(head, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
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
        // v1.13 需求 4（用户）：**只有频道名保持大号**（上面那行 22sp 的 bold），
        // 其余内容（时间段 / 剩余 / 节目名 / 状态）字号从 15sp 收到 13sp，
        // 这样信息框整体高度明显低于屏高 1/5 的上限，不会贴着上限走。
        bottomCurTime = bottomInfoText(13f, Palette.onSurfaceVariant(this))
        bottomCurRemain = bottomInfoText(13f, Palette.onSurfaceVariant(this))
        // v1.13 需求 4：节目名右对齐 + 占满剩余宽度（见下面的 weight=1），
        // 长节目名的收敛方式是**尾部省略号**，不再挤压同行其它文字。
        bottomCurTitle = bottomInfoText(13f, Palette.onSurface(this))
            .apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        bottomFill = View(this)
        bottomRest = View(this)
        bottomProgressTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bottomFill, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(bottomRest, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            visibility = View.INVISIBLE
        }
        curRow.addView(bottomCurTime, lpWrap().apply { marginEnd = s(10f) })
        curRow.addView(bottomProgressTrack, LinearLayout.LayoutParams(s(130f), s(6f)).apply { marginEnd = s(10f) })
        curRow.addView(bottomCurRemain, lpWrap().apply { marginEnd = s(10f) })
        // v1.13 需求 4：节目名吃剩余宽度（原来也是 WRAP_CONTENT，节目名一长就把自己挤成一条竖柱）
        curRow.addView(bottomCurTitle, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottomProgBlock.addView(curRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.END; topMargin = s(6f) })

        val nextRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        bottomNextTime = bottomInfoText(13f, Palette.onSurfaceVariant(this))
        bottomNextTitle = bottomInfoText(13f, Palette.onSurface(this))
            .apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        nextRow.addView(bottomNextTime, lpWrap().apply { marginEnd = s(10f) })
        nextRow.addView(bottomNextTitle, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottomProgBlock.addView(nextRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.END; topMargin = s(2f) })

        card.addView(bottomProgBlock, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.END; topMargin = s(5f) })

        // ---- 状态行（载入中 / 解析为空 / 播放失败…）----
        bottomStatusText = bottomInfoText(13f, Palette.onSurfaceVariant(this))
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

    /**
     * v1.7 换源提醒：[label] 非空 = 用户手动切换订阅源 ——
     * 底部先挂粘性的「正在切换源：XX …」，加载完换成「已切换：XX（N 个频道）」，
     * 失败则给出原因（保持粘性）。启动时传 null，维持原来的「正在载入直播源…」。
     */
    private fun loadSource(url: String, label: String? = null) {
        showBottomStatus(
            if (label != null) "正在切换源：$label …" else "正在载入直播源…",
            sticky = true,
        )
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
                    showBottomStatus(
                        if (label != null) "切换源失败：$reason" else "源加载失败：$reason",
                        sticky = true,
                    )
                    android.util.Log.w("源加载", "加载失败，保留原列表：$reason")

                    // 只有「一个频道都没有」时才用演示源兜底，保证首次启动/断网时界面不是空的。
                    if (groupList.flatChannels().isEmpty()) {
                        UiLog.i("界面", "当前列表为空 → 用演示源兜底")
                        renderAll(ChannelParser.toGroupList(ChannelParser.parse(DEMO_M3U)))
                        fetchEpg()
                    }
                } else {
                    renderAll(ChannelParser.toGroupList(parsed))
                    // v1.7：切换成功给一个非粘性的完成提示（1.5 秒后自动收）；
                    // 启动载入没有完成提示，但要把"正在载入…"的粘性状态解除，
                    // 否则节目信息被它顶住、底栏永远显示「正在载入直播源…」。
                    if (label != null) {
                        showBottomStatus("已切换：$label（共 ${groupList.flatChannels().size} 个频道）")
                    } else {
                        bottomStatusSticky = false
                        bottomStatusUntil = 0L
                        refreshBottomVisibility()
                    }
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
     * 三级能力（v1.8 把设置页里两处「摆设」接上）：
     *  ① **本地缓存**（「直播源缓存时间」）：`cacheTime > 0` 且缓存未过期时直接返回，不碰网络；
     *     默认 0 = 不缓存，行为与改动前一致。对应原版 `IptvRepository(cacheTime)`。
     *  ② **重试**（「HTTP请求重试次数 / 重试间隔」）：整条抓取链失败后按设置重试；
     *     只对**瞬时故障**（读超时 / 连接重置 / 空响应）重试 —— 握手失败、域名解析不了、
     *     HTTP 4xx 这类确定性错误重试只是白等（老设备一次超时就是 15 秒）。
     *  ③ **协议回落**（v1.6）：`https` 失败时换 `http` 再试一次。
     *
     * @return (正文, 失败原因)；成功时原因为 null。
     */
    private fun fetchSourceBody(url: String): Pair<String, String?> {
        // ① 缓存：cacheTime>0 时命中未过期缓存直接返回
        val cacheTime = Prefs.iptvSourceCacheTime(this)
        val cache = if (cacheTime > 0L) {
            top.yogiczy.mytv.legacy.data.FileCache(
                java.io.File(cacheDir, "iptv"),
                "source-" + url.hashCode().toUInt().toString(16) + ".txt",
            )
        } else null
        if (cache != null) {
            val cached = cache.read()
            if (!cached.isNullOrBlank() &&
                System.currentTimeMillis() - cache.lastModified() < cacheTime
            ) {
                android.util.Log.i("源加载", "命中缓存（${cacheTime / 1000} 秒内）")
                return cached to null
            }
        }

        // ② 重试
        val attempts = Prefs.httpRetryCount(this).coerceIn(1, 5)
        val interval = Prefs.httpRetryInterval(this).coerceIn(0, 10_000).toLong()
        var last: Pair<String, String?> = "" to "未开始"
        for (i in 1..attempts) {
            last = fetchSourceBodyOnce(url)
            if (last.first.isNotBlank()) break
            if (i >= attempts || !isRetryableSourceError(last.second)) break
            android.util.Log.i("源加载", "第 $i/$attempts 次抓取失败（${last.second}）→ ${interval}ms 后重试")
            if (interval > 0) runCatching { Thread.sleep(interval) }
        }

        // 成功后写缓存（下次在 cacheTime 内直接命中）
        if (cache != null && last.first.isNotBlank()) cache.write(last.first)
        return last
    }

    /**
     * 失败原因是否值得重试。
     *
     * 判据是失败原因里的异常类名（[Http.open] 会把原异常包成 `HttpError` 的中文消息，
     * 类名保留在括号里）。只有「超时 / 连接被重置 / 空响应」算瞬时故障；
     * TLS 握手失败、域名解析失败、HTTP 4xx 都是确定性的，重试无意义。
     */
    private fun isRetryableSourceError(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return true          // 无异常但正文为空 → 值得再试
        return reason.contains("SocketTimeoutException") ||
            reason.contains("SocketException") ||
            reason.contains("ConnectionShutdownException") ||
            reason.contains("响应为空")
    }

    /** 单次抓取链：首选协议一次，失败再回落 http 一次。 */
    private fun fetchSourceBodyOnce(url: String): Pair<String, String?> {
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
        // v1.8：设置页「收藏启用」在这里真实生效 —— 关掉后导航列不再出现「我的收藏」，
        // 长按也不再能收藏（见 onChannelLongClick）。之前这个开关只是往 Prefs 写值。
        if (Prefs.iptvChannelFavoriteEnable(this)) {
            navEntries += NavEntry(NAV_FAVORITE, R.drawable.ic_star, null, null)
        }
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
        // v1.7（用户要求）：切源时在底部给一个明确的「正在切换」提醒（粘性，
        // 直到加载成功换成「已切换」或失败给出原因，不会 1.5 秒就闪没了）。
        loadSource(src.url, src.name)
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
     * v1.8：节目单管理抽屉（对应原版 1.7 的 `EpgSourceScreen`）。
     *
     * 列表 = 内置节目单 + 用户自定义；点击切换、长按/删除键删除自定义、末项添加新的。
     * 切换后立刻按新节目单重拉（[fetchEpg]）。
     */
    private fun showEpgSourceManage() {
        closeEpgSourceManage()
        // v1.9（用户需求 1）：先记下设置页当前停在哪一行，抽屉关掉后搬回去
        if (::settings.isInitialized && settings.isOpen) settings.rememberFocus()
        val view = EpgSourceView(this)
        epgSourceView = view
        val current = Prefs.epgSourceCurrent(this)
        val custom = Prefs.epgSourceList(this)
        view.onClose = { closeEpgSourceManage() }
        view.onSelect = { src ->
            if (src.url != current.url) {
                Prefs.setEpgSourceCurrent(this, src)
                UiLog.i("节目单", "切换到节目单：${src.name}")
                closeEpgSourceManage()
                // v1.9（用户需求 1）：给一条明确的可见反馈 —— 原来切完只是静默关掉抽屉，
                // 加上设置页跳回「关于」，用户体感就是"点了没反应/切不过去"。
                showBottomStatus("已切换节目单：${src.name}，正在重新加载…")
                fetchEpg()
            } else {
                closeEpgSourceManage()
            }
        }
        view.onDelete = { src ->
            Prefs.setEpgSourceList(this, custom.filterNot { it.url == src.url })
            UiLog.i("节目单", "删除自定义节目单：${src.name}")
            closeEpgSourceManage()
            showEpgSourceManage()   // 重开以刷新列表
        }
        view.onAdd = {
            val built = AppDialog.buildInput(
                this, "添加自定义节目单", "名称，例如：备用节目单", "XMLTV / e.xml 地址",
                okLabel = "保存",
                onCancel = { closeAppDialog() },
                onOk = { name, url ->
                    closeAppDialog()
                    if (url.isBlank()) {
                        showBottomStatus("地址不能为空")
                    } else {
                        val src = EpgSource(name.ifBlank { url }, url)
                        Prefs.setEpgSourceList(this, custom + src)
                        Prefs.setEpgSourceCurrent(this, src)
                        UiLog.i("节目单", "新增自定义节目单：${src.name}")
                        closeEpgSourceManage()
                        fetchEpg()
                    }
                },
            )
            showAppDialog(built)
        }
        view.show {
            val rows = ArrayList<EpgSourceView.Row>()
            // 内置在前（不可删），自定义在后（可删）—— 与原版 `EPG_SOURCE_LIST + 自定义` 同序
            Prefs.EPG_SOURCE_LIST.forEach { src ->
                rows += EpgSourceView.Row(src, src.url == current.url, deletable = false)
            }
            custom.forEach { src ->
                rows += EpgSourceView.Row(src, src.url == current.url, deletable = true)
            }
            rows
        }
        rootView.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // v1.13 需求 2：这个抽屉此前**完全没有超时**（打开就挂着），现在按设置项（默认 5 秒）自动关。
        epgSourceAutoClose.cancel()
        epgSourceAutoClose.active()
    }

    private fun closeEpgSourceManage() {
        // v1.13 需求 2：先停计时（下面有 early return，放后面会漏）。
        epgSourceAutoClose.cancel()
        val v = epgSourceView ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        epgSourceView = null
        // v1.9（用户需求 1）：抽屉摘掉后焦点会被系统丢给窗口里第一个可聚焦视图 ——
        // 那正是设置页左栏的第一项「关于」，于是**设置页会从「节目单」跳回「关于」**，
        // 用户看到的像是"点了之后什么都没发生、还退到别的分类去了"。
        // 现在把焦点还回设置页刚才那一行，让切换结果原地可见。
        if (::settings.isInitialized && settings.isOpen) {
            handler.postDelayed({ runCatching { settings.restoreContentFocus() } }, 60L)
        }
    }

    // ======================= v1.9：多线路选框 / 播放控制（用户需求 5） =======================

    /** 多线路选框的自动关闭（超时值取「超时自动关闭界面」设置）。 */
    private val linePickerAutoClose: ScreenAutoClose by lazy {
        ScreenAutoClose(Prefs.uiScreenAutoCloseDelay(this)) { closeLinePicker() }
    }

    /** 播放控制弹层的自动关闭。 */
    private val playerControlAutoClose: ScreenAutoClose by lazy {
        ScreenAutoClose(Prefs.uiScreenAutoCloseDelay(this)) { closePlayerControl() }
    }

    /**
     * 弹出**右侧**的多线路选框：列出同一频道的全部线路 + 当前态，确认键即切换。
     *
     * 入口有三处，与用户"多线路的那个点开"对齐：
     *   · 快捷菜单「多线路」；
     *   · 遥控器**长按右键**（原版 `onLongRight`）；
     *   · 播放控制弹层里的「多线路」按钮。
     */
    private fun showLinePicker() {
        if (!::player.isInitialized) return
        val total = player.lineCount
        if (total <= 1) {
            Snackbar.show("当前频道只有 1 条线路", this)
            return
        }
        closeLinePicker()
        val v = LinePickerView(this)
        linePicker = v
        v.channelName = currentChannel?.name.orEmpty()
        v.onClose = { closeLinePicker() }
        v.onPick = { idx ->
            if (idx != player.currentLineIdx) {
                player.switchLine(idx)
                channelStartedAt = SystemClock.uptimeMillis()
                showTempChannelInfo()
                UiLog.i("界面", "多线路选框 → 切到第 ${idx + 1}/$total 条")
            }
            closeLinePicker()
        }
        v.show {
            player.lineUrls.mapIndexed { i, url ->
                LinePickerView.Row(index = i, url = url, current = i == player.currentLineIdx)
            }
        }
        rootView.addView(v, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        linePickerAutoClose.timeoutMs = Prefs.uiScreenAutoCloseDelay(this)
        linePickerAutoClose.active()
        UiLog.i("界面", "多线路选框已弹出（共 $total 条，当前第 ${player.currentLineIdx + 1} 条）")
    }

    private fun closeLinePicker() {
        val v = linePicker ?: return
        linePickerAutoClose.cancel()
        (v.parent as? ViewGroup)?.removeView(v)
        linePicker = null
    }

    /**
     * 弹出播放控制弹层：**可调进度条** + 播放/暂停 + 快退快进 + 多线路入口。
     *
     * 入口：快捷菜单「播放控制」/ 遥控器**长按下键**（原版 `onLongDown`）。
     */
    private fun showPlayerControl() {
        if (!::player.isInitialized) return
        closePlayerControl()
        val v = PlayerControlView(this)
        playerControl = v
        v.onClose = { closePlayerControl() }
        v.onTogglePlay = {
            if (player.isPlaying) player.pause() else player.play()
            refreshPlayerControl()
            playerControlAutoClose.timeoutMs = Prefs.uiScreenAutoCloseDelay(this)
            playerControlAutoClose.active()
        }
        v.onSeekTo = { ms -> player.seekTo(ms) }
        v.onSeekBy = { delta ->
            if (player.isLive && player.duration <= 0L) {
                // 直播且拿不到时长：HLS 时移窗口内 seek 仍然有效，交给播放器自己判定
                UiLog.i("播放", "直播快进/快退 ${delta / 1000}s（时移）")
            }
            player.seekBy(delta)
        }
        v.onPickLine = { showLinePicker() }
        rootView.addView(v, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        v.open()
        refreshPlayerControl()
        playerControlAutoClose.timeoutMs = Prefs.uiScreenAutoCloseDelay(this)
        playerControlAutoClose.active()
        UiLog.i(
            "界面",
            "播放控制弹层已弹出（${if (player.isLive) "直播·进度条不可拖" else "点播·可拖动进度条"}，" +
                "时长=${player.duration}ms）"
        )
    }

    private fun closePlayerControl() {
        val v = playerControl ?: return
        playerControlAutoClose.cancel()
        (v.parent as? ViewGroup)?.removeView(v)
        playerControl = null
    }

    /** 播放控制弹层的每秒刷新（由 [clockTick] 调用）。 */
    private fun refreshPlayerControl() {
        val v = playerControl ?: return
        if (!::player.isInitialized) return
        v.bind(
            playing = player.isPlaying,
            positionMs = player.currentPosition,
            durationMs = player.duration,
            lineIdx = player.currentLineIdx,
            lineTotal = player.lineCount,
            softDecode = player.isSoftDecode,
            speedKBps = player.speedKBps,
        )
    }

    // ======================= v1.9：点播自动连播（用户需求 3） =======================

    /** 「播完才算数」的最短播放时长：低于它就判为线路异常，不连播（防止坏线路连跳一整个分组）。 */
    private val MIN_AUTO_NEXT_PLAY_MS = 15_000L

    /**
     * 点播内容播完 → 自动接下一个。
     *
     * 触发点是 [PlayerController.onEnded]（`STATE_ENDED`）—— **直播永远不会到 ENDED**，
     * 所以这个回调本身就已经是"这是个能播完的点播内容"的判据，不需要再去猜源的类型。
     *
     * 顺序（v1.12 用户需求 1 定稿）：
     *   ① **同一节目内还有下一条线路 → 接线路 N+1**（新增）
     *   ② 本节目线路全走完 → 同分组下一个频道
     *   ③ 组末 → 下一分组的第一个频道
     *   ④ 已经是最后一组最后一个 → 停止（不回头循环）
     *
     * 为什么 ① 要排在最前：潮剧 / 潮汕回放这类 txt 源的结构是
     * `剧目名,#genre#` → `剧团版本,第1集#第2集#…#第N集`，
     * 即 **一个频道 = 一个剧团版本，它的 N 条线路 = N 集**，
     * 线路就是集数，必须先把本频道内的线路播完才轮到下一个节目。
     *
     * 注意：这个规则对内置的直播源（自建源 / 虎牙 / 斗鱼 / YY轮播）**完全无副作用** ——
     * 它们是 10 条镜像线路的直播流，`STATE_ENDED` 永不触发，走不到这里
     * （已实测核对：自建源 56 个频道里 54 个是 10 线路的直播 m3u8/flv）。
     */
    private fun onPlaybackEnded() {
        if (!Prefs.iptvAutoPlayNext(this)) {
            UiLog.i("播放", "点播播完：自动连播已关闭，不接下一集")
            return
        }
        val cur = currentChannel ?: return

        // 去抖：播放器切换状态时可能连发多次 ENDED，3 秒内只认一次
        val now = SystemClock.uptimeMillis()
        if (now - lastAutoNextAt < 3_000L) return
        // 只播了几秒就到"播完" → 基本是坏线路，不是真的看完
        val played = now - channelStartedAt
        if (channelStartedAt > 0L && played < MIN_AUTO_NEXT_PLAY_MS) {
            UiLog.i("播放", "点播播完：本次仅 ${played / 1000}s（<${MIN_AUTO_NEXT_PLAY_MS / 1000}s），判为线路异常，不连播")
            return
        }

        // ① v1.12 需求 1：同一节目（频道）内优先接着播下一条线路
        if (::player.isInitialized) {
            val li = player.currentLineIdx
            val ln = player.lineCount
            if (ln > 1 && li in 0 until ln - 1) {
                val nextLine = li + 1
                lastAutoNextAt = now
                UiLog.i("播放", "点播播完自动连播 → 《${cur.name}》第 ${nextLine + 1}/${ln} 条线路（本节目内续播）")
                Snackbar.show("自动连播：第 ${nextLine + 1}/${ln} 条线路", this)
                player.switchLine(nextLine)
                // 换线路 = 重新起播，起播时刻复位（与 changeLineBy 一致，否则下一集会被
                // MIN_AUTO_NEXT_PLAY_MS 误判成"只播了几秒"）
                channelStartedAt = SystemClock.uptimeMillis()
                showTempChannelInfo()
                return
            }
            if (ln > 1) {
                UiLog.i("播放", "点播播完：《${cur.name}》${ln} 条线路已全部播完，转下一个节目")
            }
        }

        val groups = groupList.groups
        val gi = groups.indexOfFirst { g -> g.channels.any { it.name == cur.name } }
        if (gi < 0) return
        val list = groups[gi].channels
        val ci = list.indexOfFirst { it.name == cur.name }

        val next: Channel
        val reason: String
        if (ci >= 0 && ci + 1 <= list.lastIndex) {
            next = list[ci + 1]
            reason = "同分组下一个"
        } else if (gi + 1 <= groups.lastIndex && groups[gi + 1].channels.isNotEmpty()) {
            next = groups[gi + 1].channels[0]
            reason = "下一分组第一个"
        } else {
            UiLog.i("播放", "点播播完：已是最后一组最后一个，停止连播")
            return
        }

        lastAutoNextAt = now
        UiLog.i("播放", "点播播完自动连播 → 《${next.name}》（$reason，第 ${gi + 1}/${groups.size} 组）")
        Snackbar.show("自动连播：${next.name}", this)
        jumpToChannel(next)
    }

    /** 「播放器自定义UA」录入（v1.8：原来那一行只能看不能改）。 */
    private fun editUaDialog() {
        val built = AppDialog.buildSingleInput(
            this, "播放器自定义UA", "留空恢复默认 UA",
            initial = Prefs.videoPlayerUserAgent(this),
            onCancel = { closeAppDialog() },
            onOk = { value ->
                closeAppDialog()
                val ua = value.ifBlank { "" }
                Prefs.setVideoPlayerUserAgent(this, ua)
                UiLog.i("设置", "播放器UA → ${ua.ifBlank { "（默认）" }}")
                Snackbar.show("播放器UA 已保存，下次换台生效", this)
            },
        )
        showAppDialog(built)
    }

    /** 调试项的「播放器信息」文本（解码器 / 分辨率 / 网速 / 线路）。 */
    private fun playerDebugText(): String {
        if (!::player.isInitialized) return "播放器未初始化"
        return buildString {
            append("解码器：").append(player.lastDecoderName).append('\n')
            append("分辨率：").append(resolutionLabel() ?: "-").append('\n')
            append("网速：").append(player.speedKBps).append(" KB/s\n")
            append("线路：").append(player.currentLineIdx + 1).append('/').append(player.lineCount)
        }
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

    /**
     * v1.8：启动后的**静默**检查更新（由设置页「升级提醒」控制）。
     *
     * 与 [checkForUpdate]（用户手动点「检查更新」）的区别：失败、超时、已是最新都**不打扰**用户，
     * 只有确实有新版才弹确认框。延迟 8 秒再跑，避开首帧加载抢带宽。
     */
    private fun checkStartupUpdate() {
        handler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            Thread {
                val (info, err) = UpdateRepository.check(UpdateRepository.LINE_AUTO)
                android.util.Log.i("检查更新", "启动自动检查：${info?.versionName ?: "无"} / ${err ?: "无错误"}")
                if (info != null && info.isNewerThanCurrent() && !isFinishing && !isDestroyed) {
                    handler.post { if (!isFinishing && !isDestroyed) showUpdateDialog(info) }
                }
            }.start()
        }, 8_000L)
    }

    /**
     * 设置 → 应用 → 检查更新（v1.8：入口从「更新」分类移到这里）。
     *
     * v1.11（用户需求 3）：两处修正 ——
     *  ① 加 [updateChecking] 防重入：连点「检查更新」原来会并发发出多次请求、
     *     并且每次都往底栏写一条粘性提示；
     *  ② 出结果后**先 [clearBottomStatus]** 再走后续分支。原来只有"失败"分支会重写
     *     底栏、"成功"分支直接弹对话层，于是「正在检查更新…」这条粘性提示永远留在底部。
     */
    private fun checkForUpdate() {
        if (updateChecking) return
        updateChecking = true
        showBottomStatus("正在检查更新…", sticky = true)
        Thread {
            // v1.8：线路改为**自动**（raw → 镜像，谁通走谁），设置里不再让用户手动选
            // （用户原话："更新通道怎么有三个？一个就好"）。
            val (info, err) = UpdateRepository.check(UpdateRepository.LINE_AUTO)
            handler.post {
                updateChecking = false
                clearBottomStatus()
                when {
                    err != null ->
                        // 失败提示给 6 秒窗口后自动消失：不再用 sticky（否则又变成"底部多一个东西"）
                        showBottomStatus("检查更新失败：$err", durationMs = 6_000L)
                    info == null ->
                        showBottomStatus("检查更新失败：版本清单为空", durationMs = 6_000L)
                    !info.isNewerThanCurrent() ->
                        Snackbar.show("已是最新版本（${BuildConfigCompat.VERSION_NAME}）", this)
                    else -> showUpdateDialog(info)
                }
            }
        }.start()
    }

    /** v1.11：防止「检查更新」被连点出多份并发请求与多份提示。 */
    private var updateChecking = false

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

    /**
     * 下载安装包（v1.8：改为**可见的进度层**）。
     *
     * 之前只把百分比写进底栏那一行文字里，而底栏在面板收起时常常不可见 ——
     * 用户的感受就是"点了更新什么都没发生"。现在弹出 [UpdateProgressView]：
     * 进度条 + 百分比 + 已下载/总大小 + 「取消」；下载完成后按钮变「安装」，
     * 由用户点一下再拉起系统安装器。
     */
    private fun downloadUpdate(info: UpdateRepository.UpdateInfo) {
        // v1.11（用户需求 3）：进下载层之前先把底栏那条「正在检查更新…」清掉 ——
        // 否则下载进度层和它同屏，用户看到的就是"底下多了一个一直转圈的东西"。
        clearBottomStatus()
        val view = UpdateProgressView(this)
        updateProgress = view
        view.setProgress(0, 0L, info.sizeBytes)
        // v1.11：进度层刚出现时不能是一根停在 0% 的条（用户读成"卡住了"）。
        // 真实原因：`UpdateRepository.download` 会先并发探测 5 条线路（1~9 秒），
        // 这期间没有任何字节，进度条只能停在 0%。把这句写出来，等待就有了解释。
        view.setConnecting("正在选择下载线路（国内镜像 / GitHub 直连）…")
        view.onCancel = {
            cancelUpdateDownload = true
            closeUpdateProgress()
            showBottomStatus("已取消更新下载")
        }
        view.onInstall = {
            closeUpdateProgress()
            pendingInstallApk?.let { installApk(it) }
        }
        rootView.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        UiLog.i("界面", "开始下载更新：${info.versionName}（${UpdateRepository.humanSize(info.sizeBytes)}）")

        cancelUpdateDownload = false
        pendingInstallApk = null
        Thread {
            var lastPercent = -1
            val (file, err) = UpdateRepository.download(this, info, UpdateRepository.LINE_AUTO) { p ->
                if (cancelUpdateDownload) return@download false
                if (p < 0f) return@download true
                val percent = (p * 100).toInt()
                // 节流：只在整数百分比变化时回主线程刷新
                if (percent != lastPercent) {
                    lastPercent = percent
                    val downloaded = (info.sizeBytes * p).toLong()
                    handler.post { updateProgress?.setProgress(percent, downloaded, info.sizeBytes) }
                }
                true
            }
            handler.post {
                if (file == null) {
                    // v1.11（用户需求 3b）：失败原因按线路逐条列出（"所有下载线路都不可用"
                    // 时要让用户知道是网络问题还是线路问题），并在末尾给一句可执行的建议。
                    val reason = err ?: "下载失败"
                    // v1.12 需求 5：区分「存储问题」和「网络问题」—— 存储问题给 GitHub 建议
                    // 是驴唇不对马嘴（小米电视那次报的就是存储问题）。
                    val hint = when {
                        reason == "已取消" -> ""
                        reason.contains("下载目录") || reason.contains("创建目录") ->
                            "\n本机存储不可写，请清理空间或检查电视的存储权限后重试"
                        else -> "\n若多次失败，请确认本机网络能访问 GitHub（可在路由器上放行或改用热点重试）"
                    }
                    updateProgress?.setFailed(reason + hint)
                    UiLog.w("界面", "更新下载失败：$reason")
                } else {
                    pendingInstallApk = file
                    updateProgress?.setFinished(file.name)
                    UiLog.i("界面", "更新下载完成：${file.name}")
                }
            }
        }.start()
    }

    /** 用户点了「取消」。 */
    private var cancelUpdateDownload = false
    /** 下载完成、等待用户点「安装」的文件。 */
    private var pendingInstallApk: java.io.File? = null

    private fun closeUpdateProgress() {
        val v = updateProgress ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        updateProgress = null
    }

    /**
     * 拉起系统安装器。
     *
     * API 24 起不允许把 `file://` 交给别的应用（`FileUriExposedException`），必须走
     * `FileProvider` 的 `content://`；API 19~23 用 `file://` 即可。两条路都保留。
     *
     * v1.12 需求 5：API < 24 走 `file://` 时，安装器是**另一个进程/应用**，
     * 它要真的能打开这个文件 —— 而 [UpdateRepository.resolveDownloadDir] 的兜底候选
     * 可能落在 `/data/data/<pkg>/files/update`（内部私有目录，默认 0700）。
     * 所以这里必须把**父目录放开可进入、文件放开可读**（`setReadable(true, false)`），
     * 否则会出现"下载成功了，点安装却没反应/提示解析失败"。
     */
    private fun installApk(file: java.io.File) {
        val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } else {
            // API 19~23：file:// 直接给安装器，必须自己把权限放开
            runCatching {
                file.parentFile?.setExecutable(true, false)   // 目录要能"进入"
                file.parentFile?.setReadable(true, false)
                file.setReadable(true, false)                 // 文件要能被别的应用读
            }.onFailure { UiLog.w("界面", "安装包放开读权限失败：${it.javaClass.simpleName}") }
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
        // v1.13 需求 2：分组管理此前也没有超时，补上。
        groupManageAutoClose.cancel()
        groupManageAutoClose.active()
    }

    private fun closeGroupManage() {
        groupManageAutoClose.cancel()
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
        // v1.10（用户需求 3）：投屏抽屉是全屏模态，三段式面板必须让位 ——
        // 否则就是用户截图 IMG_9164 那样，面板在抽屉背后露着（而这张截图里的面板本身
        // 又是"上一次点击落到 x<32% 分支"误唤出来的，见 onScreenTap 的注释）。
        if (::panel.isInitialized && panel.visibility == View.VISIBLE) hidePanel()
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
        // v1.10（用户需求 1）：焦点必须落到**设备行**上，不能落到根覆盖层 ——
        // 根节点虽然也是 focusable，但焦点停在它上面时遥控器要先按一次方向键才进得了列表。
        view.post { runCatching { view.focusFirst() } }
        // 打开即搜索（原版 `LaunchedEffect { DlnaCastRepository.scan(context) }`）
        DlnaCastRepository.scan(this)
        // v1.13 需求 2：投屏抽屉此前也没有超时，补上。
        // ⚠️ 只收抽屉，**不影响正在进行的投送** —— `closeDlnaCast()` 只做 removeView
        // 与清回调，`DlnaCastRepository` 的推送链路不受影响。
        dlnaCastAutoClose.cancel()
        dlnaCastAutoClose.active()
        UiLog.i("投屏", "投屏抽屉已打开")
    }

    private fun closeDlnaCast() {
        dlnaCastAutoClose.cancel()
        val v = dlnaView ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        dlnaView = null
        DlnaCastRepository.onChanged = null
        UiLog.i("投屏", "投屏抽屉已关闭")
    }

    /** 选中设备：已投送同一台 → 断开；否则投送当前频道。 */
    private fun onDlnaDeviceSelected(device: DlnaDevice) {
        val view = dlnaView ?: return
        // v1.10（用户需求 1）：投送中屏蔽重复点击（对应原版 isBusyProvider）。
        view.setBusy(true)

        if (DlnaCastRepository.castingDeviceId == device.id) {
            UiLog.i("投屏", "停止投送 → ${device.displayName}")
            DlnaCastRepository.stop(device) { ok ->
                if (dlnaView !== view) return@stop
                view.setBusy(false)
                view.render()
                UiLog.i("投屏", if (ok) "已停止投送：${device.displayName}" else "停止投送失败：${device.displayName}")
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
        UiLog.i("投屏", "开始投送 → ${device.displayName}（频道=${ch.name} 线路=${idx + 1}）")
        DlnaCastRepository.cast(device, ch, idx) { result ->
            if (dlnaView !== view) return@cast
            view.setBusy(false)
            view.render()
            result.onSuccess {
                // v1.10（用户需求 1）：**投送成功才关抽屉** ——
                // 失败时保持打开并给出原因，用户可以直接改选别的设备重试，
                // 不用像以前那样「抽屉莫名消失、不知道成没成、只能重新进一遍」。
                UiLog.i("投屏", "投送成功：${device.displayName}")
                closeDlnaCast()
                Snackbar.show("已投送到 ${device.displayName}", this)
            }.onFailure { e ->
                UiLog.w("投屏", "投送失败：${device.displayName}｜${e.message}")
                Snackbar.show("投送失败：${e.message ?: "请重试"}", this)
            }
        }
    }

    // ---------------- 应用风格对话框的挂载/卸载 ----------------

    /** 当前自绘对话框（非 null 时：按键放行给控件、返回键只关它、画面点击不生效）。 */
    private var dialogView: View? = null

    /** v1.7：首次启动《使用须知》全屏层（非 null 时按键/手势都不进主界面）。 */
    private var agreementGate: View? = null

    /**
     * 挂上《使用须知》全屏层（对应原版 `AgreementScreen`：未同意时它是唯一界面）。
     * 顶层是 [AgreementView]，本身 clickable 挡住底下视图的点击；
     * 按键路由在 [currentKeyHandler] 里放行（按钮要靠焦点移动 + 确认键触发）。
     */
    private fun showAgreementGate() {
        if (agreementGate != null) return
        val gate = AgreementView(
            this,
            onAgree = {
                UiLog.i("界面", "《使用须知》：已阅读并同意")
                hideAgreementGate()
            },
            onDisagree = {
                UiLog.i("界面", "《使用须知》：不同意 → 退出应用")
                finish()
            },
        )
        agreementGate = gate
        rootView.addView(gate, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        gate.bringToFront()
        gate.post {
            // v1.8 修复：焦点必须落在**「已阅读并同意」按钮**上，而不是根覆盖层。
            // 原来这里写的是 `gate.requestFocus()` —— 结果是遥控器确认键落在根节点上、
            // 什么都不发生，没有触屏的老盒子会被永久卡在《使用须知》这一屏（模拟器实测）。
            // AgreementView 构造时的 requestFocus 因视图未入树而无效，必须在 addView 之后补。
            runCatching { gate.focusDefault() }
        }
        UiLog.i("界面", "《使用须知》：显示（首次启动）")
    }

    private fun hideAgreementGate() {
        val v = agreementGate ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        agreementGate = null
        refreshTopRight()
    }

    private fun showAppDialog(built: AppDialog.Built) {
        closeAppDialog()
        dialogView = built.root
        rootView.addView(built.root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        built.root.bringToFront()
        built.firstFocus?.post { built.firstFocus?.requestFocus() }
    }

    private fun closeAppDialog() {
        val v = dialogView ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        dialogView = null
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
        // v1.9（用户需求 3）：记下起播时刻 —— 点播播完是否算"真的看完"要靠它判定
        channelStartedAt = SystemClock.uptimeMillis()
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

    // ---------------- v1.12 需求 4：面板内的触屏层级切换 ----------------
    //
    // 这两支是按键版 [onPanelRight] / [onPanelLeft] 的**触屏等价物**。
    // 唯一的差别是判定依据：按键版要看"焦点落在哪一列"（因为右键的语义是移焦点），
    // 触屏版直接看**三态状态** —— 手指滑动是明确的意图，不需要再猜焦点。
    // 触屏下顺带把焦点也搬到该在的列上，这样"滑完接着用遥控器"不会别扭。

    /**
     * 触屏向左划：深入一层。
     *
     * 状态 0 → 展开节目单列（无节目则不展开，与 [onPanelRight] 的判据一致）；
     * 状态 1 → 焦点送进节目单列（→ 状态 2）；已在状态 2 则不动。
     */
    private fun enterPanelDeeper() {
        if (!epgVisible) {
            // 判据「菜单没必要左移」：第三列没节目时展开只是把分组栏白白推出屏幕
            if (!epgColumn.hasProgrammes()) {
                UiLog.i("界面", "第三列无节目 → 不展开（分组栏保持不动）")
                return
            }
            // 手指滑动没有"焦点"这一步，但把焦点顺手挪到频道列，滑完用遥控器接着操作更顺
            if (focusInside(navColumn)) FocusEngine.focusFirstIn(channelColumn)
            epgVisible = true
            refreshPanelState()
            return
        }
        if (!epgFocused) {
            // 与 onPanelRight 同：必须**先**把焦点送进第三列再切状态，
            // 否则 setPanelState 的 blockOffscreenFocus() 会把焦点夺走（状态到了 2 但列表没焦点）。
            epgColumn.focusProgrammeList()
            epgFocused = true
            refreshPanelState()
            // 第一帧 recycler 还没子 View，取焦会失败，动画结束后补一次
            handler.removeCallbacks(epgFocusRetry)
            handler.postDelayed(epgFocusRetry, 260L)
        }
    }

    /**
     * 触屏向右划：退回一层。
     *
     * 状态 2 → 1（焦点退回频道列）→ 0 → 再右滑**收起整个面板**。
     * 「收起」这一步是触屏特有的便利：手指滑到最外层还想退时，
     * 用户的预期就是"把菜单关掉回到全屏画面"，而不是被卡在面板里。
     */
    private fun leavePanelOneLevel() {
        when {
            epgFocused -> {
                epgFocused = false
                refreshPanelState()
                FocusEngine.restoreFocus(channelColumn, currentChannel?.name)
            }
            epgVisible -> {
                epgVisible = false
                refreshPanelState()
            }
            else -> hidePanel()
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
