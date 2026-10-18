package top.yogiczy.mytv.legacy.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.appcompat.content.res.AppCompatResources
import top.yogiczy.mytv.legacy.core.UiLog
import top.yogiczy.mytv.legacy.data.ChannelName
import top.yogiczy.mytv.legacy.net.Http
import java.util.concurrent.Executors

/**
 * 台标仓库（多级回退 + 离线兜底）
 *
 * ---------------------------------------------------------------------------
 * 来源：MyTV 1.7（源码 tag `custom-v1.7`，提交 7d1081f）
 *       `tv/ui/screens/channel/components/ChannelItemLogo.kt`
 *       （原版用 Coil 的 `SubcomposeAsyncImage` + `error = {}` 递归实现逐级回退）
 *
 * 移植方式：**回退顺序与前缀常量逐字保留**，实现改为「不带图片库的纯 Android 实现」：
 *   ① 去掉 Coil / Compose（API 19 上都不存在）→ 用 [Http] 取字节 + [BitmapFactory] 解码。
 *   ② 原版靠 `file:///android_asset/...` 交给 Coil；这里直接 `assets.open()`（更快，且
 *      天然离线可用）。**内置优先的顺序完全一致**。
 *   ③ 原版的回退是「异步组件渲染失败再试下一个」；这里等价实现为**后台线程按顺序尝试**，
 *      结果进内存缓存。**这一改动是必需的**：本工程 `bitmapFor()` 由主线程调用
 *      （见 MainActivity 的 renderChannels / selectChannel），同步联网会直接 ANR。
 *
 * 回退顺序（v1.20 需求 3，用户原话：「内置 logo 能覆盖的节目的台标优先用系统内置的版本，
 *   没有的话再用直播源内置的，实在没有再联网加载其他台标 logo 资源」）：
 *   1. **内置台标**（assets/channel_logo）—— 完全离线、瞬时显示。
 *      v1.20 起内置的是 **github.com/nid12345/mytv-logos 全量 932 张**（打包前统一压到
 *      128px 最长边 / 调色板量化，27MB → 6.5MB）。
 *   2. **直播源自带的 `tvg-logo`** —— 最贴合该源，内置没覆盖到时用它。
 *   3. 自建台标仓库 · jsDelivr CDN
 *   4. 自建台标仓库 · raw.githubusercontent 直连
 *   5. 上游台标原站 live.fanmingming.com
 *   6. 上游台标的国内镜像 gitee.com（1.7 的第 5 级，保留）
 *
 *   ⚠️ 顺序即 [candidates] 的返回顺序，[bitmapFor] 严格按序取；
 *      第 1 级命中就**不会再下载**后面任何一级。
 *
 * 文件名换算：一律走 M2 移植的 [ChannelName.logoFileName]
 *   —— 它负责 `CCTV-1`→`CCTV1`、`CCTV-5+`→`CCTV5+`、`凤凰香港`→`凤凰卫视香港台`。
 *   直接按频道名拼 URL 必然 404，症状是「除央视外都有台标」。
 *
 * ⚠️ **v0.3 的 `getDrawable(0)` 崩溃教训**（本文件专门做了防御，见 [safeGetDrawable]）：
 * 用 `resources.getIdentifier()` 按名字找资源，**找不到时返回 0**；
 * 直接把它喂给 `getDrawable()` / `AppCompatResources.getDrawable()` 会抛
 * `Resources.NotFoundException` 并且**在列表批量渲染时一次性崩掉整屏**。
 * 本文件所有「资源 id 可能为 0」的分支都必须先 `!= 0` 判断。
 * ---------------------------------------------------------------------------
 */
object ChannelLogoStore {

    private const val TAG = "台标"

    /** 内置台标所在目录（assets 下）。 */
    private const val LOGO_ASSET_DIR = "channel_logo"

    /** 自建台标仓库：公共 CDN 加速地址。 */
    private const val LOGO_SELF_CDN_PREFIX =
        "https://gcore.jsdelivr.net/gh/nid12345/mytv-logos@main/tv/"

