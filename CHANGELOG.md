# Changelog

## 2.10.1
After going back, you can take it up again where you had got to, without reciting everything in between again.
- **Back to the start of an āya, then on from its middle.** You recite half an āya, say its first words again, then change your mind: say the words where you had stopped and the recitation is there again. Until now it stayed at the start until you had said everything again up to that point.
- **Onto an āya already recited, then back to where you were.** Reciting an earlier āya by mistake took the recitation there and left you stuck. Now say the next words of where you had got to (or any phrase of what you have already recited) and it follows from there; the words in between stay as they were.
- It still never goes past a word you have not said: only what you have already recited can be skipped over this way.
- Saying everything again from where you went back still works as before, word by word.

## 2.10.0
Reciting never goes past a word you haven't said, at any level, and a word that only resembles the next one no longer lights up.
- **No word is skipped.** Saying "qul kāna" for «قُلْ مَن كَانَ» lit «كَانَ» after «قُلْ». A word the app did not hear now counts only if your voice, listened to again against the text, clearly holds it; otherwise the marker waits on that word.
- **A word that resembles is not the word.** A word counted as said with most of its letters heard, so something close to the next word could light it. Every letter must now be heard; a near miss is listened to again before it counts.
- **Strict and Tajwīd wait too.** At the three levels the recitation stays at the word until you say it. What differs is what you see: Free shows nothing in red; Strict and Tajwīd turn the word you stumbled on red, and it stays red once you say it right, so you see what to work on.
- **A pause inside a word doesn't hold you up.** A breath in the middle of a word cut it in two, and neither half is the word. The app now hears the end of what you said before the pause together with what comes after it.
- Measured on 43 recitations said without a mistake (1,644 words, four reciters): none of the clean, fast, muffled or slow ones is stopped for nothing. In heavy noise, rumble or call-quality audio it still happens (4 times in 10 such recitations, one word to say again each time), and a phone in a pocket is not followed.
- Known limits: a skipped word still slips through about 2 times in 100; a word whose letters are right but whose vowels are not (يَلِدْ for يُولَدْ) is followed, and only Tajwīd underlines it. With the older recognition model (Tarteel's) nothing changes: it mishears too often to wait at each word.

## 2.9.1
Fixed: beginning with "aʿūdhu billāh…" and the basmala no longer sends the recitation to a wrong place, and "I've lost you" no longer shows while the text is following.
- **The isti'ādha and the basmala are no longer taken for a passage.** Said before reciting, the isti'ādha could be heard a letter off and read as the end of a verse of an-Naḥl (16:98): the app went there, and what you recited next (al-Baqara, say) was not followed. They are now recognised as they are heard, not only letter for letter.
- **A first place is a guess until you go on from it.** If the app does place you somewhere and you recite something else instead, it now goes there by itself. Once you have gone on for a few words where it placed you, it stays with you as in 2.9.0, and another passage is only offered.
- **Fixed: "I've lost you: take it up again at the marked word" shown for nothing.** In 2.9.0 it came up after two phrases even when every word was followed (it counted the words wrongly). It now shows only when two phrases in a row really follow nothing.

## 2.9.0
Reciting waits for you: after a mistake the text stays at the word you left, instead of jumping further on and turning what lies between red. And you choose what a mistake does: Free, Strict or Tajwīd.
- **It waits where you are.** Reciting in the Qur'an, a mistake followed by words that also appear a few verses later sent the text there, marked everything in between as missed, then came back when you said the right thing. Now the recitation never leaves you: what isn't the text from the marked word moves nothing, and a line says "Take it up again at the marked word". It goes on as soon as you say that word. Saying a verse again still takes you back to it, as before.
- **It no longer looks elsewhere in the Qur'an by itself.** If you clearly start reciting another passage, the app offers it ("Reciting elsewhere? Al-Kahf 10") and goes there only if you tap it.
- **Three levels**, chosen with the pill at the top of the Recite screen (or in Settings → Reciting aloud):
  - **Free** (the default): nothing is ever marked in red. A word said differently or left out simply isn't lit: the marker waits on it until you say it.
  - **Strict**: a word said differently or left out turns red and the recitation goes on (as it did until now), but never past more than three words.
  - **Tajwīd**: as Strict, with two checks on what you recite right. The long vowels of 4 counts or more that aren't held long enough are underlined (as before, it was a switch in Settings). New: each word is compared with your voice letter by letter, and a word in which a letter or a short vowel wasn't heard as written is underlined with a broken red line; tap it to see which letter.
- **How reliable the pronunciation check is.** On 1,533 words recited correctly by four reciters, 2 were underlined wrongly (0.13 %). With one short vowel of the text changed for another, the word is caught 9 times in 10; with one letter changed, 94 %. That was measured by changing the text, not the voice: a real slip can still be missed, and a muffled voice can be underlined wrongly. It needs the new recognition model.
- **The microphone button is alive.** The strip of bars scrolling under the text is gone. While it listens, the button itself moves: the app's eight-pointed star turns slowly behind it, breathes in silence and swells with your voice, and five small bars on the button rise and fall with what you say.
- **One place for what the app has to tell you.** "Take it up again at the marked word", "I can hardly hear you", a microphone that doesn't answer, another passage offered: each now shows in the same form, in a line of its own between the text and the microphone. Nothing covers the text or the button, and the text no longer shifts when a message comes. No more toast for a refused microphone.
- **Fixed: "muffled microphone" while you were only thinking.** Pausing for several seconds with noise around (a car) could bring the warning up: the noise was taken for a voice that couldn't be read. Now the room's noise doesn't count, and neither does anything you say that is heard clearly, whether or not it is the text. The warning is kept for a voice the app really can't make out.
- Waiting needs the new recognition model. With the older one (Tarteel's), Free only stops marking mistakes and goes on: that model mishears too often to stop you at each word.
- In Free, an āya of a passage you were held up in still counts as recited with a mistake for your learning schedule, even though nothing was shown in red.

## 2.8.0
One button instead of two, and everything that was said twice is gone.
- **One button.** The bar had a play button and a microphone side by side, plus "next" and an arrow. Now it has one round button: it does what you did last (recite, or listen; its icon says which) and pauses what is playing. **Hold it** and a small menu opens above it: Recite from memory, Recite while reading, Listen. Choose one and it starts; the button does the same from then on.
- **On the tabs the bar is gone when it has nothing to say**: the button floats alone in the corner (Home, Faḍāʾil, Coran). It isn't on Stats and Settings unless something is playing.
- **The full player** opens with a tap on the bar's text. The green "Offline" badge and the "All" line are gone: saving for offline is a quiet line among the settings, shown only when there is something to do.
- **Said once, not twice.** Removed: the microphone in the search field (the button recites anywhere), the palette at the top of every text (the colours are reached from a verse's tajwīd, and in Settings), the day's goal on Stats (it is on Home), the list editor's icon on Faḍāʾil and the order chooser in Home's heading (both in Settings), Home's link to the Faḍāʾil tab, the count above the list of sūras.
- **Recite's screen is lighter**: no line of recognised text under the page, the "Microphone: phone" line only when earbuds give a choice, the hint lamp only when reciting from memory; the tajwīd switch moved to Settings → Reciting aloud.

## 2.7.0
One microphone: recite any passage, the app finds it in the whole Qur'an, shows the page and follows you from there.
- **Reciting and finding a passage are one thing now.** Tap the microphone (in the bar at the bottom of every screen, or in the search field) and recite: from a sūra's beginning or the middle of a verse, anything. The app finds where you are, opens that sūra's page at the word you began at, and follows you word by word. No text to choose first, no "from which āya?", no separate search by voice.
- **It listens at once.** Recite no longer waits for a second tap on its microphone.
- **It keeps up with you.** Go to another passage and it finds you there. Finish a sūra and it goes on into the next. Close it and the page stays open where you were: reciting a few words is also how you look a verse up.
- **From a page you are reading**, the page stays on screen while you begin; the marker joins you wherever you start, in that sūra or another.
- **When the text says your words in several places** (a phrase many āyāt end with), it waits for what you say next, or lists the places for you to choose; in a sūra you have open, it takes that sūra's.
- **The arrow beside the microphone** chooses how you recite: *from memory* (the text hidden, each word showing as you say it) or *reading* (the text shown, the marker following your voice).
- It knows the isti'ādha and the basmala before a passage for what they are, and a sūra's opening letters (الٓمٓ, حمٓ, يسٓ…).
- A passage of the Faḍāʾil recited from its own screen is as before, and still counts for your learning.
- **How sure it is.** Tried on 400 recitations taken anywhere in the Qur'an (four reciters; verses heard from their first word, from their middle, sūra beginnings with and without the basmala): 392 found, none placed wrongly; it takes about five seconds of recitation. The eight others are passages two sūras tell in the very same words (the places are listed), or a slow recitation of an opening several sūras share (a few more seconds).
- Home no longer has a "Resume" card for what you were listening to: the bar just below it is that.
- Needs the new recognition model (173 MB, offered on Recite's screen): the older one reads too roughly to find a place.

## 2.6.0
In a sūra, a bar at the top always says where you are and opens the search; the text no longer jumps back to the verse being played.
- **Where you are, always in view.** Over a sūra's text a bar stays in place while you scroll: the sūra and the āya at the top of the screen, with its juzʾ and ḥizb. Tap it: the search and the lists (sūras, juzʾ, ḥizb, favourites) open right there, the keyboard up, with no going back to the Coran tab. Type 2:150, a word, or recite a few words, and the text goes there.
- **Fixed: the text jumped back to the verse being played.** Opening a search result while a sūra was playing showed it for an instant, then returned to the āya recited. The text now follows the recitation only while the āya recited is on screen. Go elsewhere and it stays where you put it; a chip at the bottom ("Back to āya N, playing") brings you back, and following starts again.
- **The app reopens as you left it.** A sūra opens at the āya you were reading, not at its beginning; the list shows which sūra you are reading and where. The "Resume" card of the Coran tab is gone: nothing to resume, you are already there. The play button listens from the āya at the top of the screen ("Listen from āya N").
- When a sūra ends, the next one takes the screen only if you were following the recitation; reading elsewhere, you stay where you are.
- Passages behave the same: scroll away from the āya played and the text stays, with the chip to come back.

## 2.5.0
Tajwīd: the letters in the colours of their rules, and Recite can time your long vowels.
- **Coloured letters.** The Qur'an's text now shows the rules of tajwīd as the printed muṣḥafs of tajwīd do: the reds of the long vowels deepen with their length (2, 2-4-6, 4-5, 6 counts), green for the ghunna (ikhfāʾ, iqlāb, idghām), sky blue for the qalqala, grey for what is not pronounced. The palette button on a text's screen (or Settings → Reading) opens the legend, where each colour, or all of them, can be switched off; the heavy rāʾ and the lām of the Name have a colour too, off until you ask for it.
- **An āya's rules.** Tap a verse of a sūra (or hold one of a passage) → "Tajwīd of this āya": each rule in order, with its word, its name and what to do.
- **How the rules are found.** They are read from the Uthmani text itself, for the reading of Ḥafṣ: nothing is left unplaced in the 6,236 āyāt, and the result was compared word by word with the annotation the coloured editions online share (it is the same, but where that one misses a rule or marks one wrongly). The text is Tanzil's, unchanged: only colour is added.
- **Recite times your long vowels** (the "Tajwīd" switch at the top of Recite, with the new recognition model). A madd of 4–5 or 6 counts that you hold no longer than your own plain long vowels is underlined; a tap says how long it lasted. Tried on four master reciters: none of their 213 such madds is flagged, and one cut short is caught 8 to 10 times in 10 at a measured pace (about half at a fast one). It never corrects a madd held four counts.
- **What that check does not judge**, because timing can't tell it reliably letter by letter: the ghunna, the points of articulation, heavy and light letters, a madd held too long. These stay for a teacher's ear.
- **Home** shows your day in one card (the goal, your streak, the week at a glance, what is due); Stats and a sūra's heading are tidier.

## 2.4.0
One bar to listen and recite from, where three sets of controls were stacked.
- **One bar.** Under a sūra or a passage there is now a single bar, just above the tabs: the round button listens or pauses, then the next āya, **Recite**, and an arrow that opens the full player. It replaces the Reading | Reciting switch at the top of a sūra, the player panel that covered a fifth of the screen, and the second player that showed under it whenever something else was loaded. The text gets that room back.
- **The full player** (the arrow, or a tap on the bar): where the āya is, previous and next, saving offline, and the settings chosen right there: speed, reciter and timer, and for a passage the repeats, the pause and the range.
- **The same bar on every screen.** On the tabs it shows what you were listening to, with the same buttons; a tap opens that text. If something else is still playing when you open a text, the bar keeps it until you leave, so what you hear can always be paused.
- **Recite** closes with its ✕ (the switch it carried did the same). On a passage, Recite moved from under the title to the bar.
- The Coran tab's "Resume" card no longer doubles the bar when a sūra is already loaded.

## 2.3.0
The Qur'an section finds any verse (by a word, a reference, or by reciting a few words), lists by juzʾ or ḥizb, and reads or recites.
- **Search everything.** The search of the Coran tab now finds a reference (2:255, "baqara 255"), a sūra by name or number, "juz 5" or "hizb 12", words of the text typed in Arabic however you spell them (the words found are lit in the results), and words of the translation.
- **Find a passage by reciting.** The microphone in the search field listens to a few words and shows where they are in the Qur'an; a tap opens the text there. It uses Recite's model, on the phone: nothing is recorded or sent.
- **By juzʾ or ḥizb.** The tab lists the sūras, the 30 ajzāʾ or the 60 aḥzāb, each opening the text where it begins; in a sūra's text, a line marks where a juzʾ or a ḥizb starts.
- **Reading mode.** A sūra opens to be read: a tap on a verse no longer plays it. It offers to keep the verse as a favourite, to listen from there, or to recite from there. Favourites are listed in the tab (and saved in backups). Listening is still the panel's play button.
- **Reciting mode.** The switch at the top of a sūra, Reading | Reciting, goes to Recite on that sūra and back.
- **Fixed:** the keyboard stayed open over the sūra after choosing a search result.

## 2.2.1
Faster after each update on Android 15 and 16.
- Android can compile an app ahead of time from a list of the code it starts with. The part that hands Android this list refused Android 15 and later (it stopped at 14), so on those phones the app ran uncompiled for hours after every update, until Android compiled it on its own. It is now handed over on the first start, and the list covers the app's own screens as well as its libraries.

## 2.2.0
Recite hears you better: a new recognition model, more accurate and several times faster.
- **A new recogniser.** Recite can now read with a model made for Qur'an recitation in a different way (a FastConformer by mrmuminov, CC BY 4.0, from NVIDIA's Arabic model): it writes the recitation letter by letter with its vowel signs in one pass, where the Whisper model wrote word after word. On the test bench it gets 99.6 % of words right against 94.5 %, and most of the gain is where it hurt: a muffled voice, noise, the phone away from the mouth (a recitation recorded in a pocket: 28 of 29 words, against 1). A reading takes about 30 ms per second of audio on an ordinary phone processor, several times less than before.
- **To get it:** open Recite; a card offers the model (174 MB, once). Until it is there, and if it ever fails to load, the current model reads as before. Settings → Reciting aloud can keep to the old one.
- **Mistakes are judged on the sound itself.** A word the model wrote differently is checked against the audio letter by letter: it counts as said when no letter of it is missing from the sound. Words really replaced by another are still caught (4 of the bench's 5).
- **The disconnected letters** (الٓمٓ and the like) no longer take the next words with them, and the basmala before them is no longer counted as them; a word marked wrong that you then say right, in a phrase that goes on, turns right. The old model gains from these too (95.2 % on the bench).
- Like the old one, this model recognises words: it is no judge of pronunciation or tajwīd.
- Nothing the app adds sends data anywhere: the library that runs the model is pinned to its last version without telemetry, and every build is checked for it.

## 2.1.0
Recite shows more and follows any sūra, and the list of passages is yours to trim.
- **Recite, on screen.** The word being said stays in view down a long āya (it used to slip under the screen). Words light up smoothly; an āya's number turns gold once recited whole, red with a mistake; a bar counts the words and mistakes so far; the summary gives the total. If two phrases in a row match nothing, the screen says to take it up again at the marked word.
- **Recite any sūra.** In the Qur'an section, a sūra's panel has a Recite button: from its start, or from the āya on screen. A long sūra is followed as fast as a short passage (al-Baqara whole in under a second of work). These recitations stay out of the learning program and its statistics.
- **Your list of passages.** Settings → Passages shown (or the tune button of the Faḍāʾil tab): switch any passage off, al-Kahf for instance. It is then neither listed, offered to learn, reviewed nor played with the others; what you learnt of it is kept and comes back with it.
- **Review:** the card shows the last words of the āya before as a cue.
- **Fixes:** "Play all" never ended and could jump to another passage (āyāt that are in two passages); Play after the end restarts from the first āya; a passage's Play no longer replays the single āya a lesson had played; Next during the basmala goes to its āya; "stop at the end of the sūra" also holds when skipping to the end; Learn starts on the range you chose; the review opened from its reminder no longer says "nothing to review"; on a phone set to another language, the very first start already shows in the app's language.
- Not in this version: cutting a phrase where the recogniser stalls after a short pause. On the whole test bench it lost as much as it gained (fewer skipped words, more left undecided), so the recogniser reads as in 2.0.1. A different recogniser is being measured instead (97 % of words right on the same bench against 94.5 %, and several times faster): next.

## 2.0.1
Badr at-Turkī now recites the whole Qur'an in the Qur'an section, all 114 sūras.
- His recitation is published as whole sūras; every āya of the 114 is now cut out of them (6,236 āyāt, each cut listened to by the speech model), so no other reciter takes over any more and the text follows him āya by āya everywhere.
- The reciter list no longer shows "28 sūras of 114" next to his name.
- Known limit: in a few places where he doesn't pause at the end of an āya (aṣ-Ṣāffāt 22–23 and 61–62 for instance), the highlight changes a word or two early or late. Nothing is skipped or said twice when a sūra plays through.

## 2.0.0
A new look of its own: deep green or warm paper, gold, and a book's type, in place of the Nothing style.
- Colours after an illuminated muṣḥaf. Night: deep green, cream text, gold for what is lit. Paper: warm cream, dark ink, deep green buttons and ochre. Red is kept for mistakes alone, and the word being recited (or the word to say in Recite) is turquoise.
- Names and titles in a serif that writes Faḍāʾil or Āl ʿImrān well (Source Serif 4), the rest in Source Sans 3; no more dot-matrix type nor capitals everywhere.
- Smooth progress rings and bars and plain icons in place of the dots; a tab bar with icons; a sūra's heading framed in gold, as in a muṣḥaf; the eight-pointed star as ornament, and as the new app icon.
- In the Qur'an section, a sūra that isn't saved offers "save offline" quietly, without a red warning.

## 1.16.1
In the Qur'an section, Badr at-Turkī can be chosen: he recites the sūras he is cut into āyāt for, al-Kahf among them.
- The section's reciter list only had the recitations with every āya, so at-Turkī (published as whole sūras) was replaced by another reciter. He now recites the 28 sūras already cut into āyāt (al-Fātiḥa, al-Kahf, as-Sajda, Yā-Sīn, al-Mulk and others); for the other sūras Muʿayqilī takes over, and the reciter chip says so. The list shows how many sūras a recitation has.

## 1.16.0
A new Qur'an section: listen to the whole Qur'an, sūra after sūra, with its text.
- The new Coran tab lists the 114 sūras (search by name or number). A sūra opens with its text and translation; the āya being recited is lit and kept in view, and a tap on an āya plays from it.
- A sūra plays through and the next one follows by itself, to the end of the Qur'an, screen off included. Next and previous (earbuds, notification) cross from one sūra to the other, and the timer can stop at the end of the sūra.
- "Resume" starts again at the āya where you stopped, whatever passage you listened to in between.
- The section has its own reciter (the chip under the player, or Settings), among those who have every āya. A sūra can be saved for offline listening with the chip next to the āya's number.
- Listening there doesn't count in the learning statistics nor the daily goal: those stay about the passages you memorise.
- The sūras' names are written as the passages' titles are (Al-Fātiḥa, Āl ʿImrān).

## 1.15.6
Saying an āya again, the text goes back to it and stays there, even when part of it is misheard.
- Repeating an āya you had just finished, Recite went back to it, then half a second later jumped to the next āya as if you hadn't: the next readings of your phrase were misheard (الله إلى إلى for الله لا إله إلا on your earbuds) and matched nothing. Once your phrase has gone back, a misheard reading no longer undoes it.
- The marker goes back to the āya's first word: ٱللَّهُ لَآ looks like لَآ إِلَٰهَ إِلَّا without its alifs, and the marker went onto إِلَّا.
- The āya you just recited is now among what Recite expects you may say, as starting the current one over is.

## 1.15.5
In Recite, the blue marker stays on the word you are saying, and moves to the next one when you pause.
- While you held an āya's last word (a madd), the marker was already on the next āya's first word: Recite recognises a word from its first letters and showed the word to say next. It now stays on the word you are saying as long as your voice goes on, and moves to the next word once you pause; if you go straight on, it follows you word by word. On the test recitations, the marker left an āya's last word 1.7 s before its end; it now leaves it about half a second after.

## 1.15.4
Recite no longer flashes words red while you hold a long vowel, and saying an āya again starts it from its first word.
- While you prolong a vowel (madd), the word you are on could turn red for a moment and back to white once you finished it. A word now only turns red once your phrase has been read to its end; on the test recitations, words shown red then found right went from 577 to 7.
- Saying an āya you had already recited again, the blue marker went back to it but sat on its last word. It now goes to the āya's first word and follows you from there.

## 1.15.3
Recite reads your voice a little faster again.
- Your first sessions with the AI chip: a short phrase is now read in about 130 ms (540 ms before), 6 to 9 seconds of recitation in about 290 ms (650 ms before), and every āya of al-Fātiḥa and 3:18–19 was followed without a false mistake.
- While the AI chip does the heavy part, readings no longer keep to the processor's fast cores: that was 7 to 15 % slower.

## 1.15.2
Recite now really reads your voice on your phone's AI chip.
- In 1.15.1 the AI chip (NPU) gave wrong readings: your phone's chip doesn't compute with the half-precision numbers it was given, so Recite checked itself, saw it, and stayed on the processor. Recite now uses a version of the speech engine built for your chip's whole numbers, checked beforehand to read test recitations word for word as before. It downloads 11 MB in the background the first time; if its check on the reference recitation fails, the processor keeps reading as before.
- More diagnostics when the AI chip misreads (how far its result is from the processor's).

## 1.15.1
The blue marker follows you when you say part of an āya again, and Recite can read your voice on your phone's AI chip.
- Saying a phrase or an āya again, the blue marker goes back with you from the third word, and shows on words already recited too. It used to stay where you had got furthest.
- On a Snapdragon 7 Gen 4 (your phone), the heaviest part of reading your voice now runs on the phone's AI chip (NPU) instead of its processor, which should make each reading several times faster. The first time, it downloads 18 MB in the background; Recite then checks itself on a reference recitation and keeps the processor if anything is off.

## 1.15.0
A word you say wrongly now stays marked, and a word the app only misheard is checked first.
- A word said differently (يؤمنون for ينفقون) could turn white for a moment as you went on, and then stayed white: the mistake never showed nor counted. A word now stays white once your whole phrase has been read and found it right. On the test recitations with a word swapped on purpose, 4 swaps out of 5 are caught (1 before).
- Before a word counts as a mistake, Recite reads your phrase again held to the text, letter by letter: if your voice fits the right word closely, the app had only misheard it, and it turns white. A skipped āya stays missed. Replayed on your recordings, this clears words like هو, في, نزل, لينذر, به.
- Recite reads your voice faster on recent phones: the speech engine uses your processor's int8 matrix instructions where it has them (13 % faster on an ARM test machine), and it now runs on any 64-bit ARM phone (ARMv8.2 is no longer needed).
- To measure what is fastest on your phone, every other reading runs on the processor's fast cores only (diagnostics).

## 1.14.3
In Recite, the word to say next is in the same electric blue as while listening.
- The next word is electric blue on a pill of the same blue, as the word being recited when you listen (it used to pulse from grey to white). With the text hidden, the blue pill alone shows where you are.

## 1.14.2
The muffled-microphone warning only shows when the text stops following you.
- Your phone's microphone always hears your voice a little muffled, and Recite now reads it well: the warning showed for nothing. It now shows only when the voice sounds muffled and the text hardly follows (under a word every 4 seconds of recitation), as with the phone in a pocket.
- A breath or a short sound at the start of an āya no longer lights its first words before you say them.
- Recordings sent to help improve Recite (Settings → About → Send recordings) now arrive past a minute too: longer ones were refused.

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
