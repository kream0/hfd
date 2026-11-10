#!/usr/bin/env python3
"""How long a CTC model (FastConformer, ONNX, int8) takes to read an utterance on this CPU:
speed.py <model dir> <clip.wav>   (the directory holds onnx/model_int8.onnx, onnx/fb.bin, vocab.json)

The clip is repeated to 3, 6, 12 and 20 s; each length is read five times with 1, 2 and 4 threads
(best and median, features included). Prints what it reads, to see that the model runs right.
"""
import json, statistics, sys, time, wave
import numpy as np
import onnxruntime as ort

root, clip = sys.argv[1], sys.argv[2]
fb = np.fromfile(root + "/onnx/fb.bin", dtype=np.float32).reshape(80, 257).astype(np.float64)
win = np.zeros(512); win[56:456] = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(400) / 399)
vocab = json.load(open(root + "/vocab.json", encoding="utf-8"))
BLANK = len(vocab)


def features(x):
    """NeMo's front end: pre-emphasis, 25 ms Hann windows every 10 ms, 80 mels, log, per-feature normalisation."""
    x = x.astype(np.float64)
    e = np.concatenate([x[:1], x[1:] - 0.97 * x[:-1]])
    length = len(x) // 160
    pad = np.concatenate([np.zeros(256), e, np.zeros(256 + 512)])
    seg = pad[np.arange(length + 1)[:, None] * 160 + np.arange(512)[None, :]]
    mel = np.log((np.abs(np.fft.rfft(seg * win, axis=1)) ** 2) @ fb.T + 2.0 ** -24).T
    v = mel[:, :length]
    mean = v.mean(axis=1, keepdims=True)
    std = np.sqrt(((v - mean) ** 2).sum(axis=1, keepdims=True) / max(1, length - 1)) + 1e-5
    out = (mel - mean) / std
    out[:, length:] = 0
    return out.astype(np.float32), length


def greedy(lp):
    out, prev = [], -1
    for i in lp.argmax(axis=1):
        if i != prev and i != BLANK: out.append(vocab[i])
        prev = i
    return "".join(out)


with wave.open(clip) as w:
    assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2
    pcm = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768

for threads in (1, 2, 4):
    so = ort.SessionOptions(); so.intra_op_num_threads = threads
    t0 = time.perf_counter()
    sess = ort.InferenceSession(root + "/onnx/model_int8.onnx", so, providers=["CPUExecutionProvider"])
    print(f"== threads={threads}: model loaded in {1000 * (time.perf_counter() - t0):.0f} ms")
    for seconds in (3, 6, 12, 20):
        x = np.tile(pcm, int(np.ceil(seconds * 16000 / len(pcm))))[: seconds * 16000]
        times, text = [], ""
        for i in range(6):
            t0 = time.perf_counter()
            f, n = features(x)
            t1 = time.perf_counter()
            lp, ol = sess.run(None, {"features": f[None], "lengths": np.array([n], dtype=np.int64)})
            t2 = time.perf_counter()
            if i: times.append((1000 * (t2 - t0), 1000 * (t1 - t0)))
            text = greedy(lp[0, : int(ol[0])])
        total = [t[0] for t in times]
        print(f"   {seconds:2d} s: best {min(total):.0f} median {statistics.median(total):.0f} ms (features {statistics.median(t[1] for t in times):.0f} ms)  {text[:60]}")
