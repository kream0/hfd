#!/usr/bin/env python3
"""
How long the sounds of a recitation are held, read from the CTC recogniser's own timing: the study
behind Recite's check of the long vowels and the ghunna.

For each āya of beats.tsv as four reciters of EveryAyah say it (the Recite bench's four), the
text is held to the model's frames (Viterbi, a frame is 40 ms) and each consonant gets the frame it
is written at. A "beat" (Tajweed.beats: a consonant and what is held until the next) then lasts
from its consonant's frame to the next one's. Printed per reciter: how long the beats of each kind
last, in seconds and in ḥarakāt (the unit: what a long vowel of two adds to a short one, in that
recitation), and how the kinds separate.

Then the check Recite makes (core's TajweedCheck: the same thresholds) is tried on them: on the
recitations as they are (every flag is a false one: these are masters), and with each held sound
cut down to a short one's length (every one should be flagged).

Usage: timing.py <cache dir for the recordings> <model dir (vocab.json, onnx/)> <beats.tsv>
beats.tsv: TajweedReferenceBench.dump with HFD_TAJWEED_DUMP="beats:<āyāt>".
"""
import json, os, sys, subprocess, urllib.request, collections
import numpy as np
import onnxruntime as ort

cache, FC, beats_path = sys.argv[1], sys.argv[2], sys.argv[3]
RECITERS = {"maher": "MaherAlMuaiqly128kbps", "alafasy": "Alafasy_128kbps", "husary": "Husary_128kbps", "dossary": "Yasser_Ad-Dussary_128kbps"}
UA = "Mozilla/5.0 (X11; Linux x86_64) HFD-tajweed-study"
fb = np.fromfile(FC + "/onnx/fb.bin", dtype=np.float32).reshape(80, 257).astype(np.float64)
win = np.zeros(512); win[56:456] = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(400) / 399)
vocab = json.load(open(FC + "/vocab.json", encoding="utf-8"))
index = {c: i for i, c in enumerate(vocab)}
BLANK = len(vocab)
so = ort.SessionOptions(); so.intra_op_num_threads = 4
sess = ort.InferenceSession(FC + "/onnx/model_int8.onnx", so, providers=["CPUExecutionProvider"])
FRAME = 0.04


def features(x):
    x = x.astype(np.float64)
    e = np.concatenate([x[:1], x[1:] - 0.97 * x[:-1]])
    length = len(x) // 160
    frames = length + 1
    pad = np.concatenate([np.zeros(256), e, np.zeros(256 + 512)])
    seg = pad[np.arange(frames)[:, None] * 160 + np.arange(512)[None, :]]
    mel = np.log((np.abs(np.fft.rfft(seg * win, axis=1)) ** 2) @ fb.T + 2.0 ** -24).T
    v = mel[:, :length]
    mean = v.mean(axis=1, keepdims=True)
    std = np.sqrt(((v - mean) ** 2).sum(axis=1, keepdims=True) / max(1, length - 1)) + 1e-5
    out = (mel - mean) / std
    out[:, length:] = 0
    return out.astype(np.float32), length


def logprobs(pcm):
    f, n = features(pcm)
    lp, ol = sess.run(None, {"features": f[None], "lengths": np.array([n], dtype=np.int64)})
    return lp[0, :int(ol[0])]


