package top.yogiczy.mytv.legacy.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.RenderersFactory
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.util.Util
import top.yogiczy.mytv.legacy.core.UiLog
import top.yogiczy.mytv.legacy.data.Channel
import top.yogiczy.mytv.legacy.data.Prefs
import top.yogiczy.mytv.legacy.data.VideoPlayerDisplayMode

/**
 * ExoPlayer 2.18.1 封装
 *
 * ---------------------------------------------------------------------------
 * 对应原版：MyTV 1.7（源码 tag `custom-v1.7`）
 *           `tv/ui/screens/videoplayer/player/Media3VideoPlayer.kt`（360 行，media3）
 *
 * ⚠️ **不能用 media3** —— 它的 minSdk 是 21，本工程锁 19（PROJECT-STATE 冻结决策）。
 * 因此这里把原版的**策略**移植到 ExoPlayer 2.18.1 的 API 上：
 *
 * | 原版（media3）                                  | 本工程（ExoPlayer 2.18.1）                |
 * |-------------------------------------------------|-------------------------------------------|
 * | `ErrorCode.BEHIND_LIVE_WINDOW/DECODING_FAILED/  | 同名常量（2.x 已有），行为一致：           |
 * |  IO_UNSPECIFIED → `seekToDefaultPosition()`+    | 归位到 live 位置重试（**每条线路只试一次**）|
 * |  `prepare()`                                    |                                           |
 * | `PARSING_CONTAINER_UNSUPPORTED` → 依次改试       | `Util.inferContentType` + `HlsMediaSource`|
 * |  HLS → RTSP → OTHER                             |  / `RtspMediaSource` / `Progressive...`   |
 * | `DECODER_INIT_FAILED` → `softDecode=true` +      | `setMediaCodecSelector(软解优先)` +        |
 * |  `reInitPlayer()`（只切一次）                    |  `reInitPlayer()`（只切一次）             |
 * | `currentLiveOffset` 供"正在播出"进度             | [currentLiveOffset] 直接暴露              |
 *
 * **在原版基础上保留的 v0.5 能力**：多线路自动降级（上限 12 次）+ 手动 [switchLine]。
 * 恢复优先级：先按错误码做**同一条线路内**的恢复，恢复分支全部走完才降级到下一条线路。
 *
 * **未移植**：原版用协程每秒轮询 `currentLiveOffset` 推送"当前播放位置"。
 * 本工程不引协程，且当前（M8 之前）没有任何进度 UI 消费它 →
 * 只**暴露** [currentLiveOffset] / [currentPosition]，轮询交给 M8 的进度条自己起线程。
 * ---------------------------------------------------------------------------
 */
class PlayerController(private val context: Context) {

    companion object {
        /**
         * 拉取播放列表用的 UA。
         *
         * ⚠️ 与"播放器自身的 UA"是两回事：播放器 UA 走 [Prefs.videoPlayerUserAgent]
         * （原版默认 `ExoPlayer`，见 `Constants.VIDEO_PLAYER_USER_AGENT`）。
         * 这个常量保持不变，因为 `MainActivity.loadSource` 在用。
         */
        const val UA = "Mozilla/5.0 (Linux; Android 4.2.2; MyTV-Legacy) AppleWebKit/537.36"

        private const val TAG = "播放器"

        /** 多线路自动降级上限（v0.5 既有行为，保留）。 */
        private const val MAX_AUTO_FALLBACK = 12
    }

    // ---------------- 回调（保持 v0.5 签名，MainActivity 依赖） ----------------

