"""Play Store graphics: captioned 1080x1920 screenshots and the 1024x500 feature graphic.

Usage: python tools/store_assets.py <raw screenshot dir> <fonts dir> <out dir>
Fonts: Poppins (SIL Open Font License), see store/fonts-OFL.txt.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ORANGE = (255, 122, 26)
PINK = (255, 46, 136)

SHOTS = [
    ("s_home2.png", "Bugün hangi frekanstasın?", "Ruh haline göre radyo, tek dokunuşla"),
    ("s_pick.png", "Keyfime bırak", "Sana uygun radyoyu biz seçelim"),
    ("s_player.png", "Müzikle dans eden ekran", "Canlı şarkı bilgisi ve neon spektrum"),
    ("s_radios.png", "560'tan fazla canlı radyo", "Pop, Türk Halk, Arabesk, Rock ve fazlası"),
    ("s_profile.png", "Senin Keyfe Keder'in", "Haftalık özetin ve rozetlerin"),
    ("s_settings.png", "Tam sana göre", "Uyku zamanlayıcısı, bildirimler ve daha fazlası"),
]


def backdrop(w, h):
    bg = Image.new("RGBA", (w, h), (7, 7, 11, 255))
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(glow)
    d.ellipse([-w * .3, -h * .15, w * .9, h * .45], fill=ORANGE + (130,))
    d.ellipse([w * .4, h * .35, w * 1.4, h * 1.05], fill=PINK + (90,))
    d.ellipse([-w * .4, h * .65, w * .6, h * 1.3], fill=(139, 92, 255, 60))
    return Image.alpha_composite(bg, glow.filter(ImageFilter.GaussianBlur(min(w, h) * .18)))


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1], radius, fill=255)
    out = img.convert("RGBA"); out.putalpha(mask)
    return out


def centered(draw, text, font, y, width, fill):
    w = draw.textlength(text, font=font)
    draw.text(((width - w) / 2, y), text, font=font, fill=fill)


def screenshot(raw, title, subtitle, fonts, out):
    W, H = 1080, 1920
    canvas = backdrop(W, H)
    d = ImageDraw.Draw(canvas)
    bold = ImageFont.truetype(os.path.join(fonts, "Poppins-Bold.ttf"), 64)
    reg = ImageFont.truetype(os.path.join(fonts, "Poppins-Regular.ttf"), 36)
    centered(d, title, bold, 110, W, (246, 243, 255))
    centered(d, subtitle, reg, 205, W, (255, 214, 190))

    shot = Image.open(raw).convert("RGB")
    ph = 1530
    pw = int(shot.width * ph / shot.height)
    shot = rounded(shot.resize((pw, ph), Image.LANCZOS), 56)
    x, y = (W - pw) // 2, 330
    halo = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(halo).rounded_rectangle([x - 6, y - 6, x + pw + 6, y + ph + 6], 62, fill=ORANGE + (170,))
    canvas = Image.alpha_composite(canvas, halo.filter(ImageFilter.GaussianBlur(28)))
    frame = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(frame).rounded_rectangle([x - 10, y - 10, x + pw + 10, y + ph + 10], 64, fill=(20, 16, 26, 255), outline=(255, 140, 70, 200), width=3)
    canvas = Image.alpha_composite(canvas, frame)
    canvas.alpha_composite(shot, (x, y))
    canvas.convert("RGB").save(out, quality=95)


def feature_graphic(logo_path, fonts, out):
    W, H = 1024, 500
    canvas = backdrop(W, H)
    logo = Image.open(logo_path).convert("RGBA")
    logo = logo.crop(logo.getbbox())
    s = 400 / max(logo.size)
    logo = logo.resize((int(logo.width * s), int(logo.height * s)), Image.LANCZOS)
    canvas.alpha_composite(logo, (40, (H - logo.height) // 2))
    d = ImageDraw.Draw(canvas)
    bold = ImageFont.truetype(os.path.join(fonts, "Poppins-Bold.ttf"), 58)
    semi = ImageFont.truetype(os.path.join(fonts, "Poppins-SemiBold.ttf"), 28)
    reg = ImageFont.truetype(os.path.join(fonts, "Poppins-Regular.ttf"), 26)
    x = 480
    d.text((x, 118), "Keyfe Keder", font=bold, fill=(246, 243, 255))
    d.text((x, 188), "Radyo", font=bold, fill=ORANGE)
    d.text((x, 285), "560+ canlı radyo", font=semi, fill=(246, 243, 255))
    d.text((x, 325), "Ruh haline göre seçim • Neon spektrum", font=reg, fill=(255, 214, 190))
    # little equaliser accent
    for i, h in enumerate([18, 34, 52, 30, 44, 24, 38, 14]):
        bx = x + i * 16
        d.rounded_rectangle([bx, 440 - h, bx + 9, 440], 4, fill=ORANGE if i % 2 == 0 else PINK)
    canvas.convert("RGB").save(out, quality=95)


def main():
    raw, fonts, out = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(out, exist_ok=True)
    for i, (name, title, subtitle) in enumerate(SHOTS, 1):
        screenshot(os.path.join(raw, name), title, subtitle, fonts, os.path.join(out, f"screenshot-{i}.png"))
    feature_graphic(os.path.join(os.path.dirname(out.rstrip("/\\")), "KeyfeKederRadyo.png"), fonts, os.path.join(out, "feature-graphic-1024x500.png"))
    print("store assets written to", out)


if __name__ == "__main__":
    main()