def onsets(lp, text):
    """The frame each character of [text] is written at when the text is held to the frames; None for one the model doesn't write."""
    ids, where = [], []
    for i, c in enumerate(text):
        if c in index:
            ids.append(index[c]); where.append(i)
    T, L = lp.shape[0], len(ids)
    if L == 0 or T < L:
        return None
    S = 2 * L + 1
    sym = np.array([BLANK if s % 2 == 0 else ids[s // 2] for s in range(S)])
    skip = np.zeros(S, dtype=bool); skip[3::2] = sym[3::2] != sym[1:-2:2]
    NEG = -1e30
    d = np.full(S, NEG); d[0] = lp[0, BLANK]; d[1] = lp[0, sym[1]]
    bp = np.zeros((T, S), dtype=np.int8)
    for t in range(1, T):
        a = d
        b = np.concatenate([[NEG], d[:-1]])
        c = np.where(skip, np.concatenate([[NEG, NEG], d[:-2]]), NEG)
        best = np.maximum(a, np.maximum(b, c))
        bp[t] = np.where(best == a, 0, np.where(best == b, 1, 2))
        d = best + lp[t, sym]
    s = S - 1 if d[S - 1] >= d[S - 2] else S - 2
    first = [None] * L
    for t in range(T - 1, -1, -1):
        if s % 2 == 1:
            first[s // 2] = t
        s -= int(bp[t, s])
    out = [None] * len(text)
    for k, i in enumerate(where):
        out[i] = first[k]
    return out


def fetch(folder, sura, aya):
    path = os.path.join(cache, folder, f"{sura:03d}{aya:03d}.mp3")
    if not os.path.exists(path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        req = urllib.request.Request(f"https://everyayah.com/data/{folder}/{sura:03d}{aya:03d}.mp3", headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=120) as r, open(path + ".part", "wb") as f:
            f.write(r.read())
        os.replace(path + ".part", path)
    return path


def decode(path):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-af", "highpass=f=80,lowpass=f=7000", "-ac", "1", "-ar", "16000", "-f", "f32le", "-"], check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()


beats = {}
texts = {}
for line in open(beats_path, encoding="utf-8"):
    ref, text, rest = line.rstrip("\n").split("\t")
    texts[ref] = text
    beats[ref] = []
    for b in rest.split(" "):
        if not b:
            continue  # the letters that open a sūra: no beats
        at, vowel, madd, doubled, nasal, tanwin, last, word = b.split(":")
        beats[ref].append(dict(at=int(at), vowel=vowel, madd=None if madd == "-" else madd, doubled=doubled == "1",
                               nasal=None if nasal == "-" else nasal, tanwin=tanwin == "1", last=last == "1", word=int(word)))

def measure(pcm, ref):
    """Each beat of [ref] in the recording: (beat, kind, seconds of voice from its consonant to the next, first frame, last frame)."""
    lp = logprobs(pcm)
    on = onsets(lp, texts[ref])
    if on is None:
        return []
    bs = beats[ref]
    n = lp.shape[0]
    energy = np.array([np.sqrt(np.mean(pcm[i * 640:(i + 1) * 640] ** 2) + 1e-12) if (i + 1) * 640 <= len(pcm) else 0.0 for i in range(n)])
    quiet = energy < 0.06 * np.percentile(energy, 95)
    # A pause (the breath between two phrases) is a quiet stretch of 0.2 s or more: it is not part of what is held.
    pause = np.zeros(n, dtype=bool)
    i = 0
    while i < n:
        if quiet[i]:
            j = i
            while j < n and quiet[j]: j += 1
            if j - i >= 5: pause[i:j] = True
            i = j
        else:
            i += 1
    loud = np.where(~quiet)[0]
    end = int(loud[-1]) + 1 if len(loud) else n
    out = []
    for k, bt in enumerate(bs):
        t0 = on[bt["at"]]
        t1 = on[bs[k + 1]["at"]] if k + 1 < len(bs) else end
        if t0 is None or t1 is None or t1 <= t0:
            continue
        voiced = int((~pause[t0:t1]).sum())
        paused = bool(pause[t0:t1].any())
        if bt["madd"]:
            kind = bt["madd"]
        elif bt["nasal"]:
            kind = bt["nasal"] + ("+V" if bt["vowel"] != "N" else "")
        elif bt["vowel"] == "N":
            kind = "cluster"
        elif bt["tanwin"]:
            kind = "tanwin"
        elif bt["doubled"]:
            kind = "doubled"
        else:
            kind = "short"
        if bt["madd"] and (bt["nasal"] or bt["doubled"]):
            kind += "+g"
        if bt["last"]:
            kind += "@stop"
        elif paused:
            kind += "@pause"
        out.append((bt, kind, voiced * FRAME, t0, t1))
    return out


NASAL_OWN = ("GHUNNAH", "IKHFA", "IQLAB", "IKHFA_SHAFAWI")


def windows(ms):
    """
    For each ghunna, and for each plain doubled or vowelless consonant (what a ghunna dropped sounds
    like): (index, what, seconds, plain) over the window the sound is in. The recogniser writes a
    nasal's letter where it starts or where it ends: the window takes in the beat before too, so the
    hold is counted either way. [plain]: which usual beats the window would last without the hold.
    """
    out = []
    for i, (bt, kind, sec, t0, t1) in enumerate(ms):
        base = kind.split("@")[0]
        if "@" in kind or i == 0 or "@" in ms[i - 1][1]:
            continue
        prev = ms[i - 1]
        pk = prev[1]
        two = prev[2] + sec
        if bt["madd"] or prev[0]["madd"] or prev[0]["nasal"] or prev[0]["tanwin"]:
            continue
        pplain = "short" if prev[0]["vowel"] == "S" else "cluster"
        if prev[0]["doubled"]:
            pplain = "doubled"
        if bt["nasal"] in NASAL_OWN:
            out.append((i, "nasal", two, (pplain, "doubled" if bt["vowel"] != "N" else "cluster")))
        elif bt["nasal"]:  # after the beat's vowel: a tanwīn hidden, a nūn merged into the next letter
            out.append((i, "nasal", two, (pplain, "short")))
        elif bt["doubled"] and bt["vowel"] == "S":
            out.append((i, "plain doubled", two, (pplain, "doubled")))
        elif bt["vowel"] == "N" and not bt["doubled"]:
            out.append((i, "plain cluster", two, (pplain, "cluster")))
    return out


LONG = ("MADD_MUNFASIL", "MADD_MUTTASIL")


def check(ms):
    """
    The check on one recording's beats: [(index, what)] flagged. The yardstick is the recording's own:
    its usual natural madd (a long vowel of two), which every recitation has plenty of.
    """
    nat = [s for _, k, s, _, _ in ms if k == "MADD_NATURAL"]
    short = [s for _, k, s, _, _ in ms if k == "short"]
    if len(nat) < MIN_NATURAL and len(short) < 4:
        return []
    n = float(np.median(nat)) if len(nat) >= MIN_NATURAL else float(np.median(short)) * NAT_FROM_SHORT
    flags = []
    for i, (bt, kind, sec, _, _) in enumerate(ms):
        base = kind.split("@")[0].replace("+g", "")
        stopped = "@" in kind
        # The reciter stops on this word: a long vowel before a hamza opening the next word is then a plain one.
        if base == "MADD_MUTTASIL" and sec < LONG_MIN * n:
            flags.append((i, "short madd"))
        elif base == "MADD_MUNFASIL" and not stopped and sec < LONG_MIN * n:
            flags.append((i, "short madd"))
        elif base == "MADD_LAZIM" and sec < LAZIM_MIN * n:
            flags.append((i, "short lazim"))
        elif base == "MADD_NATURAL" and "+g" not in kind and not stopped and os.environ.get("NATURAL_MAX") and sec > float(os.environ["NATURAL_MAX"]) * n:
            # Tried and not kept (2 % of the masters' flagged: they stop inside an āya, and an echo hides the pause).
            flags.append((i, "long natural"))
    return flags


LONG_MIN = float(os.environ.get("LONG_MIN", "1.45"))
LAZIM_MIN = float(os.environ.get("LAZIM_MIN", "1.9"))
NAT_FROM_SHORT = 1.4
MIN_NATURAL = 3

rows = []
flagged = collections.Counter()
held = collections.Counter()
cut_total = collections.Counter()
cut_caught = collections.Counter()
examples = []
misses = []
for reciter, folder in RECITERS.items():
    for ref in beats:
        if not beats[ref]:
            continue
        sura, aya = (int(x) for x in ref.split(":"))
        try:
            pcm = decode(fetch(folder, sura, aya))
        except Exception as e:
            print("skip", reciter, ref, e, file=sys.stderr)
            continue
        ms = measure(pcm, ref)
        if not ms:
            continue
        words = texts[ref].split(" ")
        for bt, kind, sec, _, _ in ms:
            rows.append((reciter, ref, kind, sec, words[bt["word"]] if bt["word"] < len(words) else "?"))
        for i, what in check(ms):
            flagged[(reciter, what)] += 1
            examples.append((reciter, ref, what, ms[i][1], round(ms[i][2], 2)))
        for bt, kind, sec, _, _ in ms:
            base = kind.split("@")[0].replace("+g", "")
            if base == "MADD_MUTTASIL" or (base == "MADD_MUNFASIL" and "@" not in kind): held[(reciter, "short madd")] += 1
            if base == "MADD_LAZIM": held[(reciter, "short lazim")] += 1
            if base == "MADD_NATURAL" and "@" not in kind and "+g" not in kind: held[(reciter, "long natural")] += 1
        # Each long vowel of four or more cut down to the recording's usual natural madd: is it flagged?
        if os.environ.get("CUTS", "1") == "1":
            nat = [s for _, k, s, _, _ in ms if k == "MADD_NATURAL"]
            if len(nat) >= MIN_NATURAL:
                n = float(np.median(nat))
                for i, (bt, kind, sec, t0, t1) in enumerate(ms):
                    base = kind.split("@")[0].replace("+g", "")
                    if "@" in kind or base not in LONG + ("MADD_LAZIM",):
                        continue
                    what = "short madd" if base in LONG else "short lazim"
                    if sec <= n + 0.16:
                        continue
                    # Keep the beat's start (a natural madd's length less 80 ms) and its last 80 ms, take the middle out
                    # (a 20 ms fade across the cut): the vowel is then about as long as the reciter's plain long vowels.
                    a = int((t0 * FRAME + max(0.08, n - 0.08)) * 16000)
                    b = int((t1 * FRAME - 0.08) * 16000)
                    if b - a < 1600:
                        continue
                    f = 320
                    x = np.concatenate([pcm[:a - f], pcm[a - f:a] * np.linspace(1, 0, f) + pcm[b - f:b] * np.linspace(0, 1, f), pcm[b:]])
                    ms2 = measure(x, ref)
                    cut_total[(reciter, what)] += 1
                    at = bt["at"]
                    if any(ms2[j][0]["at"] == at and w == what for j, w in check(ms2)):
                        cut_caught[(reciter, what)] += 1
                    else:
                        misses.append((reciter, ref, what, [round(m[2], 2) for m in ms2 if m[0]["at"] == at], round(n, 2)))

json.dump(rows, open(os.environ.get("OUT", "timing.json"), "w"), ensure_ascii=False)
by = collections.defaultdict(list)
for r, ref, kind, sec, word in rows:
    by[(r, kind)].append(sec)
reciters = sorted({r for r, _ in by})
kinds = sorted({k for _, k in by}, key=lambda k: -sum(len(by[(r, k)]) for r in reciters))
print("seconds of voice: median [10th–90th] (n)")
print("kind".ljust(24) + "".join(r.rjust(26) for r in reciters))
for k in kinds:
    if sum(len(by[(r, k)]) for r in reciters) < 8:
        continue
    print(k.ljust(24) + "".join((("%.2f [%.2f–%.2f] (%d)" % (np.median(by[(r, k)]), np.percentile(by[(r, k)], 10), np.percentile(by[(r, k)], 90), len(by[(r, k)]))) if by[(r, k)] else "-").rjust(26) for r in reciters))
print("\nthe check on the recitations as they are: flagged / checked (all false: these are masters)")
for what in ("short madd", "short lazim", "long natural"):
    print(what.ljust(14) + "".join(("%s %d/%d" % (r, flagged[(r, what)], held[(r, what)])).rjust(22) for r in reciters))
print("\nheld sounds cut down to a short one: caught / cut")
for what in ("short madd", "short lazim"):
    print(what.ljust(14) + "".join(("%s %d/%d" % (r, cut_caught[(r, what)], cut_total[(r, what)])).rjust(22) for r in reciters))
print("\nfalse flags:")
for e in examples[:60]:
    print("  ", *e)
print("\ncuts not caught (what the cut vowel measured, the recording's natural madd):")
for e in misses[:40]:
    print("  ", *e)
