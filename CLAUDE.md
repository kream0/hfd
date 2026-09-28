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
  Robolectric (`app/src/testDebug/.../ScreenshotTest.kt`) and commits PNGs to `docs/screenshots`
  (pull before your next push, then look at them). It runs on pushes touching `app/src/testDebug`
  or `tools/screenshots.trigger`; the session token can't dispatch or cancel workflows (403), so
  touch that file to re-render.
- Recite mode's speech model: `.github/workflows/model.yml` (on pushes touching `tools/model/**`)
  converts Tarteel's Hugging Face models with whisper.cpp v1.7.6 (`convert.sh`), scores them on
  EveryAyah clips (`evaluate.py`, word error per model and decoding), and uploads the files to
  the `speech-model` pre-release (never "latest", so the updater ignores it). The app uses
  `ggml-tiny-ar-quran-q8_0.bin` (43 MB, ~13 % WER, greedy); `SpeechModel.DEFAULT` pins its
  SHA-256, so a re-converted file needs the new checksum there. Keep audio_ctx 0 (a shorter
  context wrecks this model) and chunks ≤ 20 s (it slips past ~25 s). The base models don't
  load in whisper.cpp yet. whisper.cpp's version is pinned in `app/src/main/cpp/CMakeLists.txt`
  and in `convert.sh`: change both together. Native code is arm64-v8a only.

## Content rules
- Qur'an text: Tanzil Uthmani, rendered verbatim, never altered; keep Tanzil's notice.
- The passages (and their counts, ×7…) must match the owner's reference app *سور وآيات فاضلة*
  (com.yassine.mob.ayatfadila), as listed with the app's own recitation (SoundCloud,
  "مصحف السور والآيات الفاضلة") and by its publisher (aljamaa.net, "سور وآيات فاضلــة");
  `FadailDatasetTest` pins that list in order. Don't add or drop passages without the owner.
- Narrations in `fadail.json`: never invent a reference. Each source is checked against the
  source text (verify job log, or the hadith dataset on raw.githubusercontent.com). Unverified
  narrations get `verified: false` and stay hidden. Weak (ḍaʿīf / mawḍūʿ) ones only show behind
  the setting. A passage with no known narration says so.
- `:core` tests validate every range against sūra āya counts and require a source per narration.
