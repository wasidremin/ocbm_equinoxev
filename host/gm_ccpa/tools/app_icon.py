#!/usr/bin/env python3
"""Generate the GM app's launcher icon (adaptive, vector) and its 512 px Play listing PNG.

WHAT IT DRAWS
-------------
A car's wide centre display showing a CarPlay-style home screen: a white dock on the left, then a
3x2 grid of rounded app tiles (phone, music, navigation, Siri mic, audio, messages) over a teal-to-blue
wallpaper. It is CarPlay-THEMED, not Apple's CarPlay mark. The app is listed on Play under our own
name, so it must not look like Apple's logo; a home screen of generic tiles says "CarPlay" without
copying Apple's mark.

The tile glyphs are Material Icons paths (Apache 2.0; Google asks for no attribution) drawn on a
24-unit grid and scaled into each tile.

WHY ONE SOURCE, TWO OUTPUTS
---------------------------
The launcher icon is an <adaptive-icon> (minSdk 26) made of two vector drawables, so it needs no
per-density PNGs and tools/build_apk.sh's plain `aapt2 compile --dir res` handles it like any other
resource. The Play listing needs a 512x512 PNG. Both are written from the shape table below, so the
store icon and the launcher icon cannot drift apart.

Geometry is the adaptive-icon canvas: 108x108 units, of which the launcher shows the middle 72
(18..90) and guarantees only a 66-unit circle under any mask. The display's rounded corners sit at
radius 32.9 from the centre, inside that circle. The Play PNG is that same visible 72-unit square,
full-bleed; Play rounds the corners itself.

Usage:
    tools/app_icon.py                      # rewrite res/drawable/ic_launcher_{background,foreground}.xml
    tools/app_icon.py --png icon-512.png   # ... and render the Play icon (needs google-chrome)
"""
import argparse
import os
import subprocess
import tempfile

GM_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(GM_ROOT, "netprobe_app", "app", "src", "main", "res")


def rr(x, y, w, h, r):
    """Rounded rectangle as path data (absolute arcs; VectorDrawable's parser takes them)."""
    n = lambda v: f"{round(v, 3):g}"
    return (f"M{n(x + r)},{n(y)}H{n(x + w - r)}A{n(r)},{n(r)} 0 0 1 {n(x + w)},{n(y + r)}"
            f"V{n(y + h - r)}A{n(r)},{n(r)} 0 0 1 {n(x + w - r)},{n(y + h)}"
            f"H{n(x + r)}A{n(r)},{n(r)} 0 0 1 {n(x)},{n(y + h - r)}"
            f"V{n(y + r)}A{n(r)},{n(r)} 0 0 1 {n(x + r)},{n(y)}Z")


class Grad:
    def __init__(self, x1, y1, x2, y2, *stops):
        self.x1, self.y1, self.x2, self.y2, self.stops = x1, y1, x2, y2, stops


# Each shape: (path data, fill, transform). fill is ("#RRGGBB", alpha) or a Grad; transform is
# None or (translateX, translateY, scale), applied scale-first as both SVG and VectorDrawable do.
BACKGROUND = [
    ("M0,0H108V108H0Z", Grad(0, 0, 108, 108, (0, "#9BEADF"), (0.45, "#49BFD8"), (1, "#2A55D4")), None),
    ("M0,0H74C58,26 34,52 0,66Z", ("#FFFFFF", 0.22), None),
    ("M108,46C84,54 66,78 58,108H108Z", ("#1C3FBF", 0.55), None),
]

GLYPH = {
    "call": "M20.01,15.38c-1.23,0 -2.42,-0.2 -3.53,-0.56c-0.35,-0.12 -0.74,-0.03 -1.01,0.24l-1.57,1.97"
            "c-2.83,-1.35 -5.48,-3.9 -6.89,-6.83l1.95,-1.66c0.27,-0.28 0.35,-0.67 0.24,-1.02"
            "c-0.37,-1.11 -0.56,-2.3 -0.56,-3.53c0,-0.54 -0.45,-0.99 -0.99,-0.99H4.19C3.65,3 3,3.24 3,3.99"
            "C3,13.28 10.73,21 20.01,21c0.71,0 0.99,-0.63 0.99,-1.18v-3.45c0,-0.54 -0.45,-0.99 -0.99,-0.99z",
    "music": "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55c-2.21,0 -4,1.79 -4,4s1.79,4 4,4s4,-1.79 4,-4V7h4V3h-6z",
    "nav": "M12,2L4.5,20.29l0.71,0.71L12,18l6.79,3l0.71,-0.71z",
    "mic": "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z"
           "M17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28"
           "c3.28,-0.48 6,-3.3 6,-6.72h-1.7z",
    "headphones": "M12,3c-4.97,0 -9,4.03 -9,9v7c0,1.1 0.9,2 2,2h4v-8H5v-1c0,-3.87 3.13,-7 7,-7s7,3.13 7,7v1h-4v8h4"
                  "c1.1,0 2,-0.9 2,-2v-7c0,-4.97 -4.03,-9 -9,-9z",
    "chat": "M20,2H4c-1.1,0 -2,0.9 -2,2v18l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2z",
}
TILES = [("#34C759", "call"), ("#FF375F", "music"), ("#0A84FF", "nav"),
         ("#8E5CF7", "mic"), ("#FF9F0A", "headphones"), ("#30D158", "chat")]
