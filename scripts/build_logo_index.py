#!/usr/bin/env python3
"""
内置台标重建脚本（v1.21 编写，v1.22 归档进仓库）

用途（见 HANDOVER.md §8.5）：
  ① 按规则裁剪 app/src/main/assets/channel_logo/（只保留常见台标）
  ② 重新生成 app/src/main/assets/logo_alias.txt（归一化键 -> 文件名）

用法：
  git clone --depth 1 https://github.com/nid12345/mytv-logos.git /tmp/mytv-logos
  curl -sL https://raw.githubusercontent.com/nid12345/iptv-api/master/config/alias.txt -o /tmp/alias.txt
  # 先看会保留哪些（不落盘）：
  python3 scripts/build_logo_index.py --dry
  # 确认后真正执行（会删文件、会写索引）：
  python3 scripts/build_logo_index.py

⚠️ 改完必须重新出包（assets 打进 APK）。
⚠️ 归一化规则必须与 Kotlin ChannelName.normalize 保持一致，改了那边就要同步改这边。
⚠️ 这会**删除** assets 里不在保留清单内的文件，跑之前先确认清单（--dry）。
"""
import os, re, glob, shutil, unicodedata, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.environ.get('LOGO_SRC', '/tmp/mytv-logos/tv')     # 台标仓库 tv/ 目录
DST = os.path.join(ROOT, 'app/src/main/assets/channel_logo')
IDX = os.path.join(ROOT, 'app/src/main/assets/logo_alias.txt')
ALIAS = os.environ.get('LOGO_ALIAS', '/tmp/alias.txt')     # iptv-api 的 alias.txt

CN_DIGITS = {'一':1,'二':2,'三':3,'四':4,'五':5,'六':6,'七':7,'八':8,'九':9,'十':10,
             '十一':11,'十二':12,'十三':13,'十四':14,'十五':15,'十六':16,'十七':17,'十八':18}

# ---- 与 Kotlin ChannelName.normalize 逐条对齐 ----
re_cctv_cn = re.compile(r'中央(?:电视台|电视|台)?[\s\-_·]*([一二三四五六七八九十]{1,3}|\d{1,2})')
re_cctv = re.compile(r'cctv[\s\-_·]*(\d{1,2})(\s*(?:\+|plus))?')
re_quality = re.compile(r'(超高清|高清|标清|超清|蓝光|fhd|uhd|hd)')
re_noise = re.compile(r'[\s\-_·.,，。:：;；!！?？()（）\[\]【】{}"\'“”‘’|/\\、＋]+')


def normalize(raw: str) -> str:
    if not raw:
        return ''
    v = unicodedata.normalize('NFKC', raw).lower()

    def cn_sub(m):
        n = CN_DIGITS.get(m.group(1))
        if n is None:
            try:
                n = int(m.group(1))
            except ValueError:
                return m.group(0)
        return 'cctv%d' % n

    v = re_cctv_cn.sub(cn_sub, v)

    def cctv_sub(m):
        plus = '' if not m.group(2) or not m.group(2).strip() else 'plus'
        return 'cctv%s%s' % (m.group(1), plus)

    v = re_cctv.sub(cctv_sub, v)
    v = re_quality.sub('', v)
    v = re_noise.sub('', v)
    return v


# ---------------- 常见台标的挑选规则 ----------------
HK_TW = set("""
TVB翡翠台 TVB明珠台 TVBJ2 TVB+ TVB星河 TVBclassic TVB经典台 TVBS TVBS新闻 TVBS亚洲 TVBS欢乐 TVBS精采
无线新闻台 无线财经台 翡翠台 明珠台
凤凰卫视中文台 凤凰卫视资讯台 凤凰卫视香港台 凤凰卫视电影台 凤凰中文 凤凰资讯
星空卫视 卫视中文台 卫视中文
中天新闻台 中视 中视新闻台 中视新闻 台视 台视新闻台 台视新闻 台视综合 台视财经
华视 华视新闻 公视 公视戏剧台 民视 民视新闻台 民视第一台 民视综艺台 民视台湾台 民视旅游台
年代MUCH 年代ERANewsHD 寰宇新闻 人间卫视 大爱电视 大爱一台
香港卫视 香港国际财经台 hongkongkai 澳亚卫视 澳视澳门 澳门莲花 莲花卫视
NOW新闻台 RTHK31 RTHK32 viutv viutv6 TaiwanPlus
EBCNews EBCFinancialNews EBCMovies EBCDrama EBCVariety EBCSuper EBCWesternMovies EBCYoYoTV EbcAsiaWeishi
""".split())

