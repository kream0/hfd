# HFD

A personal, sideloaded Android app for learning by ear the Qur'an verses and sūras that have
established virtues (faḍāʾil): listen, repeat, test yourself and track progress per āya.
Offline-first, no accounts, no analytics, no ads. English and French UI.

Styled like Nothing OS (black and white, one red accent, dot-matrix type), with the Qur'an text
given room and calm.

## Install

**https://github.com/kream0/hfd/releases/latest/download/hfd.apk**

Open that link on the phone, allow "install unknown apps" for the browser, then install. After
that the app updates itself from new releases. Android 8.0 or newer.

## Features

- **Faḍāʾil, verified:** 20 entries (Al-Fātiḥa, Āyat al-Kursī ×3, end of al-Baqara, end of
  Āl ʿImrān, al-Kahf, al-Mulk, as-Sajda, the three Quls, al-Kāfirūn, al-Baqara and Āl ʿImrān),
  each with its āya ranges, a short faithful paraphrase of the ḥadīth (French / English), its
  sources (sunnah.com / dorar.net links) and grading with the grader. Ṣaḥīḥ and ḥasan show by
  default; weak (ḍaʿīf) and fabricated (mawḍūʿ) narrations only appear in their own, clearly
  labelled group when *Settings → Reading → Show weak narrations* is on.
- **List by occasion:** morning, evening, after prayer, at night, before sleep, Friday, any time,
  then long sūras. Home suggests what fits the time of day (and al-Kahf on Fridays).
- **Reading view:** Tanzil's Uthmani text, verbatim, in Amiri Quran, right-to-left, large, with
  ḥarakāt, waqf marks and the ۝ āya-end medallion with Arabic-Indic numbers. Each sūra's basmala
  is shown above its first āya. Optional translation of meanings under each āya (Hamidullah in
  French, Saheeh International in English; *Auto* follows the app language). Text size S–XL.
- **Per-āya player:** playback is built as an explicit plan, a flat list of items such as
  basmala, āya (repetition 1), gap, āya (repetition 2), gap, …, next āya. Settings: repeat each
  āya ×1/3/5/7/10/∞, repeat the range ×1/2/3/5/10/∞, a pause after each recitation of ½×, 1× or
  1½× the āya's length (to repeat it aloud), speed 0.75–1.25×, reciter, basmala before a sūra,
  and a sub-range of the faḍīla (range chip, or long-press an āya). Changing a setting rebuilds
  the plan from the current āya. Gaps are `hfd://silence/<ms>` items (a WAV of silence made on
  the fly) sized from the āya's measured length.
- **Reciters** (everyayah.com, one MP3 per āya): Mishary Alafasy (`Alafasy_128kbps`),
  al-Ḥuṣarī (`Husary_128kbps`), al-Ḥuṣarī Muʿallim (`Husary_Muallim_128kbps`), al-Minshāwī
  murattal (`Minshawy_Murattal_128kbps`), ʿAbd al-Bāsiṭ murattal (`Abdul_Basit_Murattal_192kbps`).
  Folder names were checked on the server by the *Data* workflow.
- **Media session:** notification, lock screen and Bluetooth earbuds. Next / previous jump to the
  next / previous āya (a `ForwardingPlayer`), not the next repetition; the title reads like
  "Āyat al-Kursī · 2:255 · 3/5". Play on the earbuds with the app closed resumes exactly where
  you left off.
- **Reading along:** the playing āya is highlighted and scrolled into view (unless you just
  scrolled yourself); tap an āya to play from it; long-press to repeat it or start / end the
  range there. Sleep timer: end of this faḍīla, or 15 / 30 / 60 minutes.
- **Offline:** opening a faḍīla downloads all its āya files (resumable, retried when the network
  returns); local files always play first and streamed ones are cached (256 MB). The chip goes
  red → orange → yellow → green as files arrive. *Settings → Listening* shows and clears them.
