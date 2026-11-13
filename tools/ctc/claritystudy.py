#!/usr/bin/env python3
"""What tells a voice the model can't read from a clear one, and from a room's noise?
claritystudy.py <model dir> <bench dir (tools/recite/prepare.py)>

Per āya of each bench case, and of the same sound made worse (a deep pocket: everything over
300 Hz taken away; a car: low noise alone, and over the voice): how sure the model is of the
letters it writes (the mean probability of the likeliest symbol over the frames it writes a
letter in), how many letters it writes a second, and the share above 1 kHz (Clarity.isMuffled).
For MuffledWarning: the owner (13 Nov) was told of a muffled microphone while pausing in a car.
"""
import json, random, re, sys, wave
import numpy as np
import onnxruntime as ort

root, bench, tanzil = sys.argv[1], sys.argv[2], sys.argv[3]
fb = np.fromfile(root + "/onnx/fb.bin", dtype=np.float32).reshape(80, 257).astype(np.float64)
win = np.zeros(512); win[56:456] = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(400) / 399)
vocab = json.load(open(root + "/vocab.json", encoding="utf-8"))
index = {c: i for i, c in enumerate(vocab)}
BLANK = len(vocab)
sess = ort.InferenceSession(root + "/onnx/model_int8.onnx", providers=["CPUExecutionProvider"])


def features(x):
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


def logprobs(pcm):
    f, n = features(pcm)
    lp, ol = sess.run(None, {"features": f[None], "lengths": np.array([n], dtype=np.int64)})
    return lp[0, :int(ol[0])]



def clarity(pcm):
    lp = logprobs(pcm)
    best = lp.argmax(axis=1)
    written = best != BLANK
    letters = int((np.diff(np.concatenate([[-1], best])) != 0)[written].sum())
    sure = float(np.exp(lp.max(axis=1))[written].mean()) if written.any() else float('nan')
    return sure, letters

def lowpass(x, hz):
    f = np.fft.rfft(x); k = np.fft.rfftfreq(len(x), 1 / 16000)
    f[k > hz] *= 0.02
    return np.fft.irfft(f, len(x)).astype(np.float32)

def car(n, level):
    rnd = np.random.RandomState(7)
    f = np.fft.rfft(rnd.randn(n)); k = np.fft.rfftfreq(n, 1 / 16000)
    f *= 1 / np.maximum(k, 20.0)          # a rumble: most of it under 200 Hz
    x = np.fft.irfft(f, n); x = x / np.abs(x).max() * level
    return x.astype(np.float32)

def norm(x):
    peak = np.abs(x).max()
    return x * min(100.0, 0.5 / peak) if peak > 0 else x

rows = []
for line in open(bench + '/cases.tsv', encoding='utf-8'):
    f = line.rstrip('\n').split('\t')
    case = f[0]
    if '-swap' in case or '-skip' in case or '-again' in case: continue
    with wave.open(bench + '/' + f[2]) as w: pcm = np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(np.float32) / 32768
    kind = [k for k in ('flow', 'noisy', 'fast', 'pauses', 'loud', 'muffled', 'rumble', 'pocket', 'sco') if '-' + k in case][0]
    for seg in f[3].split(','):
        ref, span = seg.split('@'); span = span.split('~')[0]; a, b = map(float, span.split('-'))
        x = pcm[max(0, int((a - 0.2) * 16000)):int((b + 0.3) * 16000)]
        if len(x) < 16000: continue
        secs = len(x) / 16000
        variants = [(kind, x)]
        if kind == 'flow':
            variants += [('flow under 300 Hz', lowpass(x, 300)), ('flow under 150 Hz', lowpass(x, 150)),
                         ('flow in a car', x / max(1e-9, np.abs(x).max()) * 0.3 + car(len(x), 0.3)),
                         ('flow faint in a car', x / max(1e-9, np.abs(x).max()) * 0.05 + car(len(x), 0.3)),
                         ('car alone', car(len(x), 0.3))]
        for name, y in variants:
            sure, letters = clarity(norm(y))
            rows.append((name, sure, letters / secs))
names = []
for r in rows:
    if r[0] not in names: names.append(r[0])
for name in names:
    sure = np.array([r[1] for r in rows if r[0] == name]); rate = np.array([r[2] for r in rows if r[0] == name])
    ok = ~np.isnan(sure)
    q = lambda v, p: float(np.percentile(v, p)) if len(v) else float('nan')
    print(f"{name:22s} n={len(sure):3d}  wrote nothing {int((~ok).sum()):3d}  sure p10 {q(sure[ok],10):.2f} p50 {q(sure[ok],50):.2f} p90 {q(sure[ok],90):.2f}   symbols/s p10 {q(rate,10):4.1f} p50 {q(rate,50):4.1f} p90 {q(rate,90):4.1f}")
