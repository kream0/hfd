#!/bin/bash
# The tajwīd engine (core's Tajweed) against the annotation the coloured editions online share
# (alquran.cloud's "quran-tajweed" edition), word by word over the whole Qur'an: prints the counts
# per rule and writes every difference, to be read by someone who knows the rules (TajweedTest
# names the kinds of difference and why the engine stands by its own).
# Usage: compare.sh <work dir> [gradle project dir with :core, default the repository]
set -eu
work=$1
project=${2:-$(cd "$(dirname "$0")/../.." && pwd)}
mkdir -p "$work"
[ -s "$work/quran-tajweed.json" ] || curl -fsS -m 300 -o "$work/quran-tajweed.json" "https://api.alquran.cloud/v1/quran/quran-tajweed"
cd "$project"
HFD_TAJWEED_REF="$work/quran-tajweed.json" HFD_TAJWEED_OUT="$work/differences.txt" gradle -q :core:cleanTest :core:test --tests '*TajweedReferenceBench.compare' -i | grep -E "^ *[a-z] [0-9]|code both|āyāt|doubts" || true
echo "differences: $work/differences.txt"
