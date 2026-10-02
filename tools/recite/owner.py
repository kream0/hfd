#!/usr/bin/env python3
"""
The owner's Recite sessions (opt-in recordings, Settings → About → Send recordings) as Recite bench
cases: <session>-session-<passage>-<S_A>-<S_B>[-<HHmmss>].wav, the microphone as the phone gave it, becomes a
case of the passage S:A–B with no known timings (the bench replays it through the app's pipeline
and prints what was heard and how each word ended), scored on the words recited in it when
tools/recite/owner.tsv says which. Usage: owner.py <recordings dir> <bench dir>
"""
import os
import re
import shutil
import sys

src, out = sys.argv[1], sys.argv[2]
recited = {}
for line in open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "owner.tsv"), encoding="utf-8"):
    if line.strip() and not line.startswith("#"):
        name, words, *start = line.rstrip("\n").split("\t")
        recited[name] = (words, start[0] if start else "")
lines = []
if os.path.isdir(src):
    for name in sorted(os.listdir(src)):
        m = re.match(r"(\w+)-session-(.+)-(\d+)_(\d+)-(\d+)_(\d+)(?:-(\d{6}))?\.wav$", name)
        if not m:
            continue
        session, passage, s1, a1, s2, a2, time = m.groups()
        if s1 != s2:
            print("skipped (several sūras):", name)
            continue
        case = f"owner-{session}-{passage}" + (f"-{time}" if time else "")
        shutil.copy(os.path.join(src, name), os.path.join(out, case + ".wav"))
        said, start = recited.get(name[:-len(".wav")], ("", ""))
        lines.append(f"{case}\t{s1}:{a1}-{a2}\t{case}.wav\t\t{said}\t{start}")
        print("owner case:", case, f"{s1}:{a1}-{a2}", os.path.getsize(os.path.join(src, name)), "bytes", "recited:", said or "?", "from:", start or "the start")
with open(os.path.join(out, "cases.tsv"), "a", encoding="utf-8") as f:
    for line in lines:
        f.write(line + "\n")
print(len(lines), "owner sessions")
