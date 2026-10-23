"""Generates the Ankineer launcher icon (card + padlock) in several densities."""
import os
import sys
from PIL import Image, ImageDraw

OUT = sys.argv[1]
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def draw(size: int) -> Image.Image:
    S = 1024
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # blue gradient rounded background
    bg = Image.new("RGBA", (S, S))
    gd = ImageDraw.Draw(bg)
    for y in range(S):
        t = y / S
        r = int(0x5B + (0x2B - 0x5B) * t)
        g = int(0x8C + (0x4F - 0x8C) * t)
        b = int(0xFF + (0xD6 - 0xFF) * t)
        gd.line([(0, y), (S, y)], fill=(r, g, b, 255))
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([40, 40, S - 40, S - 40], radius=230, fill=255)
    img.paste(bg, (0, 0), mask)

    # back card (tilted, offset)
    d.rounded_rectangle([250, 230, 690, 760], radius=60, fill=(255, 255, 255, 90))
    # front card
    d.rounded_rectangle([330, 300, 770, 830], radius=60, fill=(255, 255, 255, 255))
    # padlock on the front card
    cx = 550
    d.arc([cx - 95, 430, cx + 95, 640], start=180, end=360, fill=(0x2B, 0x4F, 0xD6, 255), width=46)
    d.line([(cx - 95, 535), (cx - 95, 600)], fill=(0x2B, 0x4F, 0xD6, 255), width=46)
    d.line([(cx + 95, 535), (cx + 95, 600)], fill=(0x2B, 0x4F, 0xD6, 255), width=46)
    d.rounded_rectangle([cx - 150, 580, cx + 150, 770], radius=36, fill=(0x2B, 0x4F, 0xD6, 255))
    d.ellipse([cx - 26, 640, cx + 26, 692], fill=(255, 255, 255, 255))
    d.rectangle([cx - 11, 680, cx + 11, 725], fill=(255, 255, 255, 255))
    return img.resize((size, size), Image.LANCZOS)


for dens, px in SIZES.items():
    folder = os.path.join(OUT, f"mipmap-{dens}")
    os.makedirs(folder, exist_ok=True)
    draw(px).save(os.path.join(folder, "ic_launcher.png"))
draw(512).save(os.path.join(OUT, "..", "icon_preview.png"))
print("ok")
