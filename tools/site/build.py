#!/usr/bin/env python3
"""
Builds the website (docs/index.html, docs/icon.png, docs/og.png) from tools/site/template.html and
site.js, filling them from the repository's own sources, so nothing on the page is typed by hand
that the app already holds:
  - Qur'an text: app/src/main/assets/quran/quran-uthmani.txt (Tanzil, verbatim; a sūra's first
    āya without the basmala, which Tanzil's text file prefixes to it and its XML sets apart);
  - virtues: app/src/main/assets/fadail.json (the verified dataset), with their sources;
  - the reciters' count: core/.../playback/Reciters.kt; the dot font: the app's Doto (subset);
  - the screenshots: docs/screenshots (tools/screenshots/normalise.py).
Needs Pillow, fontTools and segno. Usage: python3 tools/site/build.py
"""
import base64
import html
import io
import json
import os
import re
import sys

from PIL import Image, ImageDraw, ImageFont
from fontTools import subset
import segno

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", ".."))
SITE = os.path.join(ROOT, "tools", "site")
DOCS = os.path.join(ROOT, "docs")
FONTS = os.path.join(ROOT, "app", "src", "main", "res", "font")
APK = "https://github.com/kream0/hfd/releases/latest/download/hfd.apk"


def read(*p):
    return open(os.path.join(ROOT, *p), encoding="utf-8").read()


# ------------------------------------------------------------------ Qur'an text (Tanzil, verbatim)
QURAN = {}
for line in read("app", "src", "main", "assets", "quran", "quran-uthmani.txt").splitlines():
    m = re.match(r"^(\d+)\|(\d+)\|(.*)$", line)
    if m:
        QURAN[(int(m[1]), int(m[2]))] = m[3]
BASMALA = QURAN[(1, 1)]


def aya(s, a):
    text = QURAN[(s, a)]
    if a == 1 and s not in (1, 9):
        assert text.startswith(BASMALA + " "), (s, a)
        text = text[len(BASMALA) + 1:]
    return text


def indic(n):
    return "".join("٠١٢٣٤٥٦٧٨٩"[int(d)] for d in str(n))


def words(text):
    # Words with letters (the pause and section signs stand alone between them).
    return [w for w in text.split(" ") if re.search(r"[ء-يٱ-ۓ]", w)]


# ------------------------------------------------------------------ French typography
def fr(text):
    """Non-breaking spaces before : ; ! ? » and after «, as French typesetting wants."""
    t = html.escape(text, quote=False)
    t = re.sub(r" ([:;!?»])", "&nbsp;\\1", t)
    t = re.sub(r"« ", "«&nbsp;", t)
    return t


# ------------------------------------------------------------------ pieces
def doto_woff():
    opts = subset.Options()
    opts.flavor = "woff"
    font = subset.load_font(os.path.join(FONTS, "doto_black.ttf"), opts)
    s = subset.Subsetter(opts)
    s.populate(unicodes=list(range(0x20, 0x7F)) + list(range(0xA0, 0x100)) + [0x2019, 0x2013, 0x00B7, 0x2026, 0x0152, 0x0153])
    s.subset(font)
    buf = io.BytesIO()
    subset.save_font(font, buf, opts)
    return base64.b64encode(buf.getvalue()).decode()


def icon_dots():
    """The launcher icon's dots (res/drawable/ic_launcher_foreground.xml): (x, y, r, colour)."""
    xml = read("app", "src", "main", "res", "drawable", "ic_launcher_foreground.xml")
    dots = []
    for colour, d in re.findall(r'android:fillColor="(#[0-9A-Fa-f]+)" android:pathData="([^"]+)"', xml):
        for m in re.finditer(r"M([\d.]+),([\d.]+)a([\d.]+),", d):
            x, y, r = map(float, m.groups())
            dots.append((x + r, y, r, colour))
    return dots


def qr_rows():
    code = segno.make(APK, error="m", micro=False, boost_error=False)
    return ["".join("1" if v else "0" for v in row) for row in code.matrix]


def reciter_count():
    src = read("core", "src", "main", "kotlin", "app", "hfd", "core", "playback", "Reciters.kt")
    return len(re.findall(r'^\s+Reciter\("', src, re.M))


