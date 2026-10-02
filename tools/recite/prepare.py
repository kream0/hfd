#!/usr/bin/env python3
"""
Recordings for the Recite bench (core/src/test/kotlin/app/hfd/core/recite/ReciteBench.kt), made
from EveryAyah's per-āya recitations, whose text is known exactly: each case is a passage recited
through (the basmala first at the start of a sūra), with the short pauses between āyāt of someone
reciting from memory, brought down to what the owner's phone microphone gives (voice around
−38 dBFS at its loudest over low-pitched noise around −60 dBFS, from the diagnostics).
Writes <out>/<case>.wav (16-bit mono 16 kHz) and <out>/cases.tsv. Usage: prepare.py <out>
"""
import json
import os
import subprocess
import sys
import urllib.request

import numpy as np

RATE = 16000
# When each word starts in each reciter's āyāt (tools/words/words.py, the app's word highlighting):
# the bench times how soon each word shows from when it was said, not from a guess.
WORDS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "assets", "audio", "words")
UA = "Mozilla/5.0 (X11; Linux x86_64) HFD-recite-bench"
RECITERS = {
    "maher": "MaherAlMuaiqly128kbps",
    "alafasy": "Alafasy_128kbps",
    "husary": "Husary_128kbps",
    "dossary": "Yasser_Ad-Dussary_128kbps",
}
PASSAGES = {
    "ikhlas": (112, 1, 4),
    "fatiha": (1, 1, 7),
    "baqara-opening": (2, 1, 5),
    "kursi": (2, 255, 255),
    "nas": (114, 1, 6),
    "imran-opening": (3, 1, 9),
}
# How the voice reaches the phone: pause between āyāt (s), tempo, voice level (dBFS, loud
# frames), room noise (dBFS), an ffmpeg filter on the voice, low rumble (dBFS).
VARIANTS = {
    "flow": dict(gap=0.3, tempo=1.0, level=-38, room=-60),
    "fast": dict(gap=0.15, tempo=1.25, level=-38, room=-60),
    "pauses": dict(gap=1.2, tempo=1.0, level=-38, room=-60),
    "loud": dict(gap=0.3, tempo=1.0, level=-20, room=-60),
    # The owner's room on 29 September: noise at −47 dB for a voice around −37 dB.
    "noisy": dict(gap=0.3, tempo=1.0, level=-35, room=-47),
    # The owner's later attempts: 94–99 % of the energy below 300 Hz, none above 1 kHz: a
    # covered microphone (pocket, hand)…
    "muffled": dict(gap=0.3, tempo=1.0, level=-40, room=-58, af="lowpass=f=700,lowpass=f=700"),
    # …or handling / breath rumble louder than the voice.
    "rumble": dict(gap=0.3, tempo=1.0, level=-38, room=-60, rumble=-32),
    # The owner's session of 29 Sept, 21:32 (earbuds on, the phone away from the mouth): the voice
    # at −50 dB with 95 % of it below 300 Hz, under handling rumble.
    "pocket": dict(gap=0.3, tempo=1.0, level=-50, room=-64, rumble=-42, af="lowpass=f=400,lowpass=f=400,lowpass=f=400"),
    # Earbuds' microphone on the classic Bluetooth call link (SCO, CVSD): 8 kHz, 300–3400 Hz, at
    # the level of a microphone near the mouth. (LE Audio and mSBC keep 16 kHz: like "flow".)
    "sco": dict(gap=0.3, tempo=1.0, level=-28, room=-62, af="highpass=f=300,lowpass=f=3400,aresample=8000,aresample=16000"),
}
# (passage, reciter, variant, the āyāt recited in order: all by default). Restarting, repeating
# and skipping are the reciter's, not mistakes of the app: the recited words should all end right.
CASES = [(p, r, "flow", None) for p in PASSAGES if p != "imran-opening" for r in RECITERS] + [
    ("imran-opening", "husary", "flow", None),
    ("imran-opening", "maher", "noisy", None),
    ("fatiha", "maher", "noisy", None),
    ("baqara-opening", "alafasy", "noisy", None),
    ("ikhlas", "dossary", "noisy", None),
    ("fatiha", "maher", "fast", None),
    ("baqara-opening", "maher", "fast", None),
    ("kursi", "dossary", "fast", None),
    ("fatiha", "alafasy", "pauses", None),
    ("baqara-opening", "husary", "pauses", None),
    ("fatiha", "husary", "loud", None),
    ("baqara-opening", "maher", "flow", [1, 2, 4, 5]),
    ("fatiha", "alafasy", "flow", [1, 2, 3, 5, 6, 7]),
    ("imran-opening", "alafasy", "flow", [1, 2, 1, 2, 3, 4]),
    ("ikhlas", "husary", "flow", [1, 2, 1, 2, 3, 4]),
    ("baqara-opening", "dossary", "flow", [1, 2, 3, 3, 4, 5]),
    ("fatiha", "maher", "flow", [1, 2, 3, 4, 5, 4, 5, 6, 7]),
    ("imran-opening", "maher", "muffled", [1, 2, 3, 4]),
    ("fatiha", "alafasy", "muffled", None),
    ("baqara-opening", "husary", "muffled", None),
    ("imran-opening", "husary", "rumble", [1, 2, 3, 4]),
    ("fatiha", "dossary", "rumble", None),
    ("baqara-opening", "alafasy", "rumble", None),
    ("fatiha", "alafasy", "muffled", [1, 2, 3, 5, 6, 7]),
    ("ikhlas", "husary", "muffled", [1, 2, 1, 2, 3, 4]),
    ("imran-opening", "maher", "pocket", [1, 2, 3, 4]),
    ("fatiha", "husary", "pocket", None),
    ("imran-opening", "maher", "sco", None),
    ("fatiha", "alafasy", "sco", None),
    ("baqara-opening", "husary", "sco", None),
    ("ikhlas", "dossary", "sco", None),
]


