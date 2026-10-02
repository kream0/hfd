# Changelog

## 1.14.1
Recite keeps up with your pace.
- Each reading of your voice takes about half the time: the speech engine now uses the dot-product and half-float instructions of your phone's processor (539 ms instead of 1178 ms on an ARM test machine).
- The text follows your voice every 0.3 s instead of every second, and newly recited words appear over 0.3 s instead of 0.7 s.
- The first words are followed at once: a check that held up the start by about 6 s now runs when you leave Recite.
- Recite needs a processor with these instructions (ARMv8.2, phones from about 2018 on); without them everything else still works.

## 1.14.0
Recite follows your voice better, and what you recited stays white.
- Recognition is now guided by the passage: among what it almost hears, the app prefers the words you should be saying. Replayed on your own recordings (7 sessions, earbuds and phone), it follows 88 % of the words you recited; clear recitations are read exactly as before.
- Say part of an āya again: what you already recited stays white (and doesn't count as a mistake if it's misheard the second time).
- Skip an āya: the text goes on from where you are, once a clear stretch is heard.
- Al-Fātiḥa: الرحمن الرحيم after 1:2 is 1:3, not 1:1 said again.
- While you listen, the word being recited is in electric blue (red stays for mistakes).
- Choosing the phone's microphone uses it even with earbuds connected.

## 1.13.0
While you listen, the word being recited lights up.
- Reading a passage, Learn and Review: in the āya playing, the word the reciter is saying is coloured, word after word, for every reciter (and in the basmala). Follow the text with the voice to learn it.
- When each word starts was measured once on each reciter's recordings; nothing to download.

## 1.12.0
The screen stays on while the verses are shown.
- Reading a passage, Learn, Review and Recite keep the screen on, so it doesn't go dark while you read or recite. It sleeps again as soon as you leave them.

