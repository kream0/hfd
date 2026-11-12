#!/usr/bin/env python3
"""
The recitations LocateBench listens to (core/src/test/kotlin/app/hfd/core/quran/LocateBench.kt):
places taken at random in the whole Qur'an (any āya, the same chance each), and beginnings of
sūras, each with the two āyāt after it, by four reciters of EveryAyah in turn. Writes
<out>/cases.tsv: id, reciter's folder, the āyāt (s:a,…), kind ("any" or "opening").
Usage: sample.py <quran-uthmani.txt or the assets' text> <out> [places] [openings]
"""
import os
import random
import sys

RECITERS = ["Husary_128kbps", "Alafasy_128kbps", "MaherAlMuaiqly128kbps", "Yasser_Ad-Dussary_128kbps"]


def main():
    text, out = sys.argv[1], sys.argv[2]
    places = int(sys.argv[3]) if len(sys.argv) > 3 else 160
    openings = int(sys.argv[4]) if len(sys.argv) > 4 else 40
    counts = {}
    for line in open(text, encoding="utf-8"):
        f = line.split("|")
        if len(f) >= 3 and f[0].isdigit():
            counts[int(f[0])] = max(counts.get(int(f[0]), 0), int(f[1]))
    assert len(counts) == 114 and sum(counts.values()) == 6236, (len(counts), sum(counts.values()))
    rng = random.Random(20261112)
    ayat = [(s, a) for s in sorted(counts) for a in range(1, counts[s] + 1)]
    cases = []
    for s, a in rng.sample(ayat, places):
        cases.append(("any", s, a))
    # (Not al-Fātiḥa, whose first āya is the basmala, nor at-Tawba, which has none.)
    for s in rng.sample([s for s in sorted(counts) if s not in (1, 9)], openings):
        cases.append(("opening", s, 1))
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "cases.tsv"), "w", encoding="utf-8") as f:
        for i, (kind, s, a) in enumerate(cases):
            refs = [f"{s}:{x}" for x in range(a, min(a + 3, counts[s] + 1))]
            f.write(f"{kind}-{s}-{a}\t{RECITERS[i % len(RECITERS)]}\t{','.join(refs)}\t{kind}\n")
    print(len(cases), "cases")


main()
