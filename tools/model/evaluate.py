#!/usr/bin/env python3
"""
Transcribes EveryAyah recitations with each converted model (out/*.bin) using whisper.cpp's CLI
and prints word error rate and speed against Tanzil's text, to choose the model the app ships.
Words are compared on their letter skeleton (no ḥarakāt, no alif, one yāʾ), as the app does.
"""
import glob, os, re, subprocess, sys, time, urllib.request

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
CLI = "work/whisper.cpp/build/bin/whisper-cli"
RECITERS = ["Husary_128kbps", "Alafasy_128kbps"]
# Single āyāt and whole short passages (recited in one go, as a user would).
CLIPS = [
    # Short chunks, as the app sends them (a pause ends a chunk): single āyāt, short sūras.
    [(1, 1)], [(1, 2)], [(1, 5)], [(1, 6)], [(1, 7)],
    [(2, 5)], [(2, 257)], [(2, 285)],
    [(3, 18)], [(3, 26)],
    [(9, 128)], [(9, 129)],
    [(36, 1), (36, 2), (36, 3)],
    [(67, 1)], [(67, 2)],
    [(103, 1), (103, 2), (103, 3)],
    [(112, 1), (112, 2), (112, 3), (112, 4)],
    [(113, 1), (113, 2), (113, 3)],
]
# Decoding settings compared (the app decodes greedily, possibly with a shorter audio context).
VARIANTS = {"greedy": ["-bs", "1", "-bo", "1"], "greedy-ac768": ["-bs", "1", "-bo", "1", "-ac", "768"]}


def quran():
    text = {}
    for line in open(os.path.join(ROOT, "app/src/main/assets/quran/quran-uthmani.txt"), encoding="utf-8"):
        p = line.rstrip("\n").split("|")
        if len(p) == 3 and p[0].isdigit():
            text[(int(p[0]), int(p[1]))] = p[2]
    # Tanzil writes each sūra's basmala at the start of its āya 1; EveryAyah's files don't have it.
    basmala = skeleton(text[(1, 1)])
    for (s, a), t in list(text.items()):
        if a == 1 and s not in (1, 9):
            words = t.split(" ")
            if skeleton(" ".join(words[:4])) == basmala:
                text[(s, a)] = " ".join(words[4:])
    return text


def skeleton(s):
    s = re.sub("[ً-ٰٟۖ-ۭـ]", "", s)
    s = re.sub("[اٱأإآ]", "", s).replace("ي", "ى")
    s = re.sub(r"[^ء-يٱ-ۓ ]", " ", s)
    return [w for w in s.split() if w]


def wer(ref, hyp):
    d = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        prev, d[0] = d[0], i
        for j, h in enumerate(hyp, 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (r != h))
    return d[len(hyp)] / max(1, len(ref))


def audio(reciter, clip):
    os.makedirs("audio", exist_ok=True)
    name = f"audio/{reciter}-{clip[0][0]}-{clip[0][1]}-{len(clip)}.wav"
    if os.path.exists(name):
        return name
    parts = []
    for s, a in clip:
        mp3 = f"audio/{reciter}-{s:03d}{a:03d}.mp3"
        if not os.path.exists(mp3):
            urllib.request.urlretrieve(f"https://everyayah.com/data/{reciter}/{s:03d}{a:03d}.mp3", mp3)
        parts.append(mp3)
    inputs = sum((["-i", p] for p in parts), [])
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", *inputs, "-filter_complex",
                    f"concat=n={len(parts)}:v=0:a=1", "-ar", "16000", "-ac", "1", name], check=True)
    return name


def main():
    text = quran()
    models = sorted(glob.glob("out/*.bin"))
    print("models:", *(f"{m} ({os.path.getsize(m) / 1e6:.1f} MB)" for m in models), sep="\n  ")
    for m in models:
        for variant, flags in VARIANTS.items():
            total_ref = total_err = 0.0
            total_audio = total_time = 0.0
            shown_error = False
            print(f"\n=== {m} [{variant}]")
            for reciter in RECITERS:
                for clip in CLIPS:
                    wav = audio(reciter, clip)
                    dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", wav],
                                               capture_output=True, text=True).stdout.strip() or 0)
                    t0 = time.time()
                    out = subprocess.run([CLI, "-m", m, "-f", wav, "-l", "ar", "-nt", "-np", "-t", "4", *flags], capture_output=True, text=True)
                    took = time.time() - t0
                    hyp = out.stdout.strip()
                    ref = skeleton(" ".join(text[k] for k in clip))
                    e = wer(ref, skeleton(hyp))
                    total_ref += len(ref); total_err += e * len(ref); total_audio += dur; total_time += took
                    print(f"{reciter:16} {clip[0][0]}:{clip[0][1]}+{len(clip) - 1:<2} audio {dur:5.1f}s  took {took:5.1f}s  WER {e * 100:5.1f}%  | {hyp[:400]}")
                    if out.returncode != 0 and not shown_error:
                        shown_error = True
                        print("   stderr:", out.stderr[:3000])
            print(f"TOTAL {m} [{variant}]: WER {total_err / max(1, total_ref) * 100:.1f}%  speed {total_audio / max(0.01, total_time):.1f}x realtime")

if __name__ == "__main__":
    sys.exit(main())
