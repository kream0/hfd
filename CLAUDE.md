# HFD — notes for Claude

## How the owner works
- No manual steps for them (GitHub UI, git, CI buttons, adb). Do everything yourself: commit,
  push, read CI, release. If something truly needs their authorization, send the exact link
  (GitHub access: https://claude.ai/connect-github) instead of instructions.
- They install and update from their phone (Nothing Phone (4a) Pro, Android 16). The in-app
  updater follows **published releases** only.
- Release on your own, without asking, once a user-facing change is ready and CI is green on
  it (the owner said never to ask for release permission): bump `VERSION`, add its section to
  `CHANGELOG.md`, push. Say which version went out.
- The app is for **learning** (memorising) the passages; the daily wird (recitation counts,
  time-of-day suggestions) is not the goal. Design for the learner first.
- Keep replies short: what changed, what to do on the phone (ideally nothing), known limits.
- No pull requests unless asked. Work in small verified steps: run the JVM tests, re-read the
  diff, push, then check CI is green.

## Releasing
- Bump `VERSION` (e.g. `0.1.0` → `0.2.0`) and push to the working branch. CI tags that commit
  `vX.Y.Z` and publishes the GitHub release with `hfd.apk` + `version.json`. Release notes come
  from the matching `## X.Y.Z` section of `CHANGELOG.md` (the first line is what the in-app
  update sheet shows).
- A release build is never cancelled by a later push (its concurrency group includes the plan
  mode), but check the release exists (`list_releases`) before telling the owner.
- versionCode = major·1,000,000 + minor·1,000 + patch. Plain pushes build a test APK
  (`0.dev.<run>`, Actions artifact) that the app never offers.

## Building
- This sandbox can't reach Google's Maven / Android SDK hosts, so Android builds only run in
  GitHub Actions (`.github/workflows/build.yml`). Push, then read the run with the GitHub MCP
  tools; the "Show compiler errors" step prints Kotlin errors and test failures compactly.
- Pure-Kotlin logic lives in the `:core` module (no Android): Qur'an data, faḍāʾil dataset,
  playback plan, FSRS, progress model, recitation tracking (`recite/Tracker.kt`). Test it
  locally with the harness in the scratchpad or recreate it: a `settings.gradle.kts` that
  includes `:core` with
  `projectDir = /home/user/hfd/core`, the version catalog from `gradle/libs.versions.toml`, and
  the Maven Central mirror `https://maven-central.storage-download.googleapis.com/maven2` for
  plugins and dependencies; run with the system `gradle` (`gradle -q :core:test`).
- The sandbox also can't reach tanzil.net, sunnah.com, dorar.net or everyayah.com.
  `.github/workflows/data.yml` runs on pushes touching `tools/**` or `fadail.json`:
  `fetch` downloads the Tanzil text/metadata/translations and commits them (pull before your
  next push), `verify` prints what each cited source says (read it with `get_job_logs`).
- UI checks without a phone: `.github/workflows/screenshots.yml` renders the main screens with
  Robolectric (`app/src/testDebug/.../ScreenshotTest.kt`, French, each screen in black then paper
  in the same state) and commits them to `docs/screenshots` as the website's 840 px WebPs
  (`<screen>.webp`, `<screen>-paper.webp`; `tools/screenshots/normalise.py`). Pull before your
  next push, then look at them (convert to PNG with Pillow). It runs on pushes touching
  `app/src/testDebug`, `tools/screenshots/**` or `tools/screenshots.trigger`; the session token
  can't dispatch or cancel workflows (403), so touch that file to re-render.
- The website (French): `tools/site/build.py` writes `docs/index.html`, `icon.png` and `og.png`
  from `tools/site/template.html` + `site.js`, the bundled Tanzil text (verbatim, with its
  reference), `fadail.json` (only verified virtues, with their sources) and the screenshots;
  `pip install pillow fonttools segno`. Check it with Playwright (Chromium in `/opt/pw-browsers`,
  the global `playwright` node module) at 1440 and 390 px in both schemes before pushing. The
  *Site* workflow (`pages.yml`) publishes `docs/` on pushes touching it (a bot's screenshot commit
  doesn't trigger it: push `docs/` yourself). No Glyph Matrix for this app (the owner).
- Recite mode's speech model: `.github/workflows/model.yml` (on pushes touching `tools/model/**`)
  converts Tarteel's Hugging Face models with whisper.cpp v1.7.6 (`convert.sh`), scores them on
  EveryAyah clips (`evaluate.py`, word error per model and decoding), and uploads the files to
  the `speech-model` pre-release (never "latest", so the updater ignores it). The app uses
  `ggml-tiny-ar-quran-q8_0.bin` (43 MB, ~13 % WER, greedy); `SpeechModel.DEFAULT` pins its
  SHA-256, so a re-converted file needs the new checksum there. Keep audio_ctx 0 (a shorter
  context wrecks this model) and chunks ≤ 20 s (it slips past ~25 s). The base models don't
  load in whisper.cpp yet. whisper.cpp's version is pinned in `app/src/main/cpp/CMakeLists.txt`
  and in `convert.sh`: change both together. Native code is arm64-v8a only.
- Reciters: everyayah.com folders (probe new ones with `tools/reference/reciters.py`, run by
  reference.yml). A reciter only published as whole-sūra files (mp3quran.net) gets āya byte
  ranges from `tools/audio/align.py` (`.github/workflows/audio.yml`, ~45 min, commits
  `assets/audio/<id>.json`; set `SURAS` in the workflow for a quick trial that writes nothing).
  Read its report: every cut is transcribed; "to look at" lines are misreadings or bad cuts.
  If passages change, re-run it (the `:core` test requires every passage āya). Re-timing is safe
  for users: saved āyāt whose size no longer matches their range are deleted at start, and the
  stream cache is keyed by the range. The report prints each repair with its scores.

- Recite: `.github/workflows/recite.yml` (on pushes touching `:core` `recite/**`, the bench or
  `tools/recite/**`) streams EveryAyah passages at the owner's microphone level through the exact
  pipeline (`Segmenter` with its high-pass, `Level`, the JNI's decoding in
  `tools/recite/decoder.c`, `Follower`) with the phone's recognition time, and prints words
  right / wrong / missed and how soon they show per case (`ReciteBench`, "Report" step). Cases
  (`tools/recite/prepare.py`) cover noise, a muffled microphone, rumble, restarts, repetitions and
  skipped āyāt. Run it after any change to Recite's logic, and compare with the previous run
  before releasing. Pushing core changes while a bench runs cancels it.
- The owner's own sessions: with *Send recordings* on, the app uploads each Recite session's raw
  microphone (`session-<passage>-<S_A>-<S_B>.wav`, ≤ 4 min). `diag.yml` prints its spectrum and a
  large model's transcription, and keeps it in the Actions cache (not published); touch
  `tools/recite/owner.trigger` to replay the kept sessions in `recite.yml` ("OWNER'S SESSION"
  lines, and "+boost" lines with `Clarity`'s highs boost, which the app doesn't use: it made muffled
  speech worse). ntfy keeps attachments ~3 h: run diag.yml soon after.
- The owner recites with earbuds on (Nothing Ear (3)); Android records from the phone's own mic
  unless asked otherwise, and the phone away from the mouth gives nothing usable (bench "pocket"
  and the owner's 29 Sept session: 0–1 words). Recite listens through connected earbuds
  (`recite/HeadsetMic.kt`: communication mode + `setCommunicationDevice`, LE Audio first); the
  bench's "sco" cases (8 kHz call audio, mic near the mouth) end ~99 % right. `mic.device` /
  `mic.headset` in the diagnostics say which mic was used.
- What the owner's audio showed (29–30 Sept): the phone held in front (17:17, 1.8.2) followed
  3:1–3:3; the phone away from the mouth and the earbuds' microphone both gave a voice 20–25 dB
  darker from 300 Hz up, with no cut-off (third-octave table in diag.yml), which the app's model
  can't read (1 word of 3:1–2). Measured on the bench and not kept: a shelf above 1 kHz
  (`Clarity.highShelf`), priming whisper with the text before (80 % → 38 %), equalising each
  utterance to a speaking voice (`Clarity.match`, "+match" lines: owner 1 → 4 words, others
  80.4 % → 79.3 %).
- `LogReplayTest` replays the owner's logged readings (`core/src/test/resources/recite/*.log`,
  from `recite.partial` / `recite.heard`) through `Follower`: add a log there when a session goes
  wrong, to reproduce it without audio.

## Debugging on the owner's phone
- The app posts diagnostics (`app/src/main/java/app/hfd/diag/Diag.kt`: `Diag.log(event, …)`) to
  a ntfy.sh topic, kept 12 h. The sandbox can't reach ntfy.sh: write the time into
  `.github/diag.trigger` and push; `.github/workflows/diag.yml` prints the last 12 h (read it with
  `get_job_logs`). Each app run has a session id (shown in Settings → About). Add events where you
  need them; never log audio, location or anything personal beyond what's there.

## Content rules
- Qur'an text: Tanzil Uthmani, rendered verbatim, never altered; keep Tanzil's notice.
- The passages (and their counts, ×7…) must match the owner's reference app *سور وآيات فاضلة*
  (com.yassine.mob.ayatfadila), as listed with the app's own recitation (SoundCloud,
  "مصحف السور والآيات الفاضلة") and by its publisher (aljamaa.net, "سور وآيات فاضلــة");
  `FadailDatasetTest` pins that list in order. Don't add or drop passages without the owner.
- Narrations in `fadail.json`: the app no longer shows them (1.6.0, the owner: it's for
  memorising; text and translation only), nor the weak-narration setting, nor reminders tied to
  a passage's time (only the review reminder stays). If they come back: never invent a
  reference, check each source against the source text, keep unverified ones hidden.
- `:core` tests validate every range against sūra āya counts and require a source per narration.
