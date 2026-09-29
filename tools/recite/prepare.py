#!/usr/bin/env python3
"""
Recordings for the Recite bench (core/src/test/kotlin/app/hfd/core/recite/ReciteBench.kt), made
from EveryAyah's per-āya recitations, whose text is known exactly: each case is a passage recited
through (the basmala first at the start of a sūra), with the short pauses between āyāt of someone
reciting from memory, brought down to what the owner's phone microphone gives (voice around
−38 dBFS at its loudest over low-pitched noise around −60 dBFS, from the diagnostics).
Writes <out>/<case>.wav (16-bit mono 16 kHz) and <out>/cases.tsv. Usage: prepare.py <out>
"""
import os
import subprocess
import sys
import urllib.request

import numpy as np

RATE = 16000
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
# name: pause between āyāt (s), tempo, voice level (dBFS, loud frames), room noise (dBFS)
VARIANTS = {
    "flow": (0.3, 1.0, -38, -60),
    "fast": (0.15, 1.25, -38, -60),
    "pauses": (1.2, 1.0, -38, -60),
    "loud": (0.3, 1.0, -20, -60),
    # The owner's room on 29 September: noise at −47 dB for a voice around −37 dB.
    "noisy": (0.3, 1.0, -35, -47),
}
CASES = [(p, r, "flow") for p in PASSAGES if p != "imran-opening" for r in RECITERS] + [
    ("imran-opening", "husary", "flow"),
    ("imran-opening", "maher", "noisy"),
    ("fatiha", "maher", "noisy"),
    ("baqara-opening", "alafasy", "noisy"),
    ("ikhlas", "dossary", "noisy"),
    ("fatiha", "maher", "fast"),
    ("baqara-opening", "maher", "fast"),
    ("kursi", "dossary", "fast"),
    ("fatiha", "alafasy", "pauses"),
    ("baqara-opening", "husary", "pauses"),
    ("fatiha", "husary", "loud"),
    ("baqara-opening", "maher", "skip:2:3"),
    ("fatiha", "alafasy", "skip:1:4"),
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


def decode(path, tempo):
    af = "highpass=f=80,lowpass=f=7000" + (f",atempo={tempo}" if tempo != 1.0 else "")
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-af", af, "-ac", "1", "-ar", str(RATE), "-f", "f32le", "-"],
        check=True, capture_output=True,
    ).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()


def frame_rms(x, hop=320):
    n = len(x) // hop
    return np.sqrt(np.mean(x[:n * hop].reshape(n, hop) ** 2, axis=1) + 1e-12)


def trim(x):
    """Without the silence before and after the voice (100 ms kept)."""
    e = frame_rms(x)
    on = np.where(e > e.max() * 0.03)[0]
    if len(on) == 0:
        return x
    a, b = max(0, on[0] - 5), min(len(e), on[-1] + 6)
    return x[a * 320:b * 320]


def noise(n, rng, db):
    """Low-pitched room noise at [db] dBFS (most of the phone's noise energy is below 300 Hz)."""
    spectrum = np.fft.rfft(rng.standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / RATE)
    y = np.fft.irfft(spectrum * (1 / np.sqrt(1 + (f / 150) ** 2) + 0.1), n).astype(np.float32)
    return y / np.sqrt(np.mean(y ** 2)) * 10 ** (db / 20)


def main():
    out = sys.argv[1]
    cache = os.path.join(out, "mp3")
    os.makedirs(out, exist_ok=True)
    rng = np.random.default_rng(7)
    lines = []
    for passage, reciter, variant in CASES:
        sura, first, last = PASSAGES[passage]
        folder = RECITERS[reciter]
        skip = set()
        if variant.startswith("skip:"):
            s, a = variant[5:].split(":")
            skip = {(int(s), int(a))}
            gap, tempo, level, room = 0.3, 1.0, -38, -60
        else:
            gap, tempo, level, room = VARIANTS[variant]
        parts = [np.zeros(int(0.6 * RATE), np.float32)]
        spans = []
        t = 0.6
        # The basmala before a sūra's first āya (al-Fātiḥa's is its āya 1; at-Tawba has none).
        if first == 1 and sura not in (1, 9):
            b = trim(decode(fetch(folder, 1, 1, cache), tempo))
            parts += [b, np.zeros(int(gap * RATE), np.float32)]
            t += (len(b) + int(gap * RATE)) / RATE
        for aya in range(first, last + 1):
            if (sura, aya) in skip:
                continue
            x = trim(decode(fetch(folder, sura, aya, cache), tempo))
            spans.append(f"{sura}:{aya}@{t:.2f}-{t + len(x) / RATE:.2f}")
            parts += [x, np.zeros(int(gap * RATE), np.float32)]
            t += (len(x) + int(gap * RATE)) / RATE
        parts.append(np.zeros(int(0.3 * RATE), np.float32))
        voice = np.concatenate(parts)
        e = frame_rms(voice)
        loud = np.percentile(e[e > e.max() * 0.03], 90)
        pcm = voice * (10 ** (level / 20) / loud) + noise(len(voice), rng, room)
        pcm = np.clip(pcm, -1, 1)
        name = f"{passage}-{reciter}-{variant.replace(':', '')}"
        with open(os.path.join(out, name + ".wav"), "wb") as f:
            data = (pcm * 32767).astype("<i2").tobytes()
            f.write(b"RIFF" + (36 + len(data)).to_bytes(4, "little") + b"WAVE")
            f.write(b"fmt " + (16).to_bytes(4, "little") + (1).to_bytes(2, "little") + (1).to_bytes(2, "little"))
            f.write(RATE.to_bytes(4, "little") + (RATE * 2).to_bytes(4, "little") + (2).to_bytes(2, "little") + (16).to_bytes(2, "little"))
            f.write(b"data" + len(data).to_bytes(4, "little") + data)
        lines.append(f"{name}\t{sura}:{first}-{last}\t{name}.wav\t{','.join(spans)}")
        print(f"{name}: {len(pcm) / RATE:.1f} s", flush=True)
    with open(os.path.join(out, "cases.tsv"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
