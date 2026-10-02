#!/usr/bin/env python3
"""
Recognition experiment for Recite (.github/workflows/experiment.yml): each recording decoded by
the app's model and Tarteel's base model freely, steered toward the passage recited
(app/src/main/cpp/bias.h, bonus 6) and toward another passage (a control: it must not "recognise"
it); and by general models, freely. Prints one line per recording and arm: tokens steered, time, the
model's mean log-probability of the tokens, the text.
Usage: run.py <work dir> <recordings…>  (passage from the name: …-session-<passage>-<S_A>-<S_B>…, or <SSSAAA>…)
"""
import os, re, subprocess, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
work = sys.argv[1]
CLI = os.path.join(work, "biascli")
ISTIADHA = "أَعُوذُ بِاللَّهِ مِنَ الشَّيْطَانِ الرَّجِيمِ"


def quran():
    text = {}
    for line in open(os.path.join(ROOT, "app/src/main/assets/quran/quran-uthmani.txt"), encoding="utf-8"):
        p = line.rstrip("\n").split("|")
        if len(p) == 3 and p[0].isdigit():
            text[(int(p[0]), int(p[1]))] = p[2]
    return text


TEXT = quran()
BASMALA = TEXT[(1, 1)]


def ayah(s, a):
    t = TEXT[(s, a)]
    if a == 1 and s not in (1, 9) and t.startswith(BASMALA.split(" ")[0]):
        t = " ".join(t.split(" ")[4:])
    return t


def continuations(sura, first, last, words=60):
    """What a reciter may say from the start of each āya of the passage (as the app would give from
    where the recitation stands), with the isti'ādha and basmala before or not."""
    out = []
    for a in range(first, last + 1):
        body = " ".join(" ".join(ayah(sura, b) for b in range(a, last + 1)).split(" ")[:words])
        for pre in ("", BASMALA + " ", ISTIADHA + " ", ISTIADHA + " " + BASMALA + " "):
            out.append(pre + body)
    return "\n".join(out)


def expected_file(name, sura, first, last):
    path = os.path.join(work, f"expected-{name}.txt")
    open(path, "w", encoding="utf-8").write(continuations(sura, first, last))
    return path


IMRAN = expected_file("imran", 3, 1, 9)
MULK = expected_file("mulk", 67, 1, 10)
KAHF = expected_file("kahf", 18, 1, 10)
YASIN = expected_file("yasin", 36, 1, 12)


def passage_of(path):
    name = os.path.basename(path)
    m = re.search(r"session-.+-(\d+)_(\d+)-(\d+)_(\d+)", name)
    if m:
        return int(m[1])
    m = re.match(r"(\d{3})(\d{3})", name)
    return int(m[1]) if m else None


def run(model, wav, mode, expected=None):
    args = [CLI, model, wav, mode] + ([expected] if expected else [])
    r = subprocess.run(args, capture_output=True, text=True, errors="replace")
    return (r.stdout.strip() or r.stderr.strip())


models = {name: os.path.join(work, f) for name, f in [
    ("tiny-quran", "model.bin"), ("base-quran", "base-quran.bin"), ("base", "base.bin"), ("small", "small.bin"),
] if os.path.exists(os.path.join(work, f))}

for wav in sys.argv[2:]:
    sura = passage_of(wav)
    right = {3: IMRAN, 67: MULK, 18: KAHF}.get(sura)
    wrong = MULK if sura != 67 else YASIN
    print(f"\n== {os.path.basename(wav)}", flush=True)
    arms = []
    for m in ("tiny-quran", "base-quran"):
        arms.append((m, "free", None))
        if right:
            arms.append((m, "6", right))
        arms.append((m, "6", wrong))
    for m in ("base", "small"):
        arms.append((m, "free", None))
    for m, mode, exp in arms:
        if m not in models:
            continue
        tag = "right" if exp == right and exp else "WRONG" if exp else ""
        print(f"  {m:11s} {mode:5s} {tag:6s} {run(models[m], wav, mode, exp)}", flush=True)
