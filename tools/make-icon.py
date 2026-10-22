#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
从一张成品图生成两端的图标资源（用户 m08537 第⑧点）。

源图（`C:\\Users\\superv\\Desktop\\图片.png`）是「圆角红方块 + 白色鹿角 + 深色鹿头」，
四角是白底 —— 直接当图标用，在圆形启动器上会露出一圈白边，所以：

  * **安卓**：自适应图标。前景只留「鹿角 + 鹿头」这个图形本身（纯白/深色都保留），
    背景是那块红。这样系统套圆形/方形/水滴形都不会露出白角。
  * **桌面**：`.ico` + `.png`。Windows 的图标不做遮罩，白边必须自己抠掉 ——
    按红方块的圆角轮廓做圆角矩形裁切，四角填成透明。

## 关键：只算一次图形，两个尺寸口径一致

安卓自适应图标的画布是 108dp，只有中间 72dp 保证可见，再往里直径 66dp 的圆才是
「一定不被切」的区域。所以前景必须按**实际像素**缩放：求出图形不透明像素到重心的
最大距离，再据此定缩放，而不是拍一个「看起来差不多」的比例。

用法：
    python tools/make-icon.py                       # 用默认源图
    python tools/make-icon.py <源图路径>
    python tools/make-icon.py <源图路径> --desktop   # 只重做桌面端那两张
