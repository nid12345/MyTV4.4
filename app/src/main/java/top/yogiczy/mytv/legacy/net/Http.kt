package top.yogiczy.mytv.legacy.net

import android.content.Context
import android.util.Log
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.Security
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * OkHttp 统一封装
 *
 * ---------------------------------------------------------------------------
 * 对齐原版（MyTV 1.7，源码 tag `custom-v1.7`）：
 *
 *   原版位置                                        → 本文件对应
 *   ------------------------------------------------------------------------
 *   core/data/utils/Constants.kt
 *     `VIDEO_PLAYER_USER_AGENT = "ExoPlayer"`        → [DEFAULT_USER_AGENT]
 *     `VIDEO_PLAYER_LOAD_TIMEOUT = 1000L * 15`       → [DEFAULT_LOAD_TIMEOUT_MS]
 *   tv/ui/utils/Configs.kt
 *     `videoPlayerUserAgent` / `videoPlayerLoadTimeout`
 *     → 由 [Prefs] 提供（本文件只给默认值与构件工厂）
 *   media3 播放器的网络层：跟随 302、允许 http↔https 跨协议重定向
 *     → [client] 里显式打开 `followRedirects` + `followSslRedirects`
 *
 * ⚠️ **与原版的不同点（必须说明）**：原版把所有网络都交给 media3/OkHttp 默认客户端，
 * 没有集中式封装。本工程因为 **API 19 上不可能有 media3**，改用 **OkHttp 3.12.13**
 * （4.x 要求 API 21），于是需要一个统一出口来保证「UA / 超时 / 重定向」三处一致，
 * 否则每个调用点各写一遍必然漂移。这是**结构性改动，不是逻辑改动**。
 *
 * 线程约定：本文件全部方法都是**同步阻塞**的（本工程不引协程）。
 * 调用方必须在后台线程执行，禁止在主线程直接调用。
 * ---------------------------------------------------------------------------
 */
object Http {

    /**
     * TLS 协商结果的 logcat 标签。
     *
     * ⚠️ 用**中文字面量**而非 `javaClass.simpleName`：R8 会把类名混淆成单字母，
     * 按 tag 筛日志会全部落空（M0 期踩过）。
     */
    private const val TLS_TAG = "网络TLS"

    /** 与 `Constants.VIDEO_PLAYER_USER_AGENT` 一致。 */
    const val DEFAULT_USER_AGENT = "ExoPlayer"

    /** 与 `Constants.VIDEO_PLAYER_LOAD_TIMEOUT` 一致：15 秒。 */
    const val DEFAULT_LOAD_TIMEOUT_MS = 1_000L * 15

    /** 节目单来源的连接超时（M2 §6 指定：连接 15s）。 */
    const val EPG_CONNECT_TIMEOUT_MS = 1_000L * 15

    /** 节目单来源的读取超时（M2 §6 指定：读取 60s）。节假日的完整 EPG 有十几 MB。 */
    const val EPG_READ_TIMEOUT_MS = 1_000L * 60

    /**
     * 网络失败。`message` 是**可直接拼接给用户看的中文原因**
     * （供 EpgRepository 做「逐源失败原因拼接」）。
     */
    class HttpError(reason: String) : Exception(reason)

