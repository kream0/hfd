#!/bin/bash
# The recordings the tajwīd timing study and TajweedCheckBench listen to: each āya of a beats.tsv
# (TajweedReferenceBench.dump, HFD_TAJWEED_DUMP="beats:<āyāt>") as four reciters of EveryAyah say
# it, as MP3 (timing.py's cache) and decoded to 16 kHz mono float32 (<reciter>/<sssaaa>.f32).
# Usage: fetch.sh <beats.tsv> <cache dir>
set -eu
beats=$1
cache=$2
mkdir -p "$cache"
cd "$cache"
for f in MaherAlMuaiqly128kbps Alafasy_128kbps Husary_128kbps Yasser_Ad-Dussary_128kbps; do
  mkdir -p "$f"
  cut -f1 "$beats" | while IFS=: read -r s a; do
    n=$(printf "%03d%03d" "$s" "$a")
    [ -s "$f/$n.mp3" ] || echo "$f/$n"
  done
done > todo.txt
xargs -P 12 -I{} sh -c 'curl -fsS -m 120 -A "Mozilla/5.0 (X11; Linux x86_64) HFD-tajweed-study" -o {}.part "https://everyayah.com/data/{}.mp3" && mv {}.part {}.mp3' < todo.txt
ls */*.mp3 | sed 's/\.mp3$//' | while read -r n; do [ -s "$n.f32" ] || echo "$n"; done > decode.txt
xargs -P 4 -I{} ffmpeg -v error -y -i {}.mp3 -af "highpass=f=80,lowpass=f=7000" -ac 1 -ar 16000 -f f32le {}.f32 < decode.txt
rm -f todo.txt decode.txt
echo "$(ls */*.f32 | wc -l) recordings"
