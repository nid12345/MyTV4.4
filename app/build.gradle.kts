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
        // v1.6 最终版。⚠️ 与 App.kt 的 BuildConfigCompat 必须一致：
        //     versionCode ↔ VERSION_CODE（升级通道用它比大小）
        //     versionName ↔ VERSION_NAME
        versionCode = 12
        versionName = "1.7-legacy.12"
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
}
