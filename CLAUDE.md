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
- Never block on a permission prompt (a session sat on one all night, 12 Oct): `.claude/settings.json`
  pre-approves every tool; if one still asks, find another way rather than wait.
- No pull requests unless asked. Work in small verified steps: run the JVM tests, re-read the
  diff, push, then check CI is green.

## Releasing
- Bump `VERSION` (e.g. `0.1.0` → `0.2.0`) and push to the working branch. CI tags that commit
  `vX.Y.Z` and publishes the GitHub release with `hfd.apk` + `version.json`. Release notes come
  from the matching `## X.Y.Z` section of `CHANGELOG.md` (the first line is what the in-app
  update sheet shows).
- A release build is never cancelled by a later push (its concurrency group includes the plan
  mode), but check the release exists (`list_releases`) before telling the owner. Push nothing
  else until it does: if its publish step fails (a transient 403 on 2 Oct), the next push's build
  sees an unreleased VERSION and publishes it from its own commit (1.13.0 went out that way). A
  publish cut short leaves a draft without assets (HTTP 500, 1.15.5 on 12 Oct): drafts count as
  unreleased, and the publish step deletes one before creating the release (3 tries). The session
  token can't create, edit or delete releases (403).
- versionCode = major·1,000,000 + minor·1,000 + patch. Plain pushes build a test APK
  (`0.dev.<run>`, Actions artifact) that the app never offers.

## Building
- Release and smoke builds run in GitHub Actions (`.github/workflows/build.yml`): push, then read
  the run (`gh api` works here); the "Show compiler errors" step prints Kotlin errors and test
  failures compactly.
- **The app compiles and its screens render in this sandbox** (since 11 Nov: dl.google.com's SDK
  repository answers). Once a session: the command-line tools (the `commandlinetools-linux-…`
  zip named in `https://dl.google.com/android/repository/repository2-3.xml`) unpacked as
  `<sdk>/cmdline-tools/latest`, then `sdkmanager --sdk_root=<sdk> --proxy=http --proxy_host=127.0.0.1
  --proxy_port=<the https.proxyPort of JAVA_TOOL_OPTIONS> "platforms;android-36"
  "build-tools;35.0.0" "platform-tools"` (`yes |` for the licences). Then, with
  `ANDROID_HOME=<sdk>`: `gradle --no-daemon -q -I tools/local/init.gradle :app:compileDebugKotlin`
  (1 min) and `… :app:testDebugUnitTest --tests app.hfd.ScreenshotTest` (1 min; PNGs in
  `build/screenshots`, 1200 px wide: look at them before pushing). The init script puts Google's
  mirror of Maven Central first, for Gradle and for Robolectric's Android images:
  repo.maven.apache.org answers 429 here, and the plugin portal redirects to it. No NDK is needed
  for these two tasks; the APK itself (whisper.cpp, QNN) still builds on CI only.
- Pure-Kotlin logic lives in the `:core` module (no Android): Qur'an data, faḍāʾil dataset,
  playback plan, FSRS, progress model, recitation tracking (`recite/Tracker.kt`). Test it
  locally with the harness in the scratchpad or recreate it: a `settings.gradle.kts` that
  includes `:core` with
  `projectDir = /home/user/hfd/core`, the version catalog from `gradle/libs.versions.toml`, and
  the Maven Central mirror `https://maven-central.storage-download.googleapis.com/maven2` for
  plugins and dependencies; run with the system `gradle` (`gradle -q :core:test`).
- The sandbox also can't reach tanzil.net, sunnah.com, dorar.net (everyayah.com and mp3quran.net
  answer since 10 Nov: the Recite bench and align.py run here, below).
  `.github/workflows/data.yml` runs on pushes touching `tools/**` or `fadail.json`:
  `fetch` downloads the Tanzil text/metadata/translations and commits them (pull before your
  next push), `verify` prints what each cited source says (read it with `get_job_logs`).
- UI checks without a phone: `.github/workflows/screenshots.yml` renders the main screens with
  Robolectric (`app/src/testDebug/.../ScreenshotTest.kt`, French, each screen in night then paper
  in the same state) and commits them to `docs/screenshots` as the website's 840 px WebPs
  (`<screen>.webp`, `<screen>-paper.webp`; `tools/screenshots/normalise.py`). Pull before your
  next push, then look at them (convert to PNG with Pillow). It runs on pushes touching
  `app/src/testDebug`, `tools/screenshots/**` or `tools/screenshots.trigger`; the session token
  can't dispatch or cancel workflows (403), so touch that file to re-render.