# The screens shown, in order: (file, label, caption, alt). Captions say what the feature does.
SHOTS = [
    ("home", "Accueil",
     "L'objectif du jour, les āyāt à réviser, puis la suite de ce que vous apprenez.",
     "Accueil de HFD : objectif du jour de 15 minutes, 4 āyāt à réviser, Al-Ikhlāṣ et Āyat al-Kursī à continuer, Al-Kawthar et Al-ʿAṣr à apprendre ensuite."),
    ("fadail", "Les 40 passages",
     "Ceux de l'app de référence, dans son ordre, chacun avec son anneau de progression. «&nbsp;Tout écouter&nbsp;» les enchaîne.",
     "Onglet Faḍāʾil : 40 passages, d'al-Fātiḥa à la fin d'at-Tawba, avec leur titre arabe et le bouton Tout écouter."),
    ("kursi-text", "Lecture",
     "Le texte de Tanzil en grand, la traduction dessous, l'āya jouée mise en avant. Répétitions, pause et récitateur à portée de pouce.",
     "Āyat al-Kursī en lecture : sourate al-Baqara, 2:255 en texte uthmani, lecteur en bas avec les réglages Āya ×1, Plage ×1, Pause et le récitateur."),
    ("learn", "Apprendre",
     "Écouter avec le texte, répéter dans les pauses, le premier mot seul, puis de mémoire.",
     "Apprendre Al-Ikhlāṣ, āya 4 sur 4 : étape Écouter, le texte de 112:4 et sa traduction, boutons Réécouter, Passer et Suivant."),
    ("recite", "Réciter",
     "Récitez au micro&nbsp;: les mots s'allument un à un, le mot sauté est barré en rouge.",
     "Réciter Al-Ikhlāṣ : les trois premières āyāt reconnues, « wa-lam » barré en rouge, la quatrième encore en attente, bouton micro."),
    ("review", "Réviser",
     "Ce qui est dû aujourd'hui, texte caché&nbsp;: réciter de mémoire, dévoiler, noter.",
     "Révision : Al-Baqara 2:255, texte caché, boutons Indice et Dévoiler."),
    ("stats", "Progrès",
     "Série, āyāt mémorisées, récitations, temps d'écoute, et vingt semaines en points.",
     "Stats : série, record, āyāt mémorisées, temps d'écoute, calendrier de points sur vingt semaines, progrès par faḍīla."),
    ("reminders", "Réglages",
     "Le rappel de révision, le thème sombre ou papier, la taille du texte, la traduction.",
     "Réglages : rappel Āyāt à réviser à 19 h, thème Système, Sombre ou Papier, taille du texte coranique, traduction des sens."),
]


def shot_html(w, h):
    out = []
    for name, label, caption, alt in SHOTS:
        out.append(
            f'        <figure class="shot">\n'
            f'          <div class="phone"><img src="screenshots/{name}.webp" width="{w}" height="{h}" loading="lazy" alt="{html.escape(alt)}">'
            f'<img class="paper" src="screenshots/{name}-paper.webp" width="{w}" height="{h}" loading="lazy" alt="" aria-hidden="true"></div>\n'
            f'          <figcaption><span class="label">{label}</span><p>{caption}</p></figcaption>\n'
            f'        </figure>'
        )
    return "\n".join(out)


def ikhlas():
    rows, counts = [], []
    for a in range(1, 5):
        text = aya(112, a)
        counts.append(len(words(text)))
        rows.append(
            f'            <li><canvas width="60" height="60" aria-hidden="true"></canvas>'
            f'<span class="ar" lang="ar">{html.escape(text)} ﴿{indic(a)}﴾</span><span class="ref">112:{a}</span></li>'
        )
    return "\n".join(rows), counts


# The virtues shown: (passage id, the start of the virtue's French text).
VIRTUES = [
    ("kursi", "Interrogé sur le plus grand verset"),
    ("baqara-end", "Celui qui récite les deux derniers versets"),
    ("ikhlas", "« Qul huwa Allāhu aḥad » équivaut au tiers"),
]


def virtues():
    data = {f["id"]: f for f in json.loads(read("app", "src", "main", "assets", "fadail.json"))["fadail"]}
    cards = []
    for pid, start in VIRTUES:
        f = data[pid]
        v = next(v for v in f["virtues"] if v["text"]["fr"].startswith(start))
        assert v.get("verified") and v["grading"]["grade"] == "sahih", pid
        ranges = " · ".join(
            f'{r["sura"]}:{r["from"]}' + (f'–{r["to"]}' if r["to"] != r["from"] else "") for r in f["ranges"]
        )
        sources = " · ".join(
            f'<a href="{html.escape(s["url"])}" target="_blank" rel="noopener">{html.escape(s["collection"])} {html.escape(s["number"])}</a>'
            for s in v["sources"]
        )
        cards.append(
            f'        <article class="virtue">\n'
            f'          <p class="ar" lang="ar">{html.escape(f["title"]["ar"])}</p>\n'
            f'          <h3>{html.escape(f["title"]["fr"])}<small>{ranges}</small></h3>\n'
            f'          <blockquote>{fr(v["text"]["fr"])}</blockquote>\n'
            f'          <p class="src">{sources}</p>\n'
            f'        </article>'
        )
    return "\n".join(cards)


