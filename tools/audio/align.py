#!/usr/bin/env python3
"""
Āya boundaries in a reciter's whole-sūra MP3s (mp3quran.net), so that HFD can fetch each āya of
its passages as a byte range of the sūra file and play it like everyayah.com's one-file-per-āya
recitations. Run by .github/workflows/audio.yml (the dev sandbox can't reach the audio hosts).

Per sūra:
  1. decode, and find the pauses (runs of quiet 20 ms frames);
  2. transcribe ~20 s windows cut at pauses with Tarteel's Qur'an model (the app's, whisper.cpp);
  3. align the words heard with Tanzil's text (letter skeletons, as the app's Recite mode does);
  4. put each āya boundary on the pause closest to it;
  5. turn times into MP3 frame offsets (bytes), keeping a little of each pause;
then cut every āya exactly as the app will fetch it, transcribe it again and compare it with its
text: the report lists the āyāt whose first or last word isn't where it should be.

Output: app/src/main/assets/audio/<reciter>.json
Usage: align.py <reciter id> [sūra …]
"""
import json, os, re, subprocess, sys, urllib.request
import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CLI = os.environ.get("WHISPER_CLI", "work/whisper.cpp/build/bin/whisper-cli")
MODEL = os.environ.get("WHISPER_MODEL", "work/ggml-tiny-ar-quran-q8_0.bin")
WORK = os.path.abspath("work/audio")
UA = "Mozilla/5.0 (X11; Linux x86_64) HFD-align"

RECITERS = {
    "badr-alturki": {
        "name": "Badr at-Turkī",
        "nameAr": "بدر التركي",
        "base": "https://server10.mp3quran.net/bader/Rewayat-Hafs-A-n-Assem/",
    },
}

ISTIADHA = "أعوذ بالله من الشيطان الرجيم"
RATE = 16000
HOP = RATE // 50            # 20 ms analysis frames
MIN_PAUSE = 13              # frames: a pause is at least 260 ms of quiet
WINDOW_S = 20.0             # transcription windows (the model slips past ~25 s)
LEAD_S, TAIL_S = 0.30, 0.45  # pause kept before / after an āya


# ---------------------------------------------------------------- text

def skeleton(w):
    """The app's Arabic.skeleton: no marks, no alif or lone hamza, one yāʾ, tāʾ marbūṭa as hāʾ."""
    out = []
    for c in w:
        o = ord(c)
        if 0x064B <= o <= 0x065F or o == 0x0670 or 0x06D6 <= o <= 0x06ED or o == 0x0640:
            continue
        if c in "اٱأإآء":
            continue
        if c == "ؤ":
            out.append("و")
        elif c in "ئيىی":
            out.append("ى")
        elif c == "ة":
            out.append("ه")
        elif c == "ک":
            out.append("ك")
        elif "ء" <= c <= "ي" or "ٱ" <= c <= "ۓ":
            out.append(c)
    return "".join(out)


def words(text):
    return [w for w in text.split() if skeleton(w)]


def quran():
    text = {}
    for line in open(os.path.join(ROOT, "app/src/main/assets/quran/quran-uthmani.txt"), encoding="utf-8"):
        p = line.rstrip("\n").split("|")
        if len(p) == 3 and p[0].isdigit():
            text[(int(p[0]), int(p[1]))] = p[2]
    # Tanzil writes each sūra's basmala at the start of its āya 1 (the app sets it apart).
    basmala = [skeleton(w) for w in words(text[(1, 1)])]
    for (s, a), t in list(text.items()):
        if a == 1 and s not in (1, 9):
            ws = words(t)
            if [skeleton(w) for w in ws[:4]] == basmala:
                text[(s, a)] = " ".join(ws[4:])
    return text


def needed():
    """Āyāt of every passage, by sūra, plus 1:1 (the basmala the app plays before a sūra)."""
    data = json.load(open(os.path.join(ROOT, "app/src/main/assets/fadail.json"), encoding="utf-8"))
    out = {1: {1}}
    for f in data["fadail"]:
        for r in f["ranges"]:
            out.setdefault(r["sura"], set()).update(range(r["from"], r["to"] + 1))
    return out


def ayah_count(sura):
    suras = json.load(open(os.path.join(ROOT, "app/src/main/assets/quran/suras.json"), encoding="utf-8"))["suras"]
    return suras[sura - 1]["ayas"]


# ---------------------------------------------------------------- similarity and alignment

_lev = {}


