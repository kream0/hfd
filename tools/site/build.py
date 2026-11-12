#!/usr/bin/env python3
"""
Builds the website (docs/index.html, docs/icon.png, docs/og.png) from tools/site/template.html and
site.js, filling them from the repository's own sources, so nothing on the page is typed by hand
that the app already holds:
  - Qur'an text: app/src/main/assets/quran/quran-uthmani.txt (Tanzil, verbatim; a sūra's first
    āya without the basmala, which Tanzil's text file prefixes to it and its XML sets apart);
  - virtues: app/src/main/assets/fadail.json (the verified dataset), with their sources;
  - the reciters' count: core/.../playback/Reciters.kt; the star: the launcher icon's;
  - the screenshots: docs/screenshots (tools/screenshots/normalise.py).
Needs Pillow and segno. Usage: python3 tools/site/build.py
"""
import html
import json
import math
import os
import re
import sys

from PIL import Image, ImageDraw, ImageFont
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
STAR = (54, 54, 25)  # the launcher icon's star (res/drawable/ic_launcher_foreground.xml): centre, radius


def star_points(cx, cy, r):
    """The eight-pointed star's two squares, one turned an eighth: their corners."""
    h = r * math.cos(math.pi / 4)
    return [(cx, cy - r), (cx + r, cy), (cx, cy + r), (cx - r, cy)], [(cx - h, cy - h), (cx + h, cy - h), (cx + h, cy + h), (cx - h, cy + h)]


def draw_star(d, cx, cy, r, width, colour, dot):
    for square in star_points(cx, cy, r):
        d.line(square + [square[0], square[1]], fill=colour, width=width, joint="curve")
    d.ellipse((cx - dot, cy - dot, cx + dot, cy + dot), fill=colour)


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
     "Accueil de HFD : la carte du jour (objectif de 15 minutes, la semaine, 4 āyāt à réviser), puis Al-Ikhlāṣ et Āyat al-Kursī à continuer, Al-Kawthar et Al-ʿAṣr à apprendre ensuite."),
    ("fadail", "Les 40 passages",
     "Ceux de l'app de référence, dans son ordre, chacun avec son anneau de progression. «&nbsp;Tout écouter&nbsp;» les enchaîne.",
     "Onglet Faḍāʾil : 40 passages, d'al-Fātiḥa à la fin d'at-Tawba, avec leur titre arabe et le bouton Tout écouter."),
    ("kursi-text", "Lecture",
     "Le texte de Tanzil en grand, la traduction dessous, l'āya jouée mise en avant. En bas, un seul bouton&nbsp;: réciter ou écouter, au choix d'un appui long.",
     "Āyat al-Kursī en lecture : sourate al-Baqara, 2:255 en texte uthmani ; en bas, la barre avec son bouton rond."),
    ("quran", "Tout le Coran",
     "Les 114 sourates à lire et à écouter, par sourate, juzʾ ou ḥizb. La recherche trouve un verset par sa référence, un mot, ou quelques mots récités.",
     "Onglet Coran : champ de recherche, choix Sourates, Juzʾ, Ḥizb ou Favoris, puis les sourates d'al-Fātiḥa à al-Anfāl avec leur nom arabe."),
    ("sura", "Une sourate",
     "En haut, toujours là&nbsp;: où vous êtes (sourate, āya, juzʾ, ḥizb). Un appui ouvre la recherche pour aller ailleurs. La sourate se rouvre où vous l'avez laissée.",
     "Sourate al-Kahf : en haut, la barre «&nbsp;Al-Kahf · 1, Juzʾ 15 · Ḥizb 30&nbsp;» avec une loupe ; le titre encadré, la basmala, les āyāt 18:1 et 18:2 avec leur traduction ; en bas, la barre avec son bouton rond."),
    ("tajweed", "Tajwīd",
     "Les lettres prennent la couleur de leur règle, comme dans les muṣḥafs de tajwīd&nbsp;; chaque couleur se choisit.",
     "Légende du tajwīd : un verset d'al-Baqara aux lettres colorées, puis les couleurs des madd de 6, de 4 ou 5, de 2-4-6 et de 2 temps, de la ghunna et de la qalqala, chacune avec son interrupteur."),
    ("learn", "Apprendre",
     "Écouter avec le texte, répéter dans les pauses, le premier mot seul, puis de mémoire.",
     "Apprendre Al-Ikhlāṣ, āya 4 sur 4 : étape Écouter, le texte de 112:4 et sa traduction, boutons Réécouter, Passer et Suivant."),
    ("recite", "Réciter",
     "Touchez le micro et récitez n'importe quel passage&nbsp;: l'app le retrouve et vous suit. Les mots s'allument un à un, le mot sauté est barré en rouge.",
     "Réciter Al-Ikhlāṣ : les trois premières āyāt reconnues, « wa-lam » barré en rouge, la quatrième encore en attente, bouton micro."),
    ("review", "Réviser",
     "Ce qui est dû aujourd'hui, texte caché&nbsp;: réciter de mémoire, dévoiler, noter.",
     "Révision : Al-Baqara 2:255, texte caché, boutons Indice et Dévoiler."),
    ("stats", "Progrès",
     "Série, āyāt mémorisées, récitations, temps d'écoute, et vingt semaines de pratique.",
     "Stats : série, record, āyāt mémorisées, temps d'écoute, calendrier de vingt semaines, progrès par faḍīla."),
    ("reminders", "Réglages",
     "Le rappel de révision, la langue, le thème nuit ou papier, la taille du texte.",
     "Réglages : rappel Āyāt à réviser à 19 h, langue Français, English ou Système, thème Système, Nuit ou Papier, taille du texte coranique."),
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


