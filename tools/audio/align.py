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
JOIN_S = 0.08               # overlap where two āyāt are recited without a pause


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


def align_all(heard, expected, band=150):
    """
    Global alignment of everything heard in a sūra with its whole text, within a band around the
    diagonal (monotonic: a repeated phrase, as in al-Kāfirūn, is matched in its own place).
    Leading expected words (the isti'ādha, the basmala) may be skipped cheaply.
    Returns the matched (heard index, expected index) pairs.
    """
    n, m = len(heard), len(expected)
    if n == 0 or m == 0:
        return []
    GAP, SKIP_LEAD = -1.0, -0.2
    NEG = -1e18
    centre = lambda i: int(i * m / n)
    rows, backs = [], []
    lo0, hi0 = 0, min(m, band)
    rows.append((0, [SKIP_LEAD * j for j in range(0, hi0 + 1)]))
    backs.append(None)
    for i in range(1, n + 1):
        lo, hi = max(0, centre(i) - band), min(m, centre(i) + band)
        plo, prow = rows[-1]
        phi = plo + len(prow) - 1
        row = [NEG] * (hi - lo + 1)
        back = [0] * (hi - lo + 1)
        h = heard[i - 1]
        for j in range(lo, hi + 1):
            best, how = NEG, 0
            if plo <= j <= phi and prow[j - plo] > NEG / 2:  # extra word heard
                best, how = prow[j - plo] + GAP, 2
            if j > 0 and plo <= j - 1 <= phi and prow[j - 1 - plo] > NEG / 2:
                sc = sim(h, expected[j - 1])
                v = prow[j - 1 - plo] + (2.0 if sc >= 0.75 else (0.5 if sc >= 0.5 else -1.0))
                if v > best:
                    best, how = v, 1
            if j > lo and row[j - 1 - lo] > NEG / 2 and row[j - 1 - lo] + GAP > best:  # expected word not heard
                best, how = row[j - 1 - lo] + GAP, 3
            row[j - lo] = best
            back[j - lo] = how
        rows.append((lo, row))
        backs.append(back)
    # From the end (trailing words heard after the text, e.g. a closing formula, are free).
    i = max(range(n + 1), key=lambda k: rows[k][1][m - rows[k][0]] if rows[k][0] <= m < rows[k][0] + len(rows[k][1]) else NEG)
    j = m
    pairs = []
    while i > 0 and j > 0:
        lo = rows[i][0]
        how = backs[i][j - lo] if 0 <= j - lo < len(backs[i]) else 2
        if how == 1:
            if sim(heard[i - 1], expected[j - 1]) >= 0.5:
                pairs.append((i - 1, j - 1))
            i, j = i - 1, j - 1
        elif how == 2:
            i -= 1
        elif how == 3:
            j -= 1
        else:
            break
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
    threshold = floor + 0.3 * (loud - floor)
    quiet = db < threshold
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
    return out, db, threshold


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
    quiet, db, threshold = pauses(pcm)
    smooth = np.convolve(db, np.ones(5) / 5, mode="same")  # 100 ms
    # A pause between āyāt lasts longer than the closure of a consonant (ك, ت, د…).
    broad = np.convolve(db, np.ones(12) / 12, mode="same")  # 240 ms
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
    aya_of = [-1] * len(exp)
    if sura not in (1, 9):
        exp += [skeleton(w) for w in words(text[(1, 1)])]
        aya_of += [0] * (len(exp) - len(aya_of))
    count = ayah_count(sura)
    first_word = {}
    for a in range(1, count + 1):
        first_word[a] = len(exp)
        exp += [skeleton(w) for w in words(text[(sura, a)])]
        aya_of += [a] * (len(exp) - len(aya_of))
    first_word[count + 1] = len(exp)
    letters = [max(1, len(x)) for x in exp]
    # Seconds per letter, to place words that weren't heard next to ones that were.
    pace = sum(b - a for a, b in speech) / max(1, sum(letters))

    # Everything heard, in order, aligned with the whole text; each expected word matched gets a time.
    flat = []
    debug = []
    for k, w in enumerate(windows):
        for x, t in heard[paths[k]]:
            sx = skeleton(x)
            if sx and t >= 0:
                flat.append((sx, starts[k] + t))
        debug.append(f"w{k} {w[0][0]:7.1f}-{w[-1][1]:7.1f} {len(w)} runs " + " ".join(f"{x}@{starts[k] + t:.1f}" for x, t in heard[paths[k]])[:220])
    at = {}
    for hi_, e in align_all([x for x, _ in flat], exp):
        at.setdefault(e, flat[hi_][1])

    def valley(lo, hi):
        """The silence around the quietest quarter second in [lo, hi] (seconds); a point if none."""
        i0, i1 = max(0, int(lo * 50)), min(len(broad), max(int(lo * 50) + 1, int(hi * 50)))
        m = i0 + int(np.argmin(broad[i0:i1]))
        # The quietest 100 ms there, then out through the silence (at most a second each way).
        return around(m)

    def around(m):
        """The silence around frame m (its quietest 100 ms nearby), at most a second each way."""
        m = max(0, m - 3) + int(np.argmin(smooth[max(0, m - 3):m + 4]))
        if smooth[m] >= threshold:
            return m / 50, m / 50
        a = m
        while a > 0 and smooth[a - 1] < threshold and m - a < 50:
            a -= 1
        b = m
        while b + 1 < len(smooth) and smooth[b + 1] < threshold and b - m < 50:
            b += 1
        return a / 50, (b + 1) / 50

    def cut_at(b):
        """The quiet stretch before expected word b (between the words heard around it)."""
        # The nearest words heard on each side; words between them that weren't heard are
        # accounted for at the recitation's pace.
        eb = next((e for e in range(b - 1, max(-1, b - 6), -1) if e in at), None)
        ea = next((e for e in range(b, min(len(exp), b + 5)) if e in at), None)
        before = at[eb] if eb is not None else None  # (a word's time is its start)
        after = at[ea] - pace * sum(letters[b:ea]) if ea is not None else None
        if after is None:
            if before is None:
                return None
            # The end: where the speech run holding the last word stops.
            run = next((r for r in speech if r[1] >= before), speech[-1])
            nxt = next((r[0] for r in speech if r[0] > run[1]), dur)
            return run[1], nxt
        # Not before most of the last word heard has been said.
        lo = before + max(0.2, 0.6 * pace * letters[eb]) if before is not None else after - 2.5
        lo = max(lo if ea == b else min(lo, after - 1.0), after - 8, 0)
        return valley(lo, max(after + 0.35, lo + 0.1))

    cuts = {}
    for a in want:
        for b in (first_word[a], first_word[a + 1]):
            if b not in cuts:
                cuts[b] = cut_at(b)

    def slice_of(a, cut=cuts):
        c0, c1 = cut.get(first_word[a]), cut.get(first_word[a + 1])
        if c0 is None or c1 is None:
            return None
        # Joined āyāt (no silence, a point): overlap a little rather than clip a letter.
        start = max((c0[0] + c0[1]) / 2, c0[1] - LEAD_S) if c0[1] > c0[0] else c0[0] - JOIN_S
        end = min((c1[0] + c1[1]) / 2, c1[0] + TAIL_S) if c1[1] > c1[0] else c1[0] + JOIN_S
        return (start, end) if end > start + 0.2 else None

    def hear(segments):
        """{key: (start, end)} → {key: text heard}."""
        paths = {}
        for key, (a0, a1) in segments.items():
            p = os.path.join(WORK, f"{rid}-{sura:03d}-{key}.wav")
            write_wav(p, pcm[int(a0 * RATE):int(a1 * RATE)])
            paths[key] = p
        got = transcribe(list(paths.values()))
        return {key: got[p] for key, p in paths.items()}

    def hear_cuts(cuts_by_key):
        """{key: (start, end)} → {key: (heard, heard at its end or None)}."""
        segs = {}
        for key, (a0, a1) in cuts_by_key.items():
            segs.update(parts(str(key), a0, a1))
        got = hear(segs)
        return {key: (got[str(key)], got.get(f"{key}~end")) for key in cuts_by_key}

    def judge(a, h):
        return verdict(text, sura, a, h[0], h[1])

    # Listen to every cut; a boundary where an āya doesn't open or close on its own words is
    # moved to the quiet point nearby that makes both sides read right.
    first = hear_cuts({a: sl for a in want if (sl := slice_of(a))})
    suspects = set()
    for a in want:
        v = judge(a, first[a]) if a in first else None
        if v is None or not v[0]:
            if v is None or not v[3]:
                suspects.add(first_word[a])
            if v is None or not v[4]:
                suspects.add(first_word[a + 1])
    trials, options = {}, {}
    for b in sorted(suspects):
        if cuts.get(b) is None:
            continue
        cur = cuts[b]
        mid = (cur[0] + cur[1]) / 2
        i0, i1 = max(1, int((mid - 1.5) * 50)), min(len(broad) - 1, int((mid + 1.5) * 50))
        idx = sorted((i for i in range(i0, i1) if broad[i] <= broad[i - 1] and broad[i] <= broad[i + 1]), key=lambda i: broad[i])
        picked = []
        for i in idx:
            if all(abs(i - j) >= 10 for j in picked):
                picked.append(i)
            if len(picked) == 6:
                break
        options[b] = [cur] + [around(i) for i in picked]
        sides = [x for x in (aya_of[b - 1] if b > 0 else None, aya_of[b] if b < len(exp) else None) if x in want]
        for n, c in enumerate(options[b]):
            trial = dict(cuts)
            trial[b] = c
            for a in sides:
                sl = slice_of(a, trial)
                if sl:
                    trials[(b, n, a)] = sl
    heard_trials = hear_cuts({f"b{b}-{n}-{a}": sl for (b, n, a), sl in trials.items()}) if trials else {}
    repaired = []
    for b, opts in options.items():
        sides = [x for x in (aya_of[b - 1] if b > 0 else None, aya_of[b] if b < len(exp) else None) if x in want]
        def score(n):
            total = 0.0
            for a in sides:
                key = f"b{b}-{n}-{a}"
                total += judge(a, heard_trials[key])[1] if key in heard_trials else -5
            return total
        best = max(range(len(opts)), key=lambda n: (score(n), n == 0))
        if best != 0 and score(best) > score(0):
            repaired.append(f"boundary before {sura}:{aya_of[b] if b < len(exp) else 'end'}: {opts[0][0]:.2f} → {opts[best][0]:.2f}")
            cuts[b] = opts[best]

    out = {}
    report = []
    for a in sorted(want):
        sl = slice_of(a)
        if sl is None:
            report.append(f"{sura}:{a} not found")
            continue
        start, end = sl
        # Frames covering [start, end], plus one before (the bit reservoir of the first).
        per = spf / frate
        i0 = max(0, int(start / per) - 1)
        i1 = min(len(fr) - 1, int(end / per) + 1)
        out[f"{sura}:{a}"] = [fr[i0][0], fr[i1][0] + fr[i1][1]]
        report.append(f"{sura}:{a} {start:8.2f} {end:8.2f}")
    report = repaired + report
    if len(windows) <= 12:
        report = debug + report
    info = {"file": "%03d.mp3" % sura, "size": len(data), "seconds": round(dur, 1), "kbps": kbps}
    stats = f"{sura:3d}: {dur/60:5.1f} min, {len(windows)} windows, {len(at)}/{len(exp)} words placed, kbps {kbps}"
    return out, info, report, stats