"""
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.dirname(HERE)
PROJECT = os.path.dirname(APP)

SRC_DEFAULT = os.path.expanduser(r"~\Desktop\网易云音乐.png")
DESKTOP_ASSETS = os.path.join(PROJECT, "netease-lite", "renderer", "assets")

# 自适应图标画布 108dp，密度无关 —— 下面统一用 1080px 的中间画布作图再缩放导出。
CANVAS = 1080
# 一定不被裁的区域：直径 66dp 的圆。
SAFE_RADIUS = CANVAS * 66 / 108 / 2

# 前景图形在安全圆里还要再缩到几成（用户 m11461 第④点：图标中间的头像缩小一些）。
#
# 原来是「正好塞满安全圆」—— 图形的外接圆贴着 66dp 那条线，启动器套上遮罩之后
# 看起来顶到边、很挤。缩到 0.8 之后四周留出一圈呼吸位，与系统图标的观感接近。
# 只作用于安卓前景；桌面图标按设计稿原比例走，缩了反而不像原图。
ANDROID_SHRINK = 0.8

DENSITIES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}

# 桌面端 .ico 要几档。16/24/32 是任务栏与列表视图，48/64 是中图标，
# 128/256 是大图标与 Alt-Tab（256 是 ico 格式的上限）。
ICO_SIZES = [16, 24, 32, 48, 64, 128, 256]


def _is_red(px, tol=60):
    r, g, b = px[0], px[1], px[2]
    return r > 120 and r - g > tol and r - b > tol


def red_bbox(im):
    """红方块的包围盒（用来知道成品的实际边界，白边就在它外面）。"""
    a = im.convert("RGB")
    w, h = a.size
    px = a.load()
    minx, miny, maxx, maxy = w, h, -1, -1
    # 抽样扫描足够：红块是整块大面积，不需要逐像素。
    step = max(1, min(w, h) // 400)
    for y in range(0, h, step):
        for x in range(0, w, step):
            if _is_red(px[x, y]):
                if x < minx:
                    minx = x
                if y < miny:
                    miny = y
                if x > maxx:
                    maxx = x
                if y > maxy:
                    maxy = y
    if maxx < 0:
        raise SystemExit("源图里找不到红色方块，检查一下源图")
    return minx, miny, maxx, maxy


def extract_glyph(im, bbox):
    """
    抠出「鹿角 + 鹿头」这个图形本身，做成带 alpha 的前景图。

    两种像素都算图形：
      * 近白（鹿角、眼睛、鼻子、脸颊）—— 原图里是白的，在红底上才看得见；
      * 深色（鹿头）—— 原图里是 #333 附近。
    红底与白边都不要（alpha=0），于是前景放到任何背景上都成立。

    ## 怎么把「鹿角」和「外围白边」分开（这一步踩过两次坑）

    两者都是白的，只按颜色分不开。前两次尝试：
      1. 按红块 bbox 裁 —— 红块是**圆角**的，bbox 四角本来就是白的，抠出来仍是全幅；
      2. 从四边泛洪近白像素 —— 外围白边确实被排掉了，但源图里鹿角**碰到了红块的
         圆角外沿**，泛洪顺着连进去了，鹿角被一起吃掉（缩放算出来 0.36，图形反而更小）。

    最终按**连通性**做：以「深色鹿头」为种子，泛洪出所有与之连通的非红像素 ——
    鹿头、眼睛、鼻子、脸颊、鹿角全都连在一起；而外围白边与它们被红底隔开，
    不会被收进来。这个判据与图的构图无关，换一张设计稿也成立。
    """
    a = im.convert("RGBA").crop((bbox[0], bbox[1], bbox[2] + 1, bbox[3] + 1))
    w, h = a.size
    px = a.load()

    # 1) 先排掉「与边界连通的近白像素」= 红方块圆角外侧那圈白边。
    #    不排的话，鹿角一旦碰到圆角外沿，第 2 步的泛洪就会顺着漏进白边，
    #    在图标右上角留下一块白色楔形（实测踩过）。
    margin = Image.new("L", (w, h), 0)
    mp = margin.load()
    for y in range(h):
        for x in range(w):
            r, g, b, _ = px[x, y]
            if min(r, g, b) > 190:
                mp[x, y] = 255
    for x in range(w):
        for y in (0, h - 1):
            if mp[x, y] == 255:
                ImageDraw.floodfill(margin, (x, y), 128)
    for y in range(h):
        for x in (0, w - 1):
            if mp[x, y] == 255:
                ImageDraw.floodfill(margin, (x, y), 128)

    # 2) 候选掩膜：非红、非外围白边，且够「有内容」（近白或够深）。
    #    抗锯齿中间地带也算进来当通路，否则眼睛边缘那圈过渡像素会把鹿头和白鹿角断开。
    cand = Image.new("L", (w, h), 0)
    cp = cand.load()
    seeds = []
    for y in range(h):
        for x in range(w):
            r, g, b, al = px[x, y]
            if al < 8 or _is_red((r, g, b)):
                continue
            if mp[x, y] == 128:
                continue
            whiteness = min(r, g, b)
            darkness = 255 - max(r, g, b)
            if whiteness > 190 or darkness > 45:
                cp[x, y] = 255
            if darkness > 100 and 60 < y < h - 60:
                seeds.append((x, y))

    if not seeds:
        raise SystemExit("源图里找不到深色鹿头，检查一下源图")

    # 3) 从鹿头内部泛洪（红底与白边都不在候选里，扩散不会漏出去）
    for s in seeds[:: max(1, len(seeds) // 200)]:
        if cp[s[0], s[1]] == 255:
            ImageDraw.floodfill(cand, s, 128)

    # 4) 只保留泛洪到的部分，按颜色分成白 / 深色两种
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    op = out.load()
    minx, miny, maxx, maxy = w, h, -1, -1
    for y in range(h):
        for x in range(w):
            if cp[x, y] != 128:
                continue
            r, g, b, _ = px[x, y]
            if min(r, g, b) > 190:
                op[x, y] = (255, 255, 255, 255)
            else:
                op[x, y] = (r, g, b, 255)
            if x < minx:
                minx = x
            if y < miny:
                miny = y
            if x > maxx:
                maxx = x
            if y > maxy:
                maxy = y
    if maxx < 0:
        raise SystemExit("源图里找不到图形，检查一下源图")
    return out.crop((minx, miny, maxx + 1, maxy + 1))


def fit_scale(glyph):
    """算出「图形正好塞进安全圆」的缩放倍数。"""
    w, h = glyph.size
    px = glyph.load()
    cx, cy = w / 2.0, h / 2.0
    worst = 0.0
    for y in range(h):
        for x in range(w):
            if px[x, y][3] > 8:
                d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
                if d > worst:
                    worst = d
    return SAFE_RADIUS / worst, worst

def build_foreground(glyph, scale):
    w, h = glyph.size
    tw, th = max(1, int(round(w * scale))), max(1, int(round(h * scale)))
    big = glyph.resize((tw, th), Image.LANCZOS)
    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    canvas.paste(big, ((CANVAS - tw) // 2, (CANVAS - th) // 2), big)
    return canvas


def average_red(im, bbox):
    """
    红底主色：取红块**上缘一带**的均值。

    不能取中心 —— 那张图的中心正好是深色鹿头，取样窗口会落进去，
    算出来的「红」其实是深灰。
    """
    a = im.convert("RGB")
    minx, miny, maxx, maxy = bbox
    px = a.load()
    # 上缘往下 8%~18% 是一条纯红带（鹿角大约从 20% 才开始）。
    y0 = miny + int((maxy - miny) * 0.08)
    y1 = miny + int((maxy - miny) * 0.18)
    rs = gs = bs = n = 0
    step = max(1, (maxx - minx) // 200)
    for y in range(y0, y1):
        for x in range(minx, maxx, step):
            r, g, b = px[x, y]
            if _is_red((r, g, b)):
                rs += r
                gs += g
                bs += b
                n += 1
    if not n:
        return (0xE6, 0x00, 0x12)
    return (rs // n, gs // n, bs // n)


def rounded_mask(size, radius_ratio=0.225):
    """
    圆角矩形遮罩，用来给桌面图标去白边。

    `radius_ratio` 是圆角半径占边长的比例 —— 原图那个红方块的圆角大约是 0.225
    （1567px 的图上圆角约 350px），照它做才和设计稿一致。
    """
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    r = int(round(size * radius_ratio))
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=255)
    return m


def build_desktop(glyph, glyph_ratio, bg):
    """
    桌面端：把抠好的图形叠在红底圆角方块上，导出 png + 多档 ico。

    ## 为什么不直接裁原图

    试过「按红块 bbox 裁 → 套圆角遮罩」，结果四周留了一圈白边：bbox 是按抽样扫描
    算的，边界上仍混进几个白像素，而圆角遮罩的半径又不可能和原图圆角严丝合缝，
    于是白边就沿着整条边露出来（实测 512px 的图上肉眼可见一圈）。

    改成**重画**：纯色圆角方块 + 抠好的图形，两者都没有白边可漏。
    图形占边长的比例直接取自源图（鹿宽 / 红块宽），所以视觉比例和设计稿一致。
    """
    os.makedirs(DESKTOP_ASSETS, exist_ok=True)
    SIDE = 512
    canvas = Image.new("RGBA", (SIDE, SIDE), (0, 0, 0, 0))
    plate = Image.new("RGBA", (SIDE, SIDE), bg + (255,))
    plate.putalpha(rounded_mask(SIDE))
    canvas.alpha_composite(plate)

    gw, gh = glyph.size
    target_w = int(round(SIDE * glyph_ratio))
    target_h = max(1, int(round(gh * target_w / gw)))
    small = glyph.resize((target_w, target_h), Image.LANCZOS)
    canvas.alpha_composite(small, ((SIDE - target_w) // 2, (SIDE - target_h) // 2))

    png = os.path.join(DESKTOP_ASSETS, "icon.png")
    canvas.save(png)
    print("  写出 %s (%dx%d)" % (png, SIDE, SIDE))

    ico_path = os.path.join(DESKTOP_ASSETS, "icon.ico")
    canvas.save(ico_path, format="ICO", sizes=[(s, s) for s in ICO_SIZES])
    print("  写出 %s (%s)" % (ico_path, "/".join(str(s) for s in ICO_SIZES)))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    desktop_only = "--desktop" in sys.argv
    android_only = "--android" in sys.argv
    src = args[0] if args else SRC_DEFAULT
    if not os.path.exists(src):
        raise SystemExit("找不到源图: %s" % src)

    im = Image.open(src).convert("RGBA")
    bbox = red_bbox(im)
    bg = average_red(im, bbox)
    print("源图: %s  %dx%d" % (src, im.size[0], im.size[1]))
    print("红块 bbox: %s → 背景色 #%02X%02X%02X" % (bbox, bg[0], bg[1], bg[2]))

    # 图形只抠一次，安卓前景与桌面图标共用 —— 两端口径不一致会显得像两个产品。
    glyph = None
    if not desktop_only:
        glyph = extract_glyph(im, bbox)
        scale, worst = fit_scale(glyph)
        print("图形: %dx%d，最远不透明像素距重心 %.1fpx → 贴满安全圆需缩放 %.4f，"
              "再乘 %.2f 得 %.4f"
              % (glyph.size[0], glyph.size[1], worst, scale, ANDROID_SHRINK, scale * ANDROID_SHRINK))
        scale *= ANDROID_SHRINK
        fg = build_foreground(glyph, scale)
        res = os.path.join(APP, "app", "src", "main", "res")
        for name, size in DENSITIES.items():
            d = os.path.join(res, "mipmap-" + name)
            os.makedirs(d, exist_ok=True)
            fg.resize((size, size), Image.LANCZOS).save(os.path.join(d, "ic_launcher_foreground.png"))
            print("  写出 mipmap-%s/ic_launcher_foreground.png (%dx%d)" % (name, size, size))
        # 背景色写进 values/ic_launcher_background.xml
        bgxml = os.path.join(res, "values", "ic_launcher_background.xml")
        with open(bgxml, "w", encoding="utf-8") as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                    "<resources>\n"
                    "    <!--\n"
                    "      图标底色。取自源图那块红底的实测均值（用户 m08537 第⑧点换图时由\n"
                    "      tools/make-icon.py 自动写入）。\n"
                    "      刻意不放一张背景位图：纯色背景交给系统拉伸不会有任何模糊，\n"
                    "      而且自适应图标会被启动器裁成圆形 / 方形 / 水滴形，位图四角本来就要被切掉。\n"
                    "    -->\n"
                    '    <color name="ic_launcher_background">#%02X%02X%02X</color>\n'
                    "</resources>\n" % (bg[0], bg[1], bg[2]))
        print("  写出 values/ic_launcher_background.xml (#%02X%02X%02X)" % (bg[0], bg[1], bg[2]))

    if not android_only:
        # 桌面端图形占边长的比例直接量自源图：鹿宽 / 红块宽。
        # 这样桌面图标里鹿的视觉比例和设计稿一致，而不是拍一个 0.7 之类的数。
        if glyph is None:
            glyph = extract_glyph(im, bbox)
        ratio = glyph.size[0] / float(bbox[2] - bbox[0] + 1)
        build_desktop(glyph, ratio, bg)


if __name__ == "__main__":
    main()
