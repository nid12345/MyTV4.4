import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "top.yogiczy.mytv.legacy"
    compileSdk = 34

    defaultConfig {
        applicationId = "top.yogiczy.mytv.legacy"
        // M0：目标设备 G750-T20 已刷官方 4.4.2（API 19），minSdk 取 19
        minSdk = 19
        // M0：34 -> 28。IPTV 源大量是 http 明文，targetSdk >= 28 时 API 28+ 设备默认禁明文；
        //      设备本身是 API 19 不受影响，但降到 28 后在新设备上调试不会被拦。
        targetSdk = 28
        // v1.9：节目单来源可在设置页直接切换 + 时间显示生效 + 点播自动连播 +
        //       手势方向对调 + 播放控制进度条 + 多线路右侧选框 + 换源卡死自愈。
        // v1.10：投屏一次点击即选中、成功后才关抽屉并给出成败反馈；
        //        左侧三段式面板与长按快捷菜单互斥；投屏抽屉在屏时不再误唤面板；
        //        三个底部抽屉（投屏 / 节目单来源 / 分组管理）的遮罩点击不再吞掉列表行的 onClick。
        // v1.11：选台面板取消定时自动隐藏（只在选台 / 返回 / 点空白时收起）；
        //        设置页改完某项后焦点停在原处不再跳回列表开头；
        //        检查更新不再残留底部粘性提示，更新下载改为并发探测镜像线路（国内网络可下）。
        // v1.12：内置「潮剧」订阅源；同一节目内多线路顺序连播；
        //        更新下载目录改为多候选 + 真实写入探针（修小米电视更新失败）；
        //        清掉两处开发话术提示（回放 / 节目单）；
        //        触屏在面板内左右滑可切层级；整体 UI 对齐到 24dp 网格并收窄第三列。
        // v1.13：面板内长按下键改为"按住快速往下挑节目"（不再唤出播放控制）；
        //        除设置页外所有弹层/面板加无操作自动隐藏（弹层 5 秒、选台面板 15 秒）；
        //        换源列表去掉头像占位、只显示源名（在用源打「使用中」角标）；
        //        三段式面板底边与右下信息框底边对齐、一级 90%/二级 70% 半透明、
        //        信息框限高屏高 1/5；一二级菜单合并为一个无中间圆角的整体；
        //        唤起左侧面板时先收起左下角临时信息块。
        // ⚠️ 与 App.kt 的 BuildConfigCompat 必须一致：
        //     versionCode ↔ VERSION_CODE（升级通道用它比大小）
        //     versionName ↔ VERSION_NAME
        versionCode = 27
        versionName = "1.22-legacy.27"
        vectorDrawables { useSupportLibrary = true }
        // minSdk < 21 时 core library desugaring 强制要求 multidex
        multiDexEnabled = true
    }

    // M14：release 签名。
    // v1.6：改为从**仓库根目录的 `keystore.properties`** 读取（该文件已在 .gitignore 里，
    //       密码与私钥都不进 Git）。仓库里只保留 `keystore.properties.example` 供他人照抄。
    //       文件不存在时不配置签名 → `assembleRelease` 产出未签名 APK，编译不受影响。
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }
    val hasSigning = keystoreProps.getProperty("storeFile") != null

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions { jvmTarget = "1.8" }

    lint {
        // M0 新增。targetSdk 由 34 降到 28 后，assembleRelease 会在这里失败：
        //   Error: Google Play requires that apps target API level 33 or higher.
        //          [ExpiredTargetSdkVersion]
        // 该检查只对「经 Google Play 分发」的应用有意义。本应用是侧载到已刷官方 4.4.2 的
        // 荣耀 G750-T20 上用的，不经过任何应用商店；targetSdk 28 是为保住 IPTV 源里
        // 大量 http 明文流量而做的**有意**选择（与 android:usesCleartextTraffic 双保险）。
        // 这里只关掉这一条与分发渠道相关的检查，其余 lintVital 检查保持开启。
        disable += "ExpiredTargetSdkVersion"
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    // v1.21：新增 JVM 单元测试（验证 EPG 匹配索引与旧线性扫描**结果等价**且不再 O(N×M)）。
    // 只跑纯 Kotlin 逻辑（不引 Robolectric），因此需要 returnDefaultValues 让
    // android.util.Log / SystemClock 这类"未 mock 的 android.jar 方法"返回默认值而不是抛异常。
    @Suppress("UnstableApiUsage")
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("androidx.multidex:multidex:2.0.1")

    implementation("androidx.appcompat:appcompat:1.4.2")
    implementation("androidx.core:core-ktx:1.8.0")
    implementation("androidx.recyclerview:recyclerview:1.2.1")
    // M0：按第二步 §1.3 移除。全工程 0 处引用（grep 实测）。
    //   androidx.constraintlayout:constraintlayout:2.1.4  -> 0 处引用
    //   androidx.leanback:leanback:1.0.0                  -> 0 处代码引用
    //     （TV 支持只需 AndroidManifest 里的 <uses-feature android:software.leanback>
    //       与 LEANBACK_LAUNCHER category，不需要这个库）

    implementation("com.google.android.exoplayer:exoplayer:2.18.1")
    implementation("com.google.android.exoplayer:exoplayer-hls:2.18.1")

    implementation("com.squareup.okhttp3:okhttp:3.12.13")

    // v1.6 新增（唯一一次为修真实阻塞引入依赖，需在 README 的「引用与致谢」里标注）：
    // Android 4.4 的 SSLSocket **不支持任何 AES-GCM 套件**（实测 supportedCipherSuites 53 个里
    // 一个 AEAD 都没有），而 sub.ottiptv.cc 这类服务端会直接拒绝只给 CBC 的 ClientHello
    // → `sslv3 alert handshake failure`，表现为「点开别的订阅源加载不出来」。
    // Conscrypt 是 Google 维护的 TLS 实现，官方支持 API 19+，提供完整的现代套件（含 GCM）。
    // 许可证：Apache-2.0。
    implementation("org.conscrypt:conscrypt-android:2.5.2")

    // v1.19：崩溃上报换成**自建 Cloudflare Worker**（mytvbug.nide.qzz.io）。
    // v1.18 引入的腾讯 Bugly SDK（com.tencent.bugly:crashreport:4.1.9.3）已按用户要求整份删除
    // —— 用户明确"不喜欢用"，且它带来约 530KB 体积与一个第三方 SDK 依赖。
    // 现在上报走 net/Http.postRaw（HTTPS POST text/plain + 共享密钥头），
    // 服务端是工程根目录 mytv-bug-report/ 下的 Cloudflare Worker（D1 存储 + Basic Auth 后台）。
    // **本文件不再新增任何崩溃上报依赖。**

    // v1.21：仅用于 JVM 单元测试（不参与 APK 打包）
    testImplementation("junit:junit:4.13.2")
}