def sim(a, b):
    if a == b:
        return 1.0
    key = (a, b)
    v = _lev.get(key)
    if v is None:
        prev = list(range(len(b) + 1))
        for i, ca in enumerate(a, 1):
            cur = [i]
            for j, cb in enumerate(b, 1):
                cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
            prev = cur
        v = 1 - prev[-1] / max(len(a), len(b), 1)
        _lev[key] = v
    return v


def align(heard, expected, lo, hi):
    """
    Semi-global word alignment of all of [heard] with a stretch of expected[lo:hi] (skeletons).
    Returns the matched (heard index, expected index) pairs, in order.
    """
    span = expected[lo:hi]
    n, m = len(heard), len(span)
    if n == 0 or m == 0:
        return []
    GAP = -1.0
    # d[i][j]: best score of heard[:i] against span ending at j; row 0 free (start anywhere).
    d = [[0.0] * (m + 1)] + [[GAP * i] + [0.0] * m for i in range(1, n + 1)]
    back = [[0] * (m + 1) for _ in range(n + 1)]  # 1 match, 2 extra heard word, 3 skipped expected word
    for i in range(1, n + 1):
        h = heard[i - 1]
        di, dp = d[i], d[i - 1]
        bi = back[i]
        for j in range(1, m + 1):
            s = sim(h, span[j - 1])
            best, how = dp[j - 1] + (2.0 if s >= 0.75 else (0.5 if s >= 0.5 else -1.0)), 1
            if dp[j] + GAP > best:
                best, how = dp[j] + GAP, 2
            if i < n and di[j - 1] + GAP > best:  # no skipping after the last word heard
                best, how = di[j - 1] + GAP, 3
            di[j] = best
            bi[j] = how
    last_row = d[n]
    j = max(range(1, m + 1), key=lambda k: last_row[k])
    i = n
    pairs = []
    while i > 0 and j > 0:
        how = back[i][j]
        if how == 1:
            if sim(heard[i - 1], span[j - 1]) >= 0.5:
                pairs.append((i - 1, lo + j - 1))
            i, j = i - 1, j - 1
        elif how == 2:
            i -= 1
        else:
            j -= 1
    pairs.reverse()
    return pairs


# ---------------------------------------------------------------- MP3 frames

BITRATES = {1: [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320],
            2: [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160]}
SRATES = {3: [44100, 48000, 32000], 2: [22050, 24000, 16000], 0: [11025, 12000, 8000]}


def header(data, pos):
    if pos + 4 > len(data):
        return None
    h = int.from_bytes(data[pos:pos + 4], "big")
    if (h >> 21) & 0x7FF != 0x7FF:
        return None
    ver, layer, br, sri, pad = (h >> 19) & 3, (h >> 17) & 3, (h >> 12) & 15, (h >> 10) & 3, (h >> 9) & 1
    if ver == 1 or layer != 1 or br in (0, 15) or sri == 3:
        return None
    rate = SRATES[ver][sri]
    kbps = BITRATES[1 if ver == 3 else 2][br]
    length = (144 if ver == 3 else 72) * kbps * 1000 // rate + pad
    return length, rate, 1152 if ver == 3 else 576, kbps


def frames(data):
    """[(offset, length)] of the audio frames, their sample rate and samples per frame."""
    pos = 0
    if data[:3] == b"ID3":
        size = (data[6] & 0x7F) << 21 | (data[7] & 0x7F) << 14 | (data[8] & 0x7F) << 7 | (data[9] & 0x7F)
        pos = 10 + size + (10 if data[5] & 0x10 else 0)
    out, rate, spf, kbps = [], None, None, set()
    while pos < len(data):
        h = header(data, pos)
        # A frame is real if another one follows it (or the file ends).
        if h and (pos + h[0] >= len(data) - 128 or header(data, pos + h[0])):
            out.append((pos, h[0]))
            rate, spf = h[1], h[2]
            kbps.add(h[3])
            pos += h[0]
        else:
            pos += 1
    # The first frame may be a Xing / Info header (no audio).
    if out:
        o, n = out[0]
        if b"Xing" in data[o:o + 64] or b"Info" in data[o:o + 64]:
            out = out[1:]
    return out, rate, spf, sorted(kbps)


# ---------------------------------------------------------------- audio

def fetch(url, path):
    if not os.path.exists(path):
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=300) as r, open(path + ".part", "wb") as f:
            while True:
                b = r.read(1 << 20)
                if not b:
                    break
                f.write(b)
        os.rename(path + ".part", path)
    return path


def decode(path):
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", path, "-ar", str(RATE), "-ac", "1", "-f", "s16le", "-"],
                         check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768


def write_wav(path, pcm):
    import wave
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes((np.clip(pcm, -1, 1) * 32767).astype(np.int16).tobytes())


