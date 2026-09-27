# HFD — notes for Claude

## How the owner works
- No manual steps for them (GitHub UI, git, CI buttons, adb). Do everything yourself: commit,
  push, read CI, release. If something truly needs their authorization, send the exact link
  (GitHub access: https://claude.ai/connect-github) instead of instructions.
- They install and update from their phone (Nothing Phone (4a) Pro, Android 16). The in-app
  updater follows **published releases** only.
- Release when a user-facing change is ready and they asked for it ("release it", "ship it"):
  bump `VERSION`, add its section to `CHANGELOG.md`, push. Say which version went out.
- Keep replies short: what changed, what to do on the phone (ideally nothing), known limits.
- No pull requests unless asked. Work in small verified steps: run the JVM tests, re-read the
  diff, push, then check CI is green.

## Releasing
- Bump `VERSION` (e.g. `0.1.0` → `0.2.0`) and push to the working branch. CI tags that commit
  `vX.Y.Z` and publishes the GitHub release with `hfd.apk` + `version.json`. Release notes come
  from the matching `## X.Y.Z` section of `CHANGELOG.md` (the first line is what the in-app
  update sheet shows).
- versionCode = major·1,000,000 + minor·1,000 + patch. Plain pushes build a test APK
  (`0.dev.<run>`, Actions artifact) that the app never offers.

## Building
- This sandbox can't reach Google's Maven / Android SDK hosts, so Android builds only run in
  GitHub Actions (`.github/workflows/build.yml`). Push, then read the run with the GitHub MCP
  tools; the "Show compiler errors" step prints Kotlin errors and test failures compactly.
- Pure-Kotlin logic lives in the `:core` module (no Android): Qur'an data, faḍāʾil dataset,
  playback plan, FSRS, progress model. Test it locally with the harness in the scratchpad or
  recreate it: a `settings.gradle.kts` that includes `:core` with
  `projectDir = /home/user/hfd/core`, the version catalog from `gradle/libs.versions.toml`, and
  the Maven Central mirror `https://maven-central.storage-download.googleapis.com/maven2` for
  plugins and dependencies; run with the system `gradle` (`gradle -q :core:test`).
- The sandbox also can't reach tanzil.net, sunnah.com, dorar.net or everyayah.com.
  `.github/workflows/data.yml` runs on pushes touching `tools/**` or `fadail.json`:
  `fetch` downloads the Tanzil text/metadata/translations and commits them (pull before your
  next push), `verify` prints what each cited source says (read it with `get_job_logs`).

## Content rules
- Qur'an text: Tanzil Uthmani, rendered verbatim, never altered; keep Tanzil's notice.
- `fadail.json`: never invent a reference. Each source is checked against the source text
  (verify job log, or the hadith dataset on raw.githubusercontent.com). Unverified entries get
  `verified: false` and stay hidden. Weak (ḍaʿīf / mawḍūʿ) entries only show behind the setting.
- `:core` tests validate every range against sūra āya counts and require a source per entry.
