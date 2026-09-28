#!/usr/bin/env python3
"""
Where per-āya recitations can come from, for a reciter the dev sandbox can't look up: the
everyayah.com folders (what HFD plays today) and the other public Qur'an audio APIs. Runs in CI
(.github/workflows/reference.yml); read the output in the job log. Only reads public pages.
"""
import json, re, sys, urllib.request

UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"
# Latin and Arabic spellings of the reciters looked for.
TERMS = ["maher", "muaiq", "mueaq", "badr", "bader", "turki", "ماهر", "المعيقلي", "بدر", "التركي"]


def get(url, method="GET"):
    req = urllib.request.Request(url, method=method, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), b""
    except Exception as e:
        return 0, {}, str(e).encode()


def header(title):
    print("\n" + "=" * 16 + " " + title, flush=True)


def matches(s):
    s = s.lower()
    return any(t in s for t in TERMS)


def everyayah():
    header("everyayah.com folders")
    for url in ["https://everyayah.com/data/", "https://everyayah.com/recitations_ayat.html"]:
        status, _, body = get(url)
        page = body.decode("utf-8", "replace")
        names = sorted(set(re.findall(r'href="(?:/data/|https?://everyayah\.com/data/)?([A-Za-z0-9_.\-]+)/?"', page)))
        print(f"--- {url} HTTP {status}, {len(names)} links")
        print("matching:", [n for n in names if matches(n)])
        print("all:", " ".join(names)[:6000])
    # The folders HFD offers: a few āyāt of the passages in each, to be sure the folder is whole.
    for folder in ["MaherAlMuaiqly128kbps", "Yasser_Ad-Dussary_128kbps", "Abdurrahmaan_As-Sudais_192kbps",
                   "Saood_ash-Shuraym_128kbps", "Nasser_Alqatami_128kbps", "Abu_Bakr_Ash-Shaatree_128kbps",
                   "Ahmed_ibn_Ali_al-Ajamy_128kbps_ketaballah.net", "Hudhaify_128kbps", "Salah_Al_Budair_128kbps",
                   "Muhammad_Ayyoub_128kbps", "Hani_Rifai_192kbps"]:
        for f in ["001001.mp3", "002255.mp3", "003200.mp3", "018110.mp3", "036083.mp3", "056096.mp3", "067030.mp3", "114006.mp3"]:
            url = f"https://everyayah.com/data/{folder}/{f}"
            status, h, _ = get(url, "HEAD")
            print(f"{status} {h.get('Content-Length', '?'):>8} {url}")


def jsonof(url):
    status, _, body = get(url)
    try:
        return status, json.loads(body)
    except Exception:
        return status, body[:300]


def alquran_cloud():
    header("api.alquran.cloud audio editions (per āya: cdn.islamic.network/quran/audio/<kbps>/<id>/<n>.mp3)")
    status, d = jsonof("https://api.alquran.cloud/v1/edition?format=audio")
    print("HTTP", status)
    for e in (d.get("data", []) if isinstance(d, dict) else []):
        mark = "  <==" if matches(e.get("identifier", "") + e.get("englishName", "") + e.get("name", "")) else ""
        print(f'{e.get("identifier")} | {e.get("englishName")} | {e.get("name")} | {e.get("language")} | {e.get("type")}{mark}')


def quran_com():
    header("api.quran.com v4 verse-by-verse recitations")
    status, d = jsonof("https://api.quran.com/api/v4/resources/recitations?language=en")
    print("HTTP", status)
    for r in (d.get("recitations", []) if isinstance(d, dict) else []):
        print(r.get("id"), "|", r.get("reciter_name"), "|", r.get("style"), "|", (r.get("translated_name") or {}).get("name"))
    header("api.qurancdn.com QDC chapter reciters (with segments)")
    status, d = jsonof("https://api.qurancdn.com/api/qdc/audio/reciters?locale=en")
    print("HTTP", status)
    for r in (d.get("reciters", []) if isinstance(d, dict) else []):
        name = json.dumps(r, ensure_ascii=False)
        print(("<== " if matches(name) else "") + name[:300])


def mp3quran():
    header("mp3quran.net reciters matching")
    status, d = jsonof("https://mp3quran.net/api/v3/reciters?language=ar")
    print("HTTP", status)
    for r in (d.get("reciters", []) if isinstance(d, dict) else []):
        if matches(r.get("name", "")):
            print(r.get("id"), r.get("name"))
            for m in r.get("moshaf", []):
                print("   moshaf", m.get("id"), m.get("name"), m.get("server"), "surahs:", m.get("surah_total"), "type:", m.get("moshaf_type"))
    header("mp3quran.net reads with āya timings")
    status, d = jsonof("https://mp3quran.net/api/v3/ayat_timing/reads")
    print("HTTP", status)
    items = d if isinstance(d, list) else (d.get("reads", []) if isinstance(d, dict) else [])
    for r in items:
        s = json.dumps(r, ensure_ascii=False)
        print(("<== " if matches(s) else "") + s[:300])


def main():
    everyayah()
    alquran_cloud()
    quran_com()
    mp3quran()


if __name__ == "__main__":
    sys.exit(main())
