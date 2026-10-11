#!/usr/bin/env python3
"""
Can Recite's recogniser run on the phone's NPU (Snapdragon's Hexagon, through Qualcomm's QNN)?
Step one, on a PC: Tarteel's tiny model's encoder (the part that reads the 30 s of audio: ~80 %
of a reading's time on the phone) exported to ONNX, checked against PyTorch, and timed on the CPU
here for reference. Writes encoder.onnx and mel.npy (a real recitation's input, for checks).
Usage: export.py <out dir>
"""
import os
import sys
import time

import numpy as np
import torch
from transformers import WhisperForConditionalGeneration, WhisperProcessor

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
repo = "tarteel-ai/whisper-tiny-ar-quran"
model = WhisperForConditionalGeneration.from_pretrained(repo).eval()
processor = WhisperProcessor.from_pretrained(repo)
encoder = model.get_encoder()

# The app's reference clip (al-Ikhlās 112:1).
import wave
w = wave.open(os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets", "diag", "112001.wav"))
pcm = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768
mel = processor.feature_extractor(pcm, sampling_rate=16000, return_tensors="np").input_features.astype(np.float32)
np.save(os.path.join(out, "mel.npy"), mel)
print("mel", mel.shape)


class Encoder(torch.nn.Module):
    def __init__(self, e):
        super().__init__()
        self.e = e

    def forward(self, input_features):
        return self.e(input_features).last_hidden_state


wrapped = Encoder(encoder).eval()
with torch.no_grad():
    ref = wrapped(torch.from_numpy(mel)).numpy()
path = os.path.join(out, "encoder.onnx")
torch.onnx.export(wrapped, (torch.from_numpy(mel),), path, input_names=["input_features"], output_names=["last_hidden_state"], opset_version=17, dynamo=False)
print("encoder.onnx", os.path.getsize(path), "bytes; output", ref.shape)

import onnxruntime as ort
sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
got = sess.run(None, {"input_features": mel})[0]
print("ONNX vs PyTorch: max abs diff %.2e (output scale %.2f)" % (np.abs(got - ref).max(), np.abs(ref).max()))
# Raw float32 input and expected output, for Qualcomm's qnn-net-run (npu.yml compares its output).
mel.tofile(os.path.join(out, "mel.raw"))
# The compiled graph takes it frame by frame ([1, 3000, 80], the converter's layout; npu.c too).
np.ascontiguousarray(mel.transpose(0, 2, 1)).tofile(os.path.join(out, "mel_t.raw"))
got.astype(np.float32).tofile(os.path.join(out, "encoder_out.raw"))
# The clip's audio for whisper.cpp (tools/npu/decode.c reads it with an encoder output).
pcm.astype(np.float32).tofile(os.path.join(out, "ref.f32"))
times = []
for _ in range(5):
    t = time.perf_counter()
    sess.run(None, {"input_features": mel})
    times.append((time.perf_counter() - t) * 1000)
print("ONNX Runtime CPU here (%d cores): encoder %.0f ms (best of 5)" % (os.cpu_count(), min(times)))
# The whole model reads the clip as the app does (greedy), as a check the export is the right model.
try:
    with torch.no_grad():
        prompt = processor.get_decoder_prompt_ids(language="arabic", task="transcribe")
        ids = model.generate(torch.from_numpy(mel), forced_decoder_ids=prompt, max_new_tokens=60)
    print("PyTorch reads:", processor.batch_decode(ids, skip_special_tokens=True)[0])
except Exception as e:  # the model's generation config is old: the check is a nicety
    print("PyTorch reading skipped:", e)
