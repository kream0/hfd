#!/usr/bin/env python3
"""
The app's Latin fonts (app/src/main/res/font/serif_*.ttf, sans_*.ttf): static instances of Source
Serif 4 and Source Sans 3 (SIL OFL 1.1, licenses/), cut down to Latin with the transliteration's
letters (ā ḥ ṣ ʿ ʾ …), punctuation and a few symbols. Both set ʿ and ʾ tightly (Faḍāʾil, Āl ʿImrān),
which Literata and Crimson Pro don't.

They are modified versions, and Source Sans reserves the name "Source": the files are named
"HFD Serif" / "HFD Sans" inside, as the licence asks.

Usage: pip install fonttools; make.py <dir with the variable fonts of google/fonts (ofl/sourceserif4,
ofl/sourcesans3)> <res/font dir>
"""
import os
import sys

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

src, out = sys.argv[1], sys.argv[2]
RANGES = [(0x20, 0x7E), (0xA0, 0x17F), (0x18F, 0x192), (0x1A0, 0x1B0), (0x218, 0x21B), (0x2B0, 0x2FF), (0x300, 0x36F),
          (0x1E00, 0x1EFF), (0x2000, 0x206F), (0x2070, 0x209F), (0x20A0, 0x20BF), (0x2122, 0x2122),
          (0x2150, 0x215F), (0x2190, 0x2193), (0x2212, 0x2212), (0x221E, 0x221E), (0x2248, 0x2248), (0x2260, 0x2265), (0x25CF, 0x25CF)]
UNICODES = [c for a, b in RANGES for c in range(a, b + 1)]
NEEDED = "āīūĀĪŪḥṣḍṭẓḤṢḌṬẒʿʾ’«»…·–—×½′∞←→éèêàçùôîœÉŒ"
JOBS = [
    ("SourceSerif4[opsz,wght].ttf", {"wght": 400, "opsz": 20}, "serif_regular.ttf", "HFD Serif", "Regular"),
    ("SourceSerif4[opsz,wght].ttf", {"wght": 600, "opsz": 28}, "serif_semibold.ttf", "HFD Serif", "SemiBold"),
    ("SourceSans3[wght].ttf", {"wght": 400}, "sans_regular.ttf", "HFD Sans", "Regular"),
    ("SourceSans3[wght].ttf", {"wght": 500}, "sans_medium.ttf", "HFD Sans", "Medium"),
    ("SourceSans3[wght].ttf", {"wght": 600}, "sans_semibold.ttf", "HFD Sans", "SemiBold"),
]
for name, axes, dst, family, style in JOBS:
    f = instancer.instantiateVariableFont(TTFont(os.path.join(src, name)), axes, inplace=False)
    opts = subset.Options()
    opts.layout_features = ["kern", "liga", "mark", "mkmk", "ccmp", "locl", "tnum", "lnum", "pnum", "case", "frac", "zero"]
    opts.name_IDs = [0, 1, 2, 3, 4, 5, 6, 13, 14]
    opts.notdef_outline = True
    opts.hinting = False
    opts.desubroutinize = True
    s = subset.Subsetter(opts)
    s.populate(unicodes=UNICODES)
    s.subset(f)
    # A modified version doesn't carry the reserved name.
    for record in f["name"].names:
        value = {1: family, 2: style, 3: f"{family} {style} (HFD)", 4: f"{family} {style}", 6: f"{family.replace(' ', '')}-{style}"}.get(record.nameID)
        if value is not None:
            record.string = value
    path = os.path.join(out, dst)
    f.save(path)
    cmap = TTFont(path).getBestCmap()
    missing = "".join(c for c in NEEDED if ord(c) not in cmap)
    print(dst, os.path.getsize(path), "bytes,", len(cmap), "characters" + (", MISSING " + missing if missing else ""))
    if missing:
        sys.exit(1)
