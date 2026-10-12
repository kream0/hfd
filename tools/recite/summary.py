#!/usr/bin/env python3
"""
The Recite bench's parts added up (recite.yml runs each config's cases in several jobs): per
config, the totals as the bench prints them, the lags by place, and every OWNER'S SESSION line.
Usage: summary.py <dir of downloaded artifacts: bench-<config>-<k>-<n>/{report.txt,totals.tsv}>
"""
import collections
import glob
import os
import sys

root = sys.argv[1]
configs = collections.defaultdict(list)
for d in sorted(glob.glob(os.path.join(root, "bench-*"))):
    configs[os.path.basename(d).split("-")[1]].append(d)


def quantile(xs, f):
    xs = sorted(xs)
    return xs[int((len(xs) - 1) * f)] if xs else float("nan")


for config, dirs in configs.items():
    counts = collections.defaultdict(collections.Counter)
    lags = collections.defaultdict(list)
    marks = collections.defaultdict(list)
    owners, missing = [], []
    for d in dirs:
        t = os.path.join(d, "totals.tsv")
        if not os.path.exists(t):
            missing.append(os.path.basename(d))
        else:
            for line in open(t, encoding="utf-8"):
                name, kind, *rest = line.split()
                if kind == "counts":
                    counts[name].update({k: int(v) for k, v in (x.split("=") for x in rest)})
                elif kind == "marks":
                    marks[name] += [(int(p), *map(float, v)) for p, *v in (x.split(":") for x in rest)]
                else:
                    lags[name] += [(int(p), float(v)) for p, v in (x.split(":") for x in rest)]
        r = os.path.join(d, "report.txt")
        if os.path.exists(r):
            owners += [l.rstrip() for l in open(r, encoding="utf-8") if "OWNER'S SESSION" in l]
    print(f"== {config}: {len(dirs)} parts" + (f", unfinished: {', '.join(missing)}" if missing else ""))
    for name in ("all", "steered", "free"):
        c, l = counts[name], [v for _, v in lags[name]]
        if not c:
            continue
        print(f"{name.upper():8} {c['cases']} cases: ok {c['ok']}/{c['said']} ({100 * c['ok'] / max(1, c['said']):.1f} %), wrong {c['wrong']}, "
              f"missed {c['missed']}, pending {c['pending']}; lag p50 {quantile(l, .5):.1f} s, p90 {quantile(l, .9):.1f} s; "
              f"mistakes caught {c['caught']}/{c['swapped']}; checks {c['checks']} (+{c['rescued']}); "
              f"red then right {c['redRight']} (shown {c['redRightShown']})")
    by = collections.defaultdict(list)
    for p, v in lags["all"]:
        by[p].append(v)
    print("LAG BY PLACE: " + "; ".join(f"{n} p50 {quantile(by[p], .5):.1f} p90 {quantile(by[p], .9):.1f} s ({len(by[p])})"
                                       for p, n in ((0, "āya's first word"), (1, "middle"), (2, "last"))))
    def marker(place):
        m = [x for x in marks["all"] if x[0] == place]
        left, nxt, reached = ([x[i] for x in m] for i in (1, 2, 3))
        return (f"leaves p10 {quantile(left, .1):.1f} p50 {quantile(left, .5):.1f} s (1.15.4: p10 {quantile(nxt, .1):.1f} "
                f"p50 {quantile(nxt, .5):.1f} s), reaches p50 {quantile(reached, .5):.1f} p90 {quantile(reached, .9):.1f} s ({len(m)})")
    if marks["all"]:
        print("MARKER (from the word's end / start): " + "; ".join(f"{n} {marker(p)}" for p, n in ((0, "āya's first word"), (1, "middle"), (2, "last"))))
    for l in sorted(owners, key=lambda l: l.replace("free   ", "").strip()):
        print("  " + l)
    print()
