#!/usr/bin/env python3
"""
Inputs to quantize Recite's encoder for the SM7750's NPU (it runs no half floats), and to check it.
Calibration: EveryAyah recitations by several reciters, whole āyāt (cut at 20 s, as Recite's
chunks) and their first seconds (Recite's partial readings), at voice levels from loud to faint,
as log-mel inputs (whisper's feature extractor, padded to 30 s): written both as ONNX has them
(80 rows of 3000 frames, "ncf") and frame by frame ("nfc", the converted graph's layout), with a
list of each for qnn-onnx-converter's --input_list. Validation: āyāt kept out of the calibration,
each with its audio (float32, for whisper.cpp), its log-mel and the ONNX encoder's output.
Never the owner's recordings: the quantized encoder is published.
Usage: calibrate.py <out dir> (the export's encoder.onnx in it)
"""
import os
import subprocess
import sys
import urllib.request

import numpy as np
import onnxruntime as ort
from transformers import WhisperProcessor

out = sys.argv[1]
RATE = 16000
UA = "Mozilla/5.0 (X11; Linux x86_64) HFD-npu-calibration"
CALIBRATION = [
    ("Alafasy_128kbps", 2, 255), ("Alafasy_128kbps", 3, 26), ("Alafasy_128kbps", 36, 1), ("Alafasy_128kbps", 112, 2),
    ("Husary_128kbps", 1, 2), ("Husary_128kbps", 2, 285), ("Husary_128kbps", 18, 1), ("Husary_128kbps", 114, 1),
    ("MaherAlMuaiqly128kbps", 1, 5), ("MaherAlMuaiqly128kbps", 2, 2), ("MaherAlMuaiqly128kbps", 3, 18), ("MaherAlMuaiqly128kbps", 67, 1),
    ("Yasser_Ad-Dussary_128kbps", 1, 7), ("Yasser_Ad-Dussary_128kbps", 2, 3), ("Yasser_Ad-Dussary_128kbps", 3, 2), ("Yasser_Ad-Dussary_128kbps", 113, 1),
    ("Minshawy_Murattal_128kbps", 2, 286), ("Minshawy_Murattal_128kbps", 3, 1), ("Minshawy_Murattal_128kbps", 18, 10), ("Minshawy_Murattal_128kbps", 112, 3),
    ("Abdul_Basit_Murattal_192kbps", 1, 1), ("Abdul_Basit_Murattal_192kbps", 2, 4), ("Abdul_Basit_Murattal_192kbps", 3, 27), ("Abdul_Basit_Murattal_192kbps", 59, 22),
]
VALIDATION = [("Husary_128kbps", 3, 26), ("MaherAlMuaiqly128kbps", 2, 255)]


def audio(folder, sura, aya):
    path = os.path.join(out, "mp3", folder, f"{sura:03d}{aya:03d}.mp3")
    if not os.path.exists(path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        req = urllib.request.Request(f"https://everyayah.com/data/{folder}/{sura:03d}{aya:03d}.mp3", headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=120) as r, open(path + ".part", "wb") as f:
            f.write(r.read())
        os.replace(path + ".part", path)
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(RATE), "-f", "f32le", "-"],
                         check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32)[:20 * RATE].copy()


processor = WhisperProcessor.from_pretrained("tarteel-ai/whisper-tiny-ar-quran")


def mel(pcm):
    return processor.feature_extractor(pcm, sampling_rate=RATE, return_tensors="np").input_features.astype(np.float32)


rng = np.random.default_rng(7)
for layout in ("ncf", "nfc"):
    os.makedirs(os.path.join(out, "calib", layout), exist_ok=True)
lists = {"ncf": [], "nfc": []}
for folder, sura, aya in CALIBRATION:
    try:
        pcm = audio(folder, sura, aya)
    except Exception as e:  # a reciter missing an āya: the others are enough
        print("skipped", folder, sura, aya, e)
        continue
    peak = float(np.abs(pcm).max()) or 1.0
    first = pcm[:int(rng.uniform(0.8, 5.0) * RATE)]
    for k, clip in enumerate((pcm, first)):
        x = clip / peak * float(10 ** rng.uniform(-2.0, -0.1))   # -40 to -2 dB below full scale
        m = mel(x)
        name = f"{folder}-{sura:03d}{aya:03d}-{k}"
        for layout, data in (("ncf", m), ("nfc", np.ascontiguousarray(m.transpose(0, 2, 1)))):
            path = os.path.abspath(os.path.join(out, "calib", layout, name + ".raw"))
            data.tofile(path)
            lists[layout].append(path)
for layout, paths in lists.items():
    with open(os.path.join(out, "calib", f"list_{layout}.txt"), "w") as f:
        f.write("\n".join(paths) + "\n")
print("calibration inputs:", len(lists["ncf"]))

encoder = ort.InferenceSession(os.path.join(out, "encoder.onnx"))
os.makedirs(os.path.join(out, "val"), exist_ok=True)
for i, (folder, sura, aya) in enumerate(VALIDATION):
    pcm = audio(folder, sura, aya)
    pcm = pcm / float(np.abs(pcm).max()) * 0.3
    m = mel(pcm)
    pcm.astype(np.float32).tofile(os.path.join(out, "val", f"v{i}.f32"))
    m.tofile(os.path.join(out, "val", f"v{i}.mel.raw"))
    encoder.run(None, {"input_features": m})[0].astype(np.float32).tofile(os.path.join(out, "val", f"v{i}.onnx.raw"))
    print(f"validation v{i}: {folder} {sura}:{aya}, {len(pcm) / RATE:.1f} s")
