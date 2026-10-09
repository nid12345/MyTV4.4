#!/usr/bin/env bash
# =============================================================================
# scripts/install-test-ca.sh —— 把自建测试 CA 装进 API 19 模拟器的**系统**信任库
#
# 用途：B1（HTTPS 播放）与 M7（EPG HTTPS）验证要连宿主机自建的 HTTPS 服务
#       （scripts/https-hls-server.py），其证书是自签 CA 签的，设备默认不信任。
#       把 CA 放进 /system/etc/security/cacerts/ 后，App 进程即可信任它。
#
# 用法： scripts/install-test-ca.sh [CA 路径，默认 /tmp/tls/ca.crt]
#
# ⚠️ 本机踩过的坑（别改掉下面的写法）：
#  1) **装完不要 reboot**。模拟器的 /system 每次启动都从镜像重建，
#     `adb remount` + push 的内容一重启就没（要持久得加 -writable-system，
#     而该参数在本机启动失败过）。不 reboot、直接起 App 进程，新进程就能读到新 CA。
#  2) 文件名必须是 `<subject_hash_old>.0`，权限 644。用 -subject_hash（新）会装不上。
#  3) 模拟器必须用 `run_in_background` 方式启动，否则会随 Bash 调用结束被回收。
# =============================================================================
set -uo pipefail

CA="${1:-/tmp/tls/ca.crt}"
export ANDROID_HOME="${ANDROID_HOME:-/Users/nid/.workbuddy/binaries/android-sdk}"
ADB="${ANDROID_HOME}/platform-tools/adb"

[[ -f "$CA" ]] || { echo "找不到 CA: $CA（先跑 scripts/make-test-ca.sh 生成）"; exit 1; }
"$ADB" devices | grep -q "emulator-.*device" || { echo "没有在线的模拟器"; exit 1; }

HASH="$(openssl x509 -subject_hash_old -in "$CA" -noout)"
echo "[1] CA = $CA"
echo "    subject_hash_old = $HASH"

echo "[2] adb remount"
"$ADB" remount 2>&1 | tail -1

echo "[3] push → /system/etc/security/cacerts/$HASH.0"
"$ADB" push "$CA" "/system/etc/security/cacerts/$HASH.0" 2>&1 | tail -1

echo "[4] chmod 644"
"$ADB" shell chmod 644 "/system/etc/security/cacerts/$HASH.0" 2>&1 | tr -d '\r'

echo "[5] 校验"
"$ADB" shell ls -l "/system/etc/security/cacerts/$HASH.0" 2>&1 | tr -d '\r' | sed 's/^/    /'

cat <<'TIP'

[6] 完成。注意：**不要 reboot**（见脚本头坑 1）。
    验证方法：起 App 去连 https://10.0.2.2:8443/...，
    服务端 /tmp/https_srv.log 出现 ver=TLSv1.2 即表示信任 + 协商都成功。
TIP
