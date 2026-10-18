#!/usr/bin/env python3
# =============================================================================
# scripts/compare-region.py —— 两幅截图在指定区域内逐像素比对
#
# 为什么需要它（M1 实测教训）：
#   界面右上角有每秒走动的时钟，**整图 MD5 必然不同**，会把"画面毫无变化"
#   误判成"画面变化了"。必须只比感兴趣的区域（例如排除时钟所在的右上角）。
#   实测案例：a1 与 a0 的整图 MD5 不同，但左半屏 x[0,700) y[0,600) 差异为 0 像素
#   —— 差异全部来自时钟的 2 秒跳动。
#
# 用法：
#   scripts/compare-region.py A.png B.png X0 X1 Y0 Y1
#     比较 A、B 在矩形区域 x∈[X0,X1)、y∈[Y0,Y1) 内的差异像素数。
#     排除右上角时钟的常用区域（1280x720）： 0 700 0 620
#
# 判读：
#   0 像素        → 真的没有变化
#   几千~几万像素 → 元素确实动了（例如面板整体位移）
#   同时给出百分比，便于区分"只是局部高亮变化"还是"整块位移"。
#
# 实现说明：本机没有 PIL，所以借 macOS 自带 `sips` 把 PNG 转成 BMP
# （去掉了 PNG 的滤镜与元数据），再用纯 Python 解析 24/32bpp 像素。
# 只比较 RGB 三通道，忽略 alpha 通道数量差异。
# =============================================================================
import os
import struct
import subprocess
import sys


def to_bmp(png: str, bmp: str) -> None:
    subprocess.run(
        ["sips", "-s", "format", "bmp", png, "--out", bmp],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False,
    )


def load(bmp: str):
    d = open(bmp, "rb").read()
    off = struct.unpack_from("<I", d, 10)[0]
    w, h = struct.unpack_from("<ii", d, 18)
    bpp = struct.unpack_from("<H", d, 28)[0]
    n = bpp // 8
    return d, off, w, abs(h), n, ((w * n + 3) // 4) * 4


def region_diff(p1: str, p2: str, x0: int, x1: int, y0: int, y1: int):
    b1, b2 = "/tmp/_cmp_a.bmp", "/tmp/_cmp_b.bmp"
    to_bmp(p1, b1)
    to_bmp(p2, b2)
    d1, o1, w1, h1, n1, s1 = load(b1)
    d2, o2, w2, h2, n2, s2 = load(b2)
    if (w1, h1) != (w2, h2):
        return None, f"尺寸不同: {w1}x{h1} vs {w2}x{h2}"
    diff = total = 0
    for y in range(y0, min(y1, h1)):
        r1, r2 = o1 + y * s1, o2 + y * s2
        for x in range(x0, min(x1, w1)):
            i1, i2 = r1 + x * n1, r2 + x * n2
            total += 1
            if d1[i1:i1 + 3] != d2[i2:i2 + 3]:
                diff += 1
    return (diff, total), None


USAGE = """用法:
  scripts/compare-region.py A.png B.png X0 X1 Y0 Y1

  比较 A、B 在矩形区域 x∈[X0,X1)、y∈[Y0,Y1) 内的差异像素数。
  排除右上角时钟的常用区域（1280x720 设备）： 0 700 0 620

判读:
  0 像素        -> 真的没有变化
  几千~几万像素 -> 元素确实动了（例如面板整体位移）

示例:
  scripts/compare-region.py before.png after.png 0 700 0 620
"""


def main() -> int:
    if len(sys.argv) != 7:
        print(USAGE)
        return 2
    a, b = sys.argv[1], sys.argv[2]
    x0, x1, y0, y1 = (int(v) for v in sys.argv[3:7])
    for p in (a, b):
        if not os.path.isfile(p):
            print(f"找不到文件: {p}")
            return 2
    res, err = region_diff(a, b, x0, x1, y0, y1)
    tag = f"区域 x[{x0},{x1}) y[{y0},{y1})"
    if err:
        print(f"  {os.path.basename(a)} vs {os.path.basename(b)}  {tag}: {err}")
        return 1
    diff, total = res
    pct = 100.0 * diff / total if total else 0.0
    verdict = "无变化" if diff == 0 else ("有变化" if diff > 500 else "细微差异")
    print(f"  {os.path.basename(a)} vs {os.path.basename(b)}  {tag}: "
          f"不同像素 {diff}/{total} ({pct:.2f}%)  -> {verdict}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
