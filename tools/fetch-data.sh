#!/usr/bin/env bash
# Downloads the Qur'an data bundled in the APK, straight from Tanzil (tanzil.net), and writes it
# to app/src/main/assets/quran/. Run by .github/workflows/data.yml (this sandbox can't reach
# tanzil.net); the workflow commits the result. Files are kept byte-for-byte as Tanzil serves
# them (text is never altered); suras.json is derived from Tanzil's metadata XML.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=app/src/main/assets/quran
mkdir -p "$OUT"
TMP=$(mktemp -d)
UA="Mozilla/5.0 (X11; Linux x86_64) hfd-data-fetch"

# Tries each URL until one returns a file that passes the check. $1 = dest, $2 = check, rest = URLs.
fetch() {
  local dest=$1 check=$2; shift 2
  for url in "$@"; do
    echo "--- trying $url"
    if curl -fsSL --retry 3 -A "$UA" -o "$TMP/f" "$url"; then
      if $check "$TMP/f"; then
        cp "$TMP/f" "$dest"
        echo "OK $dest <- $url"
        echo "$url $(sha256sum "$dest" | cut -d' ' -f1)" >> "$TMP/lock"
        return 0
      fi
      echo "rejected: $(head -c 300 "$TMP/f" | tr '\n' ' ')"
    fi
  done
  echo "::error::could not fetch $dest"; return 1
}

# sura|aya|text with all 6236 āyāt.
is_quran_txt() {
  local n; n=$(grep -cE '^[0-9]+\|[0-9]+\|' "$1" || true)
  echo "lines with sura|aya|: $n"; [ "$n" = 6236 ]
}
is_meta_xml() { grep -q '<sura index="114"' "$1"; }

T=https://tanzil.net
fetch "$OUT/quran-uthmani.txt" is_quran_txt \
  "$T/pub/download/index.php?marks=true&sajdah=true&rub=true&quranType=uthmani&outType=txt-2&agree=true" \
  "$T/pub/download/index.php?quranType=uthmani&outType=txt-2&marks=true&sajdah=true&rub=true&alef=true&agree=true"
fetch "$TMP/quran-data.xml" is_meta_xml \
  "$T/res/text/metadata/quran-data.xml"
fetch "$OUT/fr.hamidullah.txt" is_quran_txt \
  "$T/trans/?transID=fr.hamidullah&type=txt-2" "$T/trans/fr.hamidullah"
fetch "$OUT/en.sahih.txt" is_quran_txt \
  "$T/trans/?transID=en.sahih&type=txt-2" "$T/trans/en.sahih"

# suras.json: [{index, ayas, name, tname, ename, type, order}] from Tanzil's metadata.
python3 - "$TMP/quran-data.xml" "$OUT/suras.json" <<'PY'
import sys, json, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
suras = []
for s in root.iter('sura'):
    suras.append({
        'index': int(s.get('index')), 'ayas': int(s.get('ayas')), 'start': int(s.get('start')),
        'name': s.get('name'), 'tname': s.get('tname'), 'ename': s.get('ename'),
        'type': s.get('type'), 'order': int(s.get('order')),
    })
assert len(suras) == 114 and sum(s['ayas'] for s in suras) == 6236, (len(suras), sum(s['ayas'] for s in suras))
json.dump({'schema': 1, 'source': 'Tanzil quran-data.xml', 'suras': suras},
          open(sys.argv[2], 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print('suras.json written')
PY

cp "$TMP/lock" tools/data.lock
echo "==== tools/data.lock"; cat tools/data.lock
for f in "$OUT"/*.txt; do echo "==== $f (head / tail)"; head -3 "$f"; tail -12 "$f"; done