    /** 自建台标仓库：直连地址。 */
    private const val LOGO_SELF_RAW_PREFIX =
        "https://raw.githubusercontent.com/nid12345/mytv-logos/main/tv/"

    /** 上游台标原站。 */
    private const val LOGO_UPSTREAM_PREFIX = "https://live.fanmingming.com/tv/"

    /** 上游台标的国内镜像。 */
    private const val LOGO_GITEE_MIRROR_PREFIX = "https://gitee.com/sujivin/live/raw/main/tv/"

    // ---------------------------------------------------------------------------
    // v1.20 需求 1（线上崩溃 f463af208fa2b6af 的修复）：**远程台标不许再 OOM**
    // ---------------------------------------------------------------------------
    // 真机堆栈（MiTV4-ANSM0 / Android 6.0.1 / v1.19-legacy.24）：
    //   java.lang.OutOfMemoryError: Failed to allocate a 218223756 byte allocation
    //     at android.graphics.BitmapFactory.nativeDecodeByteArray
    //     at ...ChannelLogoStore.enqueueRemote$lambda$33
    // 根因三连，缺一不可：
    //   ① 远程图直接 `BitmapFactory.decodeByteArray(bytes)` —— **不探边界、不算采样**，
    //      一张 25MB / 8000px 的 PNG 就是 2 亿字节的分配请求；
    //   ② `catch (ex: Exception)` 抓不到 `OutOfMemoryError`（它是 Error 不是 Exception），
    //      于是异常从 loader 线程一路抛到默认 UncaughtExceptionHandler → 进程被杀；
    //   ③ 崩溃前没有任何"内存吃紧就先松手"的动作。
    // 对策：下载字节数设上限 → 先解边界再算 inSampleSize → 解不动直接放弃这一张 →
    //       捕获 **Throwable（含 OOM）** → OOM 时立刻清空位图缓存再上报。
    // ---------------------------------------------------------------------------

    /** 单张远程台标的**下载字节上限**；超过就整张放弃（台标显示尺寸只有 64dp）。 */
    private const val MAX_LOGO_BYTES = 512 * 1024

    /** 解码后允许的**最大像素数**；超过按 2 的幂降采样。 */
    private const val MAX_LOGO_PIXELS = 256 * 256

    /** 远程候选顺序（内置单独处理，永远排第一）。 */
    private val REMOTE_PREFIXES = listOf(
        LOGO_SELF_CDN_PREFIX,
        LOGO_SELF_RAW_PREFIX,
        LOGO_UPSTREAM_PREFIX,
        LOGO_GITEE_MIRROR_PREFIX,
    )

    // ---------------- 缓存 ----------------

    private val lock = Any()

    /** 内置文件名索引：小写文件名（去扩展名）→ 真实文件名。对应 1.7 的 `BuiltinLogoIndex`。 */
    @Volatile
    private var builtinIndex: Map<String, String>? = null

    /** 频道名 → 内置文件名 的匹配缓存（v0.3.1 已有，保留）。 */
    private val matchCache = HashMap<String, String?>()

    /** 频道名 → 远程候选地址列表 的缓存，避免每次重算。 */
    private val candidateCache = HashMap<String, List<String>>()

    /**
     * 已解码位图缓存：URL 或 "asset://xxx" → Bitmap。
     *
     * ⚠️ v1.20 改为**按字节**计量（原来是「按张数」的 `LruCache(64)`）：
     * 内置台标从 54 张涨到 v1.20 的 932 张（`github.com/nid12345/mytv-logos` 全量内置），
     * 按张数算完全无法预测实际占多少堆 —— 而老设备（崩溃那台）的可用堆只有几十 MB，
     * 位图缓存是最容易把它撑爆的一块。按字节算才能给出确定的上限。
     *
     * 上限取「进程最大堆的 1/8」，夹在 4MB～12MB 之间：
     *  · 上限太低 → 滚动时反复解码，卡顿；
     *  · 上限太高 → 正是这次 OOM 的成因。
     */
    private val bitmapCache: LruCache<String, Bitmap> by lazy {
        val max = Runtime.getRuntime().maxMemory() / 8
        val bytes = max.coerceIn(4L * 1024 * 1024, 12L * 1024 * 1024).toInt()
        object : LruCache<String, Bitmap>(bytes) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }

