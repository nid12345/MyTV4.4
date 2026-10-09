# 第三方组件与引用说明

本应用（MyTV4.4）在实现过程中参考、引用或打包了以下第三方成果，按许可要求在此逐一注明。

---

## 1. 上游项目（设计与逻辑参照）

| 项 | 内容 |
|---|---|
| 项目 | [yaoxieyoulei/mytv-android](https://github.com/yaoxieyoulei/mytv-android)（「我的电视 / MyTV」） |
| 参考版本 | 1.7（tag `custom-v1.7`） |
| 用途 | 界面布局、交互语义、设置项分类、节目单与台标处理逻辑的**参照来源** |
| 许可 | MIT |
| 版权 | Copyright (c) 2020-PRESENT yaoxieyoulei |

> 本项目为**面向 Android 4.4 的独立重写**，代码为全新实现，不含上游源码文件；
> 但界面设计与功能语义源自该项目的思路，因此完整保留其 MIT 版权声明（见 [LICENSE](LICENSE)）。

---

## 2. 随包分发的依赖

| 组件 | 版本 | 用途 | 许可 |
|---|---|---|---|
| [Conscrypt](https://github.com/google/conscrypt) `org.conscrypt:conscrypt-android` | 2.5.2 | 为 Android 4.4 补现代 TLS 套件（系统自带的 53 个密码套件里没有任何 AES-GCM，导致部分 IPTV 订阅源握手被拒） | Apache-2.0 |
| [ExoPlayer](https://github.com/google/ExoPlayer) `com.google.android.exoplayer:exoplayer` `-hls` | 2.18.1 | 播放内核（2.18 是最后支持 API 19 的版本线） | Apache-2.0 |
| [OkHttp](https://github.com/square/okhttp) `com.squareup.okhttp3:okhttp` | 3.12.13 | HTTP 客户端（3.12.13 是最后支持 API 19 的版本线） | Apache-2.0 |
| [AndroidX AppCompat](https://developer.android.com/jetpack/androidx/releases/appcompat) | 1.4.2 | 兼容主题与基础控件 | Apache-2.0 |
| [AndroidX Core KTX](https://developer.android.com/jetpack/androidx/releases/core) | 1.8.0 | Kotlin 扩展、FileProvider | Apache-2.0 |
| [AndroidX RecyclerView](https://developer.android.com/jetpack/androidx/releases/recyclerview) | 1.2.1 | 频道列表与节目单列表 | Apache-2.0 |
| [AndroidX MultiDex](https://developer.android.com/studio/build/multidex) | 2.0.1 | minSdk < 21 时的多 dex 支持 | Apache-2.0 |
| [desugar_jdk_libs](https://developer.android.com/studio/write/java8-support) `com.android.tools:desugar_jdk_libs` | 2.0.4 | Java 8 API 在 API 19 上的兼容层 | GPL-2.0 with Classpath Exception |

---

## 3. 外部数据服务（不随包分发）

| 服务 | 地址 | 用途 |
|---|---|---|
| 51zmt 节目单 | `http://epg.51zmt.top:8000/e.xml` | 默认 XMLTV 节目单来源（公开服务，可在设置中更换） |

台标图片来自公开的台标镜像仓库与订阅源自带的 `tvg-logo` 字段，均为运行时按需拉取，
不随 APK 分发。

---

## 4. 图标

应用图标为项目自行绘制（Pillow 脚本生成的多密度 PNG + 自适应图标矢量），
未使用任何第三方素材。
