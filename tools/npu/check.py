#!/usr/bin/env python3
"""
npu.yml's measures. "cosine a.raw b.raw": two encoder outputs (float32) alike, 1 the same.
"words 'text' 'text'": how many words two readings differ by (edits, on the letters alone, as
Recite compares them).
"""
import re
import sys

import numpy as np

if sys.argv[1] == "cosine":
    a, b = (np.fromfile(p, dtype=np.float32).astype(np.float64) for p in sys.argv[2:4])
    print(f"{float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12)):.5f}" if a.size == b.size else "0")
elif sys.argv[1] == "words":
    marks = re.compile("[ؐ-ًؚ-ٰٟۖ-ۭـ]")
    x, y = ([marks.sub("", w) for w in t.split()] for t in sys.argv[2:4])
    d = list(range(len(y) + 1))
    for i, u in enumerate(x, 1):
        prev, d[0] = d[0], i
        for j, v in enumerate(y, 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (u != v))
    print(d[len(y)])