def brand_svg():
    cx, cy, r = STAR
    squares = "".join(
        '        <path d="M' + "L".join(f"{x:.2f},{y:.2f}" for x, y in square) + 'z" fill="none" stroke="#DDB869" stroke-width="3.2" stroke-linejoin="round"/>\n'
        for square in star_points(cx, cy, r)
    )
    return squares + f'        <circle cx="{cx}" cy="{cy}" r="5.2" fill="#DDB869"/>'


# ------------------------------------------------------------------ images
def icon_png(size=432):
    k = 4  # drawn larger, then reduced: smooth edges
    s = size * k / 72  # viewBox 18 18 72 72
    im = Image.new("RGBA", (size * k, size * k), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse((0, 0, size * k - 1, size * k - 1), fill="#10201B", outline="#2B3E37", width=k)
    cx, cy, r = STAR
    draw_star(d, (cx - 18) * s, (cy - 18) * s, r * s, round(3.2 * s), "#DDB869", 5.2 * s)
    im.resize((size, size), Image.LANCZOS).save(os.path.join(DOCS, "icon.png"))


def og_png():
    W, H = 1200, 630
    im = Image.new("RGB", (W, H), "#0D1614")
    d = ImageDraw.Draw(im)
    serif = ImageFont.truetype(os.path.join(FONTS, "serif_semibold.ttf"), 170)
    text = ImageFont.truetype(os.path.join(FONTS, "serif_regular.ttf"), 40)
    small = ImageFont.truetype(os.path.join(FONTS, "sans_semibold.ttf"), 22)
    draw_star(d, 104, 96, 44, 6, "#DDB869", 9)
    d.text((58, 150), "HFD", font=serif, fill="#F2ECDD")
    d.multiline_text((62, 368), "Apprendre par cœur les passages\ndu Coran aux vertus établies,\nà l'oreille, āya par āya.", font=text, fill="#F2ECDD", spacing=10)
    d.text((64, 548), "ANDROID 8+  ·  SANS COMPTE  ·  HORS LIGNE", font=small, fill="#A5B4AB")
    # the home screen in a phone frame
    shot = Image.open(os.path.join(DOCS, "screenshots", "home.webp")).convert("RGB")
    pw = 300
    ph = round(pw * shot.height / shot.width)
    shot = shot.resize((pw, ph), Image.LANCZOS)
    x0, y0 = 820, 60
    frame = Image.new("RGBA", (pw + 24, ph + 24), (0, 0, 0, 0))
    fd = ImageDraw.Draw(frame)
    fd.rounded_rectangle((0, 0, pw + 23, ph + 23), radius=46, fill="#1C2D27", outline="#2B3E37", width=2)
    mask = Image.new("L", (pw, ph), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, pw - 1, ph - 1), radius=36, fill=255)
    frame.paste(shot, (12, 12), mask)
    im.paste(frame, (x0, y0), frame)
    im.save(os.path.join(DOCS, "og.png"), optimize=True)


def main():
    w, h = Image.open(os.path.join(DOCS, "screenshots", "home.webp")).size
    rows, counts = ikhlas()
    alt = {name: a for name, _, _, a in SHOTS}
    script = open(os.path.join(SITE, "site.js"), encoding="utf-8").read()
    script = script.replace("{{QR_JSON}}", json.dumps(qr_rows())).replace("{{AYAT_JSON}}", json.dumps(counts))
    page = open(os.path.join(SITE, "template.html"), encoding="utf-8").read()
    fills = {
        "BRAND_STAR": brand_svg(),
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
    icon_png()
    og_png()
    print("docs/index.html", len(page), "bytes;", reciter_count(), "reciters; āyāt words", counts)


if __name__ == "__main__":
    main()
