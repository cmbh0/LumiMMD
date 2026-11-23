"""从一张方形原图生成 Android 启动图标的全部资源。
用法：python make_icons.py <原图> <app/src/main/res 目录>

- 自适应图标（Android 8+）：前景层 108dp，原图正好占中间的 72dp——这是各家桌面裁剪形状能露出的最大范围，
  所以圆角方形 / 圆角矩形的桌面上整张图都看得到；四周 18dp 的出血区（只有拖动、缩放的动效里才露出来）
  用原图边缘向外延伸、模糊、压暗后填满，不会露出生硬的边。
- 圆形桌面另有一份前景层（ic_launcher_round_foreground）：原图缩到 62dp、边缘融进背景，底下的字不会被圆边切掉。
- 旧式图标（Android 7.x）：ic_launcher.png（圆角方形）和 ic_launcher_round.png（圆形）。
"""
import sys
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFilter, ImageOps

src_path, res = sys.argv[1], Path(sys.argv[2])
src = Image.open(src_path).convert("RGB")
side = min(src.size)
src = ImageOps.fit(src, (side, side), Image.LANCZOS)

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
SS = 4  # 超采样倍数：蒙版边缘才平滑
ROUND_ART_DP = 62

def foreground(px: int, art_dp: float = 72, feather_dp: float = 0) -> Image.Image:
    """108dp 的前景层：原图占中间 art_dp，四周是从原图边缘延伸出去的背景（镜像、模糊、压暗）。
    feather_dp > 0 时原图的边缘渐渐融进背景里（原图比露出的范围小的时候用，免得看到一条直边）。"""
    inner = round(px * art_dp / 108)
    pad = (px - inner) // 2
    art = src.resize((inner, inner), Image.LANCZOS)
    big = Image.new("RGB", (inner * 3, inner * 3))
    for gy in range(3):
        for gx in range(3):
            tile = art
            if gx != 1: tile = ImageOps.mirror(tile)
            if gy != 1: tile = ImageOps.flip(tile)
            big.paste(tile, (gx * inner, gy * inner))
    box = (inner - pad, inner - pad, inner - pad + px, inner - pad + px)
    bleed = big.crop(box).filter(ImageFilter.GaussianBlur(px / 40))
    bleed = ImageEnhance.Brightness(bleed).enhance(0.8)
    out = bleed.copy()
    f = round(px * feather_dp / 108)
    if f <= 0:
        out.paste(art, (pad, pad))
    else:
        mask = Image.new("L", (inner, inner), 0)
        ImageDraw.Draw(mask).rectangle((f, f, inner - f - 1, inner - f - 1), fill=255)
        out.paste(art, (pad, pad), mask.filter(ImageFilter.GaussianBlur(f / 2)))
    return out

def masked(px: int, shape: str) -> Image.Image:
    """旧式图标：圆角方形的是整张图；圆形的和自适应图标的圆形那份一样（原图缩小一点，字不被切掉）。"""
    big = px * SS
    margin = round(big * (1 / 48 if shape == "square" else 1 / 96))
    inner = big - 2 * margin
    mask = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(mask)
    if shape == "square":
        art = src.resize((inner, inner), Image.LANCZOS)
        d.rounded_rectangle((margin, margin, big - margin - 1, big - margin - 1), radius=round(inner * 0.2), fill=255)
    else:
        layer = round(inner * 108 / 72)
        full = foreground(layer, art_dp=ROUND_ART_DP, feather_dp=3)
        o = (layer - inner) // 2
        art = full.crop((o, o, o + inner, o + inner))
        d.ellipse((margin, margin, big - margin - 1, big - margin - 1), fill=255)
    out = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    out.paste(art, (margin, margin))
    out.putalpha(mask)
    return out.resize((px, px), Image.LANCZOS)

for name, scale in DENSITIES.items():
    d = res / f"mipmap-{name}"
    d.mkdir(parents=True, exist_ok=True)
    foreground(round(108 * scale)).save(d / "ic_launcher_foreground.png", optimize=True)
    # 圆形桌面（Pixel 等）用的那一份：原图缩小一点，「LumiMMD」几个字才不会被圆边切掉
    foreground(round(108 * scale), art_dp=ROUND_ART_DP, feather_dp=3).save(d / "ic_launcher_round_foreground.png", optimize=True)
    masked(round(48 * scale), "square").save(d / "ic_launcher.png", optimize=True)
    masked(round(48 * scale), "round").save(d / "ic_launcher_round.png", optimize=True)

# 应用商店 / 网页上用的大图
masked(512, "square").save(res.parent / "ic_launcher-playstore.png", optimize=True)

# 背景层的颜色：取原图最暗的那个角（前景层是不透明的，这个颜色只在极端情况下才看得到）
corners = [src.getpixel(p) for p in ((8, 8), (side - 9, 8), (8, side - 9), (side - 9, side - 9))]
print("background #%02X%02X%02X" % min(corners, key=sum))