def fetch(folder, sura, aya, cache):
    path = os.path.join(cache, folder, f"{sura:03d}{aya:03d}.mp3")
    if not os.path.exists(path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        req = urllib.request.Request(f"https://everyayah.com/data/{folder}/{sura:03d}{aya:03d}.mp3", headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=120) as r, open(path + ".part", "wb") as f:
            f.write(r.read())
        os.replace(path + ".part", path)
    return path


def decode(path, tempo, extra=None):
    af = "highpass=f=80,lowpass=f=7000" + (f",atempo={tempo}" if tempo != 1.0 else "") + (f",{extra}" if extra else "")
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-af", af, "-ac", "1", "-ar", str(RATE), "-f", "f32le", "-"],
        check=True, capture_output=True,
    ).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()


def frame_rms(x, hop=320):
    n = len(x) // hop
    return np.sqrt(np.mean(x[:n * hop].reshape(n, hop) ** 2, axis=1) + 1e-12)


def trim(x):
    """Without the silence before and after the voice (100 ms kept); and the samples cut before it."""
    e = frame_rms(x)
    on = np.where(e > e.max() * 0.03)[0]
    if len(on) == 0:
        return x, 0
    a, b = max(0, on[0] - 5), min(len(e), on[-1] + 6)
    return x[a * 320:b * 320], a * 320


def word_starts(reciter):
    """{"S:A": [centiseconds from the start of the āya's mp3, per word]}, or {} without timings."""
    try:
        return json.load(open(os.path.join(WORDS, reciter + ".json"), encoding="utf-8"))["words"]
    except (OSError, ValueError, KeyError):
        return {}


def noise(n, rng, db, corner=150, floor=0.1):
    """Low-pitched room noise at [db] dBFS (most of the phone's noise energy is below 300 Hz)."""
    spectrum = np.fft.rfft(rng.standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / RATE)
    y = np.fft.irfft(spectrum * (1 / np.sqrt(1 + (f / corner) ** 2) + floor), n).astype(np.float32)
    return y / np.sqrt(np.mean(y ** 2)) * 10 ** (db / 20)


