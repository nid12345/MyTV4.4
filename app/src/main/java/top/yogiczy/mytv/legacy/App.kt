package top.yogiczy.mytv.legacy

import android.content.Context
import androidx.multidex.MultiDexApplication

/**
 * minSdk 17 走 legacy multidex，必须由 Application 触发 install。
 *
 * 另外做两件事：
 * 1) 补 TLS 1.2 —— Android 4.2.2 的 HttpsURLConnection 默认只开 TLSv1/1.1，
 *    而现代 CDN 只收 TLS 1.2+，不补这一刀所有 https 流都会 IO_NETWORK_CONNECTION_FAILED。
 * 2) 装崩溃记录器 —— 老设备上没法接 adb，把堆栈落盘，下次启动直接在应用里显示出来，
 *    截图就能定位。这是上一版闪退排查困难的直接教训。
 */
class App : MultiDexApplication() {

    companion object {
        const val PREFS = "legacy_crash"
        const val KEY_LAST_CRASH = "last_crash"

        /**
         * v1.17：已被用户「看过/忽略过」的那条崩溃记录的指纹。
         *
         * 用途：同一条崩溃**只在启动时弹一次全屏**，之后再启动就只在底部提一行、
         * 不再挡着用户用遥控器（见 [top.yogiczy.mytv.legacy.data.CrashStore.isAcked]）。
         * 用户报障原话：「退出 app 重新进 app 还是卡在这个崩溃页面…不至于只能卸载 app 重装」。
         */
        const val KEY_CRASH_ACK = "crash_ack_fingerprint"
    }

    override fun onCreate() {
        super.onCreate()
        // v1.17：**第一件事**注入 Context —— `Http` 需要读 `assets/ca/*.pem`（随包的现代根证书）。
        // 必须在 enableTls12() 之前：那一句会触发 `tlsContext` 的 lazy 求值，
        // 而信任链只在那一刻组装一次（晚一步注入，随包根证书就永远不生效了）。
        top.yogiczy.mytv.legacy.net.Http.init(this)
        enableTls12()
        // v1.19：崩溃上报换成自建 Cloudflare Worker（mytvbug.nide.qzz.io）。
        // v1.18 的腾讯 Bugly SDK 已整份删除（APK 体积 -530KB、无第三方 SDK）。
        // 这里不再有 SDK 初始化动作 —— 上报由用户点「一键反馈」或启动时补报触发，
        // 崩溃落盘仍由下面的 installCrashLogger 负责。
        installCrashLogger()
        // M5：配置迁移（M2 已实现 Prefs.migrateIfNeeded，但一直没有调用点）。
        // 必须在任何读配置的界面之前执行，否则老版本升级上来的用户会拿到错的默认值。
        runCatching { top.yogiczy.mytv.legacy.data.Prefs.migrateIfNeeded(this) }
    }

    /**
     * 补 TLS 1.2。
     *
     * ⚠️ **SSLContext 的唯一来源是 `net/Http.kt`**（[Http.installDefaultTls12]），
     * 这里不再自己 `SSLContext.getInstance` —— 否则会出现「OkHttp 一份、播放一份」
     * 两套配置各自漂移（B1 修复时就踩过：只补了 HttpsURLConnection 那一侧）。
     *
     * 装到 `HttpsURLConnection` 的静态默认值上，影响的是**播放链路**：
     * ExoPlayer 2.18.1 的 `DefaultHttpDataSource` 走 `java.net.URL.openConnection()`，
     * 而它的 Factory **不提供** `setSslSocketFactory`（`javap` 实测），
     * 因此这是播放能协商到 TLS 1.2 的唯一途径。
     */
    private fun enableTls12() {
        val installed = top.yogiczy.mytv.legacy.net.Http.installDefaultTls12()
        top.yogiczy.mytv.legacy.core.UiLog.i(
            "网络",
            if (installed) "TLS 1.2：已装到 HttpsURLConnection 默认工厂（播放链路）"
            else "TLS 1.2：设备不支持，播放链路保持系统默认"
        )
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // v1.17：落盘逻辑统一收进 CrashStore（`MainActivity.buildUiSafely` 也要用同一份
            // 头部格式，写两遍必然漂移）。头部 = 机型 / Android 版本 / 应用版本。
            val errorText = runCatching {
                top.yogiczy.mytv.legacy.data.CrashStore.save(this, error)
                top.yogiczy.mytv.legacy.data.CrashStore.read(this)?.text
            }.getOrNull().orEmpty()
            // v1.20 需求 2：崩溃时**顺手自动上报一次**。
            // 原来只有用户主动点「一键反馈」才上传，于是"卡死/闪退"这类用户事后未必记得
            // 反馈的问题，后台永远看不到。这里在进程死之前补一次 POST ——
            // 卡在进程退出路径上，失败也无所谓（记录仍留在本地，下次启动还能补报）。
            runCatching { top.yogiczy.mytv.legacy.data.CrashReporter.upload(this, errorText) }
            previous?.uncaughtException(thread, error)
        }
        // v1.20 需求 2：主线程卡死（ANR）看门狗。
        // 崩溃处理器管的是"抛了异常"，看门狗管的是"没抛异常但主线程堵死了" ——
        // 用户报的「卡死没反应」正是后者，此前没有任何记录、也没有上报入口。
        installWatchdog()
    }

    private fun installWatchdog() {
        runCatching {
            top.yogiczy.mytv.legacy.data.Watchdog.install(this)
        }
    }
}

/**
 * 避开 BuildConfig。
 *
 * 工程的 gradle.properties 里关掉了该特性（android.defaults.buildfeatures.buildconfig=false），
 * 因此 BuildConfig.VERSION_NAME 不存在，版本号只能写在代码里。
 *
 * ⚠️ 与 app/build.gradle.kts 的 defaultConfig.versionName **必须保持一致**：
 *    崩溃自捕获会把这里显示的版本号写进堆栈头部，版本号骗人会让排错跑偏
 *    （M0 之前它就停在 "0.3.1-legacy"，比实际版本落后三个版本）。
 */
object BuildConfigCompat {
    const val VERSION_NAME = "1.21-legacy.26"

    /**
     * 版本序号（单调递增）。
     *
     * ⚠️ 与 `app/build.gradle.kts` 的 `defaultConfig.versionCode` **必须一致**：
     * 「检查更新」就是拿它跟远端 `update/version.json` 里的 `versionCode` 比大小，
     * 两边不一致会出现"永远提示有新版本"或"新版不提示"（原版 1.7 也是用 versionCode 比较）。
     *
     * ⚠️ v1.9：14。与 gradle 的 versionCode 保持一致（v1.7 曾因为 11 vs 12 不一致，
     * 导致「检查更新」永远认为远端更新）。
     * ⚠️ v1.10：15。
     * ⚠️ v1.11：16。
     * ⚠️ v1.12：17。
     * ⚠️ v1.13：18。
     * ⚠️ v1.14：19。
     * ⚠️ v1.15：20。
     * ⚠️ v1.16：21。
     * ⚠️ v1.17：22。
     * ⚠️ v1.18：23。
     * ⚠️ v1.19：24。
     * ⚠️ v1.20：25。
 * ⚠️ v1.21：26。
     */
    const val VERSION_CODE = 26
}