TILE, GAP, TILE_X, TILE_Y, GLYPH_SCALE = 10.4, 4.2, 38.8, 41.5, 0.27

FOREGROUND = [
    (rr(24, 37, 60, 38, 7), ("#000000", 0.18), None),  # drop shadow
    (rr(24, 35, 60, 38, 7), ("#0E1626", 1), None),  # bezel
    (rr(26.5, 37.5, 55, 33, 4.8), Grad(26.5, 37.5, 81.5, 70.5, (0, "#C9F2EC"), (1, "#6E9FEA")), None),
    (rr(28.3, 39.3, 7.4, 29.4, 3.2), ("#F4F7FB", 1), None),  # dock
]
for i, color in enumerate(["#0A84FF", "#34C759", "#8E8E93"]):
    FOREGROUND.append((rr(29.7, 41.6 + i * 6, 4.6, 4.6, 1.3), (color, 1), None))
for i, (color, glyph) in enumerate(TILES):
    x, y = TILE_X + i % 3 * (TILE + GAP), TILE_Y + i // 3 * (TILE + GAP)
    FOREGROUND.append((rr(x, y, TILE, TILE, 2.7), (color, 1), None))
    offset = TILE / 2 - 12 * GLYPH_SCALE
    FOREGROUND.append((GLYPH[glyph], ("#FFFFFF", 1), (x + offset, y + offset, GLYPH_SCALE)))


def num(v):
    return f"{round(v, 3):g}"


def vector_drawable(shapes, what):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           f"<!-- Launcher icon {what}. GENERATED by host/gm_ccpa/tools/app_icon.py; edit that, not this. -->",
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    xmlns:aapt="http://schemas.android.com/aapt"',
           '    android:width="108dp"', '    android:height="108dp"',
           '    android:viewportWidth="108"', '    android:viewportHeight="108">']
    for d, fill, tf in shapes:
        ind = "    "
        if tf:
            tx, ty, s = tf
            out.append(f'    <group android:translateX="{num(tx)}" android:translateY="{num(ty)}"'
                       f' android:scaleX="{num(s)}" android:scaleY="{num(s)}">')
            ind = "        "
        if isinstance(fill, Grad):
            out += [f'{ind}<path android:pathData="{d}">',
                    f'{ind}    <aapt:attr name="android:fillColor">',
                    f'{ind}        <gradient android:type="linear"'
                    f' android:startX="{num(fill.x1)}" android:startY="{num(fill.y1)}"'
                    f' android:endX="{num(fill.x2)}" android:endY="{num(fill.y2)}">']
            out += [f'{ind}            <item android:offset="{num(o)}" android:color="{c}"/>' for o, c in fill.stops]
            out += [f"{ind}        </gradient>", f"{ind}    </aapt:attr>", f"{ind}</path>"]
        else:
            color, alpha = fill
            a = "" if alpha == 1 else f' android:fillAlpha="{num(alpha)}"'
            out.append(f'{ind}<path android:fillColor="{color}"{a} android:pathData="{d}"/>')
        if tf:
            out.append("    </group>")
    out.append("</vector>")
    return "\n".join(out) + "\n"


def svg(px):
    defs, body = [], []
    for d, fill, tf in BACKGROUND + FOREGROUND:
        if tf:
            body.append(f'<g transform="translate({num(tf[0])},{num(tf[1])}) scale({num(tf[2])})">')
        if isinstance(fill, Grad):
            gid = f"g{len(defs)}"
            stops = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in fill.stops)
            defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{fill.x1}" y1="{fill.y1}"'
                        f' x2="{fill.x2}" y2="{fill.y2}">{stops}</linearGradient>')
            body.append(f'<path d="{d}" fill="url(#{gid})"/>')
        else:
            body.append(f'<path d="{d}" fill="{fill[0]}" fill-opacity="{fill[1]}"/>')
        if tf:
            body.append("</g>")
    # viewBox = the 72-unit square a launcher actually shows.
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{px}" height="{px}" viewBox="18 18 72 72">'
            f'<defs>{"".join(defs)}</defs>{"".join(body)}</svg>')


def render_png(path, px):
    from PIL import Image
    with tempfile.TemporaryDirectory() as tmp:
        src, shot = os.path.join(tmp, "icon.svg"), os.path.join(tmp, "shot.png")
        with open(src, "w") as f:
            f.write(svg(px))
        subprocess.run(["google-chrome", "--headless=new", "--disable-gpu", "--no-sandbox", "--hide-scrollbars",
                        f"--window-size={px},{px}", f"--screenshot={shot}", "file://" + src],
                       check=True, capture_output=True, timeout=120)
        # Play wants a 32-bit PNG; Chrome's screenshot is 24-bit.
        Image.open(shot).convert("RGBA").save(path, optimize=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--png", help="also render the full-bleed Play listing icon to this path")
    ap.add_argument("--size", type=int, default=512)
    args = ap.parse_args()
    for name, shapes, what in (("ic_launcher_background", BACKGROUND, "background (wallpaper)"),
                               ("ic_launcher_foreground", FOREGROUND, "foreground (display + home screen)")):
        path = os.path.join(RES, "drawable", name + ".xml")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write(vector_drawable(shapes, what))
        print("wrote", os.path.relpath(path, GM_ROOT))
    if args.png:
        render_png(args.png, args.size)
        print("wrote", args.png)


if __name__ == "__main__":
    main()
