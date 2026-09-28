#!/usr/bin/env python3
"""
The reference clip for the app's recognition self-test (Recite, diagnostics): al-Ikhlāṣ 112:1
by Alafasy from everyayah.com as 16 kHz mono 16-bit WAV → app/src/main/assets/diag/112001.wav.
Run by .github/workflows/levels.yml, which commits it.
"""
import os, subprocess, urllib.request

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
dest = os.path.join(ROOT, "app/src/main/assets/diag/112001.wav")
os.makedirs(os.path.dirname(dest), exist_ok=True)
urllib.request.urlretrieve("https://everyayah.com/data/Alafasy_128kbps/112001.mp3", "/tmp/112001.mp3")
subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", "/tmp/112001.mp3", "-ar", "16000", "-ac", "1",
                "-c:a", "pcm_s16le", "-map_metadata", "-1", "-fflags", "+bitexact", dest], check=True)
print(dest, os.path.getsize(dest), "bytes")
