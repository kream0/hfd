#!/usr/bin/env python3
"""The release APK on a real Android (smoke.yml's emulator): tour.py <apk> <out dir> <label> [starts]

Installs the APK, times cold starts ("am start -W" and the app's own "fully drawn", once its text
is loaded) as installed and again once ART has compiled it with its baseline profile, then walks
the main screens through the accessibility tree (French labels), scrolls a long sūra for the frame
times, and takes a screenshot of each screen. Fails on a crash, on a screen that doesn't come, or
on a class / method / serializer the build lost (what R8 could break). With "starts", only the
compiled starts are timed again (two builds compared on one emulator, one after the other);
with "update", an old build must be offered the latest release by its updater.
"""
import json, os, re, statistics, subprocess, sys, time
import xml.etree.ElementTree as ET

PKG = "app.hfd"
ACTIVITY = PKG + "/.MainActivity"
apk, out = sys.argv[1], sys.argv[2]
label = sys.argv[3] if len(sys.argv) > 3 else "apk"
starts_only = len(sys.argv) > 4 and sys.argv[4] == "starts"
os.makedirs(out, exist_ok=True)
failures, report = [], {"label": label, "apk_bytes": os.path.getsize(apk)}


def adb(*args, timeout=180, binary=False):
    r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    return r.stdout if binary else (r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace"))


def sh(cmd, **kw):
    return adb("shell", cmd, **kw)


def nodes():
    """The screen's nodes: (text, description, (left, top, right, bottom))."""
    for _ in range(4):
        sh("uiautomator dump /sdcard/ui.xml")
        xml = adb("exec-out", "cat", "/sdcard/ui.xml", binary=True)
        try:
            root = ET.fromstring(xml)
        except ET.ParseError:
            time.sleep(1.0)
            continue
        found = []
        for n in root.iter("node"):
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
            if m and n.get("package") == PKG:
                found.append((n.get("text", ""), n.get("content-desc", ""), tuple(map(int, m.groups()))))
        if found:
            return found
        time.sleep(1.0)
    return []


def find(want, seen=None):
    seen = nodes() if seen is None else seen
    for text, desc, box in seen:
        if want(text) or want(desc):
            return box
    return None


def wait(want, seconds=25):
    end = time.time() + seconds
    while time.time() < end:
        box = find(want)
        if box:
            return box
        time.sleep(0.7)
    return None


def tap(box):
    sh(f"input tap {(box[0] + box[2]) // 2} {(box[1] + box[3]) // 2}")
    time.sleep(1.2)


def shot(name):
    png = adb("exec-out", "screencap", "-p", binary=True)
    with open(os.path.join(out, f"{label}-{name}.png"), "wb") as f:
        f.write(png)


def step(name, want, then=None, seconds=25):
    """Taps the node [want] finds, waits for [then], takes the screen's picture."""
    later = find(is_("Plus tard"))  # the update sheet, should a newer release be out
    if later:
        tap(later)
    box = wait(want, seconds)
    if not box:
        failures.append(f"{name}: nothing to tap")
        shot(name + "-missing")
        return False
    tap(box)
    if then and not wait(then, seconds):
        failures.append(f"{name}: the screen didn't come")
        shot(name + "-missing")
        return False
    shot(name)
    print(f"SMOKE screen {name}: ok", flush=True)
    return True


def is_(s):
    return lambda t: t == s


def starts(s):
    return lambda t: t.startswith(s)


def ms(text):
    """ActivityTaskManager's "+1s234ms"."""
    m = re.match(r"\+(?:(\d+)s)?(\d+)ms", text)
    return int(m.group(1) or 0) * 1000 + int(m.group(2)) if m else None


def cold_start():
    sh(f"am force-stop {PKG}")
    time.sleep(1.0)
    sh("logcat -c")
    started = sh(f"am start -W -n {ACTIVITY}")
    total = re.search(r"TotalTime: (\d+)", started)
    drawn = None
    end = time.time() + 30
    while time.time() < end and drawn is None:
        m = re.search(r"Fully drawn " + re.escape(PKG) + r"\S*: (\+\S+)", sh("logcat -d -s ActivityTaskManager:I"))
        if m:
            drawn = ms(m.group(1))
        else:
            time.sleep(0.5)
    return (int(total.group(1)) if total else None), drawn


def starts_report(name, n=7):
    runs = [cold_start() for _ in range(n)]
    first = [r[0] for r in runs if r[0] is not None]
    drawn = [r[1] for r in runs if r[1] is not None]
    report[name] = {"first_frame_ms": first, "fully_drawn_ms": drawn}
    if not first:
        failures.append(f"{name}: the app didn't start")
        return
    med = lambda v: int(statistics.median(v)) if v else None
    print(f"SMOKE start {name}: first frame median {med(first)} ms {first}; fully drawn median {med(drawn)} ms {drawn}", flush=True)


def frames(name):
    info = sh(f"dumpsys gfxinfo {PKG}")
    get = lambda pattern: (re.search(pattern, info) or [None, None])[1]
    stats = {
        "frames": get(r"Total frames rendered: (\d+)"), "janky": get(r"Janky frames: (\d+ \([\d.]+%\))"),
        "p50": get(r"50th percentile: (\d+)ms"), "p90": get(r"90th percentile: (\d+)ms"),
        "p95": get(r"95th percentile: (\d+)ms"), "p99": get(r"99th percentile: (\d+)ms"),
    }
    report[name] = stats
    print(f"SMOKE frames {name}: {stats}", flush=True)


def recite(name="reciting"):
    """The speech model: downloaded, loaded (ONNX Runtime or the JNI), listening (to the emulator's silence)."""
    download = find(starts("Télécharger"))
    if download:
        tap(download)
        # The card goes once the model is on the phone (the microphone is there before, switched off).
        end = time.time() + 600
        while time.time() < end and find(starts("Télécharg")):
            time.sleep(2)
        if find(starts("Télécharg")):
            failures.append(f"{name}: the model didn't download")
            shot(name + "-missing")
            return
    sh("logcat -c")
    mic = find(is_("Commencer à réciter"))
    if not mic:
        failures.append(f"{name}: nothing to start it")
        return
    tap(mic)
    heard = wait(lambda t: t.startswith("À l") or t.startswith("La reconnaissance vocale n"), 240)
    time.sleep(3)
    shot(name)
    listening = bool(heard) and find(starts("À l")) is not None
    report[name] = "listening" if listening else "not listening"
    print(f"SMOKE {name}: {report[name]}", flush=True)
    for line in sh("logcat -d -s Diag:D").splitlines():
        if re.search(r"(whisper|ctc)\.(library|load|cpu|selftest|text)|model\.|npu\.(init|skipped)|recite\.(start|noModel)", line):
            print("   " + line[-260:], flush=True)
    if not listening:
        failures.append(f"{name}: the model didn't load")
    stop = find(is_("Arrêter"))
    if stop:
        tap(stop)


def compile_with_profile():
    # What Android does on its own some hours after an install (a sideloaded app's profile is
    # written by ProfileInstaller on its first run, then compiled while the phone charges).
    sent = sh(f"am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE {PKG}/androidx.profileinstaller.ProfileInstallReceiver")
    # 1: installed, 2: already there; 3: this Android's profile format is unknown to the library.
    result = re.search(r"result=(\d+)", sent)
    report["profile installer"] = int(result.group(1)) if result else None
    print(f"SMOKE profile installer: result={report['profile installer']}", flush=True)
    if report["profile installer"] not in (1, 2):
        failures.append("the baseline profile wasn't installed")
    print(sh(f"cmd package compile -f -m speed-profile {PKG}", timeout=600).strip()[-200:], flush=True)
    status = re.search(r"\[status=([^\]]+)\]", sh(f"dumpsys package dexopt | grep -A3 '\\[{PKG}\\]'"))
    report["compiled"] = status.group(1) if status else None
    print(f"SMOKE compiled: {report['compiled']}", flush=True)


# ------------------------------------------------------------------ install
adb("uninstall", PKG)
print(adb("install", "-r", apk, timeout=600).strip(), flush=True)
if PKG not in sh(f"pm list packages {PKG}"):
    print("SMOKE FAILED: the APK didn't install (see above)")
    sys.exit(1)
sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
sh(f"pm grant {PKG} android.permission.RECORD_AUDIO")
sh("settings put global window_animation_scale 1; settings put global transition_animation_scale 1; settings put global animator_duration_scale 1")

if len(sys.argv) > 4 and sys.argv[4] == "update":
    # A build older than the latest release: the updater finds it, downloads it and checks it
    # (version.json read, the APK's checksum), then offers it. Not installed here.
    cold_start()
    offered = wait(starts("Mise à jour prête"), 240)
    shot("update")
    print(f"SMOKE update offered: {bool(offered)}", flush=True)
    for line in sh("logcat -d -s Diag:D").splitlines():
        if re.search(r"update\.", line):
            print("   " + line[-220:], flush=True)
    sys.exit(0 if offered else 1)

if starts_only:
    cold_start()
    time.sleep(8)  # ProfileInstaller writes the profile a few seconds into the first run
    compile_with_profile()
    starts_report("compiled with its profile, again", n=9)
    sys.exit(1 if failures else 0)

# ------------------------------------------------------------------ starts
first = cold_start()
report["first_start"] = {"first_frame_ms": first[0], "fully_drawn_ms": first[1]}
print(f"SMOKE start after install: first frame {first[0]} ms, fully drawn {first[1]} ms", flush=True)
if not wait(is_("Accueil"), 40):
    failures.append("home: the app's first screen didn't come")
shot("home")
starts_report("as installed")

# ------------------------------------------------------------------ the screens
size = re.search(r"(\d+)x(\d+)", sh("wm size"))
W, H = (int(size.group(1)), int(size.group(2))) if size else (1080, 2400)
if step("passages", is_("Faḍāʾil"), is_("Āyat al-Kursī")):
    if step("passage", is_("Āyat al-Kursī"), is_("Réciter")):
        if step("recite", is_("Réciter"), lambda t: t.startswith("Télécharger") or t.startswith("Commencer")):
            recite()
        sh("input keyevent 4")
        time.sleep(1.0)
    sh("input keyevent 4")
    time.sleep(1.0)
def keyboard_shown():
    shown = re.search(r"mInputShown=(\w+)", sh("dumpsys input_method"))
    return shown.group(1) == "true" if shown else None


def voice_search():
    """The search by reciting opens, loads the recogniser and listens (the emulator's microphone is silent)."""
    mic = find(is_("Chercher en récitant"))
    if not mic:
        failures.append("voice: no microphone button in the search field")
        return
    sh("logcat -c")
    tap(mic)
    listening = wait(lambda t: t.startswith("Récitez quelques mots") or t.startswith("À l"), 240)
    shot("voice")
    for line in sh("logcat -d -s Diag:D").splitlines():
        if re.search(r"(ctc|whisper)\.(load|selftest)|voice\.|mic\.(open|device)", line):
            print("   " + line[-220:], flush=True)
    print(f"SMOKE voice search: {'listening' if listening else 'not listening'}", flush=True)
    if not listening:
        failures.append("voice: the search by reciting didn't listen")
    stop = find(starts("Arrêter l"))
    if stop:
        tap(stop)
        if not wait(lambda t: t.startswith("Je n") or t.startswith("Ce que j") or t == "Réessayer", 60):
            failures.append("voice: it didn't stop")
    # The sheet goes at a tap above it.
    sh(f"input tap {W // 2} {int(H * 0.08)}")
    time.sleep(1.0)


def search_and_favourite():
    """A reference typed in the search opens its āya and the keyboard goes; the āya is kept as a favourite."""
    field = find(starts("Sourate, verset"))
    if not field:
        failures.append("search: no search field")
        return
    tap(field)
    sh("input text 2:255")
    if not wait(is_("Al-Baqara 255"), 20):
        failures.append("search: 2:255 found nothing")
        shot("search-missing")
        return
    before = keyboard_shown()
    shot("search")
    tap(find(is_("Al-Baqara 255")))
    if not wait(is_("2:255"), 30):
        failures.append("search: the result didn't open at its āya")
        shot("search-open-missing")
        return
    time.sleep(1.5)
    after = keyboard_shown()
    report["keyboard"] = {"while typing": before, "after choosing": after}
    print(f"SMOKE search: opened at 2:255; keyboard shown while typing {before}, after choosing {after}", flush=True)
    shot("search-open")
    if after:
        failures.append("search: the keyboard stayed open over the sūra")
    # Reading mode: a tap on an āya plays nothing, it offers to keep it.
    tap(find(is_("2:255")))
    if step("favourite", is_("Ajouter aux favoris")):
        time.sleep(1.0)
        playing = re.search(r"package=" + re.escape(PKG) + r".*?state=PlaybackState \{state=(\w+)", sh("dumpsys media_session"), re.S)
        if playing and playing.group(1) == "PLAYING":
            failures.append("reading: a tap on an āya played it")
    # Back to the tab (the sheet closed itself on the action), and the search emptied.
    if find(is_("Ajouter aux favoris")) or find(is_("Retirer des favoris")):
        sh("input keyevent 4")
        time.sleep(1.0)
    sh("input keyevent 4")
    time.sleep(1.0)
    clear = find(is_("Effacer la recherche"))
    if clear:
        tap(clear)
    if keyboard_shown():  # the field took the focus again
        sh("input keyevent 4")
        time.sleep(0.8)
    shot("after-search")
    voice_search()
    step("favourites", is_("Favoris"), is_("Al-Baqara 255"))
    step("juz", is_("Juzʾ"), is_("Juzʾ 2"))
    step("hizb", is_("Ḥizb"), is_("Ḥizb 3"))
    step("suras", is_("Sourates"), starts("Al-Baqara"))


def go_while_listening():
    """While an āya is recited, the bar over the text goes to another: the text stays there (it used to
    jump back to the āya recited), a chip offers the way back, and takes it."""
    bar = find(is_("Chercher, aller ailleurs dans le Coran"))
    if not bar:
        failures.append("go: no bar over the text")
        return
    tap(bar)
    if not wait(is_("Aller à…"), 15):
        failures.append("go: the search didn't open over the text")
        shot("go-missing")
        return
    time.sleep(1.0)
    sh("input text 2:150")
    if not wait(is_("Al-Baqara 150"), 20):
        failures.append("go: 2:150 found nothing")
        shot("go-missing")
        sh("input keyevent 4")
        return
    tap(find(is_("Al-Baqara 150")))
    arrived = wait(is_("2:150"), 20)
    time.sleep(9)
    stayed = find(is_("2:150")) is not None and find(is_("Al-Baqara · 150")) is not None
    chip = find(starts("Revenir à l"))
    report["go while listening"] = {"arrived": bool(arrived), "stayed": stayed, "chip": bool(chip)}
    print(f"SMOKE going to 2:150 while 2:255 is recited: arrived {bool(arrived)}, still there after 9 s {stayed}, way back offered {bool(chip)}", flush=True)
    shot("go-while-listening")
    if not arrived or not stayed:
        failures.append("go: the text went back to the āya recited")
    if not chip:
        failures.append("go: no way back to the āya recited")
        return
    tap(chip)
    if not wait(is_("2:255"), 15):
        failures.append("go: the way back didn't bring the āya recited")
        shot("go-back-missing")


def one_player(when):
    """A text's screen has one place to listen from: the bar (no panel, no second player under it)."""
    seen = nodes()
    n = sum(1 for text, desc, _ in seen if desc in ("Écouter", "Pause"))
    report.setdefault("players", {})[when] = n
    print(f"SMOKE players on the sūra's screen {when}: {n}", flush=True)
    if n != 1:
        failures.append(f"player: {n} listening buttons on the sūra's screen {when}")
    if any(text in ("Lecture", "Récitation") for text, _, _ in seen):
        failures.append("player: the switch at the top is back")


sh("settings put secure show_ime_with_hard_keyboard 1")
if step("quran", is_("Coran"), starts("Al-Baqara")):
    search_and_favourite()
    if step("sura", starts("Al-Baqara"), is_("Chercher, aller ailleurs dans le Coran"), seconds=40):
        # The sūra comes back as it was left: at the āya the search opened it at.
        back_there = wait(lambda t: t == "Al-Baqara · 255", 15)
        report["reopened at"] = bool(back_there)
        print(f"SMOKE sūra reopened where it was left (2:255): {bool(back_there)}", flush=True)
        if not back_there:
            failures.append("reader: the sūra didn't reopen where it was left")
            shot("reopen-missing")
        # Playing: the media service, its session, the stream.
        one_player("before listening")
        # The colours of tajwīd: the legend opens from the text's screen, with its switch; Back closes it.
        if step("tajweed", is_("Couleurs du tajwīd"), is_("Lettres en couleurs")):
            if not find(starts("Madd de 6")):
                failures.append("tajweed: the legend has no colours")
            sh("input keyevent 4")
            time.sleep(1.0)
        if step("playing", is_("Écouter"), is_("Pause")):
            time.sleep(6)
            session = sh("dumpsys media_session")
            state = re.search(r"package=" + re.escape(PKG) + r".*?state=PlaybackState \{state=(\w+)", session, re.S)
            report["playback"] = state.group(1) if state else None
            print(f"SMOKE playback state: {report['playback']}", flush=True)
            one_player("while listening")
            go_while_listening()
            pause = find(is_("Pause"))
            if pause:
                tap(pause)
        # The full player behind the bar: the transport and the settings; Back closes it.
        if step("player", is_("Ouvrir le lecteur"), is_("Vitesse")):
            if not (find(is_("Récitateur")) and find(is_("Minuterie")) and find(is_("Āya précédente"))):
                failures.append("player: its settings or its transport are missing")
            sh("input keyevent 4")
            time.sleep(1.0)
        # Reciting the sūra from the bar, and back to its text.
        if step("sura-recite", is_("Réciter")):
            start = find(is_("Depuis le début de la sourate"))
            if start:
                tap(start)
            if not wait(lambda t: t.startswith("Télécharger") or t.startswith("Commencer"), 25):
                failures.append("sura-recite: the recitation didn't open")
                shot("sura-recite-missing")
            sh("input keyevent 4")
            time.sleep(1.0)
            if not wait(lambda t: "ذَٰلِكَ" in t or t == "Réciter", 20):
                failures.append("sura-recite: the sūra didn't come back")
        sh(f"dumpsys gfxinfo {PKG} reset")
        for _ in range(12):
            sh(f"input swipe {W // 2} {int(H * 0.72)} {W // 2} {int(H * 0.28)} 220")
            time.sleep(0.5)
        frames("sura scrolled")
        shot("sura-scrolled")
    sh("input keyevent 4")
    time.sleep(1.0)
    sh(f"dumpsys gfxinfo {PKG} reset")
    for _ in range(8):
        sh(f"input swipe {W // 2} {int(H * 0.72)} {W // 2} {int(H * 0.28)} 220")
        time.sleep(0.5)
    frames("sura list scrolled")
step("stats", is_("Stats"))
if step("settings", is_("Réglages")):
    # Tarteel's Whisper kept as the recogniser (Settings → Reciting aloud): Recite reads with it too.
    for _ in range(12):
        if find(starts("Tarteel ·")):
            break
        sh(f"input swipe {W // 2} {int(H * 0.7)} {W // 2} {int(H * 0.35)} 300")
        time.sleep(0.8)
    if step("settings-whisper", starts("Tarteel ·")):
        if step("passages-2", is_("Faḍāʾil"), is_("Āyat al-Kursī")) and step("passage-2", is_("Āyat al-Kursī"), is_("Réciter")):
            if step("recite-whisper", is_("Réciter"), lambda t: t.startswith("Télécharger") or t.startswith("Commencer")):
                recite("reciting-whisper")
            sh("input keyevent 4")
            time.sleep(1.0)
            sh("input keyevent 4")
            time.sleep(1.0)
step("home-again", is_("Accueil"))

# ------------------------------------------------------------------ compiled with its baseline profile
compile_with_profile()
starts_report("compiled with its profile")

# ------------------------------------------------------------------ verdict
log = sh("logcat -d", timeout=120)
crash = sh("logcat -d -b crash")
lost = [l for l in log.splitlines() if re.search(
    r"UnsatisfiedLinkError|No implementation found|ClassNotFoundException|NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|SerializationException|MissingFieldException", l)]
if PKG in crash:
    failures.append("the app crashed")
    print(crash[-6000:])
if lost:
    failures.append(f"{len(lost)} log lines about lost classes / methods")
    print("\n".join(lost[:40]))
if PKG not in sh(f"pidof {PKG} && echo {PKG}"):
    failures.append("the app is no longer running")
report["failures"] = failures
with open(os.path.join(out, f"{label}.json"), "w") as f:
    json.dump(report, f, indent=1)
with open(os.path.join(out, f"{label}-logcat.txt"), "w") as f:
    f.write(log)
print("SMOKE " + ("FAILED: " + "; ".join(failures) if failures else "OK"), flush=True)
sys.exit(1 if failures else 0)