# 常见少儿 / 教育卡通（电视端高频）
KIDS_EDU = set("""
金鹰卡通 卡酷少儿 嘉佳卡通 优漫卡通 哈哈炫动 中国教育1台 中国教育2台 中国教育3台 中国教育4台
广东嘉佳卡通 江苏优漫卡通 湖南金鹰卡通
""".split())


def is_common(name: str) -> bool:
    if name.startswith('CCTV'):
        return True
    if name.startswith('CETV') or name.startswith('CGTN'):
        return True
    if '卫视' in name:            # 央视/省级/港澳台卫视一律保留
        return True
    if name in HK_TW:
        return True
    if name in KIDS_EDU:
        return True
    return False


def main():
    names = sorted(os.path.splitext(os.path.basename(p))[0]
                   for p in glob.glob(SRC + '/*'))
    keep = [n for n in names if is_common(n)]
    drop = [n for n in names if not is_common(n)]
    print('仓库总数 %d → 保留 %d，剔除 %d' % (len(names), len(keep), len(drop)))

    # ---- ① 从 assets 里删掉非保留项 ----
    removed = 0
    for n in drop:
        for ext in ('.png', '.PNG', '.jpg', '.jpeg', '.JPG', '.jpeg'):
            p = os.path.join(DST, n + ext)
            if os.path.exists(p):
                os.remove(p)
                removed += 1
                break
    print('已从 assets 删除 %d 个文件' % removed)

    have = set()
    for p in glob.glob(DST + '/*'):
        have.add(os.path.splitext(os.path.basename(p))[0])
    print('assets 现有台标 %d 个' % len(have))

    # ---- ② 建索引：归一化键 → 文件名 ----
    index = {}

    def put(key: str, target: str):
        if not key or key in index:
            return
        index[key] = target

    for n in sorted(have):
        put(normalize(n), n)

    # 别名表：主名能对应到某张内置台标的，把它的全部别名都登记上
    alias_hit = alias_miss = 0
    if os.path.exists(ALIAS):
        for line in open(ALIAS, encoding='utf-8'):
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            parts = [p.strip() for p in line.split(',') if p.strip()]
            if len(parts) < 2:
                continue
            main = parts[0]
            mn = normalize(main)
            target = index.get(mn)
            if target is None:
                # 主名可能是 "CCTV-1" 而索引键是 "cctv1" —— 已在 normalize 里统一，
                # 再兜一次：用 logoFileName 逻辑（cctvN / cctvNplus）
                m = re.fullmatch(r'cctv(\d{1,2}(?:plus)?|4k|3d|8k)', mn)
                if m:
                    target = index.get('cctv' + m.group(1)) or \
                        index.get(('cctv' + m.group(1)).replace('plus', '+'))
            if target is None:
                alias_miss += 1
                continue
            alias_hit += 1
            put(mn, target)
            for a in parts[1:]:
                put(normalize(a), target)
    print('别名表：命中 %d 行，未命中（台标未内置）%d 行' % (alias_hit, alias_miss))

    # 兜底：CCTV 系列把 "cctvN" / "cctvNplus" / "cctvN+" 三种写法互指
    for n in sorted(have):
        m = re.fullmatch(r'CCTV(\d{1,2})([+＋])?', n)
        if m:
            put('cctv' + m.group(1), n)
            if m.group(2):
                put('cctv' + m.group(1) + 'plus', n)

    with open(IDX, 'w', encoding='utf-8') as f:
        for k in sorted(index):
            f.write('%s\t%s\n' % (k, index[k]))
    print('索引条目 %d 条 → %s（%.1f KB）' %
          (len(index), IDX, os.path.getsize(IDX) / 1024))

    # ---- ③ 抽查 ----
    for probe in ['CCTV1综合', 'CCTV-1 综合', '中央一台', 'CCTV-5+ 体育赛事', '湖南卫视高清',
                  '浙江卫视', '翡翠台', '凤凰卫视中文台', '香港卫视', 'CCTV4K']:
        k = normalize(probe)
        print('  抽查 %-16s → 键 %-16s → %s' % (probe, k, index.get(k) or '未命中'))


if __name__ == '__main__':
    if '--dry' in sys.argv:
        names = sorted(os.path.splitext(os.path.basename(p))[0]
                       for p in glob.glob(SRC + '/*'))
        keep = [n for n in names if is_common(n)]
        print('保留 %d / %d' % (len(keep), len(names)))
        print(' | '.join(keep))
    else:
        main()
