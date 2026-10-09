# MyTV4.4

在 **Android 4.4（API 19）** 老机顶盒 / 老电视盒子上可用的 IPTV 直播播放器。

面向的是「系统停在 4.4、装不了新版 App、但硬件还能用」的那批设备：整个界面与控制逻辑
不依赖 Jetpack Compose、不依赖 Media3，用系统原生 View + 老版本依赖重写，**能在
Android 4.4.2 上流畅跑**。

---

## 目录

- [功能](#功能)
- [与上游项目的关系](#与上游项目的关系)
- [安装](#安装)
- [构建](#构建)
- [应用内升级通道](#应用内升级通道)
- [权限说明](#权限说明)
- [免责声明](#免责声明)
- [引用与致谢](#引用与致谢)
- [已知限制](#已知限制)

---

## 功能

- **直播播放**：M3U / TXT（`#genre#` 格式）直播源，HLS 与普通流；多线路自动降级重试
- **订阅源管理**：内置默认源 + 自定义源；源分组显隐记忆（换源 / 重启后保持）
- **节目单（EPG）**：XMLTV 源，多来源自动回退；可自定义节目单地址
- **经典三段式选台面板**：分组栏 / 频道列 / 节目单列，三态平移；分组栏焦点自动定位
- **频道项两行显示**：频道名 + 当前节目名 + 行底进度条
- **节目信息栏**：台标 + 频道名 + 线路 / 清晰度 / 立体声 / 网速 + 当前与下一节目（含进度条）
- **收藏**：长按收藏，**跨订阅源保留**（收藏时一并记录线路快照）
- **记忆上次频道**：退出重开直接回到上次观看的频道
- **DLNA 投屏**：SSDP 发现局域网 MediaRenderer，AVTransport 推送当前频道
- **遥控器友好**：支持数字选台、返回键连按两次退出、长按收藏
- **触屏手势**（面板/弹层不在屏时）：上滑下一频道、下滑上一频道、左右滑切换线路
- **实时网速**：底栏与频道信息块的网速为播放链路的真实下载速率（每 0.5 秒刷新）
- **首次启动《使用须知》**：免责声明全屏页，同意一次后不再出现
- **应用内升级**：设置 → 更新 → 检查更新，可直接下载并安装新版本
- **无广告、无统计、不申请存储 / 通讯录 / 位置等任何敏感权限**

---

## 与上游项目的关系

本项目是 [yaoxieyoulei/mytv-android](https://github.com/yaoxieyoulei/mytv-android)（又称「我的电视 / MyTV」）
在 **Android 4.4** 上的**独立重写分支**：

| | 上游 MyTV | 本项目 MyTV4.4 |
|---|---|---|
| 最低系统 | Android 5.0（依赖 Jetpack Compose） | **Android 4.4（API 19）** |
| UI 技术 | Jetpack Compose | 系统原生 View + 自绘 |
| 播放器 | Media3 / ExoPlayer 新版 | ExoPlayer 2.18.1（最后支持 API 19 的线） |
| 定位 | 通用新版 Android | 只服务老盒子 |

界面布局、交互语义（三段式面板、快捷功能菜单、设置项分类等）**参照上游 1.7 版的设计与逻辑**重做，
代码为全新实现。上游为 MIT 许可，本项目同样以 MIT 发布并保留其版权声明，详见
[LICENSE](LICENSE) 与 [THIRD-PARTY.md](THIRD-PARTY.md)。

---

## 安装

侧载安装（无应用商店）：

```bash
adb install -r MyTV4.4-v1.6-legacy11.apk
```

或把 APK 拷进 U 盘 / 存储卡，用盒子自带的文件管理器安装（需允许「未知来源」）。

- 应用名：**MyTV4.4**，包名：`top.yogiczy.mytv.legacy`
- 首次启动直接进播放页，无引导页 / 协议页
- 默认直播源可在「设置 → 直播源 → 自定义直播源」里改

---

## 构建

环境：JDK 17、Android SDK（compileSdk 34 / minSdk 19）

```bash
# 1) 准备签名配置（可选：不配也能编译，只是 release 产物未签名）
cp keystore.properties.example keystore.properties
#    然后填入你自己的 keystore 路径与密码

# 2) 构建
./gradlew assembleDebug     # 调试包
./gradlew assembleRelease   # 正式包（签名取决于 keystore.properties 是否存在）
```

> ⚠️ `keystore.properties` 与 `keystore/` 均在 `.gitignore` 中，**签名材料不会进仓库**。

---

## 应用内升级通道

分发走 GitHub，不需要任何自建服务器：

1. **版本清单**：仓库里的 [`update/version.json`](update/version.json)

   ```json
   {
     "versionCode": 11,
     "versionName": "1.6-legacy.11",
     "url": "https://github.com/nid12345/MyTV4.4/releases/download/v1.6/MyTV4.4-v1.6-legacy11.apk",
     "size": 10289897,
     "md5": "56889dcb39d58e582daf4a5d52c85bf6",
     "notes": "更新说明，可多行"
   }
   ```

2. 客户端「设置 → 更新 → 检查更新」拉清单 → 比 `versionCode` → 提示 → 下载 → 拉起系统安装器。

清单与安装包都有**多线路回退**（直连 → ghproxy 加速 → jsDelivr），可在设置里手动切换
「下载线路」。

---

## 权限说明

本应用**只申请下面 6 项权限，全部与播放直接相关，没有读取存储、通讯录、位置、电话等任何敏感权限**：

| 权限 | 用途 | 不授予会怎样 |
|---|---|---|
| `INTERNET` | 拉取直播源列表、节目单、台标，播放网络流，检查更新 | 完全无法使用 |
| `ACCESS_NETWORK_STATE` | 判断网络是否可用、是否在计费网络 | 网络异常时提示不准 |
| `ACCESS_WIFI_STATE` | 读取 WiFi 状态（多播锁需要） | 投屏可能失败 |
| `CHANGE_WIFI_MULTICAST_STATE` | **DLNA 投屏**：SSDP 靠组播发现局域网内的电视 / 盒子；没有它息屏后会大量丢包 | 搜不到投屏设备 |
| `WAKE_LOCK` | 播放期间保持唤醒，避免看着看着息屏 | 可能中途黑屏 |
| `REQUEST_INSTALL_PACKAGES` | **应用内升级**：下载完成后拉起系统安装器（仅 API 26+ 需要，安装仍由系统弹窗确认） | 升级时无法自动拉起安装器 |

> 应用**不申请** `WRITE_EXTERNAL_STORAGE`：升级包下载到应用专属外部目录
> （`getExternalFilesDir("update")`），再用 `FileProvider` 交给安装器。

---

## 免责声明

首次安装打开应用时会出现**全屏《使用须知》**（文字与样式照搬上游 1.7 的 `AgreementScreen`），
点「已阅读并同意」后不再出现；选「退出应用」则直接关闭。

> 欢迎使用 MyTV4.4，请在使用前仔细阅读以下内容：
>
> 1. 本软件为开源软件，仅供学习交流使用，禁止用于任何商业用途。
> 2. 本软件不提供任何直播内容，所有直播内容均来自网络。
> 3. 本软件完全基于您个人意愿使用，您应该对自己的使用行为和所有结果承担全部责任。
> 4. 如果本软件存在侵犯您的合法权益的情况，请及时与作者联系，作者将会及时删除有关内容。
>
> 如您继续使用本软件即代表您已完全理解并同意上述内容。

补充说明：

1. 本项目是**开源的播放器客户端**，作者**不提供、不托管、不维护任何直播源内容**。
   应用内出现的频道与节目均来自**用户自行添加**或**互联网公开渠道**的订阅地址。
2. 本项目仅供**个人学习与技术交流**使用，请勿用于任何商业用途。
3. 使用者应自行确保其使用的直播源合法合规，并自行承担由此产生的一切后果；
   作者不对任何内容的合法性、可用性、准确性负责，也不对使用本软件造成的任何
   直接或间接损失负责。
4. 本项目按「现状」提供，不附带任何明示或暗示的担保。
5. 若权利人认为本项目侵犯其权益，请通过 Issue 联系，作者将及时处理。

---

## 引用与致谢

本项目的实现参考与依赖了以下开源项目，在此致谢：

| 项目 | 用途 | 许可 |
|---|---|---|
| [yaoxieyoulei/mytv-android](https://github.com/yaoxieyoulei/mytv-android) | **上游项目**：界面布局与交互语义的参照来源 | MIT |
| [Conscrypt](https://github.com/google/conscrypt)（`org.conscrypt:conscrypt-android:2.5.2`） | 给 Android 4.4 补上现代 TLS 套件（系统 4.4 不支持 AES-GCM，导致部分订阅源握手失败） | Apache-2.0 |
| [ExoPlayer](https://github.com/google/ExoPlayer)（`com.google.android.exoplayer:exoplayer:2.18.1`） | 播放内核（最后支持 API 19 的版本线） | Apache-2.0 |
| [OkHttp](https://github.com/square/okhttp)（`3.12.13`） | HTTP 客户端（最后支持 API 19 的版本线） | Apache-2.0 |
| [AndroidX](https://developer.android.com/jetpack/androidx) | appcompat / core-ktx / recyclerview / multidex | Apache-2.0 |
| [desugar_jdk_libs](https://developer.android.com/studio/write/java8-support) | Java 8 API 在 API 19 上的兼容层 | GPL-2.0 with Classpath Exception |

默认节目单源为 51zmt（`epg.51zmt.top`，公开 XMLTV 服务），可在设置中更换。

---

## 已知限制

- 仅支持 **Android 4.4 及以上**；Android 4.3 及更早未测试。
- **应用图标 / 界面为深色主题**，未适配浅色系统。
- 「自定义节目单」目前只支持录入并切换当前来源，没有上游那样的多来源列表管理界面。
- DLNA 投屏依赖局域网组播，部分路由器开启「AP 隔离」时会搜索不到设备。
- 部分老设备（Android 4.4）的硬件解码器不支持高码率 1080p High Profile 直播流，
  表现为黑屏但有声音 —— 这是解码器能力问题，可切换到较低画质的线路。
