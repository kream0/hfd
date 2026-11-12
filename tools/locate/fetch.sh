#!/bin/bash
# The recordings of sample.py's cases.tsv, from EveryAyah: each āya as MP3, decoded to 16 kHz mono
# float32 (<dir>/<reciter>/<sssaaa>.f32), and each reciter's basmala (001001).
# Usage: fetch.sh <dir with cases.tsv>
set -eu
cd "$1"
cut -f2 cases.tsv | sort -u | while read -r f; do mkdir -p "$f"; echo "$f/001001"; done > want.txt
while IFS=$'\t' read -r _ f refs _; do
  for r in ${refs//,/ }; do printf "%s/%03d%03d\n" "$f" "${r%%:*}" "${r##*:}"; done
done < cases.tsv >> want.txt
sort -u want.txt | while read -r n; do [ -s "$n.mp3" ] || echo "$n"; done > todo.txt
xargs -P 12 -I{} sh -c 'curl -fsS -m 120 -A "Mozilla/5.0 (X11; Linux x86_64) HFD-locate-bench" -o {}.part "https://everyayah.com/data/{}.mp3" && mv {}.part {}.mp3' < todo.txt
sort -u want.txt | while read -r n; do [ -s "$n.f32" ] || echo "$n"; done > decode.txt
xargs -P 4 -I{} ffmpeg -v error -y -f mp3 -i {}.mp3 -ac 1 -ar 16000 -f f32le {}.f32 < decode.txt
rm -f want.txt todo.txt decode.txt
echo "$(ls */*.f32 | wc -l) recordings"