def rumble(n, rng, db):
    """Handling / breath rumble: 20–150 Hz, varying in strength like a hand moving."""
    y = noise(n, rng, 0, corner=60, floor=0.0)
    f = np.fft.rfftfreq(n, 1 / RATE)
    spectrum = np.fft.rfft(y)
    spectrum[(f < 20) | (f > 150)] = 0
    y = np.fft.irfft(spectrum, n).astype(np.float32)
    swell = 0.6 + 0.4 * np.sin(np.arange(n) / RATE * 2 * np.pi * 0.7)
    y = y * swell
    return y / np.sqrt(np.mean(y ** 2)) * 10 ** (db / 20)


def bands(x):
    """Share of the energy in 0–300 Hz / 300–1k / 1–2k / 2–4k / 4–8k, as the app's AudioStats."""
    s = np.abs(np.fft.rfft(x)) ** 2
    f = np.fft.rfftfreq(len(x), 1 / RATE)
    edges = [0, 300, 1000, 2000, 4000, 8001]
    e = [s[(f >= a) & (f < b)].sum() for a, b in zip(edges, edges[1:])]
    return "/".join(str(round(100 * v / sum(e))) for v in e)


def main():
    out = sys.argv[1]
    cache = os.path.join(out, "mp3")
    os.makedirs(out, exist_ok=True)
    rng = np.random.default_rng(7)
    lines = []
    for passage, reciter, variant, script in CASES:
        sura, first, last = PASSAGES[passage]
        folder = RECITERS[reciter]
        starts = word_starts(reciter)
        v = VARIANTS[variant]
        gap, tempo = v["gap"], v["tempo"]
        order = script or list(range(first, last + 1))
        parts = [np.zeros(int(0.6 * RATE), np.float32)]
        spans = []
        t = 0.6
        # The basmala before a sūra's first āya (al-Fātiḥa's is its āya 1; at-Tawba has none).
        if order[0] == 1 and sura not in (1, 9):
            b = trim(decode(fetch(folder, 1, 1, cache), tempo, v.get("af")))[0]
            parts += [b, np.zeros(int(gap * RATE), np.float32)]
            t += (len(b) + int(gap * RATE)) / RATE
        for aya in order:
            x, lead = trim(decode(fetch(folder, sura, aya, cache), tempo, v.get("af")))
            end = t + len(x) / RATE
            # Its words' starts here: the mp3's times, at the tempo, less what trim cut before.
            words = [min(end, max(t, t + c / 100 / tempo - lead / RATE)) for c in starts.get(f"{sura}:{aya}", [])]
            spans.append(f"{sura}:{aya}@{t:.2f}-{end:.2f}" + ("~" + ";".join(f"{w:.2f}" for w in words) if words else ""))
            parts += [x, np.zeros(int(gap * RATE), np.float32)]
            t += (len(x) + int(gap * RATE)) / RATE
        parts.append(np.zeros(int(0.3 * RATE), np.float32))
        voice = np.concatenate(parts)
        e = frame_rms(voice)
        loud = np.percentile(e[e > e.max() * 0.03], 90)
        pcm = voice * (10 ** (v["level"] / 20) / loud) + noise(len(voice), rng, v["room"])
        if "rumble" in v:
            pcm = pcm + rumble(len(voice), rng, v["rumble"])
        pcm = np.clip(pcm, -1, 1)
        tag = "" if script is None else "-" + ("skip" if len(set(order)) == len(order) else "again") + "".join(map(str, order))
        name = f"{passage}-{reciter}-{variant}{tag}"
        with open(os.path.join(out, name + ".wav"), "wb") as f:
            data = (pcm * 32767).astype("<i2").tobytes()
            f.write(b"RIFF" + (36 + len(data)).to_bytes(4, "little") + b"WAVE")
            f.write(b"fmt " + (16).to_bytes(4, "little") + (1).to_bytes(2, "little") + (1).to_bytes(2, "little"))
            f.write(RATE.to_bytes(4, "little") + (RATE * 2).to_bytes(4, "little") + (2).to_bytes(2, "little") + (16).to_bytes(2, "little"))
            f.write(b"data" + len(data).to_bytes(4, "little") + data)
        lines.append(f"{name}\t{sura}:{first}-{last}\t{name}.wav\t{','.join(spans)}")
        print(f"{name}: {len(pcm) / RATE:.1f} s, bands {bands(pcm)}", flush=True)
    with open(os.path.join(out, "cases.tsv"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
