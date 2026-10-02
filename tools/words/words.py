#!/usr/bin/env python3
"""
When each word of the passages starts, in each reciter's recording, for the reading view to light
up the word being recited while listening. Run by .github/workflows/words.yml (the dev sandbox
can't reach the audio hosts), one job per reciter.

Per āya of the passages (and 1:1, the basmala played before a sūra), the audio exactly as the app
plays it (everyayah.com's file, or the āya's byte range of a whole-sūra file: assets/audio/<id>.json):
  1. decode it, and cut it at pauses into stretches the model reads well (≤ 18 s);
  2. transcribe them with Tarteel's Qur'an model (the app's, whisper.cpp) with each word's time
     (DTW token timestamps);
  3. align the words heard with the āya's words (letter skeletons, as the app's Recite mode);
  4. a word matched starts when it was heard; one that wasn't is placed between its neighbours
     by its letters (the reciter's pace).
Output: app/src/main/assets/audio/words/<reciter>.json, {"2:255": [start of each word, in
centiseconds from the start of the āya's audio], …}, the words being the app's (Arabic.words:
the text's space-separated tokens that hold letters). A report lists the āyāt where few words
were heard (their times are mostly estimates).
Usage: words.py <reciter id> [sūra …]  (run from the repository root)
"""
import json, os, re, subprocess, sys, urllib.request
import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "audio"))
import align  # the alignment tool: text, skeletons, alignment, decoding, transcription with times

ROOT = align.ROOT
OUT = os.path.join(ROOT, "app/src/main/assets/audio/words")
WORK = os.path.abspath("work/words")
EVERYAYAH = "https://everyayah.com/data/"
CHUNK_S = 18.0   # the model slips on longer stretches (CLAUDE.md: chunks ≤ 20 s)
DTW_LATE = 0.06  # a word's DTW time runs a little after its onset
MIN_HEARD = 0.5  # under this share of an āya's words heard, it goes in the report


def reciters():
    """The app's reciters (core Reciters.kt): id → (folder, byte-range asset or None)."""
    src = open(os.path.join(ROOT, "core/src/main/kotlin/app/hfd/core/playback/Reciters.kt"), encoding="utf-8").read()
    out = {}
    for m in re.finditer(r'^\s+Reciter\("([^"]+)", "([^"]+)"(.*)\),?\s*$', src, re.M):
        timings = re.search(r'timings = "([^"]+)"', m[3])
        out[m[1]] = (m[2], timings[1] if timings else None)
    return out


def fetch(url, path, rng=None):
    if os.path.exists(path):
        return path
    headers = {"User-Agent": align.UA}
    if rng:
        headers["Range"] = f"bytes={rng[0]}-{rng[1] - 1}"
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=120) as r:
                data = r.read()
            break
        except Exception as e:  # a flaky host: try again
            if attempt == 3:
                raise
            print("retry", url, e, flush=True)
    open(path + ".part", "wb").write(data)
    os.rename(path + ".part", path)
    return path


def chunks(pcm):
    """[(start_s, end_s)] of speech, cut at pauses, none longer than CHUNK_S."""
    quiet, db, _ = align.pauses(pcm)
    runs = []
    for (a0, a1), (b0, b1) in zip(quiet, quiet[1:]):
        if b0 - a1 > 0.05:
            runs.append((max(0.0, a1 - 0.15), b0 + 0.15))
    # Join runs while they fit in a chunk (the model reads phrases better than single words).
    joined = []
    for a, b in runs:
        if joined and b - joined[-1][0] <= CHUNK_S:
            joined[-1] = (joined[-1][0], b)
        else:
            joined.append((a, b))
    out = []
    for a, b in joined:
        while b - a > CHUNK_S:
            lo, hi = int((a + CHUNK_S * 0.5) * 50), int((a + CHUNK_S) * 50)
            cut = (lo + int(np.argmin(db[lo:hi]))) / 50
            out.append((a, cut))
            a = cut
        out.append((a, b))
    return out or [(0.0, len(pcm) / align.RATE)]


def transcribe(paths):
    """
    align.transcribe with times, 40 files per whisper.cpp run; when a run crashes (its DTW aborts on
    some stretches), each file of it alone, and one that still crashes is left unheard.
    """
    out = {}
    for k in range(0, len(paths), 40):
        batch = paths[k:k + 40]
        try:
            out.update(align.transcribe(batch, times=True))
        except subprocess.CalledProcessError:
            for p in batch:
                try:
                    out.update(align.transcribe([p], times=True))
                except subprocess.CalledProcessError:
                    print("whisper.cpp failed on", os.path.basename(p), flush=True)
                    out[p] = []
    return out


