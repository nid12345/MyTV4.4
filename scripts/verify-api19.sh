#!/usr/bin/env bash
# =============================================================================
# scripts/verify-api19.sh —— API 19（Android 4.4.2）验证通道
#
# 固化自 M0 阶段实测跑通的一整套流程。用途：每个模块改完后用它做一次
# 「装得上 + 起得来 + 不崩」的实机判定，避免每轮重复敲同样的 adb 序列。
#
# 用法：
#   scripts/verify-api19.sh [APK路径]
#   - 不传参数：自动选 dist/ 下最新的 *debug*.apk，退而求其次用
#               app/build/outputs/apk/debug/app-debug.apk
#
# 可选环境变量：
#   AVD_NAME=mytv19  EMU_PORT=5554  PKG=...  ACT_CLASS=...
#   RUN_SEC=8        WAIT_BOOT_SEC=180      SHOT=/tmp/x.png   （传了就抓一张截图）
#
# 退出码：0 = 通过；1 = 失败（含 SDK 版本不符、安装失败、出现 FATAL EXCEPTION）
#
# 幂等性：模拟器已在跑则复用；APK 用 `install -r` 覆盖安装。
#
# -----------------------------------------------------------------------------
# 本机踩过的坑（别改掉下面这些写法）：
#  1) API 19 的 `adb exec-out screencap -p` 会返回 0 字节，必须用
#     `shell screencap -p <path>` + `adb pull`。
#  2) 该 AVD 的 /sdcard 只读，截图必须写 /data/local/tmp。
#  3) AVD 的屏幕规格写在 config.ini（hw.lcd.*）里；用 `wm size` 覆盖只改逻辑
#     显示、不改 framebuffer，截屏拿到的仍是物理分辨率 —— 会造成尺寸误判。
#  4) 判断存活不要只信 `adb shell ps`（老系统上偶有缺项），要同时看 logcat。
#  5) 【本脚本踩过】变量展开后面**紧跟全角字符**时必须写成 ${VAR}：
#     `"${REL}）"` 会被 bash 把「）」的多字节并入变量名，报 `REL<乱码>: unbound variable`
#     （配合 set -u 直接中止）。本脚本所有变量一律用 ${} 包裹。
# =============================================================================
#
# #############################################################################
# 【M1 补充：做「按键 / 界面」类验收必须遵守的协议】—— 不遵守会得到假阴性
# #############################################################################
#
#  A) 不要用 `input tap` 去唤出界面。
#     任何触摸都会让设备进入 touch mode，此后**第一个导航键（DPAD_*）可能被系统
#     吞掉**用于退出 touch mode，应用根本收不到它。
#     实测证据：同一条 shell 里 `tap → DPAD_RIGHT` 时 RIGHT 零日志；
#               去掉 tap（或先补一个"暖场"方向键）后 RIGHT 立即正常。
#     → 若面板确实已自动隐藏、必须先用 tap 唤出，则在 tap 之后
#       **先发一个暖场方向键**，再开始真正要测的按键序列。
#
#  B) 不要在两次工具调用之间留 10 秒以上。
#     界面自动隐藏是 10 秒（MainActivity.AUTO_HIDE_MS）。面板被隐藏后按键仍然
#     生效、但没有画面变化，会让"位移可见"的判定假失败。
#     → 把整段按键序列连同截图全部塞进**同一条 adb shell** 里跑。
#
#  C) 截图比对不要用整图 MD5。
#     右上角时钟每秒都在变，整图必然不同，会把"毫无变化"误判成"有变化"
#     （M1 实测：a1 与 a0 的 MD5 不同，但实际画面逐像素相同，只是时钟走了 2 秒）。
#     → 用同目录的 `scripts/compare-region.py` 只比区域，排除时钟：
#       scripts/compare-region.py a.png b.png 0 700 0 620
#       0 像素差异 = 真的没变；上万像素 = 真的动了。
#
#  D) Android 4.4 的 shell 没有 `seq`（`/system/bin/sh: seq: not found`）。
#     循环写成： i=0; while [ $i -lt 50 ]; do ...; i=$((i+1)); done
#
#  E) logcat 环形缓冲会被刷掉。
#     连发 50 次按键会产生大量日志，把前面的路由日志挤出缓冲。
#     → 分步 `logcat -d > 文件` 落盘，不要只在最后 dump 一次。
#
#  F) 【M3 补测期发现，最隐蔽的一个】必须给模拟器指定**真实 DNS**。
#     本机跑着代理，宿主的 DNS 被代理的 fake-IP 劫持：`scutil --dns` 的首个
#     nameserver 是 198.18.0.2（utun7），于是**所有域名都解析成 198.18.1.x**。
#     宿主自己的 curl 能通是因为它走代理；但模拟器的 NAT 流量**不走**代理，
#     TCP 打到 198.18.1.x 就是不存在的地址 → 连接既不成功也不超时，直接挂死。
#     症状极具误导性：HTTP 请求 100 秒不返回、**没有任何异常**、也没有崩溃，
#     看起来像"应用卡住"，实际是环境问题。
#     → 启动参数固定加 `-dns-server 119.29.29.29,223.5.5.5`（实测这两个返回真 IP：
#       119.29.29.29 仅 10ms）。用 DNS_SERVERS 可覆盖。
#       验证方法：`adb shell ping -c1 <域名>` 打印出来的 IP 不是 198.18.x.x 即为正常。
# =============================================================================
set -uo pipefail

