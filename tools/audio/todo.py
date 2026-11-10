#!/usr/bin/env python3
"""
The sūras from <first> to <last> that a reciter's timings (app/src/main/assets/audio/<reciter>.json)
don't hold whole, on one line: what audio.yml's part still has to cut. All of them with FRESH=1.
To cut a sūra again, take its āyāt out of the asset.

Usage: todo.py <reciter id> <first> <last>
"""
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
rid, first, last = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
counts = [s["ayas"] for s in json.load(open(os.path.join(ROOT, "app/src/main/assets/quran/suras.json"), encoding="utf-8"))["suras"]]
try:
    have = json.load(open(os.path.join(ROOT, "app/src/main/assets/audio", rid + ".json"), encoding="utf-8"))["ayat"]
except FileNotFoundError:
    have = {}
if os.environ.get("FRESH") == "1":
    have = {}
print(" ".join(str(s) for s in range(first, last + 1) if any(f"{s}:{a}" not in have for a in range(1, counts[s - 1] + 1))))
