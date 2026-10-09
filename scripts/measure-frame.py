#!/usr/bin/env python3
"""measure-frame.py —— 客观测量「视频画面矩形」并与原版算法给出的期望值比对

为什么需要它：显示比例（7 档）的验收不能只看截图"感觉对不对"，必须有数值判据。

做法：
  1. 把截图转成 BMP（用 macOS 自带 sips，不依赖 Pillow）；
  2. 只统计**彩色像素**（max(R,G,B) - min(R,G,B) > SAT_MIN）。
     —— 界面上的时钟、底栏文字、提示条都是白/灰（低饱和），因此天然被排除；
        而测试视频（Apple bipbop 彩条）是高饱和的，于是包围盒 = 画面矩形。
  3. 若给了 --video 与 --view，则按**原版** `VideoPlayerScreen.kt:43-52` 的
     公式（Compose `Modifier.aspectRatio` = contain；CROP = fillMaxWidth+aspectRatio；
     FILL = fillMaxSize）算出 7 档的期望矩形，逐档对比。

用法：
  scripts/measure-frame.py shot.png
  scripts/measure-frame.py shot.png --view 1280x720 --video 416x240
  scripts/measure-frame.py shot.png --region 0,720,0,720        # 只测左半屏
退出码：0 = 正常输出；2 = 参数错误
"""
import struct
import subprocess
import sys
import os

SAT_MIN = 24

MODE_NAMES = ["原始(0)", "填充(1)", "裁剪(2)", "4:3(3)", "16:9(4)", "2.35:1(5)", "3:4(6)"]
FIXED_ASPECT = {3: 4 / 3, 4: 16 / 9, 5: 2.35, 6: 3 / 4}


def png_to_bmp(png: str, bmp: str) -> bool:
    r = subprocess.run(["sips", "-s", "format", "bmp", png, "--out", bmp],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return r.returncode == 0 and os.path.exists(bmp)


def load_bmp(path: str):
    d = open(path, "rb").read()
    off = struct.unpack_from("<I", d, 10)[0]
    w, h = struct.unpack_from("<ii", d, 18)
    bpp = struct.unpack_from("<H", d, 28)[0]
    return d, off, w, abs(h), bpp // 8


def content_bbox(png: str, region=None):
    bmp = "/tmp/_mf.bmp"
    if not png_to_bmp(png, bmp):
        return None, "BMP 转换失败（sips）"
    d, off, w, h, n = load_bmp(bmp)
    stride = ((w * n + 3) // 4) * 4

    x0, x1, y0, y1 = region if region else (0, w, 0, h)
    x0, x1 = max(0, x0), min(w, x1)
    y0, y1 = max(0, y0), min(h, y1)

    minx, miny, maxx, maxy, cnt = w, h, -1, -1, 0
    for y in range(y0, y1):
        base = off + y * stride
        for x in range(x0, x1):
            i = base + x * n
            b, g, r = d[i], d[i + 1], d[i + 2]
            if max(r, g, b) - min(r, g, b) > SAT_MIN:
                cnt += 1
                if x < minx: minx = x
                if x > maxx: maxx = x
                if y < miny: miny = y
                if y > maxy: maxy = y
    if cnt == 0:
        return None, "未找到彩色像素（画面全黑或全灰？）"
    return dict(left=minx, top=miny, right=maxx + 1, bottom=maxy + 1,
                width=maxx + 1 - minx, height=maxy + 1 - miny, colored=cnt,
                size=(w, h)), None


def expect(view_w, view_h, video_w, video_h):
    """原版公式。返回 mode_value -> (w, h, left, top)"""
    out = {}
    vw, vh = view_w, view_h
    var = vw / vh
    ar_video = video_w / video_h

    def contain(ar):
        if var > ar:
            h = vh
            w = int(h * ar)
        else:
            w = vw
            h = int(w / ar)
        return max(w, 1), max(h, 1)

    for m in range(7):
        if m == 0:      # ORIGINAL
            w, h = contain(ar_video)
        elif m == 1:    # FILL
            w, h = vw, vh
        elif m == 2:    # CROP = fillMaxWidth + aspectRatio(videoAR)
            w = vw
            h = max(int(vw / ar_video), 1)
        else:
            w, h = contain(FIXED_ASPECT[m])
        out[m] = (w, h, (vw - w) // 2, (vh - h) // 2)
    return out


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    png = sys.argv[1]
    region = None
    video = None
    view = None
    args = sys.argv[2:]
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--region":
            v = [int(t) for t in args[i + 1].split(",")]
            region = (v[0], v[1], v[2], v[3]); i += 2
        elif a == "--video":
            video = tuple(int(t) for t in args[i + 1].lower().split("x")); i += 2
        elif a == "--view":
            view = tuple(int(t) for t in args[i + 1].lower().split("x")); i += 2
        else:
            i += 1

    res, err = content_bbox(png, region)
    if err:
        print(f"  测量失败：{err}")
        return 1

    print(f"  文件        : {os.path.basename(png)}  图幅 {res['size'][0]}x{res['size'][1]}")
    if region:
        print(f"  测量区域    : x[{region[0]},{region[1]}) y[{region[2]},{region[3]})")
    print(f"  实测画面矩形: left={res['left']} top={res['top']} "
          f"right={res['right']} bottom={res['bottom']}")
    print(f"                宽={res['width']} 高={res['height']} "
          f"宽高比={res['width'] / res['height']:.4f}  彩色像素={res['colored']}")

    if video and view:
        print(f"\n  期望值（原版 VideoPlayerScreen.kt:43-52 的公式，"
              f"view={view[0]}x{view[1]} video={video[0]}x{video[1]}）")
        exp = expect(view[0], view[1], video[0], video[1])
        best = None
        for m in range(7):
            w, h, left, top = exp[m]
            exact = "✅" if (res["width"], res["height"]) == (w, h) else "  "
            print(f"    {MODE_NAMES[m]:10} 期望 {w:4d}x{h:<4d} 期望比={w / h if h else 0:.4f}  {exact}")
            if (res["width"], res["height"]) == (w, h):
                best = m
        print(f"  → 实测尺寸与第 {best} 档（{MODE_NAMES[best]}）一致"
              if best is not None else "  → 实测尺寸与 7 档期望值都不一致，需人工判断")
    return 0


if __name__ == "__main__":
    sys.exit(main())