# ---------------------------------------------------------------- check

def verdict(text, sura, a, heard, tail_heard=None):
    """
    Does [heard] read as āya sura:a, cut right? (ok, score, letter error, opens right, closes right).
    Opening right: its first word first, not the previous āya's last word before it; closing
    likewise with the next āya's first word. 0.6: a letter misheard in a short word (وصل for فصل)
    isn't a wrong cut. A long āya is heard in two parts ([heard] its start, [tail_heard] its end).
    """
    ref = [skeleton(w) for w in words(text[(sura, a)])]
    hyp = [x for x in (skeleton(w) for w in heard.split()) if x]
    end = hyp if tail_heard is None else [x for x in (skeleton(w) for w in tail_heard.split()) if x]
    if not hyp or not end or not ref:
        return False, -3.0, 1.0, False, False
    before = words(text[(sura, a - 1)])[-1] if a > 1 else (words(text[(1, 1)])[-1] if sura not in (1, 9) else None)
    after = words(text[(sura, a + 1)])[0] if (sura, a + 1) in text else None

    def close(x, w):
        return w is not None and sim(x, skeleton(w)) >= 0.6

    head = sim(hyp[0], ref[0]) >= 0.6 or (len(hyp) > 1 and sim(hyp[1], ref[0]) >= 0.6 and not close(hyp[0], before))
    tail = sim(end[-1], ref[-1]) >= 0.6 or (len(end) > 1 and sim(end[-2], ref[-1]) >= 0.6 and not close(end[-1], after))
    if tail_heard is not None:
        return head and tail, float(head + tail), 0.0, head, tail
    # Letter error (word splits such as يا أيها / يأيها don't count).
    r, h = "".join(ref), "".join(hyp)
    d = list(range(len(h) + 1))
    for i, cr in enumerate(r, 1):
        prev, d[0] = d[0], i
        for j, ch in enumerate(h, 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (cr != ch))
    err = d[len(h)] / max(1, len(r))
    ok = err <= 0.3 and head and tail
    return ok, (head + tail) - err, err, head, tail


