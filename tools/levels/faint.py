#!/usr/bin/env python3
"""
Does bringing faint speech to a normal level help recognition? EveryAyah recitations played at
the level the owner's phone gave in the diagnostics (voice at about −50 dBFS over a −65 dB noise
floor), transcribed by the app's model as heard and after Level.normalize (the app's gain:
loud frames to −20 dBFS, at most +40 dB, no clipping). Run by .github/workflows/levels.yml.
"""
import os, re, subprocess, sys, urllib.request, wave
import numpy as np

CLI = "work/whisper.cpp/build/bin/whisper-cli"
MODEL = "work/ggml-tiny-ar-quran-q8_0.bin"
CLIPS = [(2, 1), (2, 2), (2, 3), (2, 5), (112, 1), (112, 2), (1, 2), (1, 4), (67, 1), (36, 1)]
RECITER = "Alafasy_128kbps"
ROOT = os.path.join(os.path.dirname(__file__), "..", "..")


def skel(s):
    s = re.sub("[ً-ٰٟۖ-ۭـ]", "", s)
    s = re.sub("[اٱأإآء]", "", s).replace("ي", "ى").replace("ة", "ه")
    return "".join(c for c in s if "ء" <= c <= "ي" or c == " ")


def text(s, a):
    for line in open(os.path.join(ROOT, "app/src/main/assets/quran/quran-uthmani.txt"), encoding="utf-8"):
        p = line.rstrip("\n").split("|")
        if len(p) == 3 and p[0] == str(s) and p[1] == str(a):
            t = p[2]
            if a == 1 and s not in (1, 9):
                t = " ".join(t.split()[4:])
            return t


def cer(ref, hyp):
    r, h = skel(ref).replace(" ", ""), skel(hyp).replace(" ", "")
    d = list(range(len(h) + 1))
    for i, cr in enumerate(r, 1):
        prev, d[0] = d[0], i
        for j, ch in enumerate(h, 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (cr != ch))
    return d[len(h)] / max(1, len(r))


def normalize(pcm):
    frames = [pcm[i:i + 320] for i in range(0, len(pcm), 320)]
    rms = sorted(float(np.sqrt(np.mean(f ** 2))) for f in frames if len(f))
    loud = rms[int((len(rms) - 1) * 0.9)]
    peak = float(np.max(np.abs(pcm)))
    gain = max(1.0, min(0.1 / loud, 100.0, 0.98 / peak))
    return pcm * gain, gain


def write(path, pcm):
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
        w.writeframes((np.clip(pcm, -1, 1) * 32767).astype(np.int16).tobytes())


def main():
    os.makedirs("work/levels", exist_ok=True)
    rng = np.random.default_rng(1)
    rows, paths = [], []
    for s, a in CLIPS:
        mp3 = f"work/levels/{s:03d}{a:03d}.mp3"
        urllib.request.urlretrieve(f"https://everyayah.com/data/{RECITER}/{s:03d}{a:03d}.mp3", mp3)
        raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", mp3, "-ar", "16000", "-ac", "1", "-f", "s16le", "-"], capture_output=True, check=True).stdout
        pcm = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768
        pcm = pcm[: 16000 * 20]
        loud = np.sort(np.sqrt(np.mean(pcm[: len(pcm) // 320 * 320].reshape(-1, 320) ** 2, axis=1)))[int(len(pcm) // 320 * 0.9)]
        faint = pcm * (0.006 / loud) + rng.normal(0, 10 ** (-65 / 20), len(pcm)).astype(np.float32)
        boosted, gain = normalize(faint)
        for kind, x in (("original", pcm), ("faint", faint), ("boosted", boosted)):
            p = f"work/levels/{s:03d}{a:03d}-{kind}.wav"
            write(p, x)
            paths.append(p)
        rows.append((s, a, gain))
    args = [CLI, "-m", MODEL, "-l", "ar", "-nt", "-np", "-bs", "1", "-bo", "1", "-mc", "0", "-otxt"]
    for p in paths:
        args += ["-f", p]
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    total = {"original": [], "faint": [], "boosted": []}
    for s, a, gain in rows:
        ref = text(s, a)
        line = [f"{s}:{a} gain {20 * np.log10(gain):4.1f} dB"]
        for kind in total:
            hyp = open(f"work/levels/{s:03d}{a:03d}-{kind}.wav.txt", encoding="utf-8").read().strip()
            e = cer(ref, hyp)
            total[kind].append(e)
            line.append(f"{kind} {e:.2f} «{hyp[:60]}»")
        print(" | ".join(line))
    print("\nletter error, mean:", {k: round(sum(v) / len(v), 3) for k, v in total.items()})


if __name__ == "__main__":
    sys.exit(main())