# ---------------------------- 配置 ----------------------------
AVD_NAME="${AVD_NAME:-mytv19}"
EMU_PORT="${EMU_PORT:-5554}"
SERIAL="emulator-${EMU_PORT}"
PKG="${PKG:-top.yogiczy.mytv.legacy.debug}"
ACT_CLASS="${ACT_CLASS:-top.yogiczy.mytv.legacy.MainActivity}"
WAIT_BOOT_SEC="${WAIT_BOOT_SEC:-180}"
RUN_SEC="${RUN_SEC:-8}"
SHOT="${SHOT:-}"
DNS_SERVERS="${DNS_SERVERS:-119.29.29.29,223.5.5.5}"   # 见脚注 F：绕开代理的 fake-IP DNS
EXPECT_SDK=19

PROJ_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/Users/nid/.workbuddy/binaries/java/jdk-17.0.20.1+1/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/Users/nid/.workbuddy/binaries/android-sdk}"
ADB="${ANDROID_HOME}/platform-tools/adb"
EMULATOR="${ANDROID_HOME}/emulator/emulator"

TS="$(date +%Y%m%d-%H%M%S)"
LOGCAT="/tmp/verify-${TS}.log"
EMULOG="/tmp/verify-emulator-${TS}.log"
FAILED=0