    // =========================================================================
    // TLS 1.2（API 19 必需）—— 见 PROJECT-STATE.md 阻塞 B1
    // =========================================================================
    //
    // 【问题】API 19（Android 4.4）的默认 `SSLContext` 只协商 TLSv1 / TLSv1.1，
    // 而现代 CDN 只收 TLS 1.2+，于是每一个 https 请求都在握手阶段就被服务端拒掉：
    //
    //   SSLHandshakeException: javax.net.ssl.SSLProtocolException: SSL handshake aborted
    //   error:1407742E:SSL routines:SSL23_GET_SERVER_HELLO:tlsv1 alert protocol version
    //
    // 【为什么 App.kt 的补丁救不了 OkHttp】`App.enableTls12()` 调的是
    // `HttpsURLConnection.setDefaultSSLSocketFactory(...)` —— **只影响 HttpsURLConnection**。
    // OkHttp **不会**去读那个静态默认值，它自己从 `SSLContext.getInstance("TLS")`
    // 取 socketFactory（API 19 上解析到旧 provider，只有 TLSv1/1.1）。
    // 所以必须在这里**显式**给 OkHttpClient.Builder 配上 TLSv1.2 的 socketFactory。
    //
    // 【为什么只改这里就够了两条 HTTPS 通路】
    //   - OkHttp（本文件 / 台标 / 节目单 / 直播源）→ 本文件 [client] 统一配
    //   - ExoPlayer 的 `DefaultHttpDataSource` → **实测确认它走 `HttpURLConnection`**
    //     （反汇编 `exoplayer-datasource-2.18.1.aar` 的 `DefaultHttpDataSource.class`：
    //      `HttpURLConnection` 出现 8 次、`java.net.URL` 10 次，`okhttp3` **0 次**），
    //     因此它继承 `HttpsURLConnection` 的静态默认值 → **已被 App.kt 的补丁覆盖**。
    //
    // 【为什么不 `init(null, null, null)` 就完事】`init` 的第二个参数为 null 时用的是
    // **系统默认 TrustManager**，也就是 API 19 那份 2014 年的 CA 库。TLS 版本修好之后，
    // 还会撞上「根证书不在库里的 Trust anchor 问题」。这里显式取出来传给
    // `sslSocketFactory`，是为了让「用哪套信任链」这件事**可见、可替换、可记录**，
    // 而不是隐式依赖静态默认值。
    // =========================================================================

    /** TLS 1.2 上下文。设备不支持时返回 null（此时退回系统默认，行为等同改动前）。 */
    private val tlsContext: SSLContext? by lazy {
        // ⚠️ 必须**先**把 Conscrypt 装好再 getInstance，否则这里会拿到系统那套
        // 没有 AEAD 的老实现（v1.6 踩到：顺序反了等于没装）。
        ensureTlsReady()
        // ⚠️ 这里传的是 [trustManager]（系统信任链 ∪ 随包根证书），**不是** null。
        // 传 null 只会用系统那份老 CA 库，见下面 [EXTRA_CA_FILES] 的长注释。
        val tms = trustManager?.let { arrayOf<javax.net.ssl.TrustManager>(it) }
        runCatching { SSLContext.getInstance("TLSv1.2").apply { init(null, tms, null) } }
            .getOrNull()
    }

    // =========================================================================
    // v1.17：随包附带的现代根证书
    // =========================================================================
    //
    // 【为什么要补】老设备（Android 4.4 / 6.0）自带的 CA 库是随系统发布的，**不会再更新**。
    // 而证书颁发机构这几十年换了根：v1.17 在 API 19 模拟器上实测 `api.github.com` 的现场链条是
    //
    //   [0] CN=*.github.com
    //   [1] CN=Sectigo Public Server Authentication CA DV E36
    //   [2] CN=Sectigo Public Server Authentication Root E46  ← issuer = USERTrust ECC CA
    //
    // 系统库里没有 `USERTrust ECC Certification Authority`，于是 PKIX 找不到锚点：
    //
    //   javax.net.ssl.SSLHandshakeException:
    //     java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.
    //
    // 同一台设备上**内置的「检查更新」也一直在报同一个错**（raw.githubusercontent.com /
    // gh-proxy.com / ghproxy.net 全部 Trust anchor 失败），只是静默降级没人看见。
    //
    // 【为什么是"追加"而不是"全部信任"】只往信任库里**增加**公开 CA 的根证书，
    // 系统原本的校验一条都不放松：先按系统信任链验，验不过才用随包根证书再验一次。
    // 所以这不是 `TrustAllCerts` 那种关掉校验，而是"把现代设备的根证书库搬到老设备上"。
    //
    // 【这些根证书是哪来的】`app/src/main/assets/ca/`，取自 macOS 系统根证书库
    // （`/System/Library/Keychains/SystemRootCertificates.keychain`），逐个核过
    // CA:TRUE 与公开的 SHA-256 指纹（见该目录下 4 个 .pem 的文件名与 README 记录）。
    // =========================================================================