def transcribe(paths, times=False):
    """
    What was heard in each WAV (one whisper.cpp run per batch, the model loaded once): text, or
    with [times] the words and when each starts (DTW token timestamps, seconds into the WAV).
    """
    out = {}
    for k in range(0, len(paths), 40):
        batch = paths[k:k + 40]
        args = [CLI, "-m", MODEL, "-l", "ar", "-nt", "-np", "-bs", "1", "-bo", "1", "-mc", "0", "-t", str(os.cpu_count() or 4)]
        args += ["-dtw", "tiny", "-ojf"] if times else ["-otxt"]
        for p in batch:
            args += ["-f", p]
        subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for p in batch:
            if not times:
                try:
                    out[p] = open(p + ".txt", encoding="utf-8").read().strip()
                except FileNotFoundError:
                    out[p] = ""
                continue
            try:
                # Tokens are bytes of UTF-8 (a letter can span two): keep them raw until joined.
                doc = json.loads(open(p + ".json", "rb").read().decode("utf-8", "surrogateescape"))
            except (FileNotFoundError, ValueError):
                out[p] = []
                continue
            words = []
            for seg in doc.get("transcription", []):
                for tok in seg.get("tokens", []):
                    t = tok.get("text", "")
                    if t.startswith("[_") or t.startswith("<|"):
                        continue
                    raw = t.encode("utf-8", "surrogateescape")
                    if raw.startswith(b" ") or not words:
                        words.append([raw, tok.get("t_dtw", -1) / 100])
                    else:
                        words[-1][0] += raw
            out[p] = [(w.decode("utf-8", "replace").strip(), t) for w, t in words if w.strip()]
    return out


def pauses(pcm):
    """Quiet stretches [(start_s, end_s)] and the frame energies (dB)."""
    n = len(pcm) // HOP
    e = np.sqrt(np.mean(pcm[:n * HOP].reshape(n, HOP) ** 2, axis=1) + 1e-12)
    db = 20 * np.log10(e)
    floor, loud = np.percentile(db, 5), np.percentile(db, 90)
    quiet = db < floor + 0.3 * (loud - floor)
    out, i = [], 0
    while i < n:
        if quiet[i]:
            j = i
            while j < n and quiet[j]:
                j += 1
            if j - i >= MIN_PAUSE or i == 0 or j == n:
                out.append((i / 50, j / 50))
            i = j
        else:
            i += 1
    if not out or out[0][0] > 0:
        out.insert(0, (0.0, 0.0))
    if out[-1][1] < n / 50:
        out.append((n / 50, n / 50))
    return out, db


def split_long(chunks, db):
    """Speech runs longer than a window are cut at their quietest moment."""
    out = []
    for a, b in chunks:
        while b - a > WINDOW_S:
            lo, hi = int((a + WINDOW_S * 0.5) * 50), int((a + WINDOW_S) * 50)
            cut = (lo + int(np.argmin(db[lo:hi]))) / 50
            out.append((a, cut))
            a = cut
        out.append((a, b))
    return out


# ---------------------------------------------------------------- one sūra