LONG_S, PART_S = 20.0, 12.0  # cuts longer than LONG_S are heard as their first and last PART_S


def parts(key, a0, a1):
    """The pieces of a cut to transcribe: whole, or its start and its end."""
    if a1 - a0 <= LONG_S:
        return {key: (a0, a1)}
    return {key: (a0, a0 + PART_S), key + "~end": (a1 - PART_S, a1)}


def check(rid, text, ayat, infos):
    """Cut each āya as the app will, transcribe it and compare with its text."""
    segs, refs = {}, []
    for key, (b0, b1) in ayat.items():
        s, a = map(int, key.split(":"))
        data = open(os.path.join(WORK, f"{rid}-{s:03d}.mp3"), "rb").read()[b0:b1]
        mp3 = os.path.join(WORK, f"check-{rid}-{s:03d}{a:03d}.mp3")
        open(mp3, "wb").write(data)
        pcm = decode(mp3)
        for part, (a0, a1) in parts(key, 0, len(pcm) / RATE).items():
            wav = os.path.join(WORK, f"check-{rid}-{part.replace(':', '-').replace('~', '-')}.wav")
            write_wav(wav, pcm[int(a0 * RATE):int(a1 * RATE)])
            segs[part] = wav
        refs.append((key, s, a))
    heard = transcribe(list(segs.values()))
    bad = []
    for key, s, a in refs:
        h = heard[segs[key]]
        t = heard[segs[key + "~end"]] if key + "~end" in segs else None
        ok, _, err, head, tail = verdict(text, s, a, h, t)
        if not ok:
            bad.append((s, a, err, head, tail, h + (" … " + t if t else "")))
    print(f"\nChecked {len(refs)} āyāt: {len(refs) - len(bad)} fine, {len(bad)} to look at")
    for s, a, err, head, tail, h in bad:
        print(f"  {s}:{a} letters off {err:.2f} start {'ok' if head else 'OFF'} end {'ok' if tail else 'OFF'} | {h[:200]}")
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
