"""Builds every app icon from the brand logo (KeyfeKederRadyo.png, transparent background).

Usage: python tools/make_icons.py KeyfeKederRadyo.png app/src/main/res store

Writes: adaptive icon foreground per density, legacy square/round launcher icons,
the in-app logo (drawable-nodpi/keyfe_keder_brand.png) and the 512 px Play Store icon.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def background(size):
    """Deep plum-to-black with a warm sunset glow, matching the app's neon look."""
    bg = Image.new("RGBA", (size, size), (7, 7, 11, 255))
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(glow)
    d.ellipse([size * .05, size * .15, size * .95, size * 1.05], fill=(255, 110, 26, 120))
    d.ellipse([size * .45, -size * .2, size * 1.2, size * .55], fill=(255, 46, 136, 70))
    glow = glow.filter(ImageFilter.GaussianBlur(size * .18))
    return Image.alpha_composite(bg, glow)


def fit(logo, box):
    w, h = logo.size
    scale = box / max(w, h)
    return logo.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.LANCZOS)


def place(canvas, logo, frac):
    size = canvas.size[0]
    art = fit(logo, int(size * frac))
    canvas.alpha_composite(art, ((size - art.size[0]) // 2, (size - art.size[1]) // 2))
    return canvas


def main():
    src, res, store = sys.argv[1], sys.argv[2], sys.argv[3]
    logo = Image.open(src).convert("RGBA")
    logo = logo.crop(logo.getbbox())

    for name, k in DENSITIES.items():
        folder = os.path.join(res, f"mipmap-{name}")
        os.makedirs(folder, exist_ok=True)
        # adaptive foreground: 108dp canvas, art kept inside the 66dp safe zone
        fg = place(Image.new("RGBA", (int(108 * k),) * 2, (0, 0, 0, 0)), logo, 0.64)
        fg.save(os.path.join(folder, "ic_launcher_foreground.png"))
        # legacy icons (48dp)
        px = int(48 * k)
        square = place(background(px), logo, 0.86)
        square.save(os.path.join(folder, "ic_launcher.png"))
        mask = Image.new("L", (px, px), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, px - 1, px - 1], fill=255)
        round_icon = place(background(px), logo, 0.78)
        round_icon.putalpha(mask)
        round_icon.save(os.path.join(folder, "ic_launcher_round.png"))

    # adaptive background as a bitmap so the glow matches the store icon
    nodpi = os.path.join(res, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)
    background(432).save(os.path.join(nodpi, "ic_launcher_bg.png"))
    fit(logo, 512).save(os.path.join(nodpi, "keyfe_keder_brand.png"))

    os.makedirs(store, exist_ok=True)
    place(background(512), logo, 0.86).convert("RGB").save(os.path.join(store, "play-icon-512.png"))
    print("icons written")


if __name__ == "__main__":
    main()