def sura_timings(rid, sura, text, want):
    cfg = RECITERS[rid]
    os.makedirs(WORK, exist_ok=True)
    mp3 = fetch(cfg["base"] + "%03d.mp3" % sura, os.path.join(WORK, f"{rid}-{sura:03d}.mp3"))
    data = open(mp3, "rb").read()
    fr, frate, spf, kbps = frames(data)
    pcm = decode(mp3)
    dur = len(pcm) / RATE
    quiet, db = pauses(pcm)
    smooth = np.convolve(db, np.ones(5) / 5, mode="same")  # 100 ms
    speech = [(quiet[k][1], quiet[k + 1][0]) for k in range(len(quiet) - 1) if quiet[k + 1][0] - quiet[k][1] >= 0.12]
    speech = split_long(speech, db)

    # Windows of whole speech runs, up to WINDOW_S, transcribed with each word's time.
    # The opening runs (isti'ādha, basmala) alone: at the start of a longer window the model tends
    # to drop the basmala, and the words' times after it slip.
    windows = []
    for k, (a, b) in enumerate(speech):
        alone = k < 2 and b - a < 7
        if windows and not alone and not windows[-1][0][2] and b - windows[-1][0][0] <= WINDOW_S:
            windows[-1].append((a, b, False))
        else:
            windows.append([(a, b, alone)])
    windows = [[(a, b) for a, b, _ in w] for w in windows]
    paths, starts = [], []
    for k, w in enumerate(windows):
        p = os.path.join(WORK, f"{rid}-{sura:03d}-w{k:04d}.wav")
        a, b = max(0, w[0][0] - 0.15), min(dur, w[-1][1] + 0.15)
        write_wav(p, pcm[int(a * RATE):int(b * RATE)])
        paths.append(p)
        starts.append(a)
    heard = transcribe(paths, times=True)

    # The text as recited: isti'ādha (optional), basmala (not al-Fātiḥa, not at-Tawba), āyāt.
    exp = [skeleton(w) for w in words(ISTIADHA)]
    if sura not in (1, 9):
        exp += [skeleton(w) for w in words(text[(1, 1)])]
    count = ayah_count(sura)
    first_word = {}
    for a in range(1, count + 1):
        first_word[a] = len(exp)
        exp += [skeleton(w) for w in words(text[(sura, a)])]
    first_word[count + 1] = len(exp)
    letters = [max(1, len(x)) for x in exp]
    # Seconds per letter, to place words that weren't heard next to ones that were.
    pace = sum(b - a for a, b in speech) / max(1, sum(letters))

    # Follow the recitation window by window; each expected word matched gets a time.
    at = {}
    ptr = 0
    debug = []
    for k, w in enumerate(windows):
        ws = [(skeleton(x), t) for x, t in heard[paths[k]]]
        ws = [(x, t) for x, t in ws if x]
        h = [x for x, _ in ws]
        lo, hi = max(0, ptr - 12), min(len(exp), ptr + 3 * len(h) + 40)
        pairs = align(h, exp, lo, hi)
        if pairs and len(pairs) >= max(1, len(h) // 4):
            for hi_, e in pairs:
                t = ws[hi_][1]
                if e not in at and t >= 0:
                    at[e] = starts[k] + t
            ptr = pairs[-1][1] + 1
            span = f"{pairs[0][1]}-{pairs[-1][1]}"
        else:
            span = "-"
        debug.append(f"w{k} {w[0][0]:7.1f}-{w[-1][1]:7.1f} {len(w)} runs [{span}] " + " ".join(f"{x}@{starts[k] + t:.1f}" for x, t in heard[paths[k]])[:220])

    def valley(lo, hi):
        """The quiet stretch around the quietest 100 ms in [lo, hi] (seconds)."""
        i0, i1 = max(0, int(lo * 50)), min(len(smooth), max(int(lo * 50) + 1, int(hi * 50)))
        m = i0 + int(np.argmin(smooth[i0:i1]))
        floor = smooth[m] + 6
        a = m
        while a > 0 and smooth[a - 1] < floor and m - a < 100:
            a -= 1
        b = m
        while b + 1 < len(smooth) and smooth[b + 1] < floor and b - m < 100:
            b += 1
        return a / 50, (b + 1) / 50

    def cut_at(b):
        """The quiet stretch before expected word b (between the words heard around it)."""
        # The nearest words heard on each side; words between them that weren't heard are
        # accounted for at the recitation's pace.
        eb = next((e for e in range(b - 1, max(-1, b - 6), -1) if e in at), None)
        ea = next((e for e in range(b, min(len(exp), b + 5)) if e in at), None)
        before = at[eb] + pace * sum(letters[eb:b]) if eb is not None else None
        after = at[ea] - pace * sum(letters[b:ea]) if ea is not None else None
        if eb is not None and eb < b - 1:
            before = at[eb] + pace * letters[eb]  # the end of the last word heard, at least
        if after is None:
            if before is None:
                return None
            # The end: where the speech run holding the last word stops.
            run = next((r for r in speech if r[1] >= before), speech[-1])
            nxt = next((r[0] for r in speech if r[0] > run[1]), dur)
            return run[1], nxt
        lo = before + 0.1 if before is not None else after - 2.5
        lo = max(lo if ea == b else min(lo, after - 1.0), after - 8, 0)
        return valley(lo, max(after + 0.35, lo + 0.1))

    out = {}
    report = []
    for a in sorted(want):
        c0, c1 = cut_at(first_word[a]), cut_at(first_word[a + 1])
        if c0 is None or c1 is None:
            report.append(f"{sura}:{a} not found")
            continue
        (s0, s1), (e0, e1) = c0, c1
        start = max((s0 + s1) / 2, s1 - LEAD_S)
        end = min((e0 + e1) / 2, e0 + TAIL_S)
        if end <= start:
            report.append(f"{sura}:{a} empty ({start:.2f}-{end:.2f})")
            continue
        # Frames covering [start, end], plus one before (the bit reservoir of the first).
        per = spf / frate
        i0 = max(0, int(start / per) - 1)
        i1 = min(len(fr) - 1, int(end / per) + 1)
        out[f"{sura}:{a}"] = [fr[i0][0], fr[i1][0] + fr[i1][1]]
        report.append(f"{sura}:{a} {start:8.2f} {end:8.2f}")
    if len(windows) <= 12:
        report = debug + report
    info = {"file": "%03d.mp3" % sura, "size": len(data), "seconds": round(dur, 1), "kbps": kbps}
    stats = f"{sura:3d}: {dur/60:5.1f} min, {len(windows)} windows, {len(at)}/{len(exp)} words placed, kbps {kbps}"
    return out, info, report, stats


# ---------------------------------------------------------------- check

def check(rid, text, ayat, infos):
    """Cut each āya as the app will, transcribe it and compare with its text."""
    paths, refs = [], []
    for key, (b0, b1) in ayat.items():
        s, a = map(int, key.split(":"))
        data = open(os.path.join(WORK, f"{rid}-{s:03d}.mp3"), "rb").read()[b0:b1]
        mp3 = os.path.join(WORK, f"check-{rid}-{s:03d}{a:03d}.mp3")
        open(mp3, "wb").write(data)
        wav = mp3[:-4] + ".wav"
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", mp3, "-ar", str(RATE), "-ac", "1", wav], check=True)
        paths.append(wav)
        refs.append((s, a))
    heard = transcribe(paths)
    bad = []
    for p, (s, a) in zip(paths, refs):
        ref = [skeleton(w) for w in words(text[(s, a)])]
        hyp = [x for x in (skeleton(w) for w in heard[p].split()) if x]
        # Word error, and whether the āya's own first and last words open and close the cut.
        d = list(range(len(hyp) + 1))
        for i, r in enumerate(ref, 1):
            prev, d[0] = d[0], i
            for j, h in enumerate(hyp, 1):
                prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (sim(r, h) < 0.75))
        wer = d[len(hyp)] / max(1, len(ref))
        # (0.6: a letter misheard in a short word, وصل for فصل, isn't a wrong cut.)
        head = any(sim(ref[0], h) >= 0.6 for h in hyp[:2]) if hyp else False
        tail = any(sim(ref[-1], h) >= 0.6 for h in hyp[-2:]) if hyp else False
        if wer > 0.35 or not head or not tail:
            bad.append((s, a, wer, head, tail, heard[p]))
    print(f"\nChecked {len(paths)} āyāt: {len(paths) - len(bad)} fine, {len(bad)} to look at")
    for s, a, wer, head, tail, h in bad:
        print(f"  {s}:{a} wer {wer:.2f} start {'ok' if head else 'OFF'} end {'ok' if tail else 'OFF'} | {h[:160]}")
    return bad


