# =============================================================================
# MyTV Legacy —— ProGuard / R8 规则
#
# 【更正一条此前的错误判断】
# 第二步计划里曾断言「app/build.gradle.kts 的 release 引用了本文件、而文件不存在，
# 因此 release 构建必然失败」。M0 阶段做了对照实验（把本文件移走再跑
# assembleRelease）——结果是 **BUILD SUCCESSFUL**，判断被推翻。
#
# 真实机制：release 里 isMinifyEnabled = false 时，AGP 不会运行 R8/minify 任务，
# 也就从不解析 proguardFiles 列表，缺失的文件不会被校验。v0.1~v0.5 从未因此失败。
#
# 本文件因此**不是**构建修复，而是为「日后按需开启 R8」预置规则。
# M0 明确不开 isMinifyEnabled（老设备收益有限、ExoPlayer 反射点多、风险高）。
# =============================================================================

# ---------- 保留崩溃堆栈可读性 ----------
# 目标设备（荣耀 G750-T20）接不了 adb，App.kt 的崩溃自捕获 + 应用内堆栈界面
# 是唯一的排错通道。没有这两行，堆栈里的类名/行号会变成 a.b.c:12 而无法定位。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 本项目自身的类全部保留（同上理由：堆栈必须能直接读出类名）
-keep class top.yogiczy.mytv.legacy.** { *; }

# ---------- ExoPlayer 2.18.1 ----------
# ExoPlayer 通过反射按类名加载渲染器 / 解码器 / 音频处理器，
# 并在运行时探测可选的扩展模块；不保留会在开启 R8 后出现
# 「找不到解码器」或「黑屏无声」这类只在 release 包出现的问题。
-keep class com.google.android.exoplayer2.** { *; }
-keep class * implements com.google.android.exoplayer2.Renderer { *; }
-keep class com.google.android.exoplayer2.ext.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**

# ExoPlayer 声明了但本项目未打包的可选扩展（Cronet / FFmpeg / FLAC / Opus / VP9 …）
# —— M3 决策前不引入 FFmpeg 扩展，这些类不会存在于 APK 中，必须 -dontwarn
-dontwarn com.google.android.exoplayer2.ext.cronet.**
-dontwarn com.google.android.exoplayer2.ext.ffmpeg.**
-dontwarn com.google.android.exoplayer2.ext.flac.**
-dontwarn com.google.android.exoplayer2.ext.opus.**
-dontwarn com.google.android.exoplayer2.ext.vp9.**
-dontwarn com.google.android.exoplayer2.ext.av1.**
-dontwarn com.google.android.exoplayer2.ext.gav1.**
-dontwarn com.google.android.exoplayer2.ext.ima.**
-dontwarn com.google.android.exoplayer2.ext.leanback.**
-dontwarn com.google.android.exoplayer2.ext.mediasession.**
-dontwarn com.google.android.exoplayer2.ext.workmanager.**
-dontwarn com.google.android.exoplayer2.ext.cast.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------- OkHttp 3.12.13 + Okio ----------
# 两者都会按运行时可用的 TLS 后端（Conscrypt / BouncyCastle / OpenJSSE）做探测
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# ---------- AndroidX 常见可选依赖告警 ----------
-dontwarn androidx.**
