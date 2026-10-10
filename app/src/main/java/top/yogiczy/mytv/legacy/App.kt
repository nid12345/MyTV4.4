package top.yogiczy.mytv.legacy

import android.content.Context
import androidx.multidex.MultiDexApplication
import java.io.PrintWriter
import java.io.StringWriter

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
    }

    override fun onCreate() {
        super.onCreate()
        enableTls12()
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
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val head = "机型: ${android.os.Build.MODEL}\n" +
                        "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n" +
                        "版本: ${BuildConfigCompat.VERSION_NAME}\n\n"
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_LAST_CRASH, head + sw.toString())
                    // 必须 commit：进程马上要死了，apply 可能写不完
                    .commit()
            }
            previous?.uncaughtException(thread, error)
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
    const val VERSION_NAME = "1.15-legacy.20"

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
     */
    const val VERSION_CODE = 20
}
