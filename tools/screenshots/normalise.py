#!/usr/bin/env python3
"""
The Robolectric screenshots (1200 px wide, dark `<screen>.png` and paper `<screen>-paper.png` in the
same state) as the website's images: 840 px wide WebP, quality 84, in docs/screenshots. Every
frame has the same aspect ratio, and each pair lines up to the pixel.
Usage: normalise.py <png dir> <webp dir>
"""
import glob
import os
import sys

from PIL import Image

src, dst = sys.argv[1], sys.argv[2]
os.makedirs(dst, exist_ok=True)
shots = sorted(glob.glob(os.path.join(src, "*.png")))
if not shots:
    sys.exit("no screenshots in " + src)
heights = [Image.open(p).size for p in shots]
# Crop from the top to the smallest height (keeping the bottom), as for phone screenshots.
h0 = min(h for _, h in heights)
for path in shots:
    im = Image.open(path).convert("RGB")
    w, h = im.size
    im = im.crop((0, h - h0, w, h)).resize((840, round(840 * h0 / w)), Image.LANCZOS)
    out = os.path.join(dst, os.path.splitext(os.path.basename(path))[0] + ".webp")
    im.save(out, "WEBP", quality=84, method=6)
    print(out, im.size, os.path.getsize(out), "bytes")
