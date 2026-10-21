"""Icône de notification (cadenas blanc sur transparent) + aperçu des widgets."""
import os, sys
from PIL import Image, ImageDraw
OUT = sys.argv[1]
SIZES = {"mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72, "xxxhdpi": 96}
def draw(size):
    S = 480
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([90, 210, 390, 430], radius=40, fill=(255, 255, 255, 255))
    d.arc([140, 50, 340, 290], 180, 360, fill=(255, 255, 255, 255), width=42)
    d.line([(161, 170), (161, 215)], fill=(255, 255, 255, 255), width=42)
    d.line([(319, 170), (319, 215)], fill=(255, 255, 255, 255), width=42)
    d.ellipse([215, 280, 265, 330], fill=(0, 0, 0, 0))
    d.rectangle([230, 310, 250, 370], fill=(0, 0, 0, 0))
    return img.resize((size, size), Image.LANCZOS)
for dens, sz in SIZES.items():
    p = os.path.join(OUT, "drawable-" + dens)
    os.makedirs(p, exist_ok=True)
    draw(sz).save(os.path.join(p, "ic_stat_lock.png"))
