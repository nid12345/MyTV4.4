# MyTV4.4 项目交接文档

> 最后更新：2026-10-19 · 对应版本 **v1.22（versionCode 27 / `1.22-legacy.27`）**
> 目的：让接手的人不用翻历史会话，就能把「在哪、是什么、怎么改、怎么发」一次搞清楚。

---

## 0. 一页速览

| 项 | 值 |
|---|---|
| 项目 | Android 电视直播 App（MyTV 定制版），电视端优先、兼容触屏 |
| 包名 | `top.yogiczy.mytv.legacy`（debug 后缀 `.debug`） |
| 仓库 | **https://github.com/nid12345/MyTV4.4**（⚠️ **公开仓库**） |
| 当前版本 | `1.22-legacy.27` / versionCode **27** |
| 目标设备 | 小米电视4（Android 6.0.1）、G750-T20（Android 4.4.2 / API 19）、各类老盒子 |
| 硬约束 | `minSdk = 19`；**禁协程 / Compose / kotlinx.serialization**；ExoPlayer 2.18.1、OkHttp 3.12.13、AppCompat 1.4.2（都是最后支持 API 19 的版本线，不要随手升） |
| 代码规模 | `MainActivity.kt` 约 5900 行（最大的单文件，界面全在这）；其余按 `data/` `net/` `ui/` `player/` `core/` 分层 |
| 上游参照 | [yaoxieyoulei/mytv-android](https://github.com/yaoxieyoulei/mytv-android)（MIT），本工程是「逐字移植判定逻辑 + 纯 View 重写」的关系 |

**版本号规则**：`versionName = "<版本>-legacy.<versionCode>"`，两处必须同步改
（`App.kt` 的 `VERSION_CODE` / `VERSION_NAME`，和 `app/build.gradle.kts` 的
`versionCode` / `versionName`）。升级通道只比 `versionCode` 大小。

---

## 1. 密钥与凭据（**接手先看这节**）

> 🔴 **本仓库是公开的**。任何密钥的**真实值都不得写进代码、文档、注释**。
> 本节只记录「东西在哪、干什么用、怎么用」。

| # | 凭据 | 位置 | 用途 | 怎么用 |
|---|---|---|---|---|
| 1 | **GitHub PAT** | `~/.workbuddy/.secrets/ghtok`（首行就是 token，40 字符 `ghp_` 开头） | 建 Release、上传 APK、调 REST API | `TOK=$(head -1 ~/.workbuddy/.secrets/ghtok \| tr -d '[:space:]')`，然后 `curl -H "Authorization: Bearer $TOK"`。**这是本机唯一存放点**，别处（钥匙串/环境变量/配置文件）都没有 |
| 2 | **SSH 私钥** | `~/.ssh/id_ed25519_github`（ed25519，公钥已挂 nid12345 账号） | `git push` / `git fetch` | `export GIT_SSH_COMMAND="ssh -i ~/.ssh/id_ed25519_github -p 443 -o StrictHostKeyChecking=accept-new"` |
| 3 | **签名库** | `mytv-legacy/keystore/mytv-release.jks` + `keystore.properties`（storeFile / storePassword / keyAlias / keyPassword） | release 包签名 | `.gitignore` 已排除 `keystore/` 与 `keystore.properties`，仓库里只有 `keystore.properties.example`。证书 SHA-256 `02E1:43E8:…:36F7:C2`（**升级校验用这个**，换库 = 老用户装不上新版） |
| 4 | **崩溃上报共享密钥** | App 侧：`data/CrashReporter.kt` 的 `CRASH_UPLOAD_TOKEN`；Worker 侧：CF secret `REPORT_TOKEN` | 挡扫描器/误触，两边必须一致 | 当前值 `mytv-bug-report-2026`。⚠️ 它**编译进 APK 且仓库公开**，本质是公开的——只当限流用，别当鉴权用 |
| 5 | **Worker 后台账号** | CF secret `ADMIN_USER` / `ADMIN_PASS` | 看崩溃记录后台 | 入口 <https://mytvbug.nide.qzz.io/admin>，Basic Auth。用户名 `nedkoh`，密码由 nedkoh 掌握 |
| 6 | **Cloudflare 部署凭据** | **本机没有** | `wrangler deploy` | 需要在终端跑 `npx wrangler login`（浏览器授权），或直接跑 `mytv-bug-report/deploy.sh` |
| 7 | **D1 database_id** | `mytv-bug-report/wrangler.toml`（`2a2336f7-ff97-4468-a755-8b68dc698a66`） | Worker 绑库 | 公开信息，可直接引用 |

### 1.1 网络通道的坑（发布前必读）

- **`github.com` 的 22 端口 SSH 不通**（`kex_exchange_identification: Connection closed`）。
  必须走 GitHub 的 443 通道：`ssh://git@ssh.github.com:443/nid12345/MyTV4.4.git`。
- HTTPS clone 能用，但 `raw.githubusercontent.com` 直连不通（SSL UNEXPECTED_EOF）——
  读远端文件改用 `api.github.com` 的 contents 接口。
- 所有 `curl` 一律加 `--noproxy '*'`（本机跑着代理，不走代理才通）。
- 上传 Release 附件必须加 `-H "Expect:"`，否则大文件在 ~8MB 处被 reset。

---

## 2. 本地环境与构建

```bash
# 工具链（都在受管目录，不要用系统的）
export JAVA_HOME=/Users/nid/.workbuddy/binaries/java/jdk-17.0.20.1+1/Contents/Home
# Android SDK 由 mytv-legacy/local.properties 指定：
#   sdk.dir=/Users/nid/.workbuddy/binaries/android-sdk

cd /Users/nid/WorkBuddy/2026-10-06-16-46-21/mytv-legacy

# 调试包
./gradlew -p . assembleDebug --console=plain
# 发布包（约 3~7 分钟）
./gradlew -p . assembleRelease --console=plain
# 全部 JVM 单元测试（11 项，约 1 分钟）
./gradlew -p . testDebugUnitTest --console=plain
```

- 产物：`app/build/outputs/apk/{debug,release}/*.apk`
- 当前体积：release ≈ **10.2 MB**
- 单测为什么能跑：`app/build.gradle.kts` 里开了
  `testOptions { unitTests.isReturnDefaultValues = true }`，让 `android.util.Log` /
  `SystemClock` 这类未 mock 的方法返回默认值而不是抛异常——**不引 Robolectric 也能测纯 Kotlin 逻辑**。

---

## 3. 验证体系

### 3.1 模拟器验证（`scripts/verify-api19.sh`）

```bash
cd mytv-legacy
RUN_SEC=25 scripts/verify-api19.sh app/build/outputs/apk/debug/app-debug.apk
```

判定「装得上 / 起得来 / 0 FATAL / 进程存活」。脚本头注释里固化了 6 条坑，别改：

1. API 19 的 `adb exec-out screencap` 返回 0 字节 → 用 `shell screencap -p <path>` + `adb pull`；
2. 该 AVD 的 `/sdcard` 只读，截图写 `/data/local/tmp`；
3. 判断存活不能只信 `adb shell ps`（老系统偶有缺项），要同时看 logcat；
4. `input keyevent` 在这台 AVD 上**一次约 2.5 秒**，30 次要 75 秒，别按毫秒预估；
5. 面板唤出后光标默认在**一级菜单**，要先发一次 `DPAD_RIGHT` 才能测二级菜单；
6. **必须给模拟器指定真实 DNS**：启动参数固定 `-dns-server 119.29.29.29,223.5.5.5`，
   否则域名被代理 fake-IP 劫持成 198.18.x.x，请求**不成功也不超时**，看起来像 App 卡死。

### 3.2 ⚠️ 本机内存限制（影响验证策略）

这台 Mac（8GB / Intel）空闲内存常年只剩 ~15MB，**API 19 模拟器跑 1~2 分钟就会被系统杀掉**。
所以：长时/切源类验证**改用 JVM 单测**，模拟器只做「装得上起得来不崩」的冒烟。

### 3.3 现有 JVM 单测（11 项，`app/src/test/java/top/yogiczy/mytv/legacy/`）

| 文件 | 覆盖 |
|---|---|
| `EpgMatchTest.kt` | EPG 索引与旧线性扫描**结果等价**、耗时 O(1)、换源后索引重建（339ms → 8ms+0ms） |
| `MarqueeCurveTest.kt` | 标题跑马灯进度曲线：端点、首尾停顿、中间匀速、无 NaN |
| `SourceLogoPolicyTest.kt` | 订阅源 → 台标策略映射（含端口/query/大小写扰动） |

**新逻辑优先写成纯函数 + 单测**，这是本机唯一稳定的验收手段。

### 3.4 真机验收清单（每次发版后让 nedkoh 过一遍）

1. 装得上、起得来、能播；
2. 左侧列表挑节目（含长按连翻）不卡；
3. 投屏能搜到小米电视并能起播；
4. 台标显示正常（内置命中 / 源自带 / 联网回退）；
5. 崩溃后台 <https://mytvbug.nide.qzz.io/admin> 无新增。

---

## 4. 发布流程（完整步骤，照抄即可）

```bash
# ① 改版本号（两处）
#    app/src/main/java/top/yogiczy/mytv/legacy/App.kt   VERSION_CODE / VERSION_NAME
#    app/build.gradle.kts                                versionCode / versionName

# ② 构建 + 归档 + 记 MD5
cd /Users/nid/WorkBuddy/2026-10-06-16-46-21/mytv-legacy
export JAVA_HOME=/Users/nid/.workbuddy/binaries/java/jdk-17.0.20.1+1/Contents/Home
./gradlew -p . assembleRelease --console=plain
cp app/build/outputs/apk/release/app-release.apk dist/MyTV4.4-vX.Y-legacyN.apk
md5 -q dist/MyTV4.4-vX.Y-legacyN.apk      # 记下来，version.json 要用

# ③ 更新升级清单 update/version.json（versionCode/size/md5/url/notes）
#    ← App 内「检查更新」读的就是它（OWNER=nid12345 REPO=MyTV4.4 BRANCH=main）

# ④ 写 dist/vX.Y-报告.md，并把「实现要点 / 关键认知 / 产物」追加进 PROJECT-STATE.md

# ⑤ 推送代码与标签（在 /tmp 的克隆里做，工作区目录本身不是 git 仓库）
cd /tmp/MyTV4.4
export GIT_SSH_COMMAND="ssh -i ~/.ssh/id_ed25519_github -p 443 -o StrictHostKeyChecking=accept-new"
git fetch origin main
rsync -a --delete --exclude '.git' --exclude '.DS_Store' --exclude 'local.properties' \
      --exclude 'keystore.properties' --exclude 'keystore/' --exclude 'build/' \
      --exclude '.gradle/' --exclude '*.apk' --exclude 'dist/' \
      /Users/nid/WorkBuddy/2026-10-06-16-46-21/mytv-legacy/ ./
git add -A
git -c user.name="nid12345" -c user.email="nid12345@users.noreply.github.com" commit -m "vX.Y：…"
git tag vX.Y && git push origin main vX.Y

# ⑥ 建 Release 并上传 APK（脚本模板见 /tmp/release_v*.py，核心三段）
#    POST /repos/nid12345/MyTV4.4/releases  →  拿 upload_url
#    POST <upload_url>?name=xxx.apk          →  带 -H "Expect:" + Content-Type: vnd.android.package-archive
#    核对返回的 state == "uploaded" 且本地 MD5 与 version.json 一致
```

**发布后自检**：Release 页能看到附件且大小正确；`version.json` 的 md5 与 APK 一致；
应用内升级能检测到新版本。

---

## 5. 代码地图

| 文件 / 目录 | 职责 | 接手注意 |
|---|---|---|
| `App.kt` | Application；全局 UncaughtExceptionHandler（崩溃落盘 + 自动上报一次）；版本号常量 | 改版本号这里也要改 |
| `MainActivity.kt` | 全部界面与交互：三段式面板、换台、按键路由、源加载链、投屏入口、设置页 | **约 5900 行**。任何改动先 `grep` 找到对应 `判据/需求` 注释块，注释里写了为什么这么写 |
| `data/Prefs.kt` | 全部 SharedPreferences（文件 `legacy_prefs`）；内置源列表；**配置版本迁移** | 见 §6 |
| `data/ChannelName.kt` | 频道名归一化 / 核心标识 / 台标文件名换算 | 纯函数 + memo，被 EPG 与台标共用 |
| `data/Epg.kt` | 节目单模型 + **匹配索引** | 性能关键，见 §9 |
| `data/ChannelLogoStore.kt` / `ui/ChannelLogoStore.kt` | 台标：内置索引、别名索引、三级回退、按字节 LRU、`builtinBitmapFor` | 见 §8 |
| `data/SourceLogoPolicy.kt` | 按订阅源定台标策略 | v1.22 新增 |
| `data/DlnaDiscovery.kt` / `DlnaController.kt` / `DlnaCastRepository.kt` | 投屏：SSDP 发现 / SOAP 控制 / 状态仓库 | 见 §10 |
| `data/Watchdog.kt` | 主线程卡死看门狗（8s 阈值、连续两次、退后台停判、10 分钟节流） | v1.20 新增 |
| `data/CrashReporter.kt` / `CrashStore.kt` | 崩溃上报 / 本地崩溃记录 | 见 §7 |
| `net/Http.kt` | 网络层（Conscrypt + TLS1.2 + 随包根证书，兼容 API 19） | 老设备 TLS 的关键，别换成默认 OkHttp |
| `ui/ChannelCardView.kt` | 频道行卡片（台标 + 标题 + 节目 + 进度条 + **跑马灯**） | 见 §8.3 |
| `ui/panel/ChannelColumnView.kt` | 中栏 RecyclerView（频道行 + 文本行） | 焦点/稳定 ID 逻辑很脆，改前读注释 |
| `scripts/verify-api19.sh` | 模拟器验证 | §3.1 |
| `update/version.json` | 应用内升级清单 | 每次发版必改 |
| `PROJECT-STATE.md` | 逐版本决策与踩坑流水 | **接手必读**，2587 行 |
| `../mytv-bug-report/` | 崩溃上报 Cloudflare Worker（独立小工程） | §7 |

---

## 6. 内置订阅源与配置迁移

### 6.1 内置源（`Prefs.defaultSources()`，顺序固定）

| # | 名称 | 地址 |
|---|---|---|
| 1 | 自建源 | `https://tvlive.nide.qzz.io` |
| 2 | 虎牙 | `https://sub.ottiptv.cc/huyayqk.m3u` |
| 3 | 斗鱼 | `https://sub.ottiptv.cc/douyuyqk.m3u` |
| 4 | YY轮播 | `https://sub.ottiptv.cc/yylunbo.m3u` |
| 5 | 潮汕节目回放 | `https://gitee.com/nid123/chaoshan-tv/raw/master/data/chaoshan.txt` |
| 6 | 潮剧 | `https://chaoju.nide.qzz.io/chaoju.txt` |

源格式有两种：**M3U**（`#EXTINF` + `tvg-logo` / `group-title`）和 **txt**（`名称,#genre#` + `条目,url1#url2#…`）。
txt 源里「一个频道 = 一个剧团版本，N 条线路 = N 集」，所以 `onPlaybackEnded` 的连播规则是
「**先播完本频道内所有线路，再走下一个频道**」。

### 6.2 配置版本迁移（`CURRENT_CONFIG_VERSION`，当前 = 8）

**改内置源地址只改 `defaultSources()` 对已装设备无效**——源列表在首次启动就落盘了。
必须「版本号 +1 + 在 `migrateIfNeeded()` 里加一段迁移」。历次迁移：

| 版本 | 做了什么 |
|---|---|
| v4/v5/v6 | 新增内置源（虎牙斗鱼YY / 潮汕 / 潮剧）——按 url 去重**补到末尾**，不动用户已有顺序 |
| v7 | 虎牙/斗鱼「4K频道」、YY「car」分组默认隐藏（**只补缺、不覆盖**，判据是 key 在不在） |
| v8 | 潮剧换地址——按 url **就地改写**，保持顺序，当前源一起跟改 |

---

## 7. 崩溃与卡顿上报链路

```
App 崩溃（UncaughtExceptionHandler） 或 主线程卡死 ≥8s（Watchdog）
        ↓
CrashStore.save / saveText        ← 落盘，设置→关于→崩溃记录 可看可复制
        ↓
CrashReporter.upload              ← HTTPS POST text/plain，头带 X-Crash-Token / X-App-Version / X-Device-Model
        ↓
Cloudflare Worker（mytv-bug-report/）
        ↓
D1（database_name = mytv-bug）
        ↓
https://mytvbug.nide.qzz.io/admin   ← Basic Auth 后台，按指纹聚合
```

- **上报正文会"去变量"**：后台按正文做 SHA-256（前 16 位）指纹聚合，写"阻塞 5123ms"会让
  同一处卡顿每次都是新指纹 → 列表被刷屏。已改成取整到秒。
- **后台线程解码/分配必须 `catch (t: Throwable)`**：`OutOfMemoryError` 是 `Error`，
  `catch (Exception)` 抓不到，v1.19 那次线上闪退就是这么死的（2.18 亿字节的位图分配）。
- **远程图片必须"先探边界再采样"**：`decodeByteArray` 不探边界时，一张大图就是全尺寸分配请求。
- Worker 工程（`mytv-bug-report/`）：`wrangler.toml` / `schema.sql` / `src/index.js` /
  `test-worker.mjs`（46 项断言）/ `dev-server.mjs`（本地真 HTTP）/ `deploy.sh`。
  部署：`cd mytv-bug-report && ./deploy.sh`（会提示输入 REPORT_TOKEN 与后台账号密码）。
  密钥只用 `wrangler secret put`，**不写进 wrangler.toml**。

---

## 8. 台标体系

### 8.1 优先级（用户定稿，顺序即 `candidates()` 返回顺序）

1. **内置台标**（`app/src/main/assets/channel_logo/`，离线瞬时）
2. **直播源自带 `tvg-logo`**
3. **联网**：自建 mytv-logos 的 jsDelivr CDN → raw.githubusercontent → 上游 → gitee 镜像

### 8.2 内置台标：165 张 + 648 条别名索引

- 只保留常见台标：央视全套 / 各省卫视 / 港台常见 / 少儿卡通（用户明确要求"不必全内置"）。
  assets 从 932 张 6.5MB 降到 **165 张 1.4MB**。
- 别名索引：`app/src/main/assets/logo_alias.txt`（`归一化键 \t 文件名`，648 条，13KB）。
  来源是 [iptv-api 的 alias.txt](https://github.com/nid12345/iptv-api/blob/master/config/alias.txt)，
  只收「主名能对应到内置台标」的行。它解决的就是「CCTV1综合 / 中央一台 / 湖南卫视高清 索引不到」。
- 匹配三级：**文件名直查 → 别名索引 → cctv 核心标识**（`ChannelLogoStore.doMatch`）。

### 8.3 按订阅源的策略（`SourceLogoPolicy`，v1.22）

| 源 | 策略 | 效果 |
|---|---|---|
| 虎牙 / 斗鱼 / YY轮播 | `BUILTIN_ONLY` | 只查内置，**不看 tvg-logo、不联网**（这三个源 1086 条全是主播头像） |
| 潮汕节目回放 | `NONE` | 不显示台标，**台标位让给标题**（`GONE`，不是 `INVISIBLE`） |
| 其它 | `DEFAULT` | 上述三级回退 |

判据匹配 **URL 路径部分**（剥掉协议/主机/端口/query），不用源名（源名可改）。

### 8.4 标题跑马灯（`ChannelCardView`）

标题超出可用宽度**且该行有焦点**时横向循环滚动：匀速 26dp/s、**首尾各停 900ms**、
无限循环；失焦/换绑/文字变短立刻取消并把 `translationX` 归零（否则 RecyclerView 复用会错位）。
曲线数学是纯函数 `ChannelCardView.marqueeFraction`，有单测。
**不要改用 TextView 自带的 `ellipsize=marquee`**——它要求 TextView 自身获得焦点才启动，
而列表项的焦点在整张卡片上，起不来。

### 8.5 增删内置台标的方法

脚本：`scripts/build_logo_index.py`（v1.21 编写，v1.22 归档进仓库）。
跑一次会：① 按规则裁剪 `assets/channel_logo/`；② 重新生成 `assets/logo_alias.txt`。
规则在脚本顶部的 `is_common()` / `HK_TW` / `KIDS_EDU` 里改。**改完必须重新出包**（assets 打进 APK）。

---

## 9. EPG 匹配索引（性能关键，别改回线性扫描）

**背景**：v1.20 线上一条 `ANRError`，主线程阻塞 11 秒，堆栈末端是 `Pattern.matcher`。
根因：`EpgList.match` 对每个频道线性扫整份节目单（上千个），每次比较跑 2~4 次
`normalize`（每次 = NFKC + 4 条正则）→ 千万级正则调用，老设备 CPU 直接被压死。
**这就是"左侧列表挑节目卡死"的真凶。**

**现状**：节目单解析完时在后台线程建一次哈希索引
（`normIndex`：归一化名→Epg；`coreIndex`：cctv 核心→Epg），之后匹配是 O(1) 查表，
结果与旧实现**逐条等价**（`EpgMatchTest` 锁死）。JVM 实测：1000 频道 × 70 次查询
**339ms → 建索引 8ms + 匹配 0ms**；规模放大 4 倍只 10ms。

**教训**：凡是「集合 A 每个元素都要在集合 B 里找对应」，一律先建索引；
「焦点移动 → 重活」是电视端卡顿第一嫌疑。

---

## 10. 投屏（DLNA / SSDP）

- **小米电视用的是私有设备类型**：`urn:schemas-upnp-org:device:MiMediaRenderer:0.5` /
  `urn:schemas-upnp-org:service:MiAVTransport:0.5`，**只回应 `ssdp:all` 和 `upnp:rootdevice`**，
  对标准 `MediaRenderer:1/2/3` 的 M-SEARCH 一个包都不回（抖音等 App 发的是 `ssdp:all`，所以它们搜得到）。
- 因此：搜索目标 = `ssdp:all` + `upnp:rootdevice` + 标准 MediaRenderer 三条；
  「是不是渲染器」拉到描述 XML 后判（有含 `AVTransport` 的服务即可，**用 `contains` 不用等于**）。
- **必须逐张网卡搜索**（wlan→eth→en，排除 tun/ppp/utun）：盒子同时插网线又连 WiFi 时，
  只挑一张会挑错，现象就是"搜不到"。
- `MX` 要 ≥3（小米响应要 1 秒以上）；搜索时长 8s。
- SOAP 的 `serviceType` **必须用设备描述里的原值**（`MiAVTransport:0.5`），
  控制器读的是 `avTransportServiceType`，这点是对的，别改成硬编码标准 URN。
- 多播需要 `CHANGE_WIFI_MULTICAST_STATE` 权限 + `MulticastLock`（Manifest 已有）。

---

## 11. 已知问题 / 待办 / 验证缺口

| # | 事项 | 状态 |
|---|---|---|
| 1 | v1.22 的三项新行为（三源不联网取头像 / 潮汕无台标位 / 跑马灯）**未真机验证** | 本机内存不足，模拟器在切源验证时被系统杀掉。仅有单测 + 代码路径证据 |
| 2 | 潮剧源同样适合「去台标位 + 跑马灯」 | 用户未要求，**未做**。要做就在 `SourceLogoPolicy.NO_LOGO_MARKS` 加 `chaoju` |
| 3 | `CrashReporter` 注释里写着"工程是私有仓库" | **已不成立**（仓库公开），上报 token 实际是公开的。建议改写注释 |
| 4 | 工作区目录 `mytv-legacy/` 本身**不是 git 仓库** | 发布靠 `/tmp/MyTV4.4` 克隆中转（§4 ⑤）。可以考虑把克隆放回工作区 |
| 5 | Cloudflare 部署凭据不在本机 | 需要 `wrangler login` 才能重新部署 Worker |
| 6 | 内置台标只覆盖常见频道 | 地方数字频道靠「源自带 → 联网」；要补内置就改 §8.5 的规则 |

---

## 12. 常见维护场景速查

| 场景 | 怎么做 |
|---|---|
| 发一个新版本 | §4 全流程 |
| 改某个内置源地址 | 改 `defaultSources()` + `CURRENT_CONFIG_VERSION +1` + 加一段迁移（§6.2） |
| 新增一个内置源 | 同上（迁移模式用"按 url 去重补到末尾"） |
| 加/减内置台标 | 改 `scripts/build_logo_index.py` 的规则 → 跑 → 重新出包（§8.5） |
| 某个源的台标行为要变 | 改 `SourceLogoPolicy.modeOf()` 的匹配标记，同步加单测 |
| 节目单匹配出错/变慢 | 别碰 `EpgList.match` 的索引结构；先看 `EpgMatchTest` 是否还过 |
| 投屏搜不到设备 | 先按 §10 自查：ST 是否含 `ssdp:all`、是否逐网卡、MX、设备描述里的服务类型 |
| 看线上崩溃 | <https://mytvbug.nide.qzz.io/admin>（Basic Auth）；指纹相同的会聚合成一条 |
| 换签名库 | ⚠️ 老用户将无法覆盖安装，必须先卸载。证书 SHA-256 见 §1 |

---

## 13. 踩坑记录（精华，**改这些地方前必读**）

1. **`catch (Exception)` 抓不到 `OutOfMemoryError`**（它是 `Error`）。后台线程解码/分配的兜底一律 `catch (t: Throwable)`。
2. **远程图片必须"先探边界再算 inSampleSize 再真解"**，并给下载加字节上限；位图缓存要**按字节**（`byteCount`）而不是按张数。
3. **`RecyclerView` 的 `notifyDataSetChanged()` 在 layout/滚动中调用会抛异常** → `submit()` 有 `post` 延后一帧的保护，别去掉。
4. **"列表到底/到顶"的分支，决定不消费也要显式 `return true`**：放行按键会被系统 `focusSearch` 交给窗口里第一个可聚焦视图（= 一级菜单「换源」）。
5. **`View.post` 的回调里要重新判 `isFocused`**：RecyclerView 复用会让回调落在已换绑的行上。
6. **ANR 堆栈里出现 `Pattern.matcher` 基本可以直接定罪**：主线程跑正则 = 灾难。
7. **模拟器不给真实 DNS 时，请求"不成功也不超时"**，看起来像 App 卡死，实际是 fake-IP。
8. **`timeout` 命令在 macOS 不存在**；`ps` 在子 shell 里可能被沙箱禁；`head`/`grep` 在子 shell 里也可能不可用 → 文本处理统一用 Python。
9. **测 Cloudflare/urllib 用默认 UA 会被 403**；同一个 URL 换浏览器 UA 立刻 200。
10. **`.gitignore` 已挡掉 `keystore/`、`keystore.properties`、`*.apk`、`dist/`**——rsync 同步时也要显式排除，否则会把签名材料推进公开仓库。

---

## 附：目录结构

```
/Users/nid/WorkBuddy/2026-10-06-16-46-21/
├── mytv-legacy/            ← 主工程（App 源码、脚本、产物、文档）
│   ├── app/src/main/       ← 源码与资源（assets/channel_logo、assets/logo_alias.txt）
│   ├── app/src/test/       ← JVM 单测（11 项）
│   ├── scripts/            ← 验证 / 台标重建 / 抓帧等脚本
│   ├── update/version.json ← 应用内升级清单
│   ├── dist/               ← 各版本 APK + 交付报告
│   ├── keystore/           ← 🔒 签名库（不进 Git）
│   └── PROJECT-STATE.md    ← 逐版本流水（必读）
└── mytv-bug-report/        ← 崩溃上报 Cloudflare Worker（独立工程）
```