def main():
    rid = sys.argv[1]
    only = {int(x) for x in sys.argv[2:]}
    cfg = RECITERS[rid]
    text = quran()
    want = needed()
    # Does the server serve byte ranges? (The app fetches each āya as one.)
    req = urllib.request.Request(cfg["base"] + "001.mp3", headers={"User-Agent": UA, "Range": "bytes=0-99"})
    with urllib.request.urlopen(req, timeout=60) as r:
        print("Range request:", r.status, r.headers.get("Content-Range"), r.headers.get("Accept-Ranges"))
    ayat, suras = {}, {}
    for sura in sorted(want):
        if only and sura not in only:
            continue
        out, info, report, stats = sura_timings(rid, sura, text, want[sura])
        print(stats)
        for line in report:
            print("   ", line)
        sys.stdout.flush()
        ayat.update(out)
        suras[str(sura)] = info
    bad = check(rid, text, ayat, suras)
    dest = os.path.join(ROOT, "app/src/main/assets/audio", rid + ".json")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    doc = {
        "schema": 1,
        "reciter": rid,
        "name": cfg["name"],
        "nameAr": cfg["nameAr"],
        "base": cfg["base"],
        "note": "Byte ranges [start, end) of each āya in the sūra files, found by tools/audio/align.py.",
        "suras": suras,
        "ayat": {k: ayat[k] for k in sorted(ayat, key=lambda k: tuple(map(int, k.split(":"))))},
        "unchecked": [f"{s}:{a}" for s, a, *_ in bad],
    }
    if not only:
        json.dump(doc, open(dest, "w", encoding="utf-8"), ensure_ascii=False, indent=None, separators=(",", ":"))
        print("wrote", dest, len(ayat), "āyāt")


if __name__ == "__main__":
    sys.exit(main())