    /** 随包根证书清单（`assets/` 下）。 */
    private val EXTRA_CA_FILES = arrayOf(
        "ca/usertrust_ecc.pem",     // Sectigo/USERTrust ECC 根（GitHub 现在这条链需要它）
        "ca/usertrust_rsa.pem",     // Sectigo/USERTrust RSA 根（另一条常见链）
        "ca/isrg_x1.pem",           // Let's Encrypt ISRG Root X1
        "ca/digicert_g2.pem",       // DigiCert Global Root G2（大量 CDN 在用）
    )

    /** 应用上下文，只用来读上面那份 assets。由 `App.onCreate` 调 [init] 注入。 */
    @Volatile
    private var appContext: Context? = null

    /**
     * 注入上下文（必须在任何网络调用**之前**调用，`App.onCreate` 的第一件事）。
     *
     * 为什么不让 [Http] 自己去拿：本工程没有全局单例持有 Context 的惯例，
     * 与其偷偷从 `ActivityThread` 反射取，不如显式注入一次、来源清楚。
     */
    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        ensureTlsReady()
    }

    /** 随包根证书构成的 TrustManager（assets 读不到时返回 null，行为退回改动前）。 */
    private val extraTrustManager: X509TrustManager? by lazy {
        val ctx = appContext
        if (ctx == null) {
            Log.i(TLS_TAG, "额外根证书：未注入 Context，跳过（Http.init 未被调用）")
            null
        } else {
            runCatching {
                val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
                val cf = java.security.cert.CertificateFactory.getInstance("X.509")
                var loaded = 0
                EXTRA_CA_FILES.forEach { path ->
                    runCatching {
                        ctx.assets.open(path).use { input ->
                            ks.setCertificateEntry(path.substringAfterLast('/'), cf.generateCertificate(input))
                        }
                        loaded++
                    }.onFailure { Log.w(TLS_TAG, "额外根证书读取失败：$path（${it.javaClass.simpleName}）") }
                }
                if (loaded == 0) {
                    Log.i(TLS_TAG, "额外根证书：一个都没读到，继续用系统信任链")
                    null
                } else {
                    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    tmf.init(ks)
                    val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                    Log.i(TLS_TAG, "额外根证书：已装载 $loaded 个（assets/ca）")
                    tm
                }
            }.getOrNull()
        }
    }

    /** 真正生效的信任链：系统 ∪ 随包（有哪个用哪个，两个都没有就返回 null = 系统默认）。 */
    private val trustManager: X509TrustManager? by lazy {
        val sys = defaultTrustManager
        val extra = extraTrustManager
        when {
            sys != null && extra != null -> CompositeTrustManager(sys, extra) {
                if (!loggedExtraCaHit) {
                    loggedExtraCaHit = true
                    Log.i(TLS_TAG, "证书链由随包根证书验证通过（系统库缺根）")
                }
            }
            else -> sys ?: extra
        }
    }

    /** 是否已经因为"系统库里没有"而走了随包根证书（只打一次日志，避免刷屏）。 */
    @Volatile
    private var loggedExtraCaHit = false

    /**
     * 组合信任链：**先按系统信任链验，验不过再按随包根证书验一次**。
     *
     * 顺序不能反：正常情况下必须走系统那套（保持行为与改动前一致），
     * 只有在老设备撞上"根证书不在库里"时才落到第二道。
     */
    private class CompositeTrustManager(
        private val primary: X509TrustManager,
        private val secondary: X509TrustManager,
        /** 落到第二道信任链时回调一次（宿主用来打一行现场日志）。 */
        private val onFallback: () -> Unit,
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
            try {
                primary.checkClientTrusted(chain, authType)
            } catch (e: java.security.cert.CertificateException) {
                secondary.checkClientTrusted(chain, authType)
            }
        }

        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
            try {
                primary.checkServerTrusted(chain, authType)
            } catch (e: java.security.cert.CertificateException) {
                // 落到这里 = 系统信任链不认（老设备缺根证书）。用随包根证书再验一次；
                // 还是不认就把**第二道的异常**抛出去（它更接近真实原因，且不会误导成"证书无效"）。
                secondary.checkServerTrusted(chain, authType)
                onFallback()
            }
        }

        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
            primary.acceptedIssuers + secondary.acceptedIssuers
    }

    /**
     * 幂等：设备不支持 AEAD 套件时装载 Conscrypt。
     *
     * 【为什么需要】v1.6 真机反馈「点开其他订阅源加载不出来」，在 API 19 上逐源实测定位到：
     * 该设备的 `supportedCipherSuites` 共 53 个，**一个 AES-GCM / ChaCha20 都没有**，
     * 于是 ClientHello 里只有 CBC，被 `sub.ottiptv.cc` 这类只收 AEAD 的服务端直接拒绝：
     *
     *   error:14077410:SSL routines:SSL23_GET_SERVER_HELLO:sslv3 alert handshake failure
     *
     * 这**不是证书信任问题**，是套件协商问题；平台层无解，只能换一套 TLS 实现。
     * Conscrypt（Google 维护，Apache-2.0，官方支持 API 19+）自带的 BoringSSL 提供完整
     * 现代套件。判定依据用「套件表里有没有 AEAD」而**不是** `SDK_INT` —— 更贴近事实。
     *
     * 装载失败（例如该 ABI 没有随包 native 库）时静默继续用系统实现，不影响其它功能。
     */
    @Synchronized
    private fun ensureTlsReady() {
        if (tlsReady) return
        tlsReady = true

        // ⚠️ 这里**不做条件判断**，一律装载：曾经用「平台套件表里有没有 AEAD」做开关，
        // 但探测用的 `socketFactory.createSocket()`（无参）在 API 19 上会抛异常，
        // 被 runCatching 兜成"支持" → 直接跳过 Conscrypt（实测踩到，白跑一轮）。
        // 无条件装载最稳：Conscrypt 在 API 21+ 同样可用（Android 7+ 系统自带），
        // 失败（例如该 ABI 没打进 native 库）也只是回落到系统实现，不影响功能。
        val platformAead = platformSupportsAead()
        val result = runCatching {
            org.conscrypt.Conscrypt.newProvider()
            Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1)
            // ⚠️ 复检**不能**用 `createSocket(host, port)`：那会触发 DNS 解析，
            // 若本方法恰好跑在主线程（首次 `Http.client()` 可能来自 UI 线程）会抛
            // NetworkOnMainThreadException，把"其实装成功了"误报成失败（实测踩到）。
            // 只读 `supportedCipherSuites` 属性，不做任何 IO。
            val ctx = SSLContext.getInstance("TLSv1.2").apply { init(null, null, null) }
            Triple(
                ctx.provider.name,
                ctx.socketFactory.supportedCipherSuites.any { it.contains("_GCM_") || it.contains("CHACHA20") },
                ctx.supportedSSLParameters.protocols.contains("TLSv1.2"),
            )
        }
        Log.i(
            TLS_TAG,
            if (result.isSuccess) {
                val (provider, aead, tls12) = result.getOrThrow()
                "平台 AEAD=$platformAead；Conscrypt 已装载（provider=$provider AEAD=$aead TLS1.2=$tls12）"
            } else {
                "平台 AEAD=$platformAead；Conscrypt 装载失败，继续用系统实现：" +
                    (result.exceptionOrNull()?.javaClass?.simpleName ?: "未知")
            }
        )
    }

    /** 平台默认 `SSLSocketFactory` 的套件表里是否有 AEAD（GCM / ChaCha20）。仅用于日志。 */
    private fun platformSupportsAead(): Boolean = runCatching {
        // 同上：只用属性，不建连、不解析域名。
        SSLContext.getInstance("TLS").socketFactory
            .supportedCipherSuites.any { it.contains("_GCM_") || it.contains("CHACHA20") }
    }.getOrDefault(false)

    /** 是否已装载 [tlsReady] 流程。 */
    @Volatile
    private var tlsReady = false

    /** 系统默认信任链（API 19 的 CA 库）。 */
    private val defaultTrustManager: X509TrustManager? by lazy {
        ensureTlsReady()   // 同上：先装 Conscrypt，再取 TrustManager（否则拿到的是老实现）
        runCatching {
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as KeyStore?) }
                .trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull()
        }.getOrNull()
    }

    /**
     * 把 TLS 1.2 配到一个 Builder 上。
     *
     * 抽成公开函数是为了让**任何**自建 OkHttpClient 的调用点（例如 MainActivity 的
     * 源列表加载）都能复用同一份配置 —— 否则就会出现「这里修好了、那里还是裸客户端」
     * 的漏网（B1 修复时实际踩到过：`MainActivity.loadSource` 用的是 `OkHttpClient()`）。
     */
    fun applyTls(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val factory = tls12SocketFactory()
        // v1.17：这里用 [trustManager]（系统 ∪ 随包根证书）而不是 [defaultTrustManager] ——
        // 理由见 [EXTRA_CA_FILES] 那段（老的 Android 信任库里没有 GitHub 现在这条链的锚点）。
        val tm = trustManager
        if (factory != null && tm != null) {
            builder.sslSocketFactory(factory, tm)
        }
        return builder
    }

    // =========================================================================
    // 播放链路（ExoPlayer 的 DefaultHttpDataSource）的 TLS 1.2
    // =========================================================================
    //
    // 【为什么这里必须另外想办法】`javap` 反汇编 `exoplayer-datasource-2.18.1.aar`：
    //   DefaultHttpDataSource$Factory 只有 setUserAgent / setConnectTimeoutMs /
    //   setReadTimeoutMs / setAllowCrossProtocolRedirects / setContentTypePredicate /
    //   setTransferListener / setKeepPostFor302Redirects / setDefaultRequestProperties
    //   —— **没有 setSslSocketFactory**；DefaultHttpDataSource 的构造函数也不收
    //   SSLSocketFactory。所以在 2.18.1 上「给 Factory 配 TLS」这条路**根本不存在**。
    //
    // 【实际走的通路】DefaultHttpDataSource 内部用 `java.net.URL.openConnection()`
    //   → `HttpsURLConnection`，而 `HttpsURLConnection` 在**没有**单独设置时用
    //   `getDefaultSSLSocketFactory()` 的静态默认值。因此唯一可行的做法是
    //   把 TLS 1.2 的 factory 装到那个静态默认值上（见 [installDefaultTls12]）。
    //   原版（media3 的 DefaultHttpDataSource）同样是基于 HttpURLConnection，
    //   所以这与原版**同一条通路**，不是新增行为。
    // =========================================================================

    /** 优先启用的协议顺序（只从设备实际支持的里面挑）。 */
    private val PREFERRED_PROTOCOLS = arrayOf("TLSv1.2", "TLSv1.1", "TLSv1")

    /**
     * 优先启用的密码套件（只从设备实际支持的里面挑），**AEAD 在前、CBC 在后**。
     *
     * 【为什么必须显式指定】v1.6 真机反馈：「点开其他订阅源加载不出来，然后自动换成
     * 演示源」。在 API 19 模拟器上逐源实测定位到根因 —— `sub.ottiptv.cc` 这一类服务端
     * **只接受现代 AEAD 套件**（GCM / ChaCha20），会直接拒绝只给 CBC 的 ClientHello：
     *
     *   error:14077410:SSL routines:SSL23_GET_SERVER_HELLO:sslv3 alert handshake failure
     *
     * 该结论可用本机复现：`curl --tlsv1.2 --ciphers 'ECDHE-RSA-AES128-SHA:AES128-SHA:...'`
     * 会得到**同一个** sslv3 alert；换成默认（含 GCM）即 200。
     *
     * Android 4.4 的 `SSLSocket` 默认套件表以 CBC 为主、顺序也不利于服务端挑选，
     * 于是"协议版本补到 1.2 了、仍然握手失败"。这里把 AEAD 提到最前，
     * 让 ClientHello 里先出现服务端认的那几个。
     */
    private val PREFERRED_CIPHERS = arrayOf(
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384",
        // 以下为回落：老服务端仍可能只认 CBC
        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
        "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
        "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
        "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
        "TLS_DHE_RSA_WITH_AES_128_CBC_SHA",
        "TLS_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_RSA_WITH_AES_128_CBC_SHA",
        "TLS_RSA_WITH_AES_256_CBC_SHA",
        "TLS_RSA_WITH_3DES_EDE_CBC_SHA",
    )

    /** 记录一次「实际生效的协议/套件」，便于真机上核对（只打一次，避免刷日志）。 */
    @Volatile
    private var loggedNegotiationCapability = false

    /**
     * TLS 1.2 的 `SSLSocketFactory`。返回 null 表示设备不支持（此时退回系统默认）。
     *
     * **做了「强制启用协议」这一层包装**：`SSLContext.getInstance("TLSv1.2")` 只是让
     * 上下文**支持** 1.2，它产出的 socket 的 `enabledProtocols` 在部分 4.x 设备上
     * 仍只有 TLSv1。这里把 socket 的 enabledProtocols 显式改成
     * 「设备支持的 ∩ 优先列表」，确保握手时真的会把 TLSv1.2 发在 ClientHello 里。
     */
    fun tls12SocketFactory(): SSLSocketFactory? {
        val ctx = tlsContext ?: return null
        return runCatching {
            ForcedTlsSocketFactory(ctx.socketFactory, PREFERRED_PROTOCOLS, PREFERRED_CIPHERS)
        }.getOrNull() ?: runCatching { ctx.socketFactory }.getOrNull()
    }

    /**
     * 把 [tls12SocketFactory] 装到 `HttpsURLConnection` 的静态默认值上。
     *
     * 这是**播放链路（ExoPlayer）拿到 TLS 1.2 的唯一途径**（理由见上一段注释）。
     * 幂等：重复调用只是重新赋同一个值。
     *
     * @return 是否真的装上去了（false = 设备不支持 TLS 1.2，此时保持系统默认）
     */
    fun installDefaultTls12(): Boolean {
        ensureTlsReady()
        val factory = tls12SocketFactory() ?: return false
        return runCatching {
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(factory)
        }.isSuccess
    }

    /**
     * 强制启用高版本 TLS 的 `SSLSocketFactory` 包装。
     *
     * 做两件事：
     *  ① 把 `enabledProtocols` 改成「设备支持 ∩ [preferredProtocols]」（必须有，否则
     *     部分 4.x 设备默认只发 TLSv1）；
     *  ② 把 `enabledCipherSuites` 改成「设备支持 ∩ [preferredCiphers]」，**AEAD 排前** ——
     *     否则只发 CBC 会被现代服务端以 `sslv3 alert handshake failure` 直接拒掉（v1.6 根因）。
     */
    private class ForcedTlsSocketFactory(
        private val delegate: SSLSocketFactory,
        private val preferredProtocols: Array<String>,
        private val preferredCiphers: Array<String>,
    ) : SSLSocketFactory() {

        private fun patch(socket: Socket): Socket {
            if (socket is SSLSocket) {
                runCatching {
                    val supProto = socket.supportedProtocols
                    val enProto = preferredProtocols.filter { supProto.contains(it) }.toTypedArray()
                    if (enProto.isNotEmpty()) socket.enabledProtocols = enProto

                    val supCiphers = socket.supportedCipherSuites.toSet()
                    // 先把偏好列表里设备支持的按偏好顺序排好，再把其余支持的补在后面，
                    // 避免"设备支持但不在偏好表里"的套件被我们一刀切掉。
                    val picked = ArrayList<String>(supCiphers.size)
                    preferredCiphers.forEach { if (supCiphers.contains(it)) picked.add(it) }
                    supCiphers.forEach { if (!picked.contains(it)) picked.add(it) }
                    if (picked.isNotEmpty()) socket.enabledCipherSuites = picked.toTypedArray()

                    if (!loggedNegotiationCapability) {
                        loggedNegotiationCapability = true
                        Log.i(
                            TLS_TAG,
                            "TLS 能力已强制：协议=${socket.enabledProtocols.joinToString(",")} " +
                                "套件数=${socket.enabledCipherSuites.size} " +
                                "前4=${socket.enabledCipherSuites.take(4).joinToString(",")}"
                        )
                        // 完整套件表（只打一次）：判定设备到底有没有 AEAD 套件，
                        // 这是"能不能协商上现代服务端"的硬依据（API 19 实测没有）。
                        Log.i(TLS_TAG, "支持的全部套件=${socket.supportedCipherSuites.joinToString(",")}")
                    }
                }
            }
            return socket
        }

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            patch(delegate.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String, port: Int): Socket =
            patch(delegate.createSocket(host, port))

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            patch(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress, port: Int): Socket =
            patch(delegate.createSocket(host, port))

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            patch(delegate.createSocket(address, port, localAddress, localPort))
    }

    /**
     * 客户端缓存：同一组 (连接超时, 读取超时, UA) 复用同一个客户端。
     *
     * 复用是为了共用连接池 —— 每条线路各建一个客户端会让线程与连接数成倍增长
     * （原版 `ChannelLineSpeedRepository` 的注释里也记着同一个教训）。
     */
    private val clients = HashMap<String, OkHttpClient>()

    /**
     * 取（或构建）一个客户端。
     *
     * 显式打开三个开关：
     *  - `followRedirects(true)` —— 跟随 302/301（IPTV 源大量使用跳转）
     *  - `followSslRedirects(true)` —— 允许 **http ↔ https 跨协议**跳转
     *    （只开前者的话，http→https 的跳转会被 OkHttp 拒绝，这是最常见的「源明明能播却拉不到」原因）
     *  - `retryOnConnectionFailure(true)` —— 老设备上偶发的连接复用失败自动重试
     *
     * 另外调用 [applyTls] 挂上 TLS 1.2（API 19 必需，见上文长注释）。
     */
    fun client(
        connectTimeoutMs: Long = DEFAULT_LOAD_TIMEOUT_MS,
        readTimeoutMs: Long = DEFAULT_LOAD_TIMEOUT_MS,
        userAgent: String = DEFAULT_USER_AGENT,
    ): OkHttpClient {
        val key = "$connectTimeoutMs/$readTimeoutMs/$userAgent"
        synchronized(clients) {
            clients[key]?.let { return it }

            // UA 用拦截器统一注入：调用点不需要记得每条请求都写一遍
            val uaInterceptor = Interceptor { chain ->
                val request = chain.request()
                val withUa = if (request.header("User-Agent") == null && userAgent.isNotBlank()) {
                    request.newBuilder().header("User-Agent", userAgent).build()
                } else {
                    request
                }
                chain.proceed(withUa)
            }

            val builder = OkHttpClient.Builder()
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .addInterceptor(uaInterceptor)

            val built = applyTls(builder).build()

            clients[key] = built
            return built
        }
    }

    /**
     * 打开一个连接，把**已确认响应成功**的 [Response] 交给 [block]，
     * 无论成功失败都由本函数负责关闭。
     *
     * @throws HttpError 地址无效 / 网络异常 / HTTP 4xx-5xx，`message` 为中文原因
     */
    fun <T> open(
        url: String,
        client: OkHttpClient = client(),
        block: (Response) -> T,
    ): T {
        if (url.isBlank()) throw HttpError("地址为空")

        val request = try {
            Request.Builder().url(url).build()
        } catch (ex: Exception) {
            throw HttpError("地址无效（${ex.javaClass.simpleName}）")
        }

        // ⚠️ 「开始 / 失败 / 完成」三点打点：B1 排查时，缺了它就无法区分
        //    「请求根本没发出」「卡在握手」「已发出但没回包」。logcat 里按 tag 网络TLS 筛。
        Log.i(TLS_TAG, "请求开始 $url")

        val response = try {
            client.newCall(request).execute()
        } catch (ex: Exception) {
            Log.w(TLS_TAG, "请求失败 $url -> ${ex.javaClass.simpleName}: ${ex.message ?: "无描述"}")
            throw HttpError("网络异常（${ex.javaClass.simpleName}：${ex.message ?: "无描述"}）")
        }

        Log.i(TLS_TAG, "请求已回包 $url -> HTTP ${response.code()}")

        response.use {
            if (!it.isSuccessful) {
                // okhttp 3.x 的 Response 是 Java 类，字段为 package-private，
                // Kotlin 的合成属性会优先解析到字段 → 必须显式调用方法。
                throw HttpError("HTTP ${it.code()} ${it.message()}")
            }

            // HTTPS 请求打一行协商结果：这是判定 B1（TLS 1.2）是否真的生效的唯一硬证据。
            // 走 android.util.Log 而不是 UiLog —— 台标/节目单会有上百次请求，
            // 落进 UiLog 的 50 条环形缓冲会把真正有用的信息冲掉。
            it.handshake()?.let { hs ->
                Log.i(TLS_TAG, "协商成功 tls=${hs.tlsVersion()} 密码套件=${hs.cipherSuite()}")
            }

            return block(it)
        }
    }

    /** 取文本正文。失败抛 [HttpError]。 */
    fun getText(
        url: String,
        client: OkHttpClient = client(),
    ): String = open(url, client) { response ->
        response.body()?.string().orEmpty()
    }

    /** 取字节正文（台标等二进制资源用）。失败抛 [HttpError]。 */
    fun getBytes(
        url: String,
        client: OkHttpClient = client(),
    ): ByteArray = open(url, client) { response ->
        response.body()?.bytes() ?: ByteArray(0)
    }

    /** 只探响应头，不读正文（线路测速用）。失败抛 [HttpError]。 */
    fun head(
        url: String,
        client: OkHttpClient = client(),
        rangeTo: Long? = null,
    ): Int = open(url, client) { response ->
        rangeTo?.let { response.peekBody(it).string() }
        response.code()
    }

    /**
     * POST 文本正文（DLNA 的 SOAP 调用用，问题 6 投屏）。
     *
     * 返回 `(HTTP 状态码, 响应正文)`。**不**因 4xx/5xx 抛异常 —— UPnP 设备恰恰把
     * 出错原因（`<errorDescription>` / `<errorCode>`）写在错误响应体里，
     * 一律抛掉的话用户只能看到一句"HTTP 500"，没法定位。
     *
     * 只在「连不上 / 地址非法 / IO 异常」时抛 [HttpError]。
     */
    fun postRaw(
        url: String,
        body: String,
        contentType: String = "text/xml; charset=\"utf-8\"",
        extraHeaders: Map<String, String> = emptyMap(),
        client: OkHttpClient = client(connectTimeoutMs = 6_000L, readTimeoutMs = 15_000L),
    ): Pair<Int, String> {
        if (url.isBlank()) throw HttpError("地址为空")

        val builder = try {
            Request.Builder().url(url)
        } catch (ex: Exception) {
            throw HttpError("地址无效（${ex.javaClass.simpleName}）")
        }
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        // ⚠️ okhttp 3.12.13（API 19 可用的最后一个 3.x）：MediaType / RequestBody 的
        // Kotlin 扩展（toMediaTypeOrNull / toRequestBody）是 4.x 才有的，这里用 3.x 的静态工厂。
        val request = builder
            .post(RequestBody.create(MediaType.parse(contentType), body))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (ex: Exception) {
            throw HttpError("网络异常（${ex.javaClass.simpleName}：${ex.message ?: "无描述"}）")
        }

        response.use { resp ->
            return resp.code() to resp.body()?.string().orEmpty()
        }
    }
}