## 1.11.0
A paper theme, and the app in French by default.
- Paper theme: warm paper instead of white, with black ink, like a printed muṣḥaf. Settings → Appearance → Theme: System (follows the phone's dark mode), Dark, or Paper. The status and navigation bars follow the theme you choose.
- Language: French by default, whatever the phone's language. Settings → Appearance → Language: Français, English, or System.
- The app now has a website: https://kream0.github.io/hfd/

## 1.10.0
Recite listens with your earbuds' microphone, and follows again after a pause.
- With earbuds connected, Recite now listens through their microphone. Until now Android kept the phone's own, which, away from your mouth, heard your voice muffled and too faint to be recognised. A line under the waveform says which microphone listens; tap it to switch. While listening the earbuds are in call mode.
- After stopping and starting the microphone again in a recitation, what you said next was ignored for a while and the text didn't move: fixed.
- The muffled-voice warning now says what to do.

## 1.9.0
Recite follows word by word and lets you go back; it points out a muffled microphone and tries the phone's others.
- Word by word: the words you say light up one after the other.
- Going back: start an āya over (back to Alif Lām Mīm), or say a phrase again, and the text goes back there with no mistake. Mistakes are counted when you finish, as they stand then, so a correction counts.
- Your phone's microphone gives a muffled voice (almost nothing above 1 kHz; on recordings muffled like that the speech model loses most words). When the voice comes in muffled, the app now tries the phone's other microphones and keeps the clearest; if all are muffled it says so (a pocket, a hand over the phone).
- The rumble of handling and breath on the microphone is filtered out.
- *Send recordings* (Settings → About) now sends the whole session, so it can be replayed exactly on the test bench.

## 1.8.2
Recite keeps following in a noisy room, and when you say words again before going on.
- In a noisy room your voice is only a little louder than the background: the app took most of it for silence and lost track. Now, once you've started, it keeps listening until your voice really stops.
- Saying the end of an āya again before going on (as one does to find the next words) no longer throws the app off: the repeated words change nothing and it follows on.
- On recordings at your phone's level, noisy rooms included, 95 % of the words are followed right.

## 1.8.1
Recite moves on from the disconnected letters (الم، حم، يس…), and shows what the microphone hears.
- The speech model can't hear the long held letters as words (it made up منذر, فرق on your الم, so the text stayed on the first word). Whatever you say while they're next now counts as them: al-Baqara, Āl ʿImrān, as-Sajda, Yā-Sīn, Ghāfir and ad-Dukhān no longer get stuck at the start.
- While you recite, a waveform like a voice message shows the microphone's last seconds: bright where it counts as your voice, dim where it's the room.

## 1.8.0
Recite follows you as you recite: words light up while you say them, and *Play all* plays every passage in a row.
- Recite no longer waits for a pause: what you've said is recognised again every second, so the text keeps up (about 1.5 s behind). The text shows by default, dim until recited; the eye button hides it and the app remembers. The red box around the next word is gone: the next word glows softly while the app listens.
- More accurate: short stretches are recognised (the model dropped words on long ones), a word heard right stays right, a letter misheard in a short word isn't counted as your mistake, and if words are missed the app catches up with you instead of getting stuck. On recordings of five passages by four reciters, at your phone's microphone level, 97 % of the words are followed right.
- *Play all* (Faḍāʾil tab): all forty passages one after the other, without stopping. The notification and the mini player show the passage playing.

## 1.7.1
Badr at-Turkī: ash-Sharḥ 5–6 and al-ʿAlaq 8–9 now start and end on their own words.
- Ash-Sharḥ 5 and 6 are the same words: the first came out a fraction of a second long. In al-ʿAlaq, 8 held its repetition by the reciter at the start of 9. Āyāt already saved download again when you open the passage.

## 1.7.0
New reciter: Badr at-Turkī (the reciter chip under the player, or Settings → Listening).
- His recitation is only published as whole sūras: the app fetches just each āya from them. A few āyāt (al-Wāqiʿa, al-Aʿlā, ash-Sharḥ 5–6, al-ʿAlaq 8–9) may start or end a word early or late; the passage still plays through without a gap.

## 1.6.1
Recite: your voice is brought to a normal level before recognition, and scraps of words no longer move the text.
- The diagnostics showed the microphone giving a very faint voice and the model catching only bits of words; one bit was taken for "Alif Lām Mīm". The text now only moves on what really matches.
- More diagnostics: a recognition self-test on a reference recitation, and what the microphone sounds like (never the audio itself). *Settings → About → Send recordings* (off) sends a few recordings, only if you switch it on to help fix Recite.

## 1.6.0
Simpler: a passage shows its text and translation, and the only reminder left is for reviews.
- The narrations, their sources and the "Show weak narrations" setting are gone from the passages.
- The reminders for Āyat al-Kursī after prayer, al-Mulk before sleep and al-Kahf on Friday are gone, with the location they needed (it is deleted from the phone). The review reminder stays.

## 1.5.0
Recite: the microphone reacts to a normal voice, and the app can send diagnostics so problems can be fixed from afar.
- Recite listened for a voice louder than phones give it for speech recognition: it now starts at a normal speaking voice, and the ring around the microphone follows your voice.
- *Settings → About → Send diagnostics* (on): what Recite hears and does, and playback errors, go to the developer. Never audio or location.

## 1.4.0
Māhir al-Muʿayqilī is the default reciter, with eleven more to choose from.
- New: al-Muʿayqilī, Yāsir ad-Dawsarī, as-Sudays, ash-Shuraym, al-Qaṭāmī, ash-Shāṭirī, al-ʿAjamī, al-Ḥudhayfī, al-Budayr, Muḥammad Ayyūb, ar-Rifāʿī (the reciter chip under the player, or Settings → Listening).
- If you had kept Alafasy (the old default), the app switches to al-Muʿayqilī once; any reciter you pick afterwards stays. Āyāt download again for the new reciter when you open a passage.

## 1.3.1
Listening plays the passage through to the end: each āya once by default (it was ×3).
- The change applies to you too if you'd kept the old ×3; the Āya chip under the player still sets any count.
- Fix: the order chosen for "Next to learn" is now kept when the app restarts.

## 1.3.0
Choose the order of "Next to learn": shortest first, from the beginning, or from the end.
- Tap the section's title on Home, or go to *Settings → Progress*. "From the end" starts with an-Nās, al-Falaq, al-Ikhlāṣ, the usual way of learning the short sūras.
- "Continue learning" follows the same order.

## 1.2.0
Home is about learning: continue the passages you've begun, then the next ones, shortest first.
- *Continue learning* goes straight into Learn at the next new āya; *Next to learn* offers the passages not begun yet, shortest first (al-Kawthar, al-ʿAṣr, al-Ikhlāṣ…). Reviews due stay at the top.
- The time-of-day and "every day" suggestions are gone; the reminders still cover those moments.
- Listening follows your repeat settings only: the reference app's counts (×7, ×4…) no longer change playback and aren't shown.

## 1.1.0
The passages now match the app سور وآيات فاضلة: its forty passages, in its order, with its counts.
- Al-Fātiḥa, al-Baqara 1–5, Āyat al-Kursī with the two āyāt after it (255–257), 285–286, four passages of Āl ʿImrān, the end of at-Tawba ×7, the end of al-Kahf and al-Kahf in full, as-Sajda, Yā-Sīn, Ghāfir 1–3, ad-Dukhān, the end of al-Fatḥ, al-Wāqiʿa, the musabbiḥāt, al-Mulk, the short sūras from al-Aʿlā to an-Nās (az-Zalzala ×4, al-ʿAṣr ×2, al-Kawthar ×3, al-Kāfirūn ×4, an-Naṣr ×4, al-Ikhlāṣ ×3), and the closing.
- Each passage lists its narrations with sources and grading, including the app's own ("two lights": al-Fātiḥa and the end of al-Baqara, Muslim 806); weak ones stay behind the setting, and a passage without a known narration says so.
- A passage plays its count (×7, ×4…) while "Repeat the range" is ×1.
- Progress is kept (it is per āya). The first ten āyāt of al-Kahf, al-Baqara in full and the Zahrāwān are no longer listed.
- New: *Recite* (on every passage). Recite from memory into the microphone; the app follows you word by word, reveals what you've said, marks skipped and wrong words, and *Hint* shows the next one. Each āya finished counts as a review (fewer mistakes, longer interval); Stats shows recitations and accuracy.
- Recognition runs on the phone with Tarteel's Qur'an speech model (43 MB, downloaded the first time you use it). Nothing is sent anywhere.

## 1.0.0
Optional reminders: Āyat al-Kursī after each prayer, as-Sajda and al-Mulk before sleep, al-Kahf on Friday, āyāt due for review.
- *Settings → Reminders*: each one on its own, with its time. Āyat al-Kursī comes 15 minutes after each prayer time, computed on the phone from a location you set once (MWL, UOIF, ISNA, Egyptian, Umm al-Qurā or Karachi method).
- A reminder opens its faḍīla, or plays it straight away with *Listen*. The review reminder only comes when something is due.
- Reminders can arrive a few minutes late while the phone is asleep.

## 0.4.0
Progress per āya: Learn, Review and Test with spaced repetition (FSRS), stats, streak, backup.
- Learn an āya step by step: listen, repeat in the pauses, first word only, recite from memory, reveal and rate; then recite from the start of the range.
- Review what's due today across all faḍāʾil, text hidden; full-faḍīla tests when everything is memorised.
- Strength ring on every āya, progress ring on every faḍīla; Stats tab with streak, daily goal, calendar, āyāt memorised, listening time.
- The app reopens exactly where you left it. Progress is in Android's backup, and can be exported / imported as a file.
- Āya numbers now show as ﴿٤﴾ (the round medallion stayed empty on Android). Long sūras only download the part you play; tap the offline chip to save all.

## 0.3.0
Listen per āya: repeats, gaps to repeat aloud, speed, five reciters, offline audio, earbud controls.
- Repeat each āya ×1–10 or ∞, the range ×1–10 or ∞, a pause of ½×, 1× or 1½× the āya's length after each recitation, speed 0.75–1.25×, basmala before a sūra.
- Reciters: Alafasy, al-Ḥuṣarī, al-Ḥuṣarī Muʿallim (teaching), al-Minshāwī, ʿAbd al-Bāsiṭ (everyayah.com).
- The playing āya is highlighted and kept in view; tap an āya to play from it, long-press to repeat it or set a sub-range.
- Notification, lock screen and earbuds: next / previous jump by whole āya; "Āyat al-Kursī · 2:255 · 3/5". Play on the earbuds resumes where you left off.
- A faḍīla's āyāt download when you open it (offline chip red → green); streamed ones are cached.
- Sleep timer: end of this faḍīla, or 15 / 30 / 60 min.

## 0.2.0
Faḍāʾil list and reading view: 20 verified entries with sources and gradings, Tanzil's Uthmani text in Amiri Quran, optional French / English translation.
- List grouped by occasion; Home suggests what fits the time of day.
- Weak and fabricated narrations only behind *Settings → Reading → Show weak narrations*.
- Text size, translation language, Tanzil attribution in Settings.

## 0.1.0
First installable build: app skeleton, signed releases and the in-app updater (Settings → Updates).
