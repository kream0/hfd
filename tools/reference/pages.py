#!/usr/bin/env python3
"""
Prints the text of public pages the dev sandbox can't reach (its network policy blocks them):
the pages describing the reference app "سور وآيات فاضلة" (com.yassine.mob.ayatfadila) and the
sites that reference its content, so HFD's passages follow it; and the Tarteel models' cards on
Hugging Face. Runs in CI (.github/workflows/reference.yml); read the output in the job log.
Only reads web pages.
"""
import html, json, re, urllib.parse, urllib.request

UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"

# (url, max characters of text, print the page's links)
PAGES = [
    ("https://www.yassine.mobi/store/ar/Ayat-Fadila.shtml", 6000, True),
    ("https://www.yassine.mobi/store/fr/Ayat-Fadila.shtml", 6000, True),
    ("https://www.yassine.mobi/store/en/Ayat-Fadila.shtml", 6000, True),
    ("https://www.yassine.mobi/store/en/index.shtml", 4000, True),
    # The app's written content (Warsh), as shared on SlideShare.
    ("https://www.slideshare.net/slideshow/warsh-soar-wa-ayat-fadila/72085438", 60000, False),
    # The app's full recitation, with its track description.
    ("https://soundcloud.com/user563166769/sowar-wa-ayat-fadila-yassine-al-jazaeri", 8000, False),
    ("https://bouarfa.ahlamountada.com/t20-topic", 40000, False),
    ("https://www.aljamaa.net/posts/%D8%A7%D9%84%D8%AA%D8%AD%D8%B5%D9%8A%D9%86-%D9%81%D9%8A-%D8%A7%D9%84%D9%82%D8%B1%D8%A2%D9%86-%D8%A7%D9%84%D9%83%D8%B1%D9%8A%D9%85-%D9%88%D8%A7%D9%84%D8%B3%D9%86%D8%A9-%D8%A7%D9%84%D9%86%D8%A8%D9%88", 20000, False),
    ("https://mawdoo3.com/%D8%B3%D9%88%D8%B1_%D9%88%D8%A2%D9%8A%D8%A7%D8%AA_%D9%81%D8%A7%D8%B6%D9%84%D8%A9", 12000, False),
]

# Hugging Face: the Tarteel models (licence, files and sizes, model cards).
HF_API = [
    "https://huggingface.co/api/models?author=tarteel-ai&full=true",
]
HF_CARDS = ["tarteel-ai/whisper-base-ar-quran", "tarteel-ai/whisper-tiny-ar-quran"]


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "ar,fr,en"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8", "replace")


def text_of(page):
    page = re.sub(r"(?is)<(script|style|noscript|svg)\b.*?</\1>", " ", page)
    page = re.sub(r"(?i)<br\s*/?>|</(p|div|li|h\d|tr)>", "\n", page)
    page = html.unescape(re.sub(r"<[^>]+>", " ", page))
    lines = [re.sub(r"[ \t ]+", " ", l).strip() for l in page.splitlines()]
    return "\n".join(l for l in lines if l)


def header(title):
    print("\n" + "=" * 16 + " " + title, flush=True)


def show(url, limit, links):
    header(urllib.parse.unquote(url))
    try:
        page = get(url)
    except Exception as e:
        print("fetch failed:", e)
        return
    t = re.search(r"(?is)<title>(.*?)</title>", page)
    print("title:", html.unescape(t.group(1)).strip() if t else "?")
    for m in re.finditer(r'<meta[^>]+(?:name|property)="(description|og:description)"[^>]+content="([^"]*)"', page):
        print(f"{m.group(1)}:", html.unescape(m.group(2)))
    # Embedded JSON-LD / hydration data often carries the full description or transcript.
    for m in re.finditer(r'"(description|transcript|text)"\s*:\s*"((?:[^"\\]|\\.){200,})"', page):
        try:
            print(f"[{m.group(1)}]", json.loads('"' + m.group(2) + '"')[:limit])
        except Exception:
            pass
    print(text_of(page)[:limit])
    if links:
        print("-- links:")
        for href, label in re.findall(r'(?is)<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>', page):
            print("  ", urllib.parse.urljoin(url, html.unescape(href)), "|", text_of(label)[:80])


def hf():
    for url in HF_API:
        header(url)
        try:
            for m in json.loads(get(url)):
                files = [(s.get("rfilename"), s.get("size")) for s in m.get("siblings", [])]
                print(m.get("id"), "| license:", (m.get("cardData") or {}).get("license"), "| pipeline:", m.get("pipeline_tag"),
                      "| downloads:", m.get("downloads"), "| tags:", ",".join(t for t in m.get("tags", []) if not t.startswith("region")))
                for f in files:
                    print("     ", f)
        except Exception as e:
            print("fetch failed:", e)
    for repo in HF_CARDS:
        header(f"https://huggingface.co/{repo} (tree + card)")
        try:
            tree = json.loads(get(f"https://huggingface.co/api/models/{repo}/tree/main"))
            for f in tree:
                print("  ", f.get("path"), f.get("size"), (f.get("lfs") or {}).get("size"))
        except Exception as e:
            print("tree failed:", e)
        try:
            print(get(f"https://huggingface.co/{repo}/raw/main/README.md")[:8000])
        except Exception as e:
            print("card failed:", e)


def main():
    for url, limit, links in PAGES:
        show(url, limit, links)
    hf()


if __name__ == "__main__":
    main()