def place(expected_letters, heard_at, speech):
    """
    Start times of every word: [heard_at] maps word index → time heard; the others are spread by
    their letters between the words around them (or the speech's start and end).
    """
    n = len(expected_letters)
    t = [None] * n
    for j, s in heard_at.items():
        t[j] = s
    known = sorted(heard_at)
    start, end = speech
    # Pace: seconds per letter over the words heard, else over the whole speech.
    if len(known) >= 2 and known[-1] > known[0]:
        pace = (heard_at[known[-1]] - heard_at[known[0]]) / max(1, sum(expected_letters[known[0]:known[-1]]))
    else:
        pace = (end - start) / max(1, sum(expected_letters))
    # Before the first word heard: back from it at the pace (not before the speech starts).
    first = known[0] if known else n
    anchor = heard_at[first] if known else start
    for j in range(first - 1, -1, -1):
        anchor = max(start, anchor - pace * expected_letters[j])
        t[j] = anchor
    # Between words heard, and after the last one.
    for k, j0 in enumerate(known):
        j1 = known[k + 1] if k + 1 < len(known) else None
        if j1 is None:
            s = heard_at[j0]
            for j in range(j0 + 1, n):
                s = min(end, s + pace * expected_letters[j - 1])
                t[j] = s
        else:
            span = heard_at[j1] - heard_at[j0]
            letters = sum(expected_letters[j0:j1]) or 1
            acc = 0
            for j in range(j0 + 1, j1):
                acc += expected_letters[j - 1]
                t[j] = heard_at[j0] + span * acc / letters
    # Never earlier than the word before.
    for j in range(1, n):
        t[j] = max(t[j], t[j - 1])
    return t


def main():
    rid = sys.argv[1]
    only = {int(s) for s in sys.argv[2:]}
    folder, ranges_asset = reciters()[rid]
    ranges = json.load(open(os.path.join(ROOT, "app/src/main/assets", ranges_asset), encoding="utf-8")) if ranges_asset else None
    text = align.quran()
    want = sorted((s, a) for s, ayat in align.needed().items() for a in ayat if not only or s in only)
    os.makedirs(WORK, exist_ok=True)
    os.makedirs(OUT, exist_ok=True)

    # 1. The audio as the app plays it, cut into stretches.
    jobs = []  # (ref, wav path, offset s)
    speech = {}
    for s, a in want:
        key = f"{s}:{a}"
        name = f"{s:03d}{a:03d}"
        try:
            if ranges:
                rng = ranges["ayat"].get(key)
                if not rng:
                    print("no range for", key)
                    continue
                path = fetch(ranges["base"] + ranges["suras"][str(s)]["file"], os.path.join(WORK, f"{rid}-{name}.mp3"), rng)
            else:
                path = fetch(f"{EVERYAYAH}{folder}/{name}.mp3", os.path.join(WORK, f"{rid}-{name}.mp3"))
            pcm = align.decode(path)
        except Exception as e:
            print("could not get", key, e, flush=True)
            continue
        cs = chunks(pcm)
        speech[(s, a)] = (cs[0][0], cs[-1][1])
        for k, (c0, c1) in enumerate(cs):
            wav = os.path.join(WORK, f"{rid}-{name}-{k}.wav")
            align.write_wav(wav, pcm[int(c0 * align.RATE):int(c1 * align.RATE)])
            jobs.append(((s, a), wav, c0))
    print(len(speech), "āyāt,", len(jobs), "stretches", flush=True)

    # 2. What was heard, and when.
    heard = transcribe([w for _, w, _ in jobs])

    # 3–4. Aligned with each āya's words.
    out, report = {}, []
    by_ref = {}
    for ref, wav, off in jobs:
        by_ref.setdefault(ref, []).extend((w, off + t) for w, t in heard.get(wav, []) if t >= 0)
    for ref in sorted(speech):
        ws = align.words(text[ref])
        exp = [align.skeleton(w) for w in ws]
        letters = [max(1, len(e)) for e in exp]
        got = by_ref.get(ref, [])
        pairs = align.align_all([align.skeleton(w) for w, _ in got], exp, band=max(60, len(exp) + len(got)))
        heard_at = {}
        for i, j in pairs:
            heard_at.setdefault(j, max(0.0, got[i][1] - DTW_LATE))
        # Times heard out of order (a word matched late): keep the words in order.
        last = -1.0
        for j in sorted(heard_at):
            if heard_at[j] < last:
                del heard_at[j]
            else:
                last = heard_at[j]
        times = place(letters, heard_at, speech[ref])
        out[f"{ref[0]}:{ref[1]}"] = [round(x * 100) for x in times]
        share = len(heard_at) / max(1, len(exp))
        if share < MIN_HEARD:
            report.append(f"{ref[0]}:{ref[1]}: {len(heard_at)}/{len(exp)} words heard: " + " ".join(w for w, _ in got))

    data = {
        "schema": 1,
        "reciter": rid,
        "note": "When each word starts, in centiseconds from the start of the āya's audio as the app plays it; tools/words/words.py.",
        "words": out,
    }
    path = os.path.join(OUT, f"{rid}.json")
    json.dump(data, open(path, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    heard_words = sum(1 for ref in out for _ in out[ref])
    print(f"{rid}: {len(out)} āyāt, {heard_words} words → {path} ({os.path.getsize(path)} bytes)")
    print(f"{len(report)} āyāt with few words heard:")
    for line in report:
        print("  " + line)


if __name__ == "__main__":
    main()