- **Updates:** on every start the app checks the repo's latest published release. A newer APK
  is downloaded in the background, its SHA-256 checked against the release's `version.json`,
  then the app offers *Install* (Android shows its own confirmation; the first time it asks to
  allow HFD to install apps). *Settings → Updates* has the version, *Check* and an
  auto-download switch. Pre-releases and test builds are never offered.
- **Never lose progress:** settings and progress are included in Android's Auto Backup.

Roadmap: v0.4.0 per-āya progress (FSRS), Learn and Review · v1.0.0 reminders.

## Releasing

Each of these builds the APK and attaches `hfd.apk` + `version.json` to the release:

- **Bump `VERSION`** (e.g. `0.2.0`), add a `## 0.2.0` section to `CHANGELOG.md` (used as the
  release notes) and push. If `v0.2.0` isn't released yet, CI tags that commit and publishes it.
- **Tag:** `git tag v0.2.0 && git push origin v0.2.0`.
- **Actions tab:** run *Build APK* by hand with a version.

Tags must look like `vMAJOR.MINOR.PATCH`; versionCode = major·1,000,000 + minor·1,000 + patch
(`v1.2.3` → `1002003`). Branch pushes run the unit tests and build a test APK (`0.dev.<run>`,
downloadable from the run's artifacts); any release updates it.

## Signing

Without configuration, CI signs with the **public dev key** in `keystore/hfd-dev.jks` (so
updates install over each other, but anyone could sign with it). To use a private key, add
these repository secrets:

```
keytool -genkeypair -keystore hfd.jks -alias hfd -keyalg RSA -keysize 2048 -validity 36500
base64 -w0 hfd.jks   # → KEYSTORE_BASE64
```

`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Android refuses updates
across signing keys, so uninstall the dev-key build once when switching.

## Build locally

JDK 17 and the Android SDK (compileSdk 36):

```
./gradlew :core:test :app:assembleRelease   # → app/build/outputs/apk/release/app-release.apk
```

`:core` is plain Kotlin (Qur'an data, faḍāʾil dataset, playback plan, FSRS, progress) and its
tests run on any JVM.

## Data sources and licences

- **Qur'an text:** [Tanzil Project](https://tanzil.net), Uthmani text v1.1 (with pause marks,
  sajda and rub-el-ḥizb signs), CC BY 3.0, bundled verbatim with its copyright block
  (`app/src/main/assets/quran/quran-uthmani.txt`). The app parses it once into a compact JSON
  cache that carries the same notice. Changing the text is not allowed and the app never does:
  the only processing is setting each sūra's basmala apart from āya 1, the way Tanzil's own XML
  stores it (a unit test rebuilds every original line from the parsed text).
- **Sūra metadata:** Tanzil's `quran-data.xml` → `quran/suras.json`.
- **Translations of meanings:** Muhammad Hamidullah (French) and Saheeh International
  (English), from tanzil.net, bundled as published there.
- **Faḍāʾil dataset:** `app/src/main/assets/fadail.json` (schema 1). Every reference was checked
  against the source text: the sunnah.com pages (fetched by the *Data* workflow's `verify` job,
  which prints each cited page), the hadith collections of
  [fawazahmed0/hadith-api](https://github.com/fawazahmed0/hadith-api), and al-Albānī's gradings
  on dorar.net. Nothing is cited that couldn't be checked; e.g. the Āyat al-Kursī-after-prayer
  entry names an-Nasāʾī's *as-Sunan al-Kubrā* without a number for that reason. `:core` tests
  validate every range against the sūra āya counts and require a source per entry; unverified
  entries (`verified: false`) are never shown.
- **Data refresh:** `tools/fetch-data.sh`, run by `.github/workflows/data.yml`, downloads the
  Tanzil files (checksums in `tools/data.lock`).
- **Fonts:** Amiri Quran, Doto, Space Grotesk, Space Mono — SIL Open Font License (`licenses/`).
- **Playback:** AndroidX Media3 (Apache 2.0).
