#!/usr/bin/env python3
"""
Adds the parts align.py wrote with WHOLE=1 (whole sūras, audio.yml's jobs) to a reciter's timings,
app/src/main/assets/audio/<reciter>.json. The āyāt already cut keep their cut (the passages': their
word timings, tools/words, and the āyāt saved on phones go with it) unless FRESH=1.

Usage: merge.py <reciter id> <dir with the parts (*.json, anywhere under it)>
"""
import glob
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
rid, parts_dir = sys.argv[1], sys.argv[2]
fresh = os.environ.get("FRESH") == "1"
dest = os.path.join(ROOT, "app/src/main/assets/audio", rid + ".json")
doc = json.load(open(dest, encoding="utf-8"))
counts = [s["ayas"] for s in json.load(open(os.path.join(ROOT, "app/src/main/assets/quran/suras.json"), encoding="utf-8"))["suras"]]
before = len(doc["ayat"])
unchecked = set(doc.get("unchecked", []))
parts = sorted(glob.glob(os.path.join(parts_dir, "**", "*.json"), recursive=True))
if not parts:
    sys.exit("no parts under " + parts_dir)
for path in parts:
    part = json.load(open(path, encoding="utf-8"))
    assert part["reciter"] == rid and part["base"] == doc["base"], path
    for sura, info in part["suras"].items():
        old = doc["suras"].get(sura)
        if old and old["size"] != info["size"]:
            # The server's file changed: the cuts kept would be of another file.
            sys.exit(f"sūra {sura}: the file is {info['size']} bytes, the timings were made on {old['size']}")
        doc["suras"][sura] = info
    for key, cut in part["ayat"].items():
        if fresh or key not in doc["ayat"]:
            doc["ayat"][key] = cut
            unchecked.discard(key)
            if key in part.get("unchecked", []):
                unchecked.add(key)
    print(os.path.relpath(path, parts_dir), len(part["ayat"]), "āyāt,", len(part.get("unchecked", [])), "to look at")


def order(key):
    return tuple(map(int, key.split(":")))


doc["suras"] = {k: doc["suras"][k] for k in sorted(doc["suras"], key=int)}
doc["ayat"] = {k: doc["ayat"][k] for k in sorted(doc["ayat"], key=order)}
doc["unchecked"] = sorted(unchecked, key=order)
doc["note"] = "Byte ranges [start, end) of each āya in the sūra files, found by tools/audio/align.py."
whole = [s for s in range(1, 115) if all(f"{s}:{a}" in doc["ayat"] for a in range(1, counts[s - 1] + 1))]
missing = [s for s in range(1, 115) if s not in whole]
print(f"{before} → {len(doc['ayat'])} āyāt of {sum(counts)}; {len(whole)} sūras whole; {len(doc['unchecked'])} cuts to look at")
if missing:
    print("not whole:", missing)
json.dump(doc, open(dest, "w", encoding="utf-8"), ensure_ascii=False, indent=None, separators=(",", ":"))