- The website (French): `tools/site/build.py` writes `docs/index.html`, `icon.png` and `og.png`
  from `tools/site/template.html` + `site.js`, the bundled Tanzil text (verbatim, with its
  reference), `fadail.json` (only verified virtues, with their sources) and the screenshots;
  `pip install pillow segno`. Check it with Playwright (Chromium in `/opt/pw-browsers`,
  the global `playwright` node module) at 1440 and 390 px in both schemes before pushing. The
  *Site* workflow (`pages.yml`) publishes `docs/` on pushes touching it (a bot's screenshot commit
  doesn't trigger it: push `docs/` yourself). No Glyph Matrix for this app (the owner).
- Recite mode's speech model: `.github/workflows/model.yml` (on pushes touching `tools/model/**`)
  converts Tarteel's Hugging Face models with whisper.cpp v1.7.6 (`convert.sh`), scores them on
  EveryAyah clips (`evaluate.py`, word error per model and decoding), and uploads the files to
  the `speech-model` pre-release (never "latest", so the updater ignores it). The app uses
  `ggml-tiny-ar-quran-q8_0.bin` (43 MB, ~13 % WER, greedy); `SpeechModel.DEFAULT` pins its
  SHA-256, so a re-converted file needs the new checksum there. Keep audio_ctx 0 (a shorter
  context wrecks this model) and chunks ≤ 20 s (it slips past ~25 s). Tarteel's base model doesn't
  load in whisper.cpp (its decoder positions are 448, whisper.cpp expects 1024). whisper.cpp's version is pinned in `app/src/main/cpp/CMakeLists.txt`
  and in `convert.sh`: change both together. Native code is arm64-v8a only. ggml's CPU code ships
  as four builds (`GGML_CPU_ALL_VARIANTS`: ARMv8.0; 8.2 with dot products; + half floats; 8.6
  with int8 matrix multiplies), and `whisper_jni.c` loads the best one the phone runs, by name
  (logged in `whisper.library`; none loaded: `Whisper.available` is false). ARMv8.2 is 2.2× faster
  than 8.0, i8mm 13 % faster again (`armspeed.yml`, GitHub's Neoverse N2). The Nothing Phone (4a)
  Pro is a Snapdragon 7 Gen 4 (SM7750: 1+4 Cortex-A720, 3 A520; i8mm, no SVE; Adreno 722; Hexagon
  v73 NPU, `npu.yml` compiles Tarteel's encoder for it).
  Partial readings every 0.3 s (`Segmenter.PARTIAL_FRAMES`). The bench times each word from the
  reciters' word timings (prepare.py, `audio/words`): lag p50 0.0 s, p90 1.0 s; by place in the āya
  ("LAG BY PLACE"): first word p90 1.6 s, middle 1.0 s, last 0.5 s (steering completes it early).
  Tried and not kept (2 Oct): a partial 100 ms after the voice stops, alone (93.3 % vs 93.7 %) and
  standing for the final reading (first word p90 1.5 s, 93.2 %). The base model reads the owner no
  better than tiny (95 vs 94 of 105 words) and shows words ~1 s later, so tiny stays.
- Reciters: everyayah.com folders (probe new ones with `tools/reference/reciters.py`, run by
  reference.yml). A reciter only published as whole-sūra files (mp3quran.net) gets āya byte
  ranges from `tools/audio/align.py`. `.github/workflows/audio.yml` (on pushes touching
  `tools/audio/**`) cuts the whole Qur'an (the Qur'an section plays any sūra) as eight jobs
  (`WHOLE=1`, a part each), and `merge.py` adds the parts to `assets/audio/<id>.json`, keeping the
  cuts already there unless `FRESH=1` (the passages' word timings and saved āyāt go with them).
  A part only cuts the sūras the asset doesn't hold whole (`todo.py`): to cut one again, take its
  āyāt out of the asset. Without `WHOLE`, align.py with sūras is a trial that writes nothing.
  Read its report: every cut is transcribed; "to look at" lines are misreadings, a syllable of the
  neighbouring āya, or an āya the reciter says twice (5:74, 50:29, 55:27, 92:1). merge.py prints
  "LEFT OUT" / "TWICE" where consecutive cuts skip or repeat audio (`RecitersTest` pins none):
  Ṭā-Hā 128 is recorded 20 dB lower, under the echo in his pauses, and read as a 17 s pause until
  align.py's `level` (a quiet stretch over 4 s inside a sūra is recitation). This sandbox reaches
  mp3quran.net: one sūra runs here in minutes (whisper.cpp built in the scratchpad, `WHOLE=1 OUT=…`).
  ffmpeg is told the cuts are MP3 (it took 37:163 for video and stopped a part).
  The `:core` test requires every passage āya. Re-timing is safe
  for users: saved āyāt whose size no longer matches their range are deleted at start, and the
  stream cache is keyed by the range. The report prints each repair with its scores.

- Recite: `.github/workflows/recite.yml` (on pushes touching `:core` `recite/**`, the bench or
  `tools/recite/**`) streams EveryAyah passages at the owner's microphone level through the exact
  pipeline (`Segmenter` with its high-pass, `Level`, the JNI's decoding in
  `tools/recite/decoder.c`, `Follower`) with the phone's recognition time, and prints words
  right / wrong / missed and how soon they show per case (`ReciteBench`, "Report" step). Cases
  (`tools/recite/prepare.py`) cover noise, a muffled microphone, rumble, restarts, repetitions and
  skipped āyāt. Run it after any change to Recite's logic, and compare with the previous run
  before releasing. Pushing core changes while a bench runs cancels it. Each config runs in four
  parts at once (`HFD_BENCH_SHARD` k/n; one job passed 240 min with the NPU's reading times); the
  summary job adds them up (`tools/recite/summary.py`: read its log, not the parts'). Every case runs steered
  (below); the owner's sessions and the hard cases also free ("free" lines, SUBSET totals). The
  owner's sessions are scored on the words they recited (`tools/recite/owner.tsv`: add each new
  session there, from diag.yml's large-v3 transcription). `HFD_BENCH_ONLY` (a regular
  expression) picks cases for a quick local run (`gradle :core:cleanTest :core:test --tests
  …ReciteBench` with HFD_BENCH, HFD_DECODER, HFD_MODEL: whisper.cpp and the model build and
  download here, and since 10 Nov EveryAyah too: `prepare.py <dir>` makes the cases (60 since 2.10.1) in 2 min,
  a 30 s case then runs in about a minute with `HFD_BENCH_FREE=0`; one gradle run at a time).
  Iterate here, then confirm on CI (a full run takes over an hour).
- GitHub's `ubuntu-latest` is 26.04 since November; on 11 Nov its apt mirrors didn't resolve from
  the runners for hours (apt waits an hour before failing: the bench run of 79d2e42 died there, all
  parts, before any test). `recite.yml` gives apt a few minutes, then takes PyPI's static ffmpeg
  (`imageio-ffmpeg`, "ffmpeg: …" in the step's log says which); the other workflows that install
  ffmpeg (audio, model, npu, words, experiment, levels) still only use apt.
- The owner's kept sessions are gone from the Actions cache (not read for a week: 12 Oct → 10 Nov,
  "Cache not found"): the bench has no "OWNER'S SESSION" until new ones come. diag.yml's two-hourly
  schedule had not fired once in its first hours (10 Nov): don't count on it, read the diagnostics
  when the owner says they recited.
- An utterance the readings stall in (`Stall`, **off**: `Stall.DEFAULT` 0, with `Follower.finalRules`
  false; the whole bench, 10 Nov, one run: as 2.0.1 94.5 % (wrong 42, missed 19, pending 52, red
  shown 9, swaps caught 4/5); stall 1200 ms 94.5 % (34, 6, 72, 19, 3/5); with finalRules 93.9 %
  (47, 6, 72, 20, 4/5): no gain overall, so 2.1.0 reads as 2.0.1). What it is: at a short pause the model stops reading
  while the reciter goes on, and the words after it wait for the utterance's end or are lost and
  counted as skipped (al-Fātiḥa by Muʿayqilī: 1:5, said in the utterance of 1:3–4, never read: 24
  of 29 words, lag p90 3.5 s). When readings stop moving the recitation while 1.2 s are said past
  a pause of 160 ms or more, the utterance is ended at that pause (`Segmenter.splitAt`; the app:
  `recite.stall`, `mic.split`). Ending every utterance at a shorter pause was measured and not
  kept (`HFD_PAUSE_FRAMES`, one run, 58 cases): 400 ms 94.5 % (wrong 42, missed 19, lag p90
  0.3 s), 300 ms 93.8 % (47, 20, 0.1 s), 240 ms 93.1 % (63, 29, 0.1 s): words show sooner but a
  muffled voice's fragments are too short to make out (al-Baqara 1–5 muffled: 33 → 28 of 36).
  Ending an utterance sooner showed a hole: a word read right twice while it was still being said
  (the last word of two partial readings, steered to the text) stayed right whatever the whole
  utterance's reading said: the bench's swapped يُنفِقُونَ, said يُؤْمِنُونَ, read يُنفِقُونَ twice then
  يُنْمِنُونَ. Such a word now stands as the whole reading has it, for the check to rule on
  (`Follower.finalRules`, `whole`); one read right with words after it is kept as before (the
  owner's ٱلدِّينِ, right four times then اتِّينِ on the longer stretch: `LogReplayTest`).
  Tried here and not kept (10 Nov): the isti'ādha and basmala at a higher cost inside a sūra, and
  a continuation's first letter left out at no cost in bias.h (ٱلْحَمْدُ still read أَمْ أُلِ after a
  pause; free decoding reads it حَمْدُ). Wrong words on the bench are 2 % of āyāt' first words, 3 %
  of middle ones, 7.5 % of last ones; a clean reciter's wrong word costs the check 7–13 nats, as
  a real mistake does (9.7): the tolerance can't tell them apart.
- Steered recognition (2 Oct): the app's tiny model misreads the owner's dark voice (large-v3
  reads the same sessions word for word). `app/src/main/cpp/bias.h` (one header for the JNI and
  the bench's decoder) filters whisper.cpp's logits: when the model's best token takes what was
  heard away from every expected continuation (letter skeletons) and a token that doesn't is
  within 4 nats, that one is taken. `Follower.expected` gives the continuations with a cost
  (from the utterance's start 0; a few words back or the āya's start 1; next āyāt 2, 3; with or
  without the isti'ādha and basmala). Without the costs, a next āya's start made garbage look
  on track. `tools/experiment` (experiment.yml) decodes whole recordings free / steered / steered
  toward a wrong passage (a control) / by bigger models: clear recitations come out unchanged.
- The owner's own sessions: with *Send recordings* on, the app uploads each Recite session's raw
  microphone (`session-<passage>-<S_A>-<S_B>.wav`, ≤ 4 min; past a minute in `.part<i>of<n>.wav` parts,
  since ntfy.sh refuses attachments over 2 MB with a 413 (`ntfyprobe.yml`), joined by diag.yml; it
  also refuses past ~20 MB of attachments in 3 h, about two and a half 4-minute sessions: a session
  joined from k < n parts lacks its end). `diag.yml` prints its spectrum and a
  large model's transcription, and keeps it in the Actions cache (not published); touch
  `tools/recite/owner.trigger` to replay the kept sessions in `recite.yml` ("OWNER'S SESSION"
  lines, and "+boost" lines with `Clarity`'s highs boost, which the app doesn't use: it made muffled
  speech worse). ntfy keeps attachments ~3 h: run diag.yml soon after.
- The owner recites with earbuds on (Nothing Ear (3)); Android records from the phone's own mic
  unless asked otherwise, and the phone away from the mouth gives nothing usable (bench "pocket"
  and the owner's 29 Sept session: 0–1 words). Recite listens through connected earbuds
  (`recite/HeadsetMic.kt`: communication mode + `setCommunicationDevice`, LE Audio first); the
  bench's "sco" cases (8 kHz call audio, mic near the mouth) end ~99 % right. `mic.device` /
  `mic.headset` in the diagnostics say which mic was used. The owner's phone mic always sounds
  muffled (~1 % above 1 kHz) yet is read well since steering, so the muffled warning only shows when
  the text also follows under a word per 4 s of the last ~30 s of speech (`MuffledWarning`; bench:
  "MUFFLED WARNING", none on the owner's sessions, on the pocket cases).
- What the owner's audio showed (29–30 Sept): the phone held in front (17:17, 1.8.2) followed
  3:1–3:3; the phone away from the mouth and the earbuds' microphone both gave a voice 20–25 dB
  darker from 300 Hz up, with no cut-off (third-octave table in diag.yml), which the app's model
  can't read (1 word of 3:1–2). Measured on the bench and not kept: a shelf above 1 kHz
  (`Clarity.highShelf`), priming whisper with the text before (80 % → 38 %), equalising each
  utterance to a speaking voice (`Clarity.match`, "+match" lines: owner 1 → 4 words, others
  80.4 % → 79.3 %).
- Checking mistakes (10 Oct): most of the owner's marked mistakes were the model's (الحي القيوم read
  حيظهم). After a final reading that leaves WRONG/MISSED words, `Follower.toCheck` gives the text it
  covered; the recogniser reads the utterance again held to it letter for letter (`bias.h` hfd_check,
  expected "?\n<text>", costs per word after a tab) and a mistake costing ≤ `CHECK_TOLERANCE` nats is
  right; a reading that falls off the text (`CHECK_FAILED`, 99) rights nothing (a skipped āya's
  short words cost 1–5 on any voice). The app logs each check (`recite.check`, with the costs).
  Bench: "checks", and prepare.py's swapped-word cases ("SWAPPED WORDS CAUGHT": real mistakes must
  stay caught; one measured, ينفقون said يؤمنون: 9.7 nats). Words stay white once a final
  reading (after its check) finds them right (`ReciteSession.confirmed`/`wasRight`, the bench's
  `everOk`): a partial reading steered to the text lit swapped words, and they stayed white.
  The bench varies run to run with the same code (other runners' CPUs: 93.5–93.7 %, pending
  69–84): compare configs within one run; its decoder is now built for AVX2 on every runner.
- Speed (10 Oct, Nothing Phone (4a) Pro): a reading is ~560 ms + ~22 ms per second of audio
  (1.14.3), the encoder most of it. Every other reading runs pinned to the big cores, a thread
  each (`whisper.text` pinned / pinError): compare ms at equal seconds before keeping either.
  The decoder costs ~4 ms a token on the phone against 0.9 on x86 at 4 threads (the encoder only
  1.15× slower): watch whether pinning closes that. Parked: a forced prefix decoded in one batch
  (`tools/speed`, ~15 % of a long reading now; worth it once the encoder is fast). 1.15.0 on the
  phone (11 Oct, 32 readings): pinned 0–3 s 476 ms vs 544 unpinned (1.14.3: 582). 1.15.2 with the
  encoder on the NPU (11 Oct, 240 readings, phone mic): the encoder 23.5 ms (p max 32), a reading
  0–3 s 127 ms, 3–6 s 195, 6–9 s 285, 9–12 s 365 unpinned; pinned 7–15 % slower, so since 1.15.3
  only CPU readings are pinned. The decoder is now most of a reading (~25 ms per second of audio):
  the forced prefix in one batch (`tools/speed`) is the next lever.
- NPU (11 Oct, the owner: go): the encoder runs on the Snapdragon's Hexagon (v73) through QNN's C
  API (`npu.c`), the CPU's state kept as the fallback. whisper.cpp is patched for an external
  encoder (`whisper-encoder.patch`: `whisper_hfd_init_state`, a second state of the same model;
  `whisper_hfd_encoder_output` for the diagnostics). **The SM7750's NPU runs no half floats**
  (Qualcomm's compiler with soc_model 86: "The SocModel doesn't support FP16"): 1.15.1's float16
  encoder (`…-qnn242-v73.bin`, compiled for a v68 as "htp_arch" isn't a config key) loaded, ran in
  42 ms and read garbage; the self-test caught it. The keys are `"devices":[{"soc_model":86,
  "dsp_arch":"v73"}]`. QNN's HTP emulator doesn't model a SoC's missing fp16 (it read that binary
  right as an SM7750), but integer graphs are exact there. So `npu.yml` quantizes (16-bit
  activations, 8-bit weights, per channel) on EveryAyah calibration inputs (`calibrate.py`, never
  the owner's recordings), compiles for the SM7750, runs it through the app's own `npu.c` on the
  emulator (`tools/npu/npurun.c`, `HFD_NPU_EMULATE_SOC`; ~8 min a reading) and reads it with
  whisper.cpp (`tools/npu/decode.c`); it publishes `…-sm7750-w8a16.bin` only if the reference
  clip and two held-out āyāt read as with the float encoder (`check.py`). 1.15.2 uses it (10.6 MB,
  u16 in and out, SHA-256 in `NpuEncoder`): on the emulator cosine 0.998 against the float encoder
  (whisper.cpp's q8_0 on the CPU: 0.9996), the three clips word for word. `…-qnn242-v73.bin` on the
  pre-release is 1.15.1's (unused since; `NpuEncoder.fetch` deletes it from the phone). On the
  phone (1.15.2, 11 Oct): npu.init 136 ms, self-test right, `npu.compare` cosine 0.99793 against the
  CPU, no npuError in 240 readings; the owner's al-Fātiḥa and 3:18–19 (phone mic) all GOOD, no
  marked mistake. Not yet measured: the quantized encoder on the owner's dark earbud voice
  (`npu.compare` only reads the reference clip; next: once a session, one live utterance on both). The name
  `encoder-tiny-ar-quran-qnn242-sm7750.bin` is burnt (a fp16 build a gating bug uploaded; the
  workflow deletes it). Under `bash -e`, `test a && test b` only stops on the last: one test a line.
  npu.c reads float32/16 and unsigned 8/16-bit tensors (scale, offset), transposes the mel when
  the graph wants it frame by frame, and refuses a binary compiled for another Hexagon than the
  phone's (QNN's platform info).
  QNN 2.42 in three places that must agree: npu.yml's QAIRT, build.yml's headers (fetched by HTTP
  ranges from the public SDK zip, `tools/npu/headers.py`, never committed: Qualcomm marks them
  confidential) and the `qnn-runtime` AAR (Maven Central; packaging keeps the v73 parts only,
  unpacked so the DSP reads its skel: `ADSP_LIBRARY_PATH`). The manifest names `libcdsprpc.so`
  (Android 12+). At the model's load the app sets QNN up and reads the reference clip on the NPU
  (`npu.selftest`); it is used if that reading is right, else `npu.compare` logs its encoder output
  against the CPU's (cosine, sizes, NaN; also once a run at close when in use). A crash while
  setting it up leaves it off for that version (`npu.skipped`). Diagnostics: `npu.download`,
  `npu.init` (with the NPU's arch / SoC / VTCM and the tensors), `whisper.text` npu / npuMs /
  npuError. The cross-attention K/V still run on the CPU (~10 % of the old encoder).
- The marker (`Follower.voice`, display only) is on the word being said while the voice goes on
  (a partial reading: the last word heard, or the one `holdEdge` holds back) and on the word to say
  once it pauses (the final reading): 1.15.4's next-word marker sat on the next āya's first word
  while the owner held the last one's madd (12 Oct, `FollowerMarkerTest`). Bench "MARKER": when it
  leaves each word (from its end) and reaches it (from its start), by place, against 1.15.4's.
  It follows a phrase or an āya said again from three words
  (`Tracker.voiceAt`); going back takes some five words. An āya
  said again (1.15.4): `Tracker.feed` goes back when the chunk matches earlier text better than
  where it is (`AGAIN_MARGIN`), and the marker sits on the word the chunk ended inside
  (`Tracker.cut`), not the āya's last word (`FollowerAgainTest`).
- An āya said again (1.15.6, the owner's 3:2 twice on earbuds, 12 Oct, `owner-1.15.5-imran-again.log`):
  a reading that follows nothing after one of the same utterance went back leaves it gone back
  (`Follower.keepsGoingBack`, as `followed` for going on); the āya before is a continuation at cost
  1 from an āya's start (`steersPrevious`; it didn't fix that misreading: "الله إلى إلى" either way);
  `Tracker.stretch` prefers an āya's start on a tie (letters without alifs: الله لا = لا إله إلا).
  Bench (one run): ok 94.5 % / 94.4 without the āya before / 94.2 as 1.15.5; "SAID AGAIN" (the
  marker in an āya while it is said again) 158/205 vs 157: EveryAyah repeats rarely misread.
- Red flashes on held vowels (1.15.4): a word turns red only once a final reading has passed it
  (`ReciteSession.decided`, the bench's `redShown`); a partial reading's trailing non-right words
  stay pending (`Follower.holdsEdges`, `HFD_HOLD_EDGE`). Bench "red then right N (shown M)": 577
  (shown 7) without holding, 53 (shown 7) with; ok % and the owner's words unchanged.
- `LogReplayTest` replays the owner's logged readings (`core/src/test/resources/recite/*.log`,
  from `recite.partial` / `recite.heard`) through `Follower`: add a log there when a session goes
  wrong, to reproduce it without audio.

## A CTC recogniser (10 Nov, the owner: test other models, don't hold back)
- `mrmuminov/quran-fastconformer-ctc-ar` (Hugging Face, CC-BY-4.0, from NVIDIA's Arabic
  FastConformer; 109 M parameters, int8 ONNX 174 MB; Uthmani characters with their signs in
  Tanzil's order, 25 frames a second, blank last; no language model) read through the bench in
  place of whisper.cpp (free greedy text, no steering; the check as a forced alignment's mean loss
  per frame × 4): **97.2 %** (1995/2052; wrong 32, missed 13, pending 12; lag p90 0.4 s; swaps
  caught 4/5; said again 165/209) against 94.5 %. Muffled, noisy and pocket cases gain most
  (fatiha-husary-pocket 25/29, whisper: 0–1 words). Per āya, letters wrong: flow 2.0 % vs 4.5,
  muffled 5.8 vs 40.4, noisy 3.3 vs 17.1, pocket 34.6 vs 91.2.
- Speed (`ctcspeed.yml`, Neoverse N2, ONNX Runtime): 3 s of audio in 93 ms on one thread, 57 on
  two; 20 s in 246 ms on four. whisper.cpp tiny on the same machine: 420–500 ms whatever the
  length (1.4 s on one thread). So faster on a plain CPU than whisper on the owner's NPU (127 ms).
- `:core` `ctc/` (outside the Recite bench's trigger paths): `Mel` (NeMo's front end; the filters
  are Slaney's, computed, equal to the model's fb.bin), `Ctc.greedy`, `Ctc.forced` (Viterbi: per
  word the mean loss, the worst character, its frames). `CtcTest` pins them to the reference
  (numpy + ONNX Runtime) on the reference clip. The signs' order matters: compare with Tanzil's
  text, never retyped Arabic.
- In the app since 2.2.0 (`recite/CtcEngine.kt`, `OrtCtcModel`, 2 threads kept on the fast cores):
  `SpeechModel.CTC` on the speech-model pre-release (`ctcmodel.yml` copies the author's file,
  checksum pinned); `ReciteEngine.BEST` reads with it once downloaded (the Recite screen's card;
  Whisper meanwhile), `WHISPER` keeps the old one; a session falls back to Whisper if it doesn't
  load or misreads the reference clip (`ctc.load`, `ctc.selftest`, `ctc.text` with ms). The APK
  grows by 10.7 MB (libonnxruntime.so).
- On the owner's phone (2.5.0, 11 Nov, a search by reciting: 26 readings, phone mic, 2 threads
  pinned): `ctc.load` 978 ms; a reading 34 ms for 0.5 s of audio, 95 ms for 3 s, 143 ms for 5.3 s,
  201 ms for 7.8 s: about 22 ms + 23 ms per second, under the bench's 30 + 26, and under Whisper on
  the NPU (127 ms at 0–3 s, 285 at 6–9). Nothing to tune. The 7.8 s recited found their āya
  (`voice.heard` letters=84 hits=2:11). Not seen yet: a whole Recite session read by this model on
  the phone (words right, `recite.tajweed`).
- `CtcRecognizer` speaks the follower's protocol: the text read freely (nothing to steer), or
  "?\n<text>" held to the frames. A word's check cost is its **worst character** (each at its
  best frame) × 3/7: no character over 7 nats. `tools/ctc/coststudy.py` on the bench's āyāt (2,101
  words said, 624 replaced, 5 swapped in the audio): said p99 5.8, swapped 8.4–14; at 7, 99.5 % of
  the said pass, no swapped, 8 % of random others. The mean loss per frame (the first try) let 3
  of the 5 swapped pass: a long vowel's frames dilute the two letters that differ.
- The bench with the app's code (`HFD_CTC`, in process; `recite.yml`'s `ctc` config, reading time
  30 ms + 26 ms/s): 98.8 %, then **99.6 %** (2043/2052; wrong 4, missed 0, pending 5; swaps 4/5;
  red shown 7) with three fixes to the follower, all from al-Baqara's opening
  (`FollowerLettersTest`): a reading still inside the isti'ādha or basmala says nothing of the
  text (`withoutOpening`; بِسْمِلم counted as الٓمٓ); the disconnected letters' sound takes the words
  after only if it says them right (the model writes alif lām mīm as «بِسٱللَّ ٱلررَّحَحِمٓ»: its
  training labels put the basmala on a sūra's first āya); a word before the position said again
  right in a chunk that goes on is corrected (`Tracker.align`, as `back` does).
- CI (run of c4c6bce, both configs in one run): ctc 2043/2052 as here; Whisper with the same
  follower fixes 1953/2052 (95.2 %; wrong 30, missed 17, pending 52, swaps 4/5, red shown 16)
  against 2.1.0's 1939 (94.5 %; 42, 19, 52, 4/5, 9): the correction rule shows more words red
  then right. The CTC parts take 25 min, Whisper's over an hour.
- CI again on beec78a (11 Nov, 2.5.0's and 2.6.0's Recite code: `Follower.read`, `Ctc.placed`, no
  logic changed): ctc 2043/2052 (wrong 4, missed 0, pending 5), Whisper 1953/2052 (30, 17, 52,
  4/5, shown 16): the same. One difference, ctc swaps 3/5: baqara-opening-husary-flow-swap3w8's
  يُنفِقُونَ said يُؤْمِنُونَ was shown red, then righted (218 readings where the reference run and this
  sandbox have 217; here the same commit catches it, 1/1). Not the audio: that run decoded with
  PyPI's ffmpeg 9 (apt was down), whose WAVs are the same bit for bit as ffmpeg 6's here. What is
  left is the int8 model's arithmetic on that runner's CPU: this swap is borderline for the CTC
  check, on one machine in three so far. The phone is a fourth arithmetic (ARM): unknown there.
- Not caught: يُولَدْ said يَلِدْ (the model writes يُولِدْ, the text's letters: no check runs). Checking
  every word of a whole utterance at a higher bar (10?) would, at some cost in words said: not
  tried. الٓمٓ itself costs 10 nats held to the bench's audio: the letters stay the tracker's.
- **ONNX Runtime for Android phones home from 1.29.0** (a `TelemetryInitializer` provider, an
  HTTP client, `mobile.events.data.microsoft.com/OneCollector` in the library; INTERNET in its
  manifest). 1.28.0 is the last without: pin it, and check any other version's AAR the same way
  before using it.
- Gated and not used (their authors approve each request; never worked around):
  `Quran-Lab/zipformer_p-arabic-v3` (phonemes, streaming, 73 MB) and `Muno459/fastconformer-quran`
  (trained on phone recordings). Only the owner can ask for access.

## The smoke test (10 Nov)
- `.github/workflows/smoke.yml` (on pushes touching it, `tools/smoke/**`, the app's build files):
  the release APK, plain and with R8 (`-Phfd.minify=true`, names kept), on one Android 16
  emulator (Google's x86_64 image runs the arm64 libraries). `tools/smoke/tour.py` installs,
  times cold starts (first frame; "fully drawn" = the text loaded, `ReportDrawnWhen`), walks the
  screens by their French labels, plays a sūra, starts Recite, scrolls al-Baqara, and fails on a
  crash or a lost class. Read the "SMOKE" lines; screenshots are the run's artifact (`gh api
  …/artifacts/<id>/zip` works here). Runners differ too much to compare two jobs, and one
  emulator's starts vary ±300 ms: R8's effect on the start can't be read there (plain 2.0–2.3 s,
  R8 1.8–2.1 s). What R8 gives for sure: APK 19.8 → 12.4 MB, dex 27 → 4.7 MB.
- R8 is on since 2.2.0 (`isMinifyEnabled` unless `-Phfd.minify=false`; `app/proguard-rules.pro`:
  no obfuscation, the JNI's classes and ONNX Runtime's kept). Before a release, the smoke run on
  that code must have passed: it is the only check of the minified APK (the unit tests run
  unminified classes). It covers every screen, playing a sūra, Recite with each recogniser (model
  downloaded, loaded, the reference clip read: `ctc.selftest`, `whisper.load`), and an old build's
  updater offering the latest release. Not covered: reminders firing, installing an update, a
  backup read back, the NPU (no emulator has one).
- Baseline profiles (2.2.1): Compose brought `androidx.profileinstaller` 1.3.1, whose
  `MAX_SUPPORTED_SDK` is 34: on Android 15 and 16 it installs nothing (the emulator:
  `INSTALL_PROFILE` → "result=3", unsupported ART version), so after each update the owner's phone
  ran the app interpreted and JIT-compiled until Android's own idle compilation. 1.4.1 has no upper
  bound (pinned in libs.versions.toml; the tour fails unless the answer is 1 or 2).
  `app/src/main/baseline-prof.txt` adds the app's own methods (wildcards; names are kept by R8)
  to the libraries' rules: 1,928 of its 2,191 are in the built profile (decoded from the APK's
  `assets/dexopt/baseline.prof`: "pro\0010\0", zlib, per dex the hot methods, the class ids, the
  startup bitmap). Its class line (`Lapp/hfd/**;`) matches nothing: 0 of the app's 553 classes
  are in the class set (2,476 library classes are); to fix or drop.
- dl.google.com/android/maven2 answers from this sandbox since 10 Nov (group-index.xml, AARs):
  a library's bytecode can be read here (`javap`) before changing its version.
- Found there: the first start after an install showed in the phone's language (fixed:
  `AppLocale.wrap` on every Android version).

## The Qur'an section: parts, search, modes (2.3.0, the owner's list of 11 Nov)
- `assets/quran/parts.json` (30 ajzāʾ, 240 quarters; a ḥizb is every fourth) from Tanzil's
  quran-data.xml (`tools/fetch-data.sh`; tanzil.net answers again from this sandbox and from CI:
  the file fetched here has the lock's checksum). `Parts`, `PartsTest`: the ajzāʾ are the
  muṣḥaf's known starts; the text's ۞ agree with the quarters but in al-Ḥijr (text 15:49,
  metadata 15:50: a quarter inside a ḥizb, shown nowhere).
- `QuranSearch` (`:core`, `QuranSearchTest`): `search` (references, names, parts, the text by
  skeleton with the words matched, a translation with the match's place: `plain` folds letter for
  letter) and `recited` (a recogniser's writing of a phrase: each word votes for the places it
  occurs at, weighted by rarity, the best 24 are aligned by letters, kept at 70 %). 8 ms a search
  on the JVM once `prepare` has run.
- `RecitedSearchBench` (HFD_BENCH + HFD_CTC, writes search.txt; `benchctc.sh`'s variables): the
  bench's recordings cut every 7 s into clips (wherever they fall, often inside a word), read by
  the CTC recogniser, looked for in the whole text. 11 Nov: 6 s clips, the first place right 76 %,
  the āya among those listed 81 %; 10 s clips 88 % and 94 %. What is left: the disconnected
  letters (read as a basmala), pocket audio, phrases of three short words (under five letters
  once vowels and alifs are gone). A phrase the text says in several places lists them all:
  (`VoiceSearch`, the sheet that listed them, is gone since 2.7.0: below.)
- `QuranScreen`: views (sūras / juzʾ / ḥizb / favourites), results by kind; choosing a result
  hides the keyboard and clears the focus (the bug: it stayed up over the sūra), then
  `AppViewModel.openSura(sura, aya)` (`suraTarget`, which the sūra screen scrolls to and forgets).
  The microphone recites anywhere (2.7.0, below).
- `SuraScreen` is for reading (the owner: a tap on a verse must not play it): the tap opens
  a sheet (favourite, listen from here, recite from here). Listening and reciting are the bar's
  (below). `Favorites` (progress/favorites.json, in backups).
- The smoke tour types `2:255`, checks the keyboard is gone once the āya is open
  (`dumpsys input_method`, `mInputShown`), keeps the āya, finds it under Favoris, opens the juzʾ
  and ḥizb lists and the search by reciting (the emulator's microphone is silent: it only listens).

## The one bar (2.4.0, the owner: "top switch, lecture player menu and currently playing controls are redundant")
- Before: a sūra's screen stacked the Reading | Reciting switch (top), a player panel a fifth of
  the screen high (bottom), and under it the mini player whenever the player held something else
  (it nearly always does: the last session is restored at start). Now `PlayerDock`
  (`components/Player.kt`, placed once in `AppRoot` above the tabs) is the only player on any
  screen: the round button listens / pauses, the text names what it plays, next āya when loaded,
  « Réciter », and the arrow (or a tap on the text) opens `playerSheet`: the full player
  (place, previous / next, offline chip, speed; for a passage the repeats, the pause and the
  range; reciter and timer in their own sheets, which come back to it: `thenBack`).
- What the bar shows: on a tab, what the player holds (a tap opens it). With a text open, that
  text (`NowPlaying.plays(id)`: an āya left by a lesson isn't the text; Play then starts it).
  Something else *heard* while a text is open stays in the bar until the text is left (`heard`),
  so what sounds can always be paused; « Réciter » is only on a text's own bar.
- A passage's Recite pill left its intro for the bar. `ReciteScreen` has no switch (its ✕ did the
  same). Since 2.7.0 the bar's microphone is on every screen (below).
- `Graph.playingPreview` shows the player as holding something without playing (the screenshot
  test: `listen`, `fadail-listening`). The smoke tour counts the listening buttons on a sūra's
  screen (one), opens the full player, and recites from the bar.

## One button, nothing said twice (2.8.0, the owner: "why a microphone button and a play button at once? In Tarteel one button opens a floating menu on a long press. Don't stop until the whole app is rid of what is superfluous (the offline badge that stands out, for one)")
- `PlayerDock` (`components/Player.kt`) has one round button (`DockButton`, tag `dock-button`): it
  pauses what sounds, else does what was done last (`AppSettings.barListen`: listen, else recite;
  its icon and description say which: "Écouter" / "Réciter" / "Pause"); held (`combinedClickable`)
  it opens `DockMenu` (tag `dock-menu`), drawn by `AppRoot` over a scrim above the button, in the
  tree (a Popup's window isn't in the screenshots): Recite from memory / Recite reading / Listen
  (greyed with nothing to listen to); choosing one does it and sets what the button does. No next
  button, arrow, nor Recite pill; `reciteSheet` is gone. A tap on the bar's text opens the full
  player (tag `player-open`, description "Ouvrir le lecteur").
- The bar is drawn only when it has something to say (a text open, something sounding, or the
  player holding something while the button listens); else the button floats alone over the
  screen's corner (`floating`: `PlayerDock` is placed twice, in the content's box and above the
  tabs, and each draws only its case), with "Appui long : écouter ou réciter" beside it until it
  was held once (`barMenuSeen`). `quiet` (Stats, Settings): nothing unless something sounds. The
  tab lists end with 88 dp of room for it.
- The full player: `OfflineLine` (a `Choice` row, absent when a passage is whole on the phone)
  for the old `OfflineChip`; no "Tout" line (the place only once something is loaded).
- Removed as said elsewhere: the search field's microphone, the palette on a text's top bar
  (`ayahTajweedSheet` ends with a button to the legend; Settings has it too), Stats' goal of the
  day, Faḍāʾil's list-editor icon and Home's order chooser (Settings), Home's "all the faḍāʾil"
  link and its "resume listening" card (2.7.0), the count over the sūras. Recite's screen: no
  heard line once a place is followed, the microphone line only when earbuds give a choice, the
  hint only from memory, the tajwīd switch in Settings → Reciting aloud (`settings_recite_tajweed`).
- The smoke tour holds the button (`input swipe` in place, 900 ms), takes "Écouter" then
  "Réciter en lisant" in its choice (`choose`, `find_text`: the item's text, the button has the
  same word as its description), counts one button in the bar, and fails if the search field's
  microphone or the palette come back.

## 2.10.0 lagged: a word checked while the phrase goes on (2.10.1, the owner, 13 Nov: "the tracking is way too laggy: I have the time to say 10 words and it goes up 3 seconds after. I have a powerful phone. Maybe because I was in tajweed mode. Check if you have some data")
- Not speed (the phone: a reading 67 ms at 0–3 s of sound, 296 ms at 9–12 s, 582 ms at 18–21 s;
  a check on the sound just read 0–11 ms, its frames are kept), nor the level. 2.10.0 stopped a
  reading of an utterance still being said at its first word not right (`holdAt`) and left that
  word to the whole utterance's check; letter for letter, one word read a letter off early in a
  phrase kept all that followed dark until the pause, and the owner's phrases last 10–20 s.
- Measured on the owner's own readings: the session of 19:12 on 2.9.1 (al-Baqara 92–98, Strict,
  422 readings logged as `recite.partial` / `recite.heard`), replayed through `Follower` with
  each rule (the check taken to pass: no sound here). Against 2.9.1, each word shows later by:
  2.10.0 p50 0.3 s, p75 1.1, p90 3.2, max 9.4 (30 of 119 words over a second); 2.10.1 p50 0.0,
  p75 0.3, p90 0.6, max 3.1 (2 of 119). **The 2.10.0 session itself never reached ntfy** (the
  last from the phone is that 2.9.1 one, 19:03–19:14; two pulls, 19:46 and 20:05): to look at
  when the owner recites next.
- The fix: `Follower.heard(…, hold = false)` for a reading still growing (the app's with the
  CTC recogniser): only the last word it reaches waits as not said yet (`holdEdge(onlyLast)`);
  `toCheck(id, partial = true)` gives the words not right before it, each **once** per
  utterance (`tried`), the recogniser holds them to the sound it has just read
  (`ReciteSession.heardSound`), `checked` rights those the sound bears out (`righted`: they
  stay right in the utterance's later readings), then `hold(id)` stops at one still not right.
  The whole utterance's check asks again about those refused.
- Asked several times, a word not said ends up passing: with the check as it was, the bench's
  «يُنفِقُونَ» said «يُؤْمِنُونَ» (refused at 9.9 nats on its utterance) cost 4.3 nats a moment into the
  next one, with its sound carried over, and went through (swaps 3/5). So a check made while
  speaking asks a nat more (`CHECK_PARTIAL_MARGIN`: 6 for 7, 4 for 5), and a word a whole
  utterance's check refused (`refused`) is not asked about again until the next one is whole.
- The bench (`HFD_PARTIAL_CHECK`, on with `HFD_STRICT`; 60 cases, this sandbox, CTC): ok
  1978/2118, wrong 0, missed 0, swaps 4/5, skipped āya waited at 3/3, 6 stalls in 5 recitations
  (29 words), lag p50 −0.2 s, p90 0.5; words shown over a second late: an āya's first word 16
  of 271, middle ones 38 of 1,421 (2.10.0: 23 of 260 and 81 of 1,376); checks 209, 122 words
  righted (2.10.0: 48 and 37). `recite.check` with `partial=true` and the costs in the
  diagnostics.
- CI on feed03d (the code of 2.10.1; the ctc parts, 60 cases): ok 1979/2118, wrong 0, missed 0,
  swaps 4/5, skipped āya waited at in each part, 5 stalls in 4 recitations (28 words), lag p90
  0.5 s in every part. Smoke passed.
- The owner's recordings: only sessions closed are sent (`recite.session`, `diag.recording`);
  the 2.9.1 session's was not in the log (the app was updated over it).

## Going back, then on from where one had got to (2.10.1, the owner, 13 Nov, on 2.9.1: "I recite up to the middle of an āya, go back to its start, change my mind and want to pick up from the middle: I'm stuck, I have to recite all the way to the middle again. Same if I recite five āyāt and then one already recited: it goes back there and I can't get back to my progress")
- Why: going back (`Tracker.back`) puts the position after the words said again and the words
  after it pending; since 2.9.0 a chunk may begin three words past the position at most, so
  what had been recited beyond had to be said again word for word.
- `Tracker.frontier`: how far the recitation has got, and `remembered`, each word as it was
  when last recited; kept by `remember()`, which `Follower.heard` calls when a new utterance
  begins (never from a reading still growing: `Mark` / `reset` don't touch them). In `align` a
  chunk may begin, at no cost, at any word before the frontier, or at the frontier itself; the
  words it begins after are as they were (not missed, not counted among the three it may pass
  over) and `Tracker.resumed` is its first word (in `Mark`; `Follower.began`: the check, the
  timing, `edge` and `read` hold only the words this utterance said to its sound). It takes a
  clear match, as passing over a phrase does (three quarters of the chunk's letters; 16
  letters, or as many as the words between hold if fewer), and 6 letters at the frontier itself
  (`RESUME_MIN_LETTERS`). Never past the frontier: what lies beyond was not said
  (`FollowerResumeTest`: 18:5 after 18:1–3 and a return to 18:1 moves nothing).
- The same text said in two places (a refrain): the nearer place wins (the first best end), so
  saying it all again from where one went back is followed word by word as before.
- `ReciteSession.catchUp` shows the words between at once (they were shown before: no sweep).
  `ScreenshotTest`: al-Ikhlāṣ 1–3, the first āya again, then the fourth.
- The bench: two recitations added (60 cases; `prepare.py`): fatiha-dossary-flow-again12342567
  and imran-opening-alafasy-flow-again12324, an āya said again then straight on from where the
  recitation had got to: 29/29 and 37/37 here. The 58 others come out as before to the word
  (this sandbox, CTC: 1911/2052, the same lines case by case).

## No word passed, no word that only resembles (2.10.0, the owner, 13 Nov: "if I do a variation that slightly resembles the next word, it sometimes highlighted it. In either mode, free, strict or tajweed, it should never skip a word: I say 'Qul kana' for 'Qul man kana' and it highlights 'kana' after 'Qul'")
- Why it happened (2.9.x): the tracker took a word as right with most of its letters heard
  (`near`: two of three, three quarters of a longer one), passed over up to three words, and
  the check then righted what it had passed held to the sound at 7 nats (10 when free), where
  a word **not said** still passes 6 % of the time (11 % at 10): `tools/ctc/skipstudy.py`, the
  bench's āyāt with one word more in the text than was said, 3,015 of them (median cost 23
  nats whatever the word's length; under 5: 2.1 %, under 4.5: 1.4 %, under 4: 0.5 %).
- Now, with the CTC recogniser, at **every level** (`ReciteSession.follow`:
  `follower.waits = tracker.strict = !speech.rough`; Whisper's readings as before):
  - `Tracker.strict`: a word is right only with every letter of it heard. `Tracker.nearly`
    keeps which of the others had most of their letters (the old rule).
  - The check (`Follower.Check.unheard`, `checked`): a nearly-right word passes at 7 nats
    (`CHECK_TOLERANCE`), one the free reading did not write at 5 (`CHECK_TOLERANCE_UNHEARD`:
    the bench's said-but-unwritten words, noisy recitations mostly, cost 0–3.8 for six of
    fourteen, 4.1 and 4.5 for two, which at 4 nats each held a reciter up for five words; 6.8
    and more for the rest). `CHECK_TOLERANCE_FREE` is gone.
  - `settle` takes the recitation back to the first word left bad, at every level. Free shows
    nothing red; Strict and Tajwīd show the word stumbled on red (`Follower.stumbled`, also
    under the marker), and it stays red once said right (`ReciteSession.snapshot`).
  - A letter heard once that two words could own (the end of one, the start of the next) is
    the earlier word's (`align`'s trace back, strict only): «وَأَنزَلَت», the breath taken inside
    «ٱلتَّوْرَىٰةَ», had left «وَأَنزَلَ» a letter short.
- **`Carry`** (`:core` `recite/Carry.kt`, `CarryTest`): letter for letter, a pause inside a word
  held the reciter up (Ḥuṣarī's Āl ʿImrān 1–9: seven times in 135 words; neither utterance says
  the word). What a whole utterance ends on after the words it said of the text
  (`Follower.edge`: null after a stumble or going back) is kept, 0.2–2 s, and goes before the
  next utterance's sound for the recogniser (and the check, the timing); only into the next
  utterance, with the recitation still at that word. Where it begins comes from what the
  recogniser itself wrote (`CtcRecognizer.WRITTEN` "#": each written character's frame,
  `Ctc.written`; `Carry.after`: the beginning of the reading nearest the words said, by
  letters), two frames early. Tried first and dropped: holding the words said to the sound
  (`TIMES`): in noise their last characters drift into what follows (bismi llāhi's kasra placed
  in r-raḥīmi) and too little was kept. Diagnostics: `recite.carry` (seconds, word).
- The bench (`HFD_STRICT`, `HFD_CARRY`, on with `HFD_CTC`): recordings can't say a word again,
  so in those said once through as written (43 of the 58, 1,644 words) the tracker is put, at
  each utterance's beginning, at the word then begun (the reciters' word timings; with kept
  sound, the word it begins in): "STALLS n (m words to say again)", and the totals' "said once
  through as written". Each check line lists its words as n(early) or u(nheard) with their nats.
  This sandbox, the CTC model (2.9.1's rule first): strict without the rest 15 stalls in 10
  recitations, 43 words; with the earlier-word rule and `Carry` 9 in 8, 40; with the unheard
  bar at 5 for 4, **7 in 6, 30 words**: pocket (3 stalls, 26 words), noisy 1, rumble 2, call
  audio 1. None in the clean, fast, muffled, paused or loud recitations. Whole bench: ok
  1911/2052 (wrong 0, missed 0, pending 141: the swapped and skipped cases wait at the word,
  as they should, and a recording never takes it up again), swaps caught 4/5, skipped āya
  waited at 3/3, held up 12 of 370 utterances (5 of the 324 said as written), lag p50 −0.1 s,
  p90 0.6 s (2.9.0: −0.3 / 0.4).
- CI on 634d2ee (the code of 2.10.0, the ctc parts): ok 1906/2052 (wrong 0, missed 0, pending
  146), swaps 4/5, skipped āya waited at 4/4 parts, 8 stalls in 7 recitations, 35 words: this
  sandbox's seven and fatiha-maher-noisy (five words: «ٱلرَّحِيمِ», not written in the noise, costs
  4.5 nats here and over 5 on that runner; the bar is that close for it). Smoke passed.
- What is left: three of the seven stalls are at the segmenter's cut of an utterance that
  reaches 20 s with no pause (rumble, call audio): the next utterance begins inside a word and
  the model drops its first word (the lever: begin it a second earlier, the tracker already
  takes words said again at a chunk's start). A word whose letters are the text's but whose
  vowels are not (يُولَدْ said يَلِدْ, كَانَ / كُن: the skeleton has no alif) is still followed: only
  Tajwīd's `sounds` underlines it. A stop consonant an utterance ends on can be cut before its
  release (متشابهات read متشابهان when a long utterance ends at a 200 ms pause).

## The recitation waits; three levels (2.9.0, the owner, 13 Nov: "if I make a mistake and a few verses later there is a match, everything in between is marked red; it should wait where I stopped until I recite the right thing, not look for the next occurrence nor in the rest of the Qur'an: it scrolls away and back and invalidates whole passages. Prepare mistake detection: a free mode by default that marks nothing and only waits, a stricter mode that colours mistakes red, and a tajwīd mode stricter still that checks pronunciation and timing")
- **It never goes on without the reciter.** `Tracker(skip = WAIT_WORDS)` (3; the app's, every
  level): a chunk may pass over or say wrong three words in a row at most, counted with the words
  already left wrong or missed just before the position (`behind`); more, and it stops before them
  (`align`: the start is `FAR` beyond that, a longer run cuts the chunk; `back`: a chunk said again
  doesn't run on past them either). `SKIP_WORDS` (40, the default of `Tracker` and `Follower`) is
  2.8.0's "never stays behind the reciter", kept for the bench's comparison (`HFD_SKIP_WORDS=40`).
  Two ways round it found on the bench and closed (`FollowerWaitTest`): a verse that begins as the
  next («وَٱلَّذِينَ يُؤْمِنُونَ بِمَآ» said for «ٱلَّذِينَ يُؤْمِنُونَ بِٱلْغَيْبِ», 2:3 / 2:4) went on as "said again"
  (`back`), and a few words at a time (three wrong at a reading's end, then three passed by the
  next: `Follower.followed` feeding again from there).
- **Nor elsewhere.** `ReciteSession.elsewhere` only offers the place found (`ReciteUi.elsewhere`,
  a pill under "take it up again"; `goElsewhere`). The first place of a recitation anywhere is
  still found by itself (`Locator`), and a sūra finished goes on into the next.
- **`ReciteLevel`** (`AppSettings.reciteLevel`, FREE by default; the pill at the top of Recite's
  screen, tag `recite-level` → `reciteLevelSheet`; Settings → Reciting aloud; the old
  `reciteTajweed` switch on reads as TAJWEED):
  - FREE: `Follower.waits`. Nothing is ever red. A partial reading shows nothing past a word not
    right (`holdAt`); after a whole utterance and its check, `settle(id)` takes the recitation
    back to the first word it left wrong or missed (what was said after it is to say again),
    `stumbled[w]` (counted as a mistake in the āya's result, so FSRS still learns from it), and
    the screen says "take it up again at the marked word" (`ReciteUi.waiting`) until it moves.
    `Follower.reach` keeps the display from running past such a word while the check runs. A
    word right before the utterance stays right however it is read again. (2.9.x checked more
    leniently when free, 10 nats for 7: gone in 2.10.0, it let words not said pass.) The way out
    of a word the model won't hear: the hint (from memory).
    Only with the CTC model: on Whisper's readings (`SpeechReader.rough`: 47 of the bench's 2,052
    words left wrong or missed, the CTC's 4) free recitation doesn't wait, it only shows nothing red.
    The words a whole utterance ends on, with none right after them, are only not said yet
    (`Follower.atEdge`): the recitation is at them, without a stumble nor the line. A reciter who
    pauses inside a phrase is cut there, sometimes inside a word: Ḥuṣarī's slow recitation holds
    doubled letters and ghunnas so quietly that the segmenter ends the utterance in them
    («وَأَنزَلَ ٱلتَّوْرَىٰةَ» → "وَأَنزَلَت" / "ٱتَّوْرَىىٰةَ": the next utterance says the word, no harm;
    «ءَايَٰتٌ مُّحْكَمَٰتٌ» → "ءَايَـٰتٌ مّ" / "فَمَاتُننْهُنَّ": the word is in neither, and a free reciter is
    held up at it for nothing). All the bench's such holds are Ḥuṣarī's. Since 2.10.0 the two
    utterances' sound is joined there (`Carry`, above).
  - STRICT: until 2.9.1 as 2.8.0 marked mistakes (red after the check, the recitation goes on,
    within the three words); since 2.10.0 it waits as FREE does and the word stumbled on is red.
  - TAJWEED: Strict, plus on every whole utterance read right (`Follower.read`; CTC model only)
    the long vowels' timing (2.5.0) and `sounds`: the text held to the sound, each word's worst
    character (`CtcRecognizer.SOUNDS` "~\n<words>" → "cost:index" per word, `Ctc.Forced.worstAt`);
    over `UNCLEAR` (8 nats) the word is underlined with a broken red line (`ReciteUi.unclear`,
    `unclearSheet` shows the letter with its signs; not the disconnected letters). Display only.
- Measured for `sounds` (`tools/ctc/vowelstudy.py`, the bench's 49 recitations said as the text
  is, 1,533 words; **the text is changed, not the voice**: a reciter's real slip may be written
  by the model as the text has it, as يُولَدْ said يَلِدْ was): words said p99 4.8 nats, 0.13 % over 8;
  one short vowel written as another: over 8 for 91.5 % (7: 92.7, 10: 86.4); one letter as
  another: 94.3 %. Loud (clipped) audio is the weak case (72 %).
- Warsh / Ḥafṣ (the owner: "a setting, or by detection? tell me if it is needed"): not needed to
  follow. The model reads Warsh as well as Ḥafṣ at the letters (EveryAyah `warsh/…`, al-Fātiḥa
  and al-Baqara 2–10, two reciters: letters wrong 1.5 and 2.4 %, Ḥuṣarī's Ḥafṣ 2.2 %), and writes
  what Warsh says (مَلِكِ, عَلَيْهِمُ ءَآنذَرْتَهُمُ, يُومِنُونَ): the follower's letters take those. A Warsh
  *mode* is a different text (Tanzil publishes none: a source to verify), another numbering of
  āyāt, other tajwīd rules (madd badal, the munfaṣil at 6, naql, taqlīl) and lengths: Strict and
  Tajwīd judge by Ḥafṣ. Detection from the model's writing is plausible. Not built: ask first
  whether the owner recites Warsh.
- Recite's screen (the owner, 13 Nov: no more scrolling waveform, a button that reacts to the
  recitation, the microphone's messages in one form that hides neither the marked text nor the
  microphone's bar, no toasts): `MicButton` (tag `recite-mic`) draws the eight-pointed star twice
  behind the disc, turning against each other, breathing in silence and swelling with
  `Recorder.level` while `Recorder.speaking`; on the disc five bars (tap: stop). `Wave` and the
  recorder's bars are gone. `NoticeLine` (tag `recite-notice`): one slot of fixed height between
  the text and the microphone, one `Notice` at a time by gravity (model or microphone error >
  another place offered > lost / waiting > muffled > which microphone, plain): the text never
  moves nor is covered. `ReciteSession.previewListening` shows it without a microphone
  (screenshots `recite-listening`, `recite-muffled`).
- "Muffled microphone" while only thinking (the owner, reciting in a car what they hardly know,
  pauses of 5 s and more): `MuffledWarning.heard(muffled, seconds, moved, symbols, sure)`. The
  car's noise makes utterances of its own in which the CTC model writes under 2 characters a
  second (`VOICE_SYMBOLS`): they say nothing. A voice it is sure of (`CtcOutput.sure`, the mean
  probability of the likeliest symbol over the frames it writes in, ≥ `SURE` 0.6: any voice it
  reads, the text's or not) clears what was counted: the microphone hears, whatever the reason the
  text doesn't follow (the old rule, "muffled and the text hardly follows", fired on hesitations:
  the owner's phone microphone always measures muffled). `tools/ctc/claritystudy.py` (the bench's
  āyāt, and the same made worse): car noise alone 0.8 characters a second (p90 1.8) at 0.43
  sure (p90 0.51); a voice clear / muffled / in a pocket / in that car 3.4+ a second (p10) at
  0.91–0.96 (p10 0.5–0.8); a faint voice under the car's noise 3.5 at 0.50. Whisper doesn't say
  how sure it is (`sure` null): the old rule, without the room's noise. `recite.voice` in the
  diagnostics (symbols, sure, muffled, moved, warn) per whole utterance. No recording of the
  owner in a car exists: the noise was made (1/f under 200 Hz); read `recite.voice` after a drive.
- A name for the store (the owner: "HFD for Hifd, is it the best name? find alternatives; not
  now, when it is production ready"). Looked up 13 Nov (a quick search, no trademark check):
  taken: Tasmee (Eqra, 3.7 M installs), Tasmeea, Tasmi, Mutqin, Tatbith, Rusūkh, Sadr, Makeen,
  Itqan Ayah. Not found in use: Lawḥ (the wooden tablet of the Maghreb's Qur'anic schools: the
  favourite), Istiẓhār, Talqīn (also said of prompting the dying: a connotation), Msīd. Nothing
  renamed: the package id (`app.hfd`) and the updater follow whatever is decided, later.
  The owner's answer (13 Nov): those say nothing to someone who has no Arabic; "Tarteel" is the
  model, a word of the Qur'an that also sounds well in English and French. Second round, words of
  the Qur'an: taken: Mathani (a memorisation app), Moddakir / Mudakir, Tilawa (several), Thabit /
  Thabbit, Hafizun, Tathkera; not found in use: Safara (80:15, the scribes; the favourite),
  Mahfouz (85:22), Dhikra (51:55), Maknoun (56:78). Still a quick search, nothing decided.
- Warsh is not a priority (the owner, 13 Nov): Ḥafṣ is what is wanted. Nothing to build.
- CI on 30bb75e (13 Nov, the code of 2.9.0, both configs in one run): ctc 2023/2052 (wrong 4,
  missed 0, pending 25: the 20 more than 2.8.0's 2043 are the āya skipped in
  baqara-opening-maher-flow-skip1245, waited at; swaps 3/5, the borderline one again on that
  runner, 4/5 here); of 370 whole utterances 7 would hold a free reciter up, 3 of them in the 324
  said as the text is (all Ḥuṣarī's), no muffled warning. Whisper 1902/2052 (wrong 26, missed 8,
  pending 116 against 2.8.0's 30, 17, 52; swaps 4/5): where it mishears more than three words in
  a row the recitation now waits and a recording doesn't take them up again; 19 of its utterances
  said as the text is would hold a free reciter up, which is why free doesn't wait on its readings.
- 2.9.1, from the owner's first session on 2.9.0 (13 Nov, read in the diagnostics; "beginning
  with the isti'ādha and the basmala the search puts me in a place, and al-Baqara recited after
  it is not followed"):
  - `Locator` knew the two formulas letter for letter only. The recogniser has only learnt the
    Qur'an's text, where the isti'ādha is not: it wrote the owner's «أَعَوذُ بِٱللَِّ مِنَ ٱلشَّيْطَـٰنِ ٱلرَّجِيمِ»
    (a letter missing) and EveryAyah's `audhubillah.mp3` «بِسْأَعُوذُ بِٱلللَّهِ…» (with the «بس» it
    begins a sūra with), which then read as an-Naḥl 98's end (`found=16:98 word=4 score=1.0`).
    Now by letters (`attempt`'s `begins` / `starts`: a quarter of the letters may differ; the
    basmala too). `LocatorTest.theIstiadhaAsTheRecogniserWritesItIsNotAPlace`; both location
    benches unchanged on their cached readings (392/400 and 56/58, none wrong).
  - A place found by what was said is a guess until the reciter goes on from it
    (`ReciteSession.borneOut`: `CONFIRM_WORDS` 4 words followed by later utterances): until then
    a whole utterance found elsewhere takes its place by itself (`elsewhere` → `goElsewhere`); once
    borne out it is only offered, as 2.9.0 did from the first word (which held the owner in
    an-Naḥl). A place asked for (`Anywhere.start`, `choose`, a passage) is borne out from the start.
  - **2.9.0 said "I've lost you" while the text followed**: `onHeard` returned the words the
    whole reading added to the partial ones (none) where `Follower.heard` gave those from the
    utterance's start, so every utterance counted as a stray phrase (`recite.voice moved=0`
    throughout, `recite.lost position=8` in a passage followed to word 87). `moved(id)` is from
    where the utterance's first reading found the recitation. Not seen before the release: the
    bench uses `Follower.heard`'s own count, the screenshot test only whole utterances. Now
    `ReciteSession.heardAs(readings…)` gives an utterance as the recogniser does (partial
    readings, then whole, then `afterWhole`: strays, lost, elsewhere), and `ScreenshotTest`
    checks: followed is not lost, two phrases of something else are, the owner's isti'ādha is no
    place, a guess is left for what is said next, a place borne out is not.
  - CI on 12dd08d (2.9.1's code): smoke passed; the Locate bench 391/400 with **one wrong** on that
    run (opening-45-1, al-Jāthiya's opening put at 42:3): its runner read the āya otherwise than
    this sandbox and the run of 12 Nov did («ٱلْكِتَتَـٰبِ», then «ٱٱلَّفِى ٱلسَّمَِ»: the int8 model's
    arithmetic on another CPU, as for the Recite bench's borderline swap). Those readings give
    42:3 with 2.9.0's locator too (replayed here): not the formulas' change. What it shows:
    «مِنَ ٱللَّهِ ٱلْعَزِيزِ ٱلْحَكِيمِ», which three sūras open with, is settled by a few letters of the
    next utterance. A first place being a guess now, the recitation leaves it when what follows
    isn't there.
- The bench (`ReciteBench`): `HFD_SKIP_WORDS`; per case and in the totals "held up" (whole
  utterances that would hold a free reciter: a mistake left after the check at the free
  tolerance), "followed nothing", both also for the recitations said as the text is (there,
  being held up is the app's doing), and "skipped aya waited at" (the cases with an āya skipped:
  the recitation is still at its first word, nothing marked after). A recording can't take a
  word up again, so the waiting tracker's ok % on the skip cases counts as lost what the app
  rightly doesn't follow.

## One microphone: recite anywhere (2.7.0, the owner: "why did you separate reciting and finding a passage? In Tarteel one microphone finds the passage and follows from there")
- Before: Recite needed a text chosen first (a passage; a sūra "from āya N / from the start", a
  sheet asking), and finding a passage by reciting was a sheet of its own in the search field
  (`VoiceSearch`) that only listed places. Now one thing: `ReciteButton` in `PlayerDock` (on every
  screen; with nothing to listen to the bar is that button and a line saying what it does) and the
  search field's microphone both open Recite, which listens at once (`ReciteSession.listensAtOnce`,
  off in the screenshot test). A passage open: that passage, as before (its āyāt go to the
  progress log). Anything else: `Graph.reciteAnywhere(near, start, show)` →
  `ReciteSession(anywhere = Anywhere(…))`, which begins with no text.
- `:core` `quran/Locator` (`LocatorTest`): each reading of what is being said (partial, then
  whole) is looked for with `QuranSearch.recited` until one place stands out (score ≥ 0.8, 0.04
  over the next, two of the text's words said whole, `tight` ≥ 0.6; while still speaking: the same
  place twice and 14 letters). What it knows: the isti'ādha and the basmala before a passage (the
  basmala is 1:1 only when 1:2 follows); what isn't the text's at a phrase's beginning (unknown
  words, and a first word that isn't the place's first word, a «و» or «ف» apart) is set aside and
  the place must stand without it (as a word that isn't the text's at its end); **the model
  writes a sūra's first words as a broken basmala**, its opening letters and more («بِسٱللَّمٓ» for
  الٓمٓ, «بِسٱهِلٱلرَّحَحٰ…» for سَبَّحَ لِلَّهِ, «بِسََِْبَارَّكَ ٱلرَّذِىَ»; al-Fātiḥa's second āya after its basmala
  too): `opening` takes a place found among a sūra's first words, with as many words set aside in
  front of it (two more at most; past the first āya only if they have that «بس»), back to the
  sūra's first word, the sound in front of the phrase for the tracker; a phrase said in several
  places waits for what follows (`candidates`, which the screen lists to choose from) unless one
  is in the sūra `near`, or one alone follows a sūra's opening letters and their sound was heard
  (Āl ʿImrān 2 / Āyat al-Kursī; Yūnus 1 and Luqmān 2 both do, and wait); once a place stands out,
  the utterances before are tried in front of it for as long as they fit (`fits`; a near-tie of
  the longer stretch is settled by the place already found, if 14 letters found it): the place is
  where the recitation began (7:109–111 / 26:34–36, al-Ḥashr 1 / aṣ-Ṣaff 1).
- Measured (12 Nov, `LocateBench`; CI's readings and this sandbox's give the same): of 400
  recitations **392 right, none wrong**, 8 not found in 24 s (start 160/160, inside 157/160,
  opening 39/40, opening after the basmala 36/40): a passage two sūras tell in the same words
  (7:106–108 = 26:31–33: listed to choose from), an āya's last word alone, Ḥuṣarī's slow
  openings of al-Ḥadīd / al-Ḥashr / aṣ-Ṣaff (the same first āya) and Alafasy's of ash-Shūrā.
  Found after 5 s of recitation at the median (p90 9 s; an opening after its basmala 12 s). The
  first version: 382, 10 wrong. `phone()`: 56/58, none wrong (rumble and pocket not found), 9.6 s
  at the median from the passage's first word (the basmala first). Deciding sooner while still
  speaking (10 or 12 letters for 14) gains half a second and gives wrong places (3 and 1): not
  kept; nor a two-letter margin over the next place (no fewer wrong, one more not found).
  The search aligns 48 candidates (24 missed al-Anfāl 13 for a phrase whose rare word was misheard).
- `QuranSearch.recited` (also the typed search's): a place's first and last words are the first
  and last *mostly said* (a sūra's first words were found in the last āya of the sūra before, by
  a few letters); `Recited.whole` (the text's words said letter for letter) and `tight` (letters
  found over the stretch's own letters: letters scattered over a longer stretch are not it).
- `ReciteSession`: the text followed is a `Text` (follower, what is shown, what was right) that
  `place(ref, word)` sets or replaces: the sūra whole, the tracker put at the word found
  (`Tracker.startAt`: what is before is neither said nor missed). The utterance that found the
  place is given to the follower as the locator's phrase (`foundBy`, `Locator.phrase` for its
  later readings; it is not timed for tajwīd). A whole utterance that follows nothing is looked
  for elsewhere (`elsewhere`, `astray`: 14 letters at least, the place followed as `near`): found,
  the place is **offered** (`ReciteUi.elsewhere`, a pill; `goElsewhere`: same sūra `startAt` +
  `Tracker.feed`; another: a new `Text`). Until 2.8.0 the recitation went there by itself (below).
  A sūra finished goes on into the next. `Anywhere.start`: "recite from here" on an āya, no
  looking. `Anywhere.show`: from a sūra's page the page stays shown from the place read while
  the place is looked for (`ReciteUi.view`; one may be reading from it). `ReciteUi.locating`,
  `candidates`, `id`. Closing (`AppViewModel.closeRecite`) opens the sūra at the āya reached.
  Diagnostics: `recite.locate`, `recite.placed`, `recite.elsewhere`.
- How: the arrow beside the microphone (`reciteSheet`): from memory (`reciteShowText` false: the
  text hidden) or reading; choosing one starts.
- `LocateBench` (`tools/locate/sample.py` + `fetch.sh`; `locate.yml` on pushes touching them, the
  locator or the search: four parts, the summary adds them up; here `HFD_LOCATE`, `HFD_CTC`,
  readings kept in `<dir>/readings` so the locator alone is tried again in seconds,
  `HFD_LOCATE_CACHED=1` for only those): 160 places taken at random in the whole Qur'an, heard
  from the āya's first word and from two fifths into it, and 40 sūra beginnings, with and without
  the basmala; four reciters, streamed through `Segmenter` as the app hears them, 24 s at most.
  `phone()` (HFD_BENCH without HFD_LOCATE): the Recite bench's recordings from their start.
  This sandbox reads 7 a minute (four cores): a full run is an hour; CI's parts 15 min.
- The Recite bench on this code (64ec723: `Tracker.startAt` added, nothing else of the follower's):
  ctc 2043/2052 (4, 0, 5; swaps 4/5), Whisper 1953/2052 (30, 17, 52; 4/5): the reference's.
  The smoke tour recites from a passage, from the search field and from a sūra's bar (its page
  shown), and opens the microphone's menu. Not tried on a phone: finding a place by the owner's
  own voice (the benches are reciters' recordings), and going elsewhere mid-recitation
  (`elsewhere`: only its parts are tested, `Locator` and `Tracker.startAt`).

## Where one is (2.6.0, the owner: a bar always there to search from; no jump back to the āya played; no resume card, the app as it was left)
- `SuraScreen`'s `PlaceBar` (fixed over the text): the sūra and the āya at the top (`topAya`), its
  juzʾ and ḥizb; a tap opens `QuranBrowser` over the text (the Coran tab's own search and lists,
  `onClose`: focused in the search field; tags `go` / `go-search`, the tab's `quran` / `search`).
- `ReadingPlace` (`Graph.reading`, progress/reading.json; `QuranPlace` and the Coran tab's resume
  card are gone): the āya at the top of the sūra last read, and whether the text was following the
  recitation there. A sūra opens at: the place asked for (`suraTarget`); else the āya recited, if
  it was being followed; else the reading place. While followed (screen off and app closed
  included) the player moves the place on (`PlaybackEngine.keepPlace`). The list's row of that sūra says so ("Lecture en
  cours · āya N"); the bar listens from there (`player_from_aya`). `AppViewModel` already kept the
  tab and the open text: the app reopens as it was left.
- `Follow` (`components/Reading.kt`, both the sūra's and the passage's screens): the text follows
  the āya recited only while that āya is in view where the text rests (a passage also from its
  very start, `fromStart`); otherwise it stays, and `PlayingChip` (while playing) leads back. The
  bug it replaces: a search result opened while the sūra played was left for the āya played (the
  old "scroll back unless touched in the last 6 s"). The next sūra takes the screen only if the
  recitation was followed. `ScreenshotTest` checks both ways with `Graph.playingPreview` (the bar's
  text after the āya recited moves); the smoke tour goes to 2:150 while 2:255 plays, waits 9 s,
  takes the chip back, and checks the sūra reopens at the āya it was left at.
- A paused player holding another āya of the sūra read resumes there (the bar says which); "listen
  from here" is the āya's sheet.

## Tajwīd (2.5.0, the owner: coloured letters as in some muṣḥafs, configurable; an accurate check in Recite)
- `:core` `tajweed/Tajweed.kt`: `Tajweed.annotate(āya)` → `Span(from, to, rule, part, word, letter)`.
  The rules are read from Tanzil's Uthmani text itself, whose signs carry the muṣḥaf's rulings (a
  nūn with its sukūn is said clearly, without it hidden or merged; the madda sign; the small mīm of
  an iqlāb; the round zero on a letter never read; the upright zero on one read only at a stop),
  and the letters' rules name each case; `doubts` lists what the two disagree on: none in the
  6,236 āyāt (`TajweedTest.theWholeTextIsPlaced`). The āya is read in one breath and stops at its
  end (qalqala and madd ʿāriḍ there; a madda sign on an āya's last vowel is for joining the next:
  `MADD_MUNFASIL`). `Part.DROPPED` is a letter merged into the next (shown grey). Heavy rāʾ and the
  Name's lām: `TAFKHIM` (at a stop the rāʾ follows what is before it; يَسْرِ light).
- Write Kotlin that has `\u` escapes with a shell heredoc or Edit, **not the Write tool**: it
  turned every escape into the character itself (combining signs in the source).
- The reference: alquran.cloud's `quran-tajweed` edition (`[code[letters]`; api.alquran.cloud and
  api.quran.com answer here; `add_repo` for cpfair/quran-tajweed was refused, not worked around).
  `tools/tajweed/compare.sh` → `TajweedReferenceBench.compare`: equal on every rule but where the
  reference misses one (a ghunna on نّ/مّ that also carries a tanwīn, the lām of لِلنَّاسِ, بَسَطتَ, a madd
  before ئِ, a nūn hidden inside a word ending on a tanwīn, أَنَا۠) or is wrong (ٱلْتَقَى's lām, مَنْ رَاقٍ
  merged, a madd on ٱبْنَىْ ءَادَمَ); it has no mutamāthilayn nor tafkhīm. `HFD_TAJWEED_DUMP="1:1 2:255"`
  (or `ra`, `name`, `beats:<āyāt>`) prints an āya's rules word by word.
- The colours (`ui/components/TajweedColors.kt`): `LocalTajweed` (from `AppSettings.tajweed`,
  `tajweedOff`: Family names; TAFKHIM off by default) → `QuranText` adds colour spans; a sign alone
  takes its colour (a tanwīn, a dagger alif: Android shapes the run whole, the screenshots show
  it). Of the natural madds only those written with a small letter are coloured, as the muṣḥafs
  do. `tajweedSheet` is the legend and the switches; `ayahTajweedSheet` an āya's rules.
- The check in Recite (`TajweedCheck`, `AppSettings.reciteTajweed`, off by default; needs the CTC
  model): `Tajweed.beats` (a consonant and what is held until the next), `Ctc.placed` (the frame
  each character is written at; asked through the recogniser with "@\n<text>"), the recitation's
  own usual natural madd as the yardstick. After a whole utterance read right (`Follower.read`),
  its beats are timed on the same sound; a madd of 4–5 under 1.45 natural ones, or of 6 under
  1.9, is a `Finding` (the word underlined; `recite.tajweed` in the diagnostics).
- Measured (`tools/tajweed/fetch.sh` + `timing.py` for the durations, `TajweedCheckBench` with the
  app's code; 137 āyāt × 4 masters, 11 Nov): natural madd beat 0.28–0.56 s, a madd before a hamza
  1.1–1.8 s (p10 0.78–1.5); false alarms 0/213 (and 0/4 lāzim); a madd cut down to the natural
  length caught 50/50 (Ḥuṣarī), 49/53, 46/54, 25/54 (Muʿayqilī, natural 0.28 s: the cut vowel
  still measures 1.5–1.9× with the hamza's closure). **Not kept:** "natural madd too long" (2 % of
  the masters' flagged: stops inside an āya, hidden by a mosque's echo); the ghunna letter by
  letter (the model writes a doubled nūn where the ghunna *ends*, so it is measured over two
  beats: held, 1.8–2.5× the plain window on average, but one in eight of Muʿayqilī's under 1.25×).
  A finer clock than the model's 40 ms frames (the mel's 10 ms) is the next lever.

## The Qur'an section and the look
- Reciting a sūra (2.1.0): the sūra panel's Recite chip opens the passages' `ReciteSession` with
  the sūra's id (`AppViewModel.reciteSura`, from its start or the āya at the top of the screen);
  nothing goes to the progress log. `Tracker.stretch` looks for words said again among the last
  `LOOK_BACK` words and the text's opening, not the whole text (a reading cost the text so far;
  `TrackerLongTextTest` follows al-Baqara in under a second). Recite's screen keeps the marked word
  in view (`BringIntoViewRequester`), and says "lost" after two whole utterances that followed
  nothing (`ReciteUi.lost`).
- The passages shown (2.1.0, the owner: "I should be able to remove some, al-Kahf for example"):
  `AppSettings.hiddenFadail` → `Content.fadail` is the list shown, `allFadail` the reference list
  (still pinned by `FadailDatasetTest`), `fadila(id)` only finds a shown one, `shownAyat` limits
  what is due or counted as memorised (`Graph.hideFadila`, `FadailEditScreen`). Progress is kept.
- A plan item's id holds its place in the plan (`2:255:1#1@12`, 2.1.0): playing every passage, an
  āya comes twice (al-Kahf's end, the closing), and its first place was taken: the plan never ended.
- Qur'an section (1.16.0): a sūra is a `PlaySession` whose id is `QuranListening.id(n)` ("sura:2");
  it plays through once whatever the passages' settings, and `PlaybackEngine.onEnded` loads the
  next (`continueQuran`; next / previous cross sūras). Its reciter is `AppSettings.quranChoice`;
  a whole-sūra recitation recites the sūras all of whose āyāt are cut, else
  `QuranListening.FALLBACK` takes over (`Graph.quranReciter`, 1.16.1: the owner chose at-Turkī
  for al-Kahf; since 2.0.1 he is cut for all 114 sūras, 87 of 6,236 cuts "to look at"). It stays out of the progress log (`ListenTracker`). Sūra names: `SuraNames` (the passages' transliteration, checked against Tanzil's).
- The look (2.0.0, the owner: drop the Nothing theme for one fit for the app): `ui/theme/Theme.kt`.
  Night (deep green, cream, gold) or paper (cream, ink, deep green buttons, ochre). `accent` is
  what is lit, `primary` the main button, `error` mistakes alone, `wordMark` (turquoise) the word
  recited / to say. Source Serif 4 for names and titles (it sets ʿ ʾ tightly, Literata and
  Crimson Pro don't), Source Sans 3 for the rest: static subsets renamed "HFD Serif / Sans"
  inside (Source Sans reserves its name), from google/fonts' variable files with
  `tools/fonts/make.py`. The ornament is the eight-pointed star (`components/Visuals.kt`,
  `Ic.Star`, the launcher icon). No dot-matrix parts any more.

## Debugging on the owner's phone
- The app posts diagnostics (`app/src/main/java/app/hfd/diag/Diag.kt`: `Diag.log(event, …)`) to
  a ntfy.sh topic, kept 12 h. The sandbox can't reach ntfy.sh: write the time into
  `.github/diag.trigger` and push; `.github/workflows/diag.yml` prints the last 12 h (read it with
  `get_job_logs`). It also runs every two hours (cron), so sessions recited while nobody looks are in
  those runs' logs and the Actions cache. Each app run has a session id (shown in Settings → About). Add events where you
  need them; never log audio, location or anything personal beyond what's there. Batches stay under
  4 KB in UTF-8 (Arabic is two bytes a letter): ntfy turns a longer message into an attachment.

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
