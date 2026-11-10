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
  download here, and since 10 Nov EveryAyah too: `prepare.py <dir>` makes the 58 cases in 2 min,
  a 30 s case then runs in about a minute with `HFD_BENCH_FREE=0`; one gradle run at a time).
  Iterate here, then confirm on CI (a full run takes over an hour).
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
- Found there: the first start after an install showed in the phone's language (fixed:
  `AppLocale.wrap` on every Android version).

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
  for al-Kahf; since 2.0.1 he is cut for all 114 sūras, 87 of 6,236 cuts "to look at"). It stays out of the progress log (`ListenTracker`). `QuranPlace` keeps the last
  āya (resume). Sūra names: `SuraNames` (the passages' transliteration, checked against Tanzil's).
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
