#!/usr/bin/env python3
"""Does holding a text to the CTC model's frames tell a short vowel said differently, or one letter?
vowelstudy.py <model dir> <bench dir (tools/recite/prepare.py)> <quran-uthmani.txt>

As coststudy.py (its front end and alignment), per āya of each bench case: the true text held to
the frames (every word said), then the text with, in one word, a short vowel changed for another
(fatḥa, ḍamma, kasra) or a consonant for another: what the reciter did not say. A word's cost is
its worst character's (CtcRecognizer). Prints how the words said and the changed ones spread, and
what a bar on every word of an utterance read right would flag: for a pronunciation check
(ReciteLevel.TAJWEED), 13 Nov.
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


random.seed(1)
quran={}
for l in open(tanzil, encoding='utf-8'):
    p=l.rstrip('\n').split('|')
    if len(p)==3: quran[(int(p[0]),int(p[1]))]=p[2]
BASMALA=quran[(1,1)]
def text(s,a):
    t=quran[(s,a)]
    if a==1 and s not in (1,9) and t.startswith(BASMALA+' '): t=t[len(BASMALA)+1:]
    return t
def has(w): return any(c in index for c in w)
def align(lp, words):
    """Per word: mean frame loss, worst character, mean of the characters' best frames."""
    ids, word_of = [], []
    for w, word in enumerate(words):
        for c in word:
            if c in index: ids.append(index[c]); word_of.append(w)
        if w + 1 < len(words): ids.append(index[" "]); word_of.append(-1)
    T, L = lp.shape[0], len(ids)
    if L == 0 or T < L: return None
    Sn = 2 * L + 1
    sym = np.array([BLANK if s % 2 == 0 else ids[s // 2] for s in range(Sn)])
    skip = np.zeros(Sn, dtype=bool); skip[3::2] = sym[3::2] != sym[1:-2:2]
    NEG = -1e30
    d = np.full(Sn, NEG); d[0] = lp[0, BLANK]; d[1] = lp[0, sym[1]]
    bp = np.zeros((T, Sn), dtype=np.int8)
    for t in range(1, T):
        one = np.concatenate([[NEG], d[:-1]]); two = np.where(skip, np.concatenate([[NEG, NEG], d[:-2]]), NEG)
        best = np.maximum(d, np.maximum(one, two))
        bp[t] = np.where(best == d, 0, np.where(best == one, 1, 2)); d = best + lp[t, sym]
    s = Sn - 1 if d[Sn - 1] >= d[Sn - 2] else Sn - 2
    top = lp.max(axis=1); n=len(words)
    loss=[0.0]*n; frames=[0]*n; least={}
    for t in range(T - 1, -1, -1):
        if sym[s] != BLANK:
            c=s//2; w=word_of[c]; l=float(top[t]-lp[t,sym[s]])
            least[c]=min(least.get(c,1e9),l)
            if w>=0: loss[w]+=l; frames[w]+=1
        s -= int(bp[t, s])
    out=[]
    for w in range(n):
        cs=[least[c] for c in least if word_of[c]==w]
        out.append((loss[w]/frames[w], max(cs), sum(cs)/len(cs), sorted(cs)[-2] if len(cs)>1 else max(cs)) if frames[w] else None)
    return out
VOWELS = "\u064e\u064f\u0650"
LETTERS = [c for c in vocab if len(c) == 1 and "\u0621" <= c <= "\u064a" and c != "\u0640"]
rows = []
for line in open(bench + '/cases.tsv', encoding='utf-8'):
    f = line.rstrip('\n').split('\t')
    case = f[0]
    if '-swap' in case or '-skip' in case or '-again' in case: continue
    with wave.open(bench + '/' + f[2]) as w: pcm = np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(np.float32) / 32768
    kind = [k for k in ('flow', 'noisy', 'fast', 'pauses', 'loud', 'muffled', 'rumble', 'pocket', 'sco') if '-' + k in case][0]
    for seg in f[3].split(','):
        ref, span = seg.split('@'); span = span.split('~')[0]; a, b = map(float, span.split('-')); s_, a_ = map(int, ref.split(':'))
        x = pcm[max(0, int((a - 0.2) * 16000)):int((b + 0.3) * 16000)]
        peak = np.abs(x).max()
        if peak > 0: x = x * min(100.0, 0.5 / peak)
        lp = logprobs(x)
        words = [w for w in text(s_, a_).split(' ') if has(w)]
        r = align(lp, words)
        if r is None: continue
        for i, c in enumerate(r):
            if c: rows.append((kind, 'said', c[1]))
        for j, word in enumerate(words):
            at = [i for i, c in enumerate(word) if c in VOWELS]
            if at:
                i = random.choice(at)
                changed = word[:i] + random.choice([v for v in VOWELS if v != word[i]]) + word[i + 1:]
                r2 = align(lp, words[:j] + [changed] + words[j + 1:])
                if r2 and r2[j]: rows.append((kind, 'vowel', r2[j][1]))
            at = [i for i, c in enumerate(word) if c in LETTERS]
            if at:
                i = random.choice(at)
                changed = word[:i] + random.choice([v for v in LETTERS if v != word[i]]) + word[i + 1:]
                r2 = align(lp, words[:j] + [changed] + words[j + 1:])
                if r2 and r2[j]: rows.append((kind, 'letter', r2[j][1]))
for group in ('all', 'flow', 'fast', 'pauses', 'loud', 'sco', 'noisy', 'muffled', 'rumble', 'pocket'):
    def of(what): return np.array([r[2] for r in rows if r[1] == what and (group == 'all' or r[0] == group)], dtype=float)
    said, vowel, letter = of('said'), of('vowel'), of('letter')
    if len(said) == 0: continue
    print(f"== {group}: said n={len(said)} p50 {np.percentile(said, 50):.2f} p95 {np.percentile(said, 95):.2f} p99 {np.percentile(said, 99):.2f}; "
          f"vowel changed n={len(vowel)} p10 {np.percentile(vowel, 10):.2f} p50 {np.percentile(vowel, 50):.2f}; letter changed n={len(letter)} p10 {np.percentile(letter, 10):.2f} p50 {np.percentile(letter, 50):.2f}")
    for bar in (5, 6, 7, 8, 9, 10, 12):
        print(f"   bar {bar:2d}: said flagged {100 * (said > bar).mean():5.2f} %, vowel changed caught {100 * (vowel > bar).mean():5.1f} %, letter changed caught {100 * (letter > bar).mean():5.1f} %")