    /** 正在后台加载中的键，避免同一张图被重复请求。 */
    private val pending = HashSet<String>()

    /** 远程台标加载完成后的回调（M5 主界面接上它刷新对应卡片）。 */
    @Volatile
    var onLogoLoaded: ((String) -> Unit)? = null

    /** 统一的加载线程（单线程，串行避免同时拉几十张图）。 */
    private val loader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "logo-loader").apply { isDaemon = true }
    }

    // ---------------- 内置索引 ----------------

    /** 全部内置台标文件名（含扩展名）。 */
    fun allNames(ctx: Context): Array<String> {
        builtinIndex?.let { return it.values.toTypedArray() }
        return indexOf(ctx).values.toTypedArray()
    }

    private fun indexOf(ctx: Context): Map<String, String> {
        builtinIndex?.let { return it }
        synchronized(lock) {
            builtinIndex?.let { return it }
            // 注意：assets.list() 在目录不存在/读取失败时返回 null，必须显式兜底成空表
            val list = ctx.assets.list(LOGO_ASSET_DIR)?.toList().orEmpty()
            val map = HashMap<String, String>(list.size * 2)
            list.forEach { fileName ->
                map[fileName.substringBeforeLast('.').lowercase()] = fileName
            }
            builtinIndex = map
            selfCheckOnce(map)
            return map
        }
    }

    /**
     * M13 链路自检（只在首次建立内置索引时跑一次）
     *
     * 目的：把「频道名 → 台标文件名 → 全部候选地址」这条链路的实际结果打进日志。
     * 无 adb 时这是唯一能核对换算是否正确的手段；也便于日后排查「除央视外都没有台标」。
     *
     * 选的三个名字正是最容易出错的特例：
     *   `CCTV-1`   —— 直播源写法带横杠，台标仓库是 `CCTV1.png`
     *   `CCTV-5+`  —— 加号必须保留，且归一化后是 `cctv5plus` 而**不是** `cctv5`
     *   `凤凰香港`  —— 别名表改写为规范名 `凤凰卫视香港台`
     */
    private fun selfCheckOnce(index: Map<String, String>) {
        runCatching {
            UiLog.i(TAG, "链路自检：内置台标 ${index.size} 个，回退顺序＝内置 → CDN → raw → 上游 → gitee镜像")
            listOf("CCTV-1", "CCTV-5+", "凤凰香港").forEach { name ->
                val fileName = ChannelName.logoFileName(name)
                val builtin = index[fileName.lowercase()]?.substringBeforeLast('.')
                UiLog.i(
                    TAG,
                    "链路自检：「$name」→ 文件名「$fileName」→ " +
                            (builtin?.let { "命中内置 $it.png（离线可用）" }
                                ?: "未内置，将依次尝试 $fileName.png @ 4 个远程源")
                )
            }
        }
    }

    // ---------------- 匹配（不联网） ----------------

    /**
     * 返回**内置**台标文件名（不含扩展名），没有则 null。
     *
     * 结果带缓存，避免每次重算。
     *
     * 与原版 v0.3 的实现差异：不再做「双向包含」的模糊匹配 —— 那种匹配会把
     * CCTV1 抢到 CCTV10 的台标（原注释也承认了）。现在一律先用
     * [ChannelName.logoFileName] 把频道名换算成台标仓库的规范写法再查表，
     * 命中即精确，不命中就交给远程回退，比模糊猜测可靠得多。
     */
    fun match(ctx: Context, channelName: String): String? {
        synchronized(lock) { matchCache[channelName]?.let { return it } }

        val result = doMatch(ctx, channelName)

        synchronized(lock) { matchCache[channelName] = result }
        return result
    }

    private fun doMatch(ctx: Context, channelName: String): String? {
        val fileName = ChannelName.logoFileName(channelName)
        if (fileName.isBlank()) return null

        val index = indexOf(ctx)
        if (index.isEmpty()) return null

        val hit = index[fileName.lowercase()]?.substringBeforeLast('.')

        // 换算结果必须留下可核对的痕迹：CCTV-1→CCTV1、CCTV-5+→CCTV5+、凤凰香港→凤凰卫视香港台
        // 都只在这里体现。走 matchCache 保护，同一个频道名只会打一次，不会刷屏。
        UiLog.d(TAG, "台标换算：「$channelName」→「$fileName」 内置命中=${hit ?: "无（转远程回退）"}")

        return hit
    }

    // ---------------- 候选地址 ----------------

    /**
     * 生成某个频道的**全部**候选地址（顺序＝回退顺序）。
     *
     * 第一项固定是内置资源（`asset://` 前缀），其余为远程 URL。
     * 供日志与验证使用；主流程不直接消费它。
     */
    fun candidates(ctx: Context, channelName: String): List<String> =
        candidates(ctx, channelName, null)

    /**
     * 生成某个频道的**全部**候选地址（顺序＝回退顺序），并可带上直播源自带的台标地址。
     *
     * @param remoteUrl 直播源 `#EXTINF` 里的 `tvg-logo`。
     *   **问题 5**：原版 1.7 的解析器（`M3uIptvParser.kt:60`）对每个频道都会给出 logo ——
     *   `tvg-logo ?: "${Constants.CHANNEL_LOGO_SOURCE}/${ChannelName.logoFileName(name)}.png"`；
     *   渲染时 `ChannelItemLogo` 就按这个地址加载。本工程此前**只看频道名**，
     *   于是「换个订阅源就全是白板」——因为那些源里的 `tvg-logo` 根本没被用上。
     *   现在：内置 → 源自带 → 台标仓库四连（去重）。
     */
    fun candidates(ctx: Context, channelName: String, remoteUrl: String?): List<String> {
        if (remoteUrl.isNullOrBlank()) {
            synchronized(lock) { candidateCache[channelName]?.let { return it } }
        }

        val builtin = match(ctx, channelName)
        val fileName = ChannelName.logoFileName(channelName)

        val list = buildList {
            if (builtin != null) add("asset://$builtin")
            // ① 直播源自带的台标（最贴合该源，优先）
            if (!remoteUrl.isNullOrBlank()) add(remoteUrl)
            // ② 台标仓库四连（按频道名换算）—— 与源自带的去重
            if (fileName.isNotBlank()) {
                REMOTE_PREFIXES.forEach { prefix ->
                    val url = "$prefix$fileName.png"
                    if (url != remoteUrl && url !in this) add(url)
                }
            }
        }

        if (remoteUrl.isNullOrBlank()) {
            synchronized(lock) { candidateCache[channelName] = list }
        }
        return list
    }

    // ---------------- 解码（v1.20：全部走"先探边界再采样"） ----------------

    /**
     * 按 [MAX_LOGO_PIXELS] 算 2 的幂采样率。
     *
     * 只用 2 的幂是 `inSampleSize` 的硬约束（非 2 的幂会被向下取整到最近的 2 的幂），
     * 所以这里自己循环而不是直接算比值。上限 32 防止极端尺寸下死循环。
     */
    private fun sampleSizeFor(w: Int, h: Int): Int {
        if (w <= 0 || h <= 0) return 1
        var s = 1
        while (s < 32 && (w / s) * (h / s) > MAX_LOGO_PIXELS) s = s shl 1
        return s
    }

    /**
     * 解码字节数组：**先只解边界**，算出采样率再真正解码。
     *
     * 这是 v1.20 修 OOM 的主防线 —— 老实现是裸的
     * `BitmapFactory.decodeByteArray(bytes, 0, bytes.size)`，
     * 一张 8000×6800 的 PNG 会直接申请 2 亿字节。
     *
     * @return 位图；解不出 / 解到一半 OOM 都返回 **null**（调用方按"这一级失败"处理）
     */
    private fun decodeSampled(bytes: ByteArray): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) null
        else BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(w, h) },
        )
    } catch (t: Throwable) {
        // ⚠️ 必须是 Throwable：`OutOfMemoryError` 是 Error，用 Exception 抓不到，
        // 而它正是线上那条崩溃的类型。抓到后按"这张图放弃"处理，绝不让进程死。
        onDecodeFailed(t, "远程图 ${bytes.size / 1024}KB")
        null
    }

    /** 解码失败（含 OOM）的统一出口：留痕 + 内存吃紧时立刻松手。 */
    private fun onDecodeFailed(t: Throwable, what: String) {
        UiLog.w(TAG, "台标解码失败（$what）：${t.javaClass.simpleName}")
        if (t is OutOfMemoryError) {
            // 已经 OOM 了就先把位图缓存整份丢掉，给进程留一口气 ——
            // 否则下一次分配（哪怕只是普通对象）大概率还是 OOM，进程照样死。
            bitmapCache.evictAll()
            UiLog.w(TAG, "内存吃紧：已清空台标位图缓存")
        }
    }

    // ---------------- 加载 ----------------

    /**
     * 从内置 assets 解码（同步、离线、瞬时）。
     *
     * v1.20：同样走采样解码。内置台标虽然已在打包前统一压到 128px 最长边，
     * 但这里仍防御一次 —— assets 目录是可被外部替换/追加的，不能假设每张都小。
     */
    fun load(ctx: Context, name: String): Bitmap? {
        val key = "asset://$name"
        bitmapCache.get(key)?.let { return it }

        return try {
            // 一次性读成字节再走 [decodeSampled]：assets 流不可回退，
            // 「探边界 + 真解」两次解码必须重新开流，读字节反而更省事也更稳。
            val bytes = ctx.assets.open("$LOGO_ASSET_DIR/$name.png").use { it.readBytes() }
            decodeSampled(bytes)?.also { bitmapCache.put(key, it) }
        } catch (t: Throwable) {
            onDecodeFailed(t, "内置 $name")
            null
        }
    }

    /** 取一张远程台标的字节，**读到 [MAX_LOGO_BYTES] 就放弃**（不下载完整张大图）。 */
    private fun fetchCapped(url: String): ByteArray {
        val head = Http.open(url) { resp ->
            val body = resp.body() ?: return@open ByteArray(0)
            // 先看声明长度：明显超限的连正文都不读
            val declared = body.contentLength()
            if (declared > MAX_LOGO_BYTES) {
                UiLog.d(TAG, "台标过大（声明 ${declared / 1024}KB）→ 放弃：$url")
                return@open ByteArray(0)
            }
            val buf = ByteArray(8 * 1024)
            val out = java.io.ByteArrayOutputStream()
            body.byteStream().use { src ->
                while (true) {
                    val n = src.read(buf)
                    if (n <= 0) break
                    if (out.size() + n > MAX_LOGO_BYTES) {
                        // 声明长度不可信（分块传输可能没有 Content-Length）时的兜底
                        UiLog.d(TAG, "台标过大（实际超过 ${MAX_LOGO_BYTES / 1024}KB）→ 放弃：$url")
                        return@open ByteArray(0)
                    }
                    out.write(buf, 0, n)
                }
            }
            out.toByteArray()
        }
        return head
    }

    /**
     * 取频道台标。
     *
     * **同步部分**：内置资源 + 已缓存位图 —— 只做本地 IO，主线程可安全调用。
     * **异步部分**：命中不了内置时，把远程回退丢给后台线程；本次返回 `null`，
     * 加载成功后再通过 [onLogoLoaded] 通知界面刷新。
     *
     * 这样保证了：「断网时内置台标照常显示」+「联网时非内置频道最终也能拿到台标」
     * +「主线程永不联网」三者同时成立。
     */
    fun bitmapFor(ctx: Context, channelName: String): Bitmap? =
        bitmapFor(ctx, channelName, null)

    /**
     * 取频道台标（问题 5：支持直播源自带的 `tvg-logo`）。
     *
     * **同步部分**：内置资源 + 已缓存位图 —— 只做本地 IO，主线程可安全调用。
     * **异步部分**：命中不了内置时，把远程回退丢给后台线程；本次返回 `null`，
     * 加载成功后再通过 [onLogoLoaded] 通知界面刷新。
     *
     * 这样保证了：「断网时内置台标照常显示」+「联网时非内置频道最终也能拿到台标」
     * +「主线程永不联网」三者同时成立。
     */
    fun bitmapFor(ctx: Context, channelName: String, remoteUrl: String?): Bitmap? {
        if (channelName.isBlank()) return null

        // ⚠️ v1.8：设置页「台标显示」开关在这里统一生效 —— 关闭时**所有位置**
        // （频道列表卡片 / 右上台标 / 换台信息块）都不再加载台标。
        // 之前那个开关只是往 Prefs 里写了个值，没有任何地方读它（用户："纯粹是摆设按钮"）。
        // 放在这一层而不是每个调用点，是为了保证"关就全关"，不会出现只关了一半。
        if (!top.yogiczy.mytv.legacy.data.Prefs.uiShowChannelLogo(ctx)) return null

        // ① 内置：直接同步返回，断网也有台标
        match(ctx, channelName)?.let { builtin ->
            load(ctx, builtin)?.let { return it }
        }

        // ② 远程各候选：已经加载过的直接返回
        val list = candidates(ctx, channelName, remoteUrl).filter { it.startsWith("http") }
        list.forEach { url ->
            bitmapCache.get(url)?.let { return it }
        }

        // ③ 尚未加载 → 丢后台，本次返回 null（界面按「无台标」渲染，不崩）
        enqueueRemote(ctx.applicationContext, channelName, list)
        return null
    }

    /**
     * **只读缓存**，绝不触发网络。
     *
     * 远程台标加载完成后回填界面时用。**不能再走 [bitmapFor]**：带 `tvg-logo` 的订阅源里，
     * 按频道名重新解析出的候选列表与真正加载成功的那条 URL 不同，会再次返回 null，
     * 界面就永远刷不出台标（问题 5 的第二个坑）。
     */
    fun cachedBitmapFor(ctx: Context, channelName: String, remoteUrl: String?): Bitmap? {
        if (!top.yogiczy.mytv.legacy.data.Prefs.uiShowChannelLogo(ctx)) return null
        match(ctx, channelName)?.let { builtin ->
            load(ctx, builtin)?.let { return it }
        }
        candidates(ctx, channelName, remoteUrl)
            .filter { it.startsWith("http") }
            .forEach { url -> bitmapCache.get(url)?.let { return it } }
        return null
    }

    private fun enqueueRemote(ctx: Context, channelName: String, urls: List<String>) {
        if (urls.isEmpty()) return

        val key = "$channelName|${urls.first()}"
        synchronized(lock) {
            if (key in pending) return
            pending.add(key)
        }
        // 问题 5：把**首个候选地址**记进日志 —— 源自带的 tvg-logo 是否真的被用上，
        // 这是唯一能（无 adb 时也能）核对的痕迹。
        UiLog.d(TAG, "台标候选（按序尝试）：$channelName ← ${urls.first()}")

        loader.execute {
            var ok = false
            try {
                for (url in urls) {
                    if (bitmapCache.get(url) != null) {
                        ok = true
                        break
                    }
                    val bytes = try {
                        fetchCapped(url)
                    } catch (ex: Exception) {
                        // 逐级回退：这一级失败不报错，直接试下一级（对应原版 error = {} 递归）
                        UiLog.d(TAG, "台标候选失败：$url（${ex.message ?: ex.javaClass.simpleName}）")
                        continue
                    }
                    if (bytes.isEmpty()) continue          // 超限被放弃 / 空响应 → 也算这一级失败
                    // 解码失败（拿到了 HTML 错误页等）或 OOM → 都算这一级失败，绝不让进程死
                    val bitmap = decodeSampled(bytes)
                    if (bitmap == null) {
                        UiLog.d(TAG, "台标候选解码失败：$url（${bytes.size} 字节）")
                        continue
                    }
                    bitmapCache.put(url, bitmap)
                    ok = true
                    UiLog.i(TAG, "台标已加载（远程）：${ChannelName.logoFileName(channelName)} ← $url")
                    break
                }
            } catch (t: Throwable) {
                // ★ 最后一道网：catch **Throwable**。v1.19 的 `catch (Exception)` 抓不到
                // OutOfMemoryError，异常从本线程抛到默认 UncaughtExceptionHandler → 进程被杀，
                // 用户看到的就是"看电视看着看着闪退"。现在最坏情况只是这张台标没加载出来。
                onDecodeFailed(t, "候选链 $channelName")
            } finally {
                synchronized(lock) { pending.remove(key) }
                if (ok) {
                    onLogoLoaded?.invoke(channelName)
                } else {
                    // 全部候选都失败 → 优雅降级：界面按文字占位渲染，不崩
                    UiLog.i(TAG, "台标不可用，降级为文字占位：$channelName")
                }
            }
        }
    }

    // ---------------- 资源 id 防御（v0.3 崩溃教训） ----------------

    /**
     * 按资源名取 Drawable，**资源不存在时返回 null 而不是崩**。
     *
     * v0.3 的崩溃根因：`resources.getIdentifier()` 找不到名字时返回 **0**，
     * 直接 `getDrawable(0)` 会抛 `Resources.NotFoundException`；
     * 而这条路径在列表批量渲染里，一次异常就把整屏频道全部带崩。
     *
     * 所以这里把「id == 0」当成**正常分支**显式处理掉，而不是等它抛异常。
     */
    private fun safeGetDrawable(ctx: Context, resourceName: String): Drawable? {
        val resId = try {
            ctx.resources.getIdentifier(resourceName, "drawable", ctx.packageName)
        } catch (t: Throwable) {
            0
        }

        // ★ 关键防御：0 表示资源不存在，绝不能传给 getDrawable
        if (resId == 0) return null

        return try {
            AppCompatResources.getDrawable(ctx, resId)
        } catch (t: Throwable) {
            UiLog.d(TAG, "drawable 读取失败：$resourceName")
            null
        }
    }

    /**
     * 取频道的台标 Drawable：内置位图 → 按资源名查 drawable → null。
     *
     * 供需要 Drawable 而非 Bitmap 的调用点使用（例如分组栏图标）。
     * 显式走 [safeGetDrawable]，因此**资源缺失时返回 null 而非崩溃**。
     */
    fun drawableFor(ctx: Context, channelName: String): Drawable? {
        bitmapFor(ctx, channelName)?.let { return android.graphics.drawable.BitmapDrawable(ctx.resources, it) }

        val fileName = ChannelName.logoFileName(channelName)
        if (fileName.isBlank()) return null

        // 约定：打包进 res/drawable 的台标命名为 logo_<文件名小写>
        return safeGetDrawable(ctx, "logo_${fileName.lowercase()}")
    }

    /** 清空全部缓存（换源、重装台标镜像后调用）。 */
    fun clearCache() {
        synchronized(lock) {
            matchCache.clear()
            candidateCache.clear()
            pending.clear()
            builtinIndex = null
        }
        bitmapCache.evictAll()
    }
}
