"""Convert Phosphor icon SVGs (single-colour, path based) into Android VectorDrawables.

Usage: python tools/svg2vector.py <phosphor assets dir> <res/drawable dir>
Edit ICONS below to choose which icons the app uses. Phosphor Icons are MIT licensed
(see licenses/phosphor-LICENSE.txt).
"""
import os
import re
import sys

# drawable name -> (weight, phosphor icon name)
ICONS = {
    "ic_home": ("regular", "house"), "ic_home_fill": ("fill", "house"),
    "ic_radio": ("regular", "radio"), "ic_radio_fill": ("fill", "radio"),
    "ic_explore": ("regular", "compass"), "ic_explore_fill": ("fill", "compass"),
    "ic_heart_outline": ("regular", "heart"), "ic_heart": ("fill", "heart"),
    "ic_settings": ("regular", "gear-six"), "ic_settings_fill": ("fill", "gear-six"),
    "ic_search": ("regular", "magnifying-glass"),
    "ic_play": ("fill", "play"), "ic_pause": ("fill", "pause"),
    "ic_next": ("fill", "skip-forward"), "ic_prev": ("fill", "skip-back"),
    "ic_shuffle": ("bold", "shuffle"), "ic_sparkle": ("fill", "sparkle"),
    "ic_moon": ("fill", "moon-stars"), "ic_bolt": ("fill", "lightning"),
    "ic_car": ("fill", "car-profile"), "ic_coffee": ("fill", "coffee"),
    "ic_heart_break": ("fill", "heart-break"), "ic_headphones": ("fill", "headphones"),
    "ic_chevron_down": ("bold", "caret-down"), "ic_chevron_right": ("bold", "caret-right"),
    "ic_close": ("bold", "x"), "ic_share": ("regular", "share-network"),
    "ic_trash": ("regular", "trash"), "ic_dice": ("fill", "dice-five"),
    "ic_timer": ("regular", "timer"), "ic_refresh": ("regular", "arrow-clockwise"),
    "ic_info": ("regular", "info"), "ic_broadcast": ("bold", "broadcast"),
    "ic_waveform": ("bold", "waveform"), "ic_check": ("bold", "check"),
    "ic_genre": ("regular", "squares-four"), "ic_exit": ("regular", "sign-out"),
    "ic_play_circle": ("fill", "play-circle"), "ic_history": ("regular", "clock-counter-clockwise"),
    "ic_shield": ("regular", "shield-check"), "ic_sliders": ("regular", "sliders-horizontal"),
    "ic_bluetooth": ("bold", "bluetooth"), "ic_speaker": ("fill", "speaker-high"),
    "ic_devices": ("regular", "devices"), "ic_usb": ("bold", "usb"), "ic_bell": ("regular", "bell-ringing"),
    "ic_sun": ("fill", "sun-horizon"), "ic_television": ("regular", "television"), "ic_gear_small": ("regular", "gear"),
}

TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<!-- Phosphor Icons "{name}" ({weight}), MIT License -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="256" android:viewportHeight="256">
{paths}
</vector>
"""


def convert(svg: str) -> str:
    paths = []
    for m in re.finditer(r"<path\b([^>]*)/?>", svg):
        attrs = m.group(1)
        d = re.search(r'\bd="([^"]+)"', attrs).group(1)
        opacity = re.search(r'opacity="([0-9.]+)"', attrs)
        alpha = f' android:fillAlpha="{opacity.group(1)}"' if opacity else ""
        paths.append(f'    <path android:fillColor="#FFFFFFFF"{alpha} android:pathData="{d}"/>')
    if not paths:
        raise ValueError("no <path> found")
    return "\n".join(paths)


def main():
    src, out = sys.argv[1], sys.argv[2]
    for name, (weight, icon) in ICONS.items():
        suffix = "" if weight == "regular" else f"-{weight}"
        with open(os.path.join(src, weight, f"{icon}{suffix}.svg"), encoding="utf-8") as f:
            svg = f.read()
        with open(os.path.join(out, f"{name}.xml"), "w", encoding="utf-8", newline="\n") as f:
            f.write(TEMPLATE.format(name=icon, weight=weight, paths=convert(svg)))
    print(f"{len(ICONS)} icons written to {out}")


if __name__ == "__main__":
    main()
