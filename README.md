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

- **Updates:** on every start the app checks the repo's latest published release. A newer APK
  is downloaded in the background, its SHA-256 checked against the release's `version.json`,
  then the app offers *Install* (Android shows its own confirmation; the first time it asks to
  allow HFD to install apps). *Settings → Updates* has the version, *Check* and an
  auto-download switch. Pre-releases and test builds are never offered.
- **Never lose progress:** settings and progress are included in Android's Auto Backup.

Roadmap: v0.2.0 text and faḍāʾil dataset · v0.3.0 per-āya player · v0.4.0 per-āya progress
(FSRS), Learn and Review · v1.0.0 reminders.

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

## Licences

- App code: personal project.
- Fonts: Doto, Space Grotesk, Space Mono — SIL Open Font License (`licenses/`).
- Playback: AndroidX Media3 (Apache 2.0).