def brand_svg(dots):
    return "\n".join(f'        <circle cx="{x:g}" cy="{y:g}" r="{r:g}" fill="{c}"/>' for x, y, r, c in dots)


# ------------------------------------------------------------------ images
def icon_png(dots, size=432):
    s = size / 72  # viewBox 18 18 72 72
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse((0, 0, size - 1, size - 1), fill="#000000", outline="#2A2A2A")
    for x, y, r, c in dots:
        cx, cy = (x - 18) * s, (y - 18) * s
        d.ellipse((cx - r * s, cy - r * s, cx + r * s, cy + r * s), fill=c)
    im.save(os.path.join(DOCS, "icon.png"))


def og_png(dots):
    W, H = 1200, 630
    im = Image.new("RGB", (W, H), "#000000")
    d = ImageDraw.Draw(im)
    for y in range(18, H, 26):  # a faint dot field
        for x in range(18, W, 26):
            d.ellipse((x - 1.6, y - 1.6, x + 1.6, y + 1.6), fill="#161616")
    # the dot logo
    s = 1.25
    for x, y, r, c in dots:
        cx, cy = 60 + (x - 18) * s, 40 + (y - 18) * s
        d.ellipse((cx - r * s, cy - r * s, cx + r * s, cy + r * s), fill=c)
    dot = ImageFont.truetype(os.path.join(FONTS, "doto_black.ttf"), 196)
    sans = ImageFont.truetype(os.path.join(FONTS, "space_grotesk_medium.ttf"), 38)
    mono = ImageFont.truetype(os.path.join(FONTS, "space_mono_bold.ttf"), 20)
    d.text((56, 150), "HFD", font=dot, fill="#FFFFFF")
    d.ellipse((440, 172, 462, 194), fill="#D71921")
    d.multiline_text((62, 370), "Apprendre par cœur les passages\ndu Coran aux vertus établies,\nà l'oreille, āya par āya.", font=sans, fill="#FFFFFF", spacing=10)
    d.text((64, 540), "ANDROID 8+ · SANS COMPTE · HORS LIGNE", font=mono, fill="#9B9B9B")
    # the home screen in a phone frame
    shot = Image.open(os.path.join(DOCS, "screenshots", "home.webp")).convert("RGB")
    pw = 300
    ph = round(pw * shot.height / shot.width)
    shot = shot.resize((pw, ph), Image.LANCZOS)
    x0, y0 = 820, 60
    frame = Image.new("RGBA", (pw + 24, ph + 24), (0, 0, 0, 0))
    fd = ImageDraw.Draw(frame)
    fd.rounded_rectangle((0, 0, pw + 23, ph + 23), radius=46, fill="#1B1B1C", outline="#3A3A3C", width=2)
    mask = Image.new("L", (pw, ph), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, pw - 1, ph - 1), radius=36, fill=255)
    frame.paste(shot, (12, 12), mask)
    im.paste(frame, (x0, y0), frame)
    im.save(os.path.join(DOCS, "og.png"), optimize=True)


def main():
    w, h = Image.open(os.path.join(DOCS, "screenshots", "home.webp")).size
    dots = icon_dots()
    rows, counts = ikhlas()
    alt = {name: a for name, _, _, a in SHOTS}
    script = open(os.path.join(SITE, "site.js"), encoding="utf-8").read()
    script = script.replace("{{QR_JSON}}", json.dumps(qr_rows())).replace("{{AYAT_JSON}}", json.dumps(counts))
    page = open(os.path.join(SITE, "template.html"), encoding="utf-8").read()
    fills = {
        "DOTO_WOFF": doto_woff(),
        "BRAND_DOTS": brand_svg(dots),
        "SHOT_W": str(w), "SHOT_H": str(h),
        "SHOTS": shot_html(w, h),
        "ALT_HOME": html.escape(alt["home"]),
        "ALT_KURSI_TEXT": html.escape(alt["kursi-text"]),
        "IKHLAS": rows,
        "VIRTUES": virtues(),
        "RECITER_COUNT": str(reciter_count()),
        "SCRIPT": script,
    }
    for k, v in fills.items():
        page = page.replace("{{" + k + "}}", v)
    left = re.findall(r"\{\{[A-Z_]+\}\}", page)
    if left:
        sys.exit(f"unfilled: {left}")
    open(os.path.join(DOCS, "index.html"), "w", encoding="utf-8").write(page)
    icon_png(dots)
    og_png(dots)
    print("docs/index.html", len(page), "bytes;", reciter_count(), "reciters; āyāt words", counts)


if __name__ == "__main__":
    main()
