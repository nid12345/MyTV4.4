#!/usr/bin/env bash
# =============================================================================
# scripts/live-slider.sh —— 本地 HLS「直播」滑动窗口生成器
#
# 用途：把 /tmp/hls 下那批静态 fileSequenceN.ts（VOD 切片）包装成一个
#       持续滑动的 live 播放列表 live_index.m3u8，让 ExoPlayer 判定为**直播**
#       （有 liveOffset / currentLiveOffset），从而可以测：
#         · 7 档显示比例（需要真实视频尺寸）
#         · 拔网恢复（BEHIND_LIVE_WINDOW 归位重试）
#         · 播放控制条「正在播出」进度
#
# 用法： scripts/live-slider.sh &      # 后台常驻，Ctrl-C / kill 结束
# 可配： HLS_DIR=/tmp/hls  WINDOW=6  INTERVAL=10  TOTAL=180
#
# 为什么必须自己造：宿主机没有 ffmpeg 常驻服务，也不依赖外网；
# 这批 .ts 是 2026-10-07 08:15 由静态构建生成的（见 /tmp/hls）。
# =============================================================================
set -uo pipefail

HLS_DIR="${HLS_DIR:-/tmp/hls}"
WINDOW="${WINDOW:-6}"        # 滑动窗口内的切片数
INTERVAL="${INTERVAL:-10}"   # 每片 10 秒，与 #EXTINF 对齐
TOTAL="${TOTAL:-180}"        # 可用切片总数（fileSequence0..TOTAL-1）
OUT="${HLS_DIR}/live_index.m3u8"

start=0
while true; do
    {
        echo "#EXTM3U"
        echo "#EXT-X-TARGETDURATION:10"
        echo "#EXT-X-VERSION:3"
        echo "#EXT-X-MEDIA-SEQUENCE:${start}"
        i=0
        while [ "${i}" -lt "${WINDOW}" ]; do
            idx=$(( (start + i) % TOTAL ))
            echo "#EXTINF:10.0,"
            echo "fileSequence${idx}.ts"
            i=$((i + 1))
        done
    } > "${OUT}.tmp"
    mv "${OUT}.tmp" "${OUT}"
    start=$(( (start + 1) % TOTAL ))
    sleep "${INTERVAL}"
done