# ---------------------------- 输出 ----------------------------
step(){ printf '\n[%s] %s\n' "$1" "$2"; }
ok(){   printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad(){  printf '  \033[31m✗\033[0m %s\n' "$*"; FAILED=1; }
info(){ printf '  · %s\n' "$*"; }
warn(){ printf '  \033[33m!\033[0m %s\n' "$*"; }

# ---------------------------- 0. 前置检查 ----------------------------
step 0 "前置检查"
[[ -x "${ADB}" ]] || { echo "找不到 adb: ${ADB}"; exit 1; }
ok "adb: ${ADB}"

if [[ -n "${1:-}" ]]; then
    APK="$(cd "$(dirname "$1")" 2>/dev/null && pwd)/$(basename "$1")"
else
    APK="$(ls -t "${PROJ_DIR}"/dist/*debug*.apk 2>/dev/null | head -1 || true)"
    [[ -z "${APK}" ]] && APK="${PROJ_DIR}/app/build/outputs/apk/debug/app-debug.apk"
fi
[[ -f "${APK}" ]] || { echo "找不到 APK: ${APK}"; exit 1; }
ok "APK: ${APK} ($(stat -f%z "${APK}" 2>/dev/null || stat -c%s "${APK}") B)"

# ---------------------------- 1. 确保模拟器在跑 ----------------------------
step 1 "确保模拟器 ${SERIAL} 在线"
if "${ADB}" devices | grep -q "^${SERIAL}[[:space:]]*device"; then
    ok "已在运行，复用（不重启、不重置）"
else
    info "未运行，启动 AVD ${AVD_NAME} …"
    [[ -x "${EMULATOR}" ]] || { bad "找不到 emulator: ${EMULATOR}"; exit 1; }
    "${EMULATOR}" -avd "${AVD_NAME}" -no-snapshot -no-audio -no-boot-anim \
        -dns-server "${DNS_SERVERS}" \
        -gpu swiftshader_indirect -port "${EMU_PORT}" > "${EMULOG}" 2>&1 &
    disown 2>/dev/null || true
    info "模拟器日志: ${EMULOG}"
    info "DNS: ${DNS_SERVERS}（绕开代理 fake-IP，见脚本头脚注 F）"
    "${ADB}" wait-for-device
fi

# ---------------------------- 2. 等待启动完成 ----------------------------
step 2 "等待系统启动完成（上限 ${WAIT_BOOT_SEC}s）"
DEADLINE=$(( SECONDS + WAIT_BOOT_SEC ))
while (( SECONDS < DEADLINE )); do
    BC="$("${ADB}" -s "${SERIAL}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    [[ "${BC}" == "1" ]] && break
    sleep 3
done
if [[ "${BC:-}" == "1" ]]; then
    ok "sys.boot_completed = 1"
else
    bad "系统未在 ${WAIT_BOOT_SEC}s 内启动完成"
    exit 1
fi

# ---------------------------- 3. 校验 SDK = 19 ----------------------------
step 3 "校验 API 级别 == ${EXPECT_SDK}"
SDK="$("${ADB}" -s "${SERIAL}" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
REL="$("${ADB}" -s "${SERIAL}" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
SIZE="$("${ADB}" -s "${SERIAL}" shell wm size 2>/dev/null | grep -v WARNING | tr -d '\r')"
DEN="$("${ADB}" -s "${SERIAL}" shell wm density 2>/dev/null | grep -v WARNING | tr -d '\r')"
info "sdk=${SDK}  release=${REL}  ${SIZE}  ${DEN}"
if [[ "${SDK}" == "${EXPECT_SDK}" ]]; then
    ok "API 19 正确（Android ${REL}）"
else
    bad "API 级别不符：期望 ${EXPECT_SDK}，实际 ${SDK:-空} —— 后续判定无意义，中止"
    exit 1
fi

# ---------------------------- 4. 安装 ----------------------------
step 4 "安装 APK（-r 覆盖）"
INSTALL_OUT="$("${ADB}" -s "${SERIAL}" install -r "${APK}" 2>&1 | grep -v 'WARNING: linker' | tr -d '\r')"
echo "${INSTALL_OUT}" | sed 's/^/      /'
if echo "${INSTALL_OUT}" | grep -qE "Success"; then
    ok "安装成功"
else
    bad "安装失败"
    exit 1
fi

# ---------------------------- 5. 启动 ----------------------------
step 5 "启动 ${PKG}/${ACT_CLASS}"
"${ADB}" -s "${SERIAL}" logcat -c 2>/dev/null
START_OUT="$("${ADB}" -s "${SERIAL}" shell am start -n "${PKG}/${ACT_CLASS}" 2>&1 | tr -d '\r')"
echo "${START_OUT}" | sed 's/^/      /'
if echo "${START_OUT}" | grep -qE "Error|Exception"; then
    warn "am start 输出含 Error/Exception，继续看 logcat 判定"
fi
info "运行 ${RUN_SEC}s …"
sleep "${RUN_SEC}"

# ---------------------------- 6. 抓日志 ----------------------------
step 6 "抓取 logcat"
"${ADB}" -s "${SERIAL}" logcat -d -v time > "${LOGCAT}" 2>/dev/null
if [[ -s "${LOGCAT}" ]]; then
    ok "${LOGCAT}（$(wc -l < "${LOGCAT}" | tr -d ' ') 行）"
else
    bad "logcat 为空"
fi

# ---------------------------- 7. 判定 ----------------------------
step 7 "判定"
if grep -qE "FATAL EXCEPTION|E AndroidRuntime" "${LOGCAT}"; then
    bad "logcat 含 FATAL EXCEPTION / AndroidRuntime —— FAIL"
    echo "      ---- 崩溃片段 ----"
    grep -nE -A 12 "FATAL EXCEPTION|E AndroidRuntime" "${LOGCAT}" | head -40 | sed 's/^/      /'
else
    ok "无 FATAL EXCEPTION / AndroidRuntime —— PASS"
fi

if "${ADB}" -s "${SERIAL}" shell "ps | grep ${PKG##*.}" 2>/dev/null | grep -q "${PKG##*.}"; then
    ok "进程仍存活"
else
    warn "ps 未匹配到进程（老系统上可能不准，以 logcat 判定为准）"
fi

# ---------------------------- 8. 可选截图 ----------------------------
if [[ -n "${SHOT}" ]]; then
    step 8 "截图到 ${SHOT}"
    "${ADB}" -s "${SERIAL}" shell rm -f /data/local/tmp/verify.png 2>/dev/null
    "${ADB}" -s "${SERIAL}" shell screencap -p /data/local/tmp/verify.png 2>/dev/null
    if "${ADB}" -s "${SERIAL}" pull /data/local/tmp/verify.png "${SHOT}" 2>&1 | grep -q "1 file pulled"; then
        ok "已保存：${SHOT}"
    else
        bad "截图失败"
    fi
fi

# ---------------------------- 汇总 ----------------------------
printf '\n========================================\n'
if (( FAILED == 0 )); then
    printf '  \033[32mPASS\033[0m  API %s  |  %s\n' "${SDK}" "$(basename "${APK}")"
else
    printf '  \033[31mFAIL\033[0m  API %s  |  %s\n' "${SDK}" "$(basename "${APK}")"
fi
printf '  logcat: %s\n' "${LOGCAT}"
printf '========================================\n'
exit ${FAILED}
