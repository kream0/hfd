#!/usr/bin/env python3
"""
Prints the text of the public pages describing the reference app "سور وآيات فاضلة"
(com.yassine.mob.ayatfadila) and the publisher's matching web section, so HFD's faḍāʾil list
can follow the same sūras and āyāt. Runs in CI because the dev sandbox can't reach these hosts.
Only reads web pages.
"""
import html, re, urllib.parse, urllib.request

UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
PKG = "com.yassine.mob.ayatfadila"
SECTION = "https://ar.yassine.net/%D8%A7%D9%84%D9%82%D8%B1%D8%A2%D9%86-%D8%A7%D9%84%D9%83%D8%B1%D9%8A%D9%852/%D8%B3%D9%88%D8%B1-%D9%88%D8%A2%D9%8A%D8%A7%D8%AA-%D9%81%D8%A7%D8%B6%D9%84%D8%A9"
PAGES = [
    SECTION,
    "https://ar.yassine.net/%D9%86%D8%B3%D8%AE%D8%A9-%D8%AC%D8%AF%D9%8A%D8%AF%D8%A9-%D9%84%D8%AA%D8%B7%D8%A8%D9%8A%D9%82-%D8%B3%D9%88%D8%B1-%D9%88%D8%A2%D9%8A%D8%A7%D8%AA-%D9%81%D8%A7%D8%B6%D9%84%D8%A9-%D8%A8%D8%B1%D9%88%D8%A7",
    "https://ar.yassine.net/%D8%AC%D8%AF%D9%8A%D8%AF-%D8%B3%D9%88%D8%B1-%D9%88%D8%A2%D9%8A%D8%A7%D8%AA-%D9%81%D8%A7%D8%B6%D9%84%D8%A9-%D9%85%D9%86%D9%82%D8%AD%D8%A9",
    "https://new.yassine.net/2020/03/" + urllib.parse.quote("تطبيق-سور-وآيات-فاضلة") + "/",
    "https://www.yassine.mobi/store/ar/Ayat-Fadila.shtml",
    "https://www.aljamaa.net/posts/%D8%B3%D9%88%D8%B1-%D9%88%D8%A2%D9%8A%D8%A7%D8%AA-%D9%81%D8%A7%D8%B6%D9%84%D9%80%D9%80%D8%A9",
    "https://apps.apple.com/fr/app/id1102969124",
]
MAX_TEXT = 15000
MAX_SUBPAGES = 60


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "ar,fr,en"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8", "replace")


def text_of(page):
    page = re.sub(r"(?is)<(script|style|noscript|svg|nav|footer|header)\b.*?</\1>", " ", page)
    page = re.sub(r"(?i)<br\s*/?>|</(p|div|li|h\d|tr)>", "\n", page)
    page = html.unescape(re.sub(r"<[^>]+>", " ", page))
    lines = [re.sub(r"[ \t ]+", " ", l).strip() for l in page.splitlines()]
    return "\n".join(l for l in lines if l)


def show(title, url):
    print("\n" + "=" * 16 + f" {title}: {urllib.parse.unquote(url)}", flush=True)
    try:
        page = get(url)
    except Exception as e:
        print("fetch failed:", e)
        return None
    t = re.search(r"(?is)<title>(.*?)</title>", page)
    print("title:", html.unescape(t.group(1)).strip() if t else "?")
    print(text_of(page)[:MAX_TEXT])
    return page


def play():
    for hl in ("ar", "fr", "en"):
        url = f"https://play.google.com/store/apps/details?id={PKG}&hl={hl}"
        print("\n" + "=" * 16 + f" Play listing {hl}", flush=True)
        try:
            page = get(url)
        except Exception as e:
            print("fetch failed:", e)
            continue
        i = page.find('data-g-id="description"')
        if i < 0:
            m = re.search(r'<meta name="description" content="([^"]*)"', page)
            print(html.unescape(m.group(1)) if m else "no description found")
            continue
        chunk = page[i:i + 40000]
        chunk = chunk[chunk.find(">") + 1:]
        print(text_of(chunk[:chunk.find("</div>")]))


def main():
    play()
    section_page = None
    for u in PAGES:
        p = show("page", u)
        if u == SECTION:
            section_page = p
    if section_page:
        # The section lists one article per sūra / group of āyāt: print each of them.
        base = urllib.parse.urlparse(SECTION)
        links = []
        for href in re.findall(r'href="([^"#]+)"', section_page):
            full = urllib.parse.urljoin(SECTION, html.unescape(href))
            pu = urllib.parse.urlparse(full)
            if pu.netloc == base.netloc and pu.path.startswith(base.path + "/") and full not in links:
                links.append(full)
        print(f"\n{len(links)} sub-pages under the section")
        for u in links[:MAX_SUBPAGES]:
            show("sub-page", u)


if __name__ == "__main__":
    main()