    var onVideoSize: ((Int, Int) -> Unit)? = null
    var onLineChanged: ((idx: Int, total: Int, url: String) -> Unit)? = null
    var onStateChanged: ((Int) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * **播放到底**（v1.9，用户需求 3）。
     *
     * 只有点播才会到 [Player.STATE_ENDED]：直播 HLS 永远没有"播完"这一说。
     * 所以这个回调本身就是"这是个能播完的点播内容"的信号 —— 宿主据此连播下一个。
     */
    var onEnded: (() -> Unit)? = null

    /** 显示比例实际生效后回调（M8 的「显示模式」菜单用来回显当前项）。 */
    var onDisplayModeApplied: ((VideoPlayerDisplayMode) -> Unit)? = null

    // ---------------- 播放器（可重建：切软解时要换 renderersFactory） ----------------

    /**
     * 播放器监听器。
     *
     * ⚠️ **必须在 [exoPlayer] 之前声明**：Kotlin 按声明顺序初始化，
     * 而 [exoPlayer] 的初始化表达式要引用它。M3 第一版把它放在后面，
     * 结果初始播放器**没挂上监听器** —— 实测表现为「ExoPlayer 报了 404、
     * 但错误恢复与自动换线路全都没触发」，且显示比例也不生效。
     * 这个 bug 是靠实机日志（`E/ExoPlayerImplInternal: Playback error ... 404`
     * 后面没有我们的 `播放错误` 日志）抓出来的。
     */
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                fallbackCount = 0
                // v1.9：真正出画面了才撤销看门狗（否则每次 prepare 都会挂一条超时任务）
                cancelStallWatchdog()
                val vs = exoPlayer.videoSize
                if (vs.width > 0 && vs.height > 0) {
                    videoW = vs.width
                    videoH = vs.height
                    onVideoSize?.invoke(vs.width, vs.height)
                    applyDisplayMode()
                }
                UiLog.i(TAG, "播放就绪 视频尺寸=${vs.width}x${vs.height} 容器=${surfaceView?.width}x${surfaceView?.height}")
            }
            // v1.9（用户需求 3）：点播播完 —— 直播不会走到这里
            if (state == Player.STATE_ENDED) {
                UiLog.i(TAG, "播放到底（点播内容播完）→ 通知宿主连播")
                onEnded?.invoke()
            }
            onStateChanged?.invoke(state)
        }

        override fun onPlayerError(error: PlaybackException) {
            onErrorInternal(error)
        }
    }

    /**
     * 解码器打点（v1.7）：真机排查"卡顿"的关键证据 ——
     * 看日志里用的是硬解（OMX.xx / c2.qcom）还是软解（OMX.google / c2.android / ffmpeg），
     * 以及初始化耗时。没有这行，"卡"就只能是猜。
     */
    private val analytics = object : com.google.android.exoplayer2.analytics.AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: com.google.android.exoplayer2.analytics.AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            UiLog.i(TAG, "视频解码器=$decoderName 初始化耗时=${initializationDurationMs}ms")
            lastDecoderName = decoderName
        }
    }

    /** 最近一次实际使用的视频解码器名（v1.8：给「显示播放器信息」调试项用）。 */
    var lastDecoderName: String = "-"

    private var exoPlayer: ExoPlayer = buildPlayer(false)

    /** 供上层直接拿 ExoPlayer（例如 M8 做 seek / 倍速）。 */
    val player: ExoPlayer get() = exoPlayer

    private var surfaceView: SurfaceView? = null

    private var httpFactory: DefaultHttpDataSource.Factory = newHttpFactory()
    private var dataSourceFactory: DataSource.Factory = newDataSourceFactory()

    private var urlQueue: List<String> = emptyList()
    private var urlIdx = 0
    private var fallbackCount = 0

    /** 已经试过的容器类型（对应原版 `contentTypeAttempts`），换线路/换地址时清空。 */
    private val contentTypeAttempts = mutableMapOf<Int, Boolean>()

    /** 当前线路是否已经做过一次"归位到 live"重试（防止同一错误无限重试）。 */
    private var liveSeekRetried = false

    /** 是否已经切过软解（原版语义：**只切一次**，再失败才报错）。 */
    private var softDecodeUsed = false

    /** 当前频道（用来解析"按频道名记忆的显示比例"）。[play] 传 Channel 时才有值。 */
    var currentChannel: Channel? = null
        private set

    private var videoW = 0
    private var videoH = 0

    /**
     * ⚠️ **TLS 1.2 不在这里配 —— 配不了，也不该配在这里。**
     *
     * `javap` 实测 `exoplayer-datasource-2.18.1.aar`：
     * `DefaultHttpDataSource$Factory` **没有** `setSslSocketFactory`，构造函数也不收
     * `SSLSocketFactory`。所以「给播放的 DataSource 单独配 TLS」这条路在 2.18.1 上不存在。
     *
     * 实际通路：`DefaultHttpDataSource` → `java.net.URL.openConnection()` →
     * `HttpsURLConnection` → 用 `getDefaultSSLSocketFactory()` 的静态默认值，
     * 而那个默认值由 `App.enableTls12()` → `Http.installDefaultTls12()` 装上 TLS 1.2
     * （见 `net/Http.kt`）。**全工程只有那一处 SSLContext，不在这里另建一份。**
     *
     * 原版（media3 的 `DefaultHttpDataSource`）同样基于 HttpURLConnection，
     * 因此这条通路与原版一致，不是新增行为。
     */
    private fun newHttpFactory(): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setUserAgent(Prefs.videoPlayerUserAgent(context))
            .setConnectTimeoutMs(Prefs.videoPlayerLoadTimeout(context).toInt())
            .setReadTimeoutMs(Prefs.videoPlayerLoadTimeout(context).toInt())
            // 与原版 media3 的 DefaultHttpDataSource 配置对齐
            .setAllowCrossProtocolRedirects(true)

    private fun newDataSourceFactory(): DataSource.Factory =
        // v1.7：外面再包一层 MeteredDataSource —— 播放链路的每次 read 都计数，
        // 底栏/信息块的「实时网速」就是这么算出来的（见 NetMeter）。
        DataSource.Factory { MeteredDataSource(DefaultDataSource.Factory(context, httpFactory).createDataSource()) }

    /**
     * 软解优先的 MediaCodec 选择器
     *
     * ExoPlayer 2.18.1 没有"直接指定软解"的开关，等价做法是**过滤解码器列表**，
     * 只保留软件解码器。判定按名称前缀（`MediaCodecInfo.isSoftwareOnly` 需要 API 29，
     * 本工程锁 19，用不了）。
     *
     * ⚠️ **绝不能返回空列表** —— 那会让 ExoPlayer 抛异常。筛不出软件解码器时原样返回。
     */
    private object SoftwareFirstSelector : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean,
        ): List<MediaCodecInfo> {
            val all = MediaCodecSelector.DEFAULT
                .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            if (all.isEmpty()) return all

            val software = all.filter { isLikelySoftware(it.name) }
            return if (software.isEmpty()) all else software
        }

        private fun isLikelySoftware(name: String): Boolean =
            name.startsWith("OMX.google.") ||
                    name.startsWith("c2.android.") ||
                    name.startsWith("OMX.ffmpeg.") ||
                    name.contains(".sw.") ||
                    name.contains("ffmpeg")
    }

    private fun buildPlayer(softDecode: Boolean): ExoPlayer {
        val renderersFactory: RenderersFactory = DefaultRenderersFactory(context)
            // 解码器初始化失败时，让 ExoPlayer 自己先试别的解码器
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                if (softDecode) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
            .apply {
                if (softDecode) setMediaCodecSelector(SoftwareFirstSelector)
            }

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            // v1.7（用户反馈"播放比较卡"）：直播缓冲调优。
            //  · 起播门槛 2500ms → 1200ms：换台后更快出画面；
            //  · 卡顿后恢复门槛 4000ms：攒够 4 秒再恢复播放，避免一缓冲就反复卡；
            //  · 前向缓冲 30~60 秒：给 WiFi 抖动留余量（老盒子解码慢，缓存深一点更稳）。
            // 原版 media3 没配 LoadControl（全默认），但原版跑的是 Android 5+ 的设备，
            // 这里是为 API 19 老硬件做的补偿，不违背"对齐原版策略"。
            .setLoadControl(
                com.google.android.exoplayer2.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 30_000,
                        /* maxBufferMs = */ 60_000,
                        /* bufferForPlaybackMs = */ 1_200,
                        /* bufferForPlaybackAfterRebufferMs = */ 4_000,
                    )
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .build()
            .apply {
                playWhenReady = true
                addListener(listener)
                addAnalyticsListener(analytics)
            }
    }

    // ---------------- 错误恢复（对齐原版 Media3VideoPlayer.onPlayerError） ----------------

    private fun onErrorInternal(error: PlaybackException) {
        val code = error.errorCode
        UiLog.w(TAG, "播放错误 code=$code name=${error.errorCodeName} msg=${error.message}")

        when (code) {
            // ① 直播窗口 / 解码 / IO 类错误：归位到 live 位置重试（每条线路只试一次）
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> {
                if (!liveSeekRetried) {
                    liveSeekRetried = true
                    UiLog.i(TAG, "错误恢复：归位到直播位置重试（code=$code）")
                    runCatching {
                        exoPlayer.seekToDefaultPosition()
                        exoPlayer.prepare()
                    }.onFailure { UiLog.e(TAG, "归位重试失败", it) }
                    return
                }
                UiLog.i(TAG, "错误恢复：归位已重试过，转入线路降级（code=$code）")
            }

            // ② 容器格式不支持：依次改试 HLS → RTSP → Progressive（与原版同序）
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> {
                val uri = currentUri()
                if (uri != null) {
                    val next = CONTAINER_ORDER.firstOrNull { contentTypeAttempts[it] != true }
                    if (next != null) {
                        UiLog.i(TAG, "错误恢复：容器类型改试 ${containerName(next)}")
                        prepareUri(uri, next)
                        return
                    }
                }
                UiLog.i(TAG, "错误恢复：容器类型已全部试过，转入线路降级")
            }

            // ③ 解码器初始化失败：切软解 + 重建播放器（只切一次）
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> {
                if (!softDecodeUsed) {
                    softDecodeUsed = true
                    UiLog.i(TAG, "错误恢复：切换软解并重建播放器（仅此一次）")
                    reinitPlayer()
                    return
                }
                UiLog.e(TAG, "错误恢复：软解已切过仍失败，报错")
            }
        }

        // ④ 兜底：换下一条线路
        fallbackToNextLine(error)
    }

    private fun fallbackToNextLine(error: PlaybackException) {
        if (fallbackCount < MAX_AUTO_FALLBACK && urlIdx + 1 < urlQueue.size) {
            urlIdx++
            fallbackCount++
            UiLog.i(TAG, "自动换线路 → ${urlIdx + 1}/${urlQueue.size}")
            onLineChanged?.invoke(urlIdx, urlQueue.size, urlQueue[urlIdx])
            openCurrent()
        } else {
            UiLog.e(TAG, "所有线路与恢复手段都已用尽")
            onError?.invoke(error.errorCodeName + ": " + (error.message ?: "播放失败"))
        }
    }

    /** 重建播放器（切软解后 renderersFactory 变了，只能重建）。对应原版 `reInitPlayer()`。 */
    private fun reinitPlayer() {
        val uri = currentUri()

        runCatching {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }

        // UA / 超时可能已被设置页改过，重建时一并刷新
        httpFactory = newHttpFactory()
        dataSourceFactory = newDataSourceFactory()

        exoPlayer = buildPlayer(softDecode = true)

        surfaceView?.let { exoPlayer.setVideoSurfaceView(it) }

        // 重置一次性恢复标记：换了新播放器，允许它重新走一轮
        contentTypeAttempts.clear()
        liveSeekRetried = false

        if (uri != null) {
            prepareUri(uri, null)
        } else {
            UiLog.w(TAG, "重建播放器后没有可恢复的地址")
        }
    }

    /**
     * 换频道时把软解状态复位回硬解（v1.7，见 [startPlay] 里的说明）。
     *
     * 软解播放器必须整体重建才能变回硬解（renderersFactory 建播放器时就定了），
     * 这里不恢复当前媒体 —— 反正马上就要 prepare 新频道。
     */
    private fun resetSoftDecodeIfNeeded() {
        if (!softDecodeUsed) return
        softDecodeUsed = false
        contentTypeAttempts.clear()
        liveSeekRetried = false
        runCatching {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
        httpFactory = newHttpFactory()
        dataSourceFactory = newDataSourceFactory()
        exoPlayer = buildPlayer(softDecode = false)
        surfaceView?.let { exoPlayer.setVideoSurfaceView(it) }
        UiLog.i(TAG, "换频道：软解已复位，回到硬解优先")
    }

    private fun currentUri(): Uri? =
        runCatching { exoPlayer.currentMediaItem?.localConfiguration?.uri }.getOrNull()

    // ---------------- v1.9：卡死看门狗（用户需求 6） ----------------

    /**
     * 「多次换源后偶然卡住、怎么切都不动、必须退出重进」的兜底自愈（用户需求 6）。
     *
     * -----------------------------------------------------------------------
     * 为什么会卡死：换线路走的是 `stop() → clearMediaItems() → setMediaSource() → prepare()`
     * 这一串**都是异步**的。播放器内部还有自己的加载线程与解码器。若某次 prepare 之后
     * 加载线程没能起来（HTTP 连接半死不活、MediaCodec 申请失败但没抛错、上游一直不回数据），
     * 播放器就永远停在 `STATE_BUFFERING/IDLE`：
     *   · 画面是上一帧，看着像"卡住"；
     *   · 再点切换，仍然只是往同一个卡住的加载线程里塞新地址 → 还是不动；
     *   · 只有 `release()` 重造一台才能好 —— 这正是用户说的"退出重进才行"。
     *
     * 所以这里加一层**看门狗**：每次 prepare 后布防，到点检查
     * 「没到 READY **且** 一个字节都没下下来」→ 判定卡死，**就地重建播放器**（不需要退出应用）。
     * 重建两次仍然不行才降级换下一条线路。
     *
     * 判定为什么用"新增字节数"而不是只看状态：直播流合法地长时间 BUFFERING 是常态
     * （老盒子起播慢），只看状态会把"慢"误判成"死"。真卡死时是最彻底的静默 —— 零字节。
     * -----------------------------------------------------------------------
     */
    private val watchdogHandler = Handler(Looper.getMainLooper())

    /** 布防时刻的累计下载字节（[NetMeter.total]），用于判断"有没有在动"。 */
    private var stallBaselineBytes = 0L

    /** 「还在下数据但没就绪」只宽限一次，避免慢源被无限宽限。 */
    private var stallRounds = 0

    /** 本次 prepare 已经就地重建过几次播放器。 */
    private var stallRecoveries = 0

    private val stallWatchdog = Runnable { checkStall() }

    private fun armStallWatchdog() {
        watchdogHandler.removeCallbacks(stallWatchdog)
        stallBaselineBytes = NetMeter.total()
        stallRounds = 0
        val timeout = Prefs.videoPlayerLoadTimeout(context) + 8_000L
        watchdogHandler.postDelayed(stallWatchdog, timeout)
        UiLog.d(TAG, "看门狗布防：${timeout}ms 内需出画面，否则自愈")
    }

    private fun cancelStallWatchdog() {
        watchdogHandler.removeCallbacks(stallWatchdog)
        stallRounds = 0
        stallRecoveries = 0
    }

    private fun checkStall() {
        val state = runCatching { exoPlayer.playbackState }.getOrDefault(Player.STATE_IDLE)
        if (state == Player.STATE_READY) return          // 已就绪（READY 时已撤销，双保险）

        val downloaded = NetMeter.total() - stallBaselineBytes
        if (downloaded > 0L && stallRounds == 0) {
            stallRounds = 1
            stallBaselineBytes = NetMeter.total()
            watchdogHandler.postDelayed(stallWatchdog, 20_000L)
            UiLog.i(TAG, "看门狗：仍在下载（state=$state 新增=${downloaded}B）→ 只是慢，再等 20 秒")
            return
        }

        stallRecoveries++
        UiLog.w(
            TAG,
            "看门狗：判定卡死（state=$state 新增=${downloaded}B）第 $stallRecoveries 次 → 就地重建播放器"
        )
        if (stallRecoveries <= 2) {
            hardRecover()
        } else {
            stallRecoveries = 0
            if (urlIdx + 1 < urlQueue.size) {
                urlIdx++
                fallbackCount++
                UiLog.i(TAG, "看门狗：重建无效 → 降级到线路 ${urlIdx + 1}/${urlQueue.size}")
                onLineChanged?.invoke(urlIdx, urlQueue.size, urlQueue[urlIdx])
                openCurrent()
            } else {
                UiLog.e(TAG, "看门狗：全部线路均无数据")
                onError?.invoke("线路长时间无数据（已尝试自动恢复）")
            }
        }
    }

    /**
     * 就地重建播放器（不换线路、不动 urlQueue）。
     *
     * 与 [reinitPlayer] 的区别：这里**强制回到硬解**并从当前线路重新起播 ——
     * 卡死时软解往往是元凶（老盒子软解 1080p 会直接喂不上帧）。
     */
    private fun hardRecover() {
        val url = urlQueue.getOrNull(urlIdx) ?: return
        runCatching {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }.onFailure { UiLog.e(TAG, "看门狗：释放旧播放器失败", it) }

        httpFactory = newHttpFactory()
        dataSourceFactory = newDataSourceFactory()
        softDecodeUsed = false                 // 卡死多半来自软解，重建时回到硬解优先
        exoPlayer = buildPlayer(softDecode = false)
        surfaceView?.let { exoPlayer.setVideoSurfaceView(it) }
        contentTypeAttempts.clear()
        liveSeekRetried = false
        fallbackCount = 0

        UiLog.i(TAG, "看门狗：播放器已重建，重新打开线路 ${urlIdx + 1}/${urlQueue.size}")
        NetMeter.reset()
        prepareUri(Uri.parse(url.let { if (it.endsWith("?")) "${it}t" else it }), null)
    }

    /** 供宿主判断"当前播放器是不是还活着"（调试叠加层用）。 */
    val playbackStateName: String
        get() = runCatching {
            when (exoPlayer.playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "?"
            }
        }.getOrDefault("-")

    // ---------------- 播放控制 ----------------

    fun attach(surfaceView: SurfaceView) {
        this.surfaceView = surfaceView
        exoPlayer.setVideoSurfaceView(surfaceView)
    }

    /** 从 urls 的第 startIdx 条开始播（v0.5 签名，保持不动）。 */
    fun play(urls: List<String>, startIdx: Int = 0) {
        currentChannel = null
        startPlay(urls, startIdx)
    }

    /**
     * 带频道的播放：会记住频道，从而按"频道名记忆的显示比例"布局。
     *
     * M5 起主界面应改用这个重载（当前 MainActivity 用的是 [play]）。
     */
    fun play(channel: Channel, startIdx: Int = 0) {
        currentChannel = channel
        startPlay(channel.urlList, startIdx)
    }

    private fun startPlay(urls: List<String>, startIdx: Int) {
        // v1.7（卡顿根因修复）：软解只应该服务"触发它的那一次恢复"。
        // 之前 `softDecodeUsed` 一旦置 true 就**永远生效** —— 某个频道硬解 init 失败
        // 切到软解后，后面所有频道全在软解里播，老设备软解 1080p 必卡，
        // 真机表现就是"整体都卡、甚至播不动"。现在**换频道时复位回硬解**，
        // 哪个频道真需要软解，让那个频道自己再触发一次。
        resetSoftDecodeIfNeeded()
        urlQueue = urls.filter { it.isNotBlank() }
        if (urlQueue.isEmpty()) {
            onError?.invoke("该频道没有可用线路")
            return
        }
        urlIdx = startIdx.coerceIn(0, urlQueue.lastIndex)
        fallbackCount = 0
        // v1.9（用户需求 6）：换频道/换源时把"一次性恢复标记"全部复位。
        // 不复位的话，上一条线路用掉的「归位重试」「容器改试」会**跨线路**留着，
        // 新线路的第一次失败就直接被判为"已重试过"→ 立刻降级，看起来就是"切了不动"。
        liveSeekRetried = false
        contentTypeAttempts.clear()
        stallRecoveries = 0
        UiLog.i(TAG, "开始播放：共 ${urlQueue.size} 条线路，从第 ${urlIdx + 1} 条起")
        onLineChanged?.invoke(urlIdx, urlQueue.size, urlQueue[urlIdx])
        openCurrent()
    }

    /** 手动切线路。 */
    fun switchLine(idx: Int) {
        if (idx !in urlQueue.indices) return
        urlIdx = idx
        fallbackCount = 0
        // 同上：手动切线路 = 用户明确要求重开，一次性标记必须复位
        liveSeekRetried = false
        contentTypeAttempts.clear()
        stallRecoveries = 0
        UiLog.i(TAG, "手动切线路 → ${idx + 1}/${urlQueue.size}")
        onLineChanged?.invoke(urlIdx, urlQueue.size, urlQueue[urlIdx])
        openCurrent()
    }

    val currentLineIdx: Int get() = urlIdx
    val lineCount: Int get() = urlQueue.size

    /** 当前频道的全部线路地址（v1.9：「多线路」选框要展示它们）。 */
    val lineUrls: List<String> get() = urlQueue

    /**
     * 当前线路的**实时网速**（KB/s），供底栏角标与频道信息块使用（对齐原版 `ChannelInfo`）。
     *
     * v1.7 改为**真实测速**：[NetMeter] 统计播放 DataSource 每秒实际读到的字节。
     * 之前用 `DefaultBandwidthMeter.bitrateEstimate`（滑动平均估值），
     * 表现为"读数长期不动/缓冲满了也不归零"，不是用户要的实时网速。
     * 现在缓冲充满、暂停时读数自然回落到 0 —— 都是真实状态。
     */
    val speedKBps: Int
        get() = runCatching { (NetMeter.recentBytesPerSec() / 1024L).coerceAtLeast(0L).toInt() }
            .getOrDefault(0)

    private fun openCurrent() {
        val url = urlQueue.getOrNull(urlIdx) ?: return
        // 换线路/重开线路：网速窗口清零，避免把上一条线路的字节算进新线路的读数
        NetMeter.reset()
        prepareUri(Uri.parse(url.let { if (it.endsWith("?")) "${it}t" else it }), null)
    }

    private fun prepareUri(uri: Uri, contentType: Int?) {
        val type = contentType ?: Util.inferContentType(uri)
        val source: MediaSource = buildMediaSource(uri, type) ?: run {
            onError?.invoke("不支持的地址类型：${containerName(type)}")
            return
        }

        contentTypeAttempts[type] = true
        liveSeekRetried = false

        runCatching {
            // v1.8：设置页「停止上一媒体项」在这里真实生效（原版 `Configs.videoPlayerStopPreviousMediaItem`
            // 就是这个语义）。之前这一行是无条件 stop()，开关形同摆设。
            // 关掉后换台略快（不清空解码器队列），打开则换台更干净。
            if (Prefs.videoPlayerStopPreviousMediaItem(context)) exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.setMediaSource(source)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }.onFailure {
            UiLog.e(TAG, "线路打开失败", it)
            onError?.invoke("线路打开失败：${it.message}")
        }

        // v1.9（用户需求 6）：布防卡死看门狗 —— 起播失败的收尾不再只有"记一条日志"，
        // 到点会自动重建播放器恢复。
        armStallWatchdog()
    }

    private fun buildMediaSource(uri: Uri, contentType: Int): MediaSource? {
        val item = MediaItem.fromUri(uri)

        if (uri.toString().startsWith("rtp://")) {
            return RtspMediaSource.Factory().createMediaSource(item)
        }

        return when (contentType) {
            C.CONTENT_TYPE_HLS ->
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(item)

            C.CONTENT_TYPE_RTSP ->
                RtspMediaSource.Factory().createMediaSource(item)

            C.CONTENT_TYPE_OTHER ->
                ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item)

            else -> null
        }
    }

    private fun containerName(type: Int): String = when (type) {
        C.CONTENT_TYPE_HLS -> "HLS"
        C.CONTENT_TYPE_RTSP -> "RTSP"
        C.CONTENT_TYPE_OTHER -> "Progressive"
        else -> "type=$type"
    }

    // ---------------- 实时位置（供 M8 的「正在播出」进度） ----------------

    /**
     * 直播延迟（毫秒）。
     *
     * 原版用它算"正在播出"的真实时间点：
     * `livePosition = System.currentTimeMillis() - currentLiveOffset`。
     * 非直播 / 未知时返回 [C.TIME_UNSET]。
     */
    val currentLiveOffset: Long
        get() = runCatching { exoPlayer.currentLiveOffset }.getOrDefault(C.TIME_UNSET)

    /** 当前播放位置（毫秒）。 */
    val currentPosition: Long
        get() = runCatching { exoPlayer.currentPosition }.getOrDefault(0L)

    /** 时长（毫秒）。直播为 [C.TIME_UNSET]。 */
    val duration: Long
        get() = runCatching { exoPlayer.duration }.getOrDefault(C.TIME_UNSET)

    /** 是否已切到软解（M8 的「播放控制」菜单回显用）。 */
    val isSoftDecode: Boolean get() = softDecodeUsed

    // ---------------- 显示比例 ----------------

    /**
     * 应用当前生效的显示比例。
     *
     * 频道已知 → 用该频道记住的比例（没有则全局默认）；未知 → 用全局默认。
     * 对应原版 `Configs.resolveVideoPlayerDisplayMode`。
     */
    fun applyDisplayMode() {
        val view = surfaceView ?: return
        val mode = currentChannel?.let { DisplayMode.resolve(context, it) }
            ?: Prefs.videoPlayerDisplayMode(context)
        DisplayMode.apply(view, mode, videoW, videoH)
        onDisplayModeApplied?.invoke(mode)
    }

    /** 临时切换显示比例（M8 菜单调用）：立即生效并记住。 */
    fun setDisplayMode(mode: VideoPlayerDisplayMode, remember: Boolean = true) {
        currentChannel?.let { ch ->
            if (remember) DisplayMode.remember(context, ch, mode)
        }
        val view = surfaceView ?: return
        DisplayMode.apply(view, mode, videoW, videoH)
        UiLog.i(TAG, "显示比例切换为「${mode.label}」")
        onDisplayModeApplied?.invoke(mode)
    }

    // ---------------- 其它 v0.5 既有能力 ----------------

    fun play() = exoPlayer.play()
    fun pause() = exoPlayer.pause()
    fun stop() = runCatching { exoPlayer.stop() }.getOrNull()

    /** 是否正在播放（「播放控制」回显用）。 */
    val isPlaying: Boolean
        get() = runCatching { exoPlayer.isPlaying }.getOrDefault(false)

    /**
     * 是否直播。
     *
     * 判据用「时长未知」而不是 `isCurrentMediaItemLive`：
     * 后者在 ExoPlayer 2.18.1 上对**加载中/报错**的项会给出误导性的结果，
     * 而「拿不到时长」在所有直播形态（HLS 直播、RTSP、rtp）上都成立，
     * 且点播一旦就绪必然有 duration —— 这条判据对 UI 的"能不能拖"来说更可靠。
     */
    val isLive: Boolean
        get() = duration <= 0L

    /** 绝对定位（毫秒）。直播未就绪时静默忽略，不抛。 */
    fun seekTo(positionMs: Long) {
        if (positionMs < 0L) return
        runCatching { exoPlayer.seekTo(positionMs) }
            .onFailure { UiLog.w(TAG, "seek 失败：${it.message}") }
    }

    /** 相对跳转（毫秒，负数=后退）。会夹到 `[0, duration]` 区间内。 */
    fun seekBy(deltaMs: Long) {
        val dur = duration
        val target = (currentPosition + deltaMs).let { t ->
            if (t < 0L) 0L else if (dur > 0L && t > dur) dur else t
        }
        seekTo(target)
    }

    fun release() {
        cancelStallWatchdog()
        runCatching {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    /** 容器类型改试顺序，与原版 `onPlayerError` 里的判断顺序一致。 */
    private val CONTAINER_ORDER = listOf(
        C.CONTENT_TYPE_HLS,
        C.CONTENT_TYPE_RTSP,
        C.CONTENT_TYPE_OTHER,
    )
}
