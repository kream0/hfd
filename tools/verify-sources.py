#!/usr/bin/env python3
"""Prints what the cited sources actually say, so every faḍīla reference can be checked by hand.

Run by .github/workflows/data.yml (this sandbox can't reach sunnah.com, dorar.net or
everyayah.com). Reads:
  - every `sources[].url` in app/src/main/assets/fadail.json (if present),
  - tools/verify.json: extra URLs, dorar.net searches and EveryAyah folders to probe.
Output goes to the job log. Stdlib only.
"""
import html, json, os, re, sys, time, urllib.parse, urllib.request

UA = 'Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0'
ROOT = os.path.join(os.path.dirname(__file__), '..')


def get(url, method='GET'):
    req = urllib.request.Request(url, method=method, headers={'User-Agent': UA, 'Accept-Language': 'en,ar;q=0.8'})
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            return r.status, dict(r.headers), (r.read() if method == 'GET' else b'')
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers or {}), e.read() if method == 'GET' else b''
    except Exception as e:  # noqa
        return 0, {}, str(e).encode()


def text_of(fragment):
    fragment = re.sub(r'(?is)<(script|style)[^>]*>.*?</\1>', ' ', fragment)
    fragment = re.sub(r'(?i)<br\s*/?>|</p>|</div>|</tr>', '\n', fragment)
    fragment = re.sub(r'<[^>]+>', ' ', fragment)
    fragment = html.unescape(fragment)
    fragment = re.sub(r'[ \t\r\f\v]+', ' ', fragment)
    return re.sub(r'\n\s*\n+', '\n', fragment).strip()


def sunnah(url):
    status, _, body = get(url)
    page = body.decode('utf-8', 'replace')
    print(f'HTTP {status}')
    title = re.search(r'(?is)<title>(.*?)</title>', page)
    print('TITLE:', text_of(title.group(1)) if title else '-')
    # One hadith per page: English, Arabic, grade table and reference table.
    for cls in ('english_hadith_full', 'arabic_hadith_full', 'gradetable', 'hadith_reference'):
        m = re.search(r'(?is)class="%s[^"]*"(.*?)(?=class="(?:english_hadith_full|arabic_hadith_full|gradetable|hadith_reference|bottomItems|actualHadithContainer)|$)' % cls, page)
        if m:
            print(f'[{cls}]', text_of('<x ' + m.group(1))[:1800])
    if status != 200 or 'english_hadith_full' not in page:
        print('RAW:', text_of(page)[:1200])


def dorar(query, pages=1, only=None):
    """dorar.net search API: 15 results per page. [only] keeps results mentioning one of these."""
    for page in range(1, pages + 1):
        url = 'https://dorar.net/dorar_api.json?skey=' + urllib.parse.quote(query) + f'&page={page}'
        status, _, body = get(url)
        print(f'HTTP {status} page {page}')
        try:
            data = json.loads(body.decode('utf-8', 'replace'))
            result = data.get('ahadith', {}).get('result', '')
        except Exception:
            print('RAW:', text_of(body.decode('utf-8', 'replace'))[:2000])
            return
        # Results are separated by "--------------"; keep each one's links (permalinks).
        for chunk in re.split(r'-{5,}', result):
            flat = ' '.join(text_of(chunk).split())
            if not flat or (only and not any(o in flat for o in only)):
                continue
            links = sorted(set(re.findall(r'href="([^"]+)"', chunk)))
            print('*', flat[:700])
            if links:
                print('  links:', ' '.join(links))
        time.sleep(1)


def everyayah(folder, files):
    for f in files:
        url = f'https://everyayah.com/data/{folder}/{f}'
        status, headers, _ = get(url, method='HEAD')
        print(f'{status} {headers.get("Content-Length", "?"):>8} {url}')


def main():
    extra = json.load(open(os.path.join(ROOT, 'tools', 'verify.json'), encoding='utf-8'))
    urls = list(extra.get('urls', []))
    path = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'fadail.json')
    if os.path.exists(path):
        for entry in json.load(open(path, encoding='utf-8'))['fadail']:
            # Schema 2: each passage's narrations carry their sources.
            for v in entry.get('virtues', []) + [entry]:
                for src in v.get('sources', []):
                    if src.get('url') and src['url'] not in urls:
                        urls.append(src['url'])
    for url in urls:
        print(f'\n==================== {url}')
        if 'sunnah.com' in url:
            sunnah(url)
        else:
            status, _, body = get(url)
            print(f'HTTP {status}')
            print(text_of(body.decode('utf-8', 'replace'))[:3000])
        time.sleep(1.5)
    for q in extra.get('dorar', []):
        if isinstance(q, str):
            q = {'q': q}
        print(f'\n==================== dorar: {q["q"]}')
        dorar(q['q'], q.get('pages', 1), q.get('only'))
        time.sleep(1.5)
    ea = extra.get('everyayah', {})
    if ea:
        print('\n==================== everyayah')
        for u in ea.get('pages', []):
            status, _, body = get(u)
            print(f'--- {u} HTTP {status}')
            print(text_of(body.decode('utf-8', 'replace'))[:4000])
        for folder in ea.get('folders', []):
            everyayah(folder, ea.get('files', ['001001.mp3']))


if __name__ == '__main__':
    sys.exit(main())
