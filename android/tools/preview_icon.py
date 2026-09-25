#!/usr/bin/env python3
"""
图标几何预览器 —— 不构建 APK 就能看到自适应图标的实际渲染效果。

为什么要这个脚本
----------------
改 `res/drawable/ic_launcher_foreground.xml` 的几何参数后，如果每次都靠
`gradle assembleRelease`(实测约 50 秒) + adb 安装 + 桌面截图来验证，
一轮迭代两分钟起。本脚本直接解析 XML 里的 pathData，按同一套数学关系
渲染成 PNG，**秒级出结果** —— 定稿后再构建验证一次即可。

用法
----
    python tools/preview_icon.py                      # 渲染内置候选集
    python tools/preview_icon.py --mask circle        # 用圆形遮罩
    python tools/preview_icon.py --out a.png

⚠️ 踩过的坑（改这个脚本前务必先读）
----------------------------------
PIL 的 `ImageDraw.arc()` 角度约定和 Android vector 完全不同：

  · Android vector：圆弧用「起止点坐标 + sweep-flag」表示，不带角度
  · PIL：角度从 **3 点钟方向**起、**顺时针**增加

换算式：`PIL角 = 270 + 钟表角`（钟表角 0 = 12 点方向、顺时针为正）

我第一版写成 `钟表角 - 90`（数学上等价，但不直观），更要命的是
**把「圆弧起点」误当成了「开口起点」**，于是预览里开口位置整体偏了 45°，
结果拿着一张错的对比图去做设计决策，白跑了两轮构建。
所以这里统一用「圆弧起点」语义，并且渲染完会打印实际开口范围供核对。
"""

import argparse
import re
from pathlib import Path

from PIL import Image, ImageDraw

CANVAS = 108          # 自适应图标画布边长（单位 dp，即 viewport 宽高）
SUPERSAMPLE = 5       # 超级采样倍数；PIL 不做抗锯齿，靠缩放平滑
BG = (65, 95, 145)    # #415F91 —— 与 values/ic_launcher.xml 保持一致
FG = (255, 255, 255)

XML = Path(__file__).resolve().parent.parent / "app/src/main/res/drawable/ic_launcher_foreground.xml"

ARC_RE = re.compile(
    r"M\s*([-\d.]+)\s*,\s*([-\d.]+)\s*A\s*([\d.]+)\s*,\s*[\d.]+\s*[\d,]+\s*(\d)\s*(\d)\s*([-\d.]+)\s*,\s*([-\d.]+)"
)
POLY_RE = re.compile(r"M\s*([-\d.]+)\s*,\s*([-\d.]+)((?:\s*L\s*[-\d.]+\s*,\s*[-\d.]+)+)")


def parse_vector(xml_text):
    """从 vector drawable 里抽出圆弧和折线，返回 [(kind, payload, stroke_width)]。"""
    out = []
    for block in re.findall(r"<path\b[^>]*/>", xml_text, re.S):
        pd = re.search(r'android:pathData="([^"]*)"', block)
        sw = re.search(r'android:strokeWidth="([\d.]+)"', block)
        if not pd or not sw:
            continue
        pd, sw = pd.group(1), float(sw.group(1))

        m = ARC_RE.search(pd)
        if m:
            x1, y1, r, large, sweep, x2, y2 = m.groups()
            out.append(("arc", (float(x1), float(y1), float(r), int(sweep), float(x2), float(y2)), sw))
            continue

        m = POLY_RE.search(pd)
        if m:
            pts = [(float(m.group(1)), float(m.group(2)))]
            pts += [(float(a), float(b)) for a, b in re.findall(r"L\s*([-\d.]+)\s*,\s*([-\d.]+)", m.group(3))]
            out.append(("poly", pts, sw))
    return out


def clock_angle(x, y, cx, cy):
    """把画布坐标换算成钟表角（0=12 点方向，顺时针为正），返回度数。"""
    import math
    return math.degrees(math.atan2(x - cx, cy - y)) % 360


def render(items, px=520, mask="squircle"):
    scale = px / CANVAS
    img = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    if mask == "circle":
        d.ellipse([0, 0, px - 1, px - 1], fill=BG + (255,))
    else:
        d.rounded_rectangle([0, 0, px - 1, px - 1], radius=int(px * 0.27), fill=BG + (255,))

    cx = cy = CANVAS / 2
    for kind, payload, sw in items:
        w = max(1, int(round(sw * scale)))
        if kind == "arc":
            x1, y1, r, sweep, x2, y2 = payload
            # PIL 的 bbox 是「外边界」，而 Android 的 stroke 是以路径线为中心向两侧各铺 sw/2
            rr = (r + sw / 2.0) * scale
            box = [cx * scale - rr, cy * scale - rr, cx * scale + rr, cy * scale + rr]
            # 钟表角 -> PIL 角：PIL 用「3 点方向起、顺时针」，故 +270
            a1 = clock_angle(x1, y1, cx, cy) + 270
            a2 = clock_angle(x2, y2, cx, cy) + 270
            if sweep == 1:            # 顺时针
                d.arc(box, start=a1, end=a2, fill=FG + (255,), width=w)
            else:                     # 逆时针 -> 首尾互换
                d.arc(box, start=a2, end=a1, fill=FG + (255,), width=w)
        else:
            pts = [(x * scale, y * scale) for x, y in payload]
            d.line(pts, fill=FG + (255,), width=w, joint="curve")
            for x, y in (pts[0], pts[-1]):
                d.ellipse([x - w / 2.0, y - w / 2.0, x + w / 2.0, y + w / 2.0], fill=FG + (255,))
    return img


def describe(items):
    """打印实际开口范围 —— 用来核对预览有没有画歪。"""
    for kind, payload, _ in items:
        if kind != "arc":
            continue
        x1, y1, r, sweep, x2, y2 = payload
        a1, a2 = clock_angle(x1, y1, 54, 54), clock_angle(x2, y2, 54, 54)
        span = (a2 - a1) % 360          # 顺时针扫过的角度
        print(
            f"  圆弧 R={r}  起点 {a1:.1f}°  顺时针扫过 {span:.1f}°  "
            f"->  开口 {360 - span:.1f}°  (钟表角，0=12点方向)"
        )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mask", choices=["squircle", "circle"], default="squircle")
    ap.add_argument("--out", default="preview_icon.png")
    ap.add_argument("--px", type=int, default=520)
    args = ap.parse_args()

    items = parse_vector(XML.read_text(encoding="utf-8"))
    if not items:
        raise SystemExit(f"没从 {XML} 解析出任何 path，检查 pathData 格式")

    print(f"来源: {XML.name}")
    describe(items)
    img = render(items, px=args.px, mask=args.mask)
    img.resize((args.px // 2, args.px // 2), Image.LANCZOS).save(args.out)
    print(f"已输出 {args.out}")


if __name__ == "__main__":
    main()
