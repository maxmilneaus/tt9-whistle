# TT9 Whistle v64: current state (2026-10-16)

Where this fork is, what has been checked, and what has not. Source and evidence only; nothing here
was verified from a device in this pass, and no build of this fork has been installed anywhere.

Repository: `apps/tt9-whistle-v64`, branch `feat/qin-v64-dictation`, base commit
`e9f37e49ef9698cc78a61def709c191661e2fa25` (upstream v64.0). Package
`io.github.sspanak.tt9.qin`, label TT9 Whistle, version `64.0-qin` (1646), lite flavour. The
working tree carries the fork as uncommitted changes on top of that commit. The v34 fork in
`apps/tt9-fork` is untouched and is still the only build that has been deployed to the phone.

## Done

- **Base and identity (U1).** Ported onto v64.0 rather than the old v34 tree, with the side-by-side
  package id, a fork-owned app label, and internal Help/Privacy/setup screens retargeted at this app.
  Stock TT9 stays available on the device.
- **Dictation pipeline (U2).** One shared controller records 16 kHz mono PCM from the microphone,
  guards the session with a session number, treats low-peak audio as silence, and inserts the
  transcript at the original editor's cursor only when that editor object is still the current one.
  Cancellation, hiding the keyboard and an editor change all suppress insertion, and nothing is
  submitted.
- **Three engines (U2, U4, U5).** On-device Whistle (default), cloud OpenCode (optional), and TT9's
  original system voice path. The voice key is function key 3 on the hard keypad and on the function
  palette.
- **On-device tuning (U3).** Depth (2/4/6/8) and thread (automatic/1-4) settings drive the shipped
  runner, because the pinned model's public C API exposes no depth or thread entry point. Depth is
  clamped to the model's real range and threads to 1-4.
- **Cloud adapter (U4).** The proven OpenCode request path, ported with its own preferences file and
  its own Keystore alias, key trimming before encryption, an import-from-file path, and no automatic
  fallback from the on-device engine.
- **Settings (U5).** A Voice input screen plus a cloud advanced screen, with only the selected
  engine's controls shown and each engine's saved values preserved.
- **Compact packaging (U1, R14).** Lite flavour with English dictionary and ngrams bundled, shared
  metadata for every other language, the 16.9 MB Whistle model and both engine ABIs in `main`, all
  other payloads in the `full` flavour. Generated assets taken from the official v64.0 full APK and
  hash-checked.
- **Scripts.** `scripts/build-qin.sh` (tools, identity and asset checks, release build, align,
  sign) and `make-module.sh` (stages the Magisk module; installs nothing).

## Verified

- Cloud adapter harness: 41 + 33 + 47 = 121 checks pass, re-run on 2026-10-16, and
  `tests/opencode/mutation-control.sh` caught all five injected defects.
- Benchmarks on the physical Qin at 4 threads over 13 generated fixtures: 27-run screen, then a
  117-run expanded run. Medians over the speech clips were about 1.76 s at full depth (8), 1.32 s at
  depth 4 and 1.20 s at depth 2; every run exited 0; peak native RSS 49,560 KiB. Evidence:
  `work/whistle-test/evidence-2026-10-16/screening.jsonl` and `extended.jsonl`.
- Desktop FIFO probe of the shipped runner: model resident before any audio, nothing emitted before
  EOF, about 0.12 s from EOF to result, peak RSS 42.9-44.8 MB. Evidence:
  `tests/whistle/README.md` and the `probe-fifo-*` JSON files.
- Emulator (Android 11, resized to 480x640/208 dpi): the keyboard and the settings screens render,
  and the startup crash caused by creating the dictation engine before the service attached is
  fixed. Evidence: `docs/evidence/screens/` and the red log
  `docs/evidence/emulator-service-startup-red.log`.
- Asset provenance: the bundled generated assets match the pinned v64.0 APK SHA-256 in
  `docs/evidence/upstream-v64-assets.json`, and the placement is recorded in
  `docs/evidence/package-footprint.md`.
- `:app:assembleLiteDebug` builds: 27,483,225 bytes with exactly one dictionary payload, one ngram
  payload, the Whistle model and both engine ABIs.

## Not verified

- Real microphone speech to inserted text on the Qin. This is the outstanding acceptance item
  (AE1/U6), and no fixture substitutes for it.
- Installation of this fork on any device. Nothing from the v64 tree has been staged or installed.
- Speech accuracy on human speech: every fixture is synthetic text-to-speech. Depth 6 is selectable
  but unmeasured.
- The first-use microphone permission prompt for this build, and the hardware voice key on the
  physical keypad.
- Warm-path latency as the app UID on the Qin. The path is wired in and proven to engage on the
  emulator, but no timing has been taken on the phone's own kernel.

## Known failing

- Nothing known failing as of the 2026-10-16 late build. The release packaging collision is fixed:
  the colliding `app/src/main/assets/dexopt/` payload was removed, so `:app:assembleLiteRelease`
  completes and `scripts/build-qin.sh` publishes a signed APK. Do not re-add that directory.

## Open

- Install through the reversible module and complete the two-editor real-speech acceptance (U6),
  including cancel, field change, silence and retry, and a normal-typing regression check. The
  phone was powered off at the end of the 2026-10-16 session, so none of this ran.
- The phone's state after the 2026-10-15 v34 deployment reboot is still unknown; re-identify it
  over ADB before staging anything. See the newest entry in the workspace `HANDOFF.md`.
- Human-speech accuracy and the depth decision. The default (depth 4, 4 threads) rests on
  synthetic fixtures only.

## 2026-10-16 late session

Written after the sections above, from the emulator and the packaging scripts on this machine. No
physical device was touched.

- Fixed a real defect found while wiring the warm path. `DictationEngine` carried both an instance
  `ExecutorService` field and a static `worker()` accessor, and its call sites used each of them,
  so the warm-engine work ran on a different queue from the transcription that consumes it. All
  dictation work now runs on the one shared serial executor.
- Replaced the warm-path gate. `OnDeviceTranscriber` used to try the warm runner unconditionally
  and fall back whenever it threw, including on genuine engine errors. It now asks
  `PreparedWhistleTranscriber.isReady(...)` first, so the warm slot is used only when a runner
  really holds the model for those settings, while a genuine failure still gets one per-call retry
  rather than surfacing an error to the user.
- `:app:assembleLiteDebug` builds clean with these changes, and `tests/emulator/dictation-e2e.sh`
  passes against it on the Android 11 emulator at 480x640/208 dpi, landing "testing one two three
  this is dictation can you hear me" in `field_a`. The debug clip is fed through the real capture
  path, so that transcript is engine output rather than a stored fixture answer.
- The warm path is confirmed to engage. While the IME was idle and no dictation was running,
  `ps` on the emulator showed a resident `libneedle.so` process owned by the app UID
  (`u0_a169`) with its FIFO sitting in the app's own cache directory. That is the app-UID FIFO
  creation this file previously listed as unverified, now confirmed on the emulator. It does not
  by itself prove the same on the Qin's kernel.
- The settings screens render on the measured small viewport. The main list shows "Voice input -
  On-device (Whistle)"; the Voice input screen shows the engine, the readiness line, the
  performance profile (Balanced - 4 layers), 4 threads, language, the 117-run benchmark summary and
  the model's supported languages. Screenshots:
  `docs/evidence/screens/emulator-settings-main-2026-10-16.png`,
  `docs/evidence/screens/emulator-voice-whistle-2026-10-16.png`,
  `docs/evidence/screens/emulator-voice-whistle-benchmark-2026-10-16.png`.
- Both artifacts rebuilt from this revision. `dist/TT9Whistle64.apk`: 21,360,753 bytes, sha256
  `49b24ec950562a17d30e28b72f640112e70d415b34a8048ab0fa0c03fa1638cd`, debug-signed,
  arm64-v8a + armeabi-v7a, one dictionary payload and one ngram payload.
  `dist/qin_tt9_whistle-64.0-magisk.zip`: 20,800,322 bytes, sha256
  `8cf825fa5f35f2c64f7f45dddd12061e79cc3b3037613aa159112e1654862079`. The module's staged
  `libneedle.so` hash matches the source tree, and the staged APK hash matches the published one.
## 2026-10-16 second pass - name hints, review fixes and the quality question

Owner feedback during this pass was that transcriptions were not accurate enough, that the name "Max"
was not being captured, and a question of whether the quality control really changed anything. All
three were answered with measurements rather than assertion.

### The name hints (new feature)

The shipped runner takes `--audio-keywords file`, one phrase per line. That is documented in
`../../work/whistle-test/needle/README.md` and confirmed by the runner's own `--help`, but nothing in
the app was wired to it. It now is:

- `DictationSettings.KEY_KEYWORDS` and `writeKeywordsFile()`, which normalises commas and newlines to
  one hint per line and writes them to `files/whistle/keywords-<digest>.txt`. The file is named after
  its own contents, so an edited list is a different path; because the warm engine keys on that path,
  an edited list makes the next dictation start a fresh runner by itself.
- Both the warm runner and the per-call runner pass the file when it exists.
- A "Words to expect" row on the Voice input screen, a single comma-separated line because this phone
  types on a numpad, saved as it is typed.

Measured on the host with the shipped runner at depth 8 and 4 threads, over one generated sentence
("Please call Zephyr Quill about the Woy Woy invoice"):

| setting | transcript |
| --- | --- |
| no hints | Please calls effort quill about the Y-war invoice. |
| hints: Zephyr, Quill, Woy Woy | Please call Zephyr Quill about the Y-war invoice. |

Both out-of-vocabulary names come back correctly, and that run was also slightly faster to first
token. The multi-word phrase ("Woy Woy") was not recovered, so hints help single words more than
phrases. A second sentence carrying common names ("Max Milne") was not fixed: hints changed the error
from "Max Miln" to "Max Mill" rather than correcting it. The feature is real, and it is not a cure for
audio the model cannot hear clearly.

Verified live on the emulator that the whole chain works. After typing `Max,Sophie` into the new row,
the stored preference reads `Max,Sophie`, the file `files/whistle/keywords-79164bce0cf4.txt` holds
`Max` and `Sophie` one per line, and the resident warm runner's own command line ends with
`--audio-depth 8 --threads 4 --audio-keywords /data/user/0/io.github.sspanak.tt9.qin/files/whistle/keywords-79164bce0cf4.txt`.

### Does the quality control do anything (it does, though not the way it reads)

`Depth and speed` reaches the runner as `--audio-depth`, so it is a real change to the decode and not a
label. It is not a plain accuracy dial. The same generated sentence through the shipped runner at 4
threads:

| depth | transcript |
| --- | --- |
| 2 | Testing one, two, three, this is dictation. Can you hear me? |
| 4 | Testing 1, 2, 3. This is dictation. Can you hear me? |
| 6 | Testing 1. 2. 3. This is dictation. Can you hear me? |
| 8 | Testing 1. 2. 3. This is dictation. Can you hear me? |

Every depth hears the words. What changes is how numbers are written, and the decode speed: this clip
decodes at about 1847 tokens per second at depth 2 and 772 at depth 8. Depth is best read as the
accuracy ceiling against speed, and the real lever for names is the hint list. An early emulator run
appeared to show depth 8 producing only "Testing 1. 2. 3."; that was the harness stopping the
recording at 4.2 s, not the model, and the full sentence is present at every depth.

### Review fixes

A review pass over the diff found three defects worth fixing. All three are fixed.

- Cancellation was signalled as a plain `IOException`, so `OnDeviceTranscriber` read an abandoned
  recording as a warm-engine fault and re-ran the whole clip on the per-call engine. Cancellation now
  throws `TranscriptionCancelledException` and is rethrown without a retry.
- `releaseResources()` had no caller, so the roughly 45 MB runner stayed resident for the life of the
  IME process. `VoiceHandler.onFinishInputView()` now releases it when the keyboard hides, and
  `TraditionalT9.onStartInputView()` re-warms it, which is where dictation becomes possible again.
- `prepareReplacement()` read the `released` flag outside the lock that `release()` writes it under, so
  a release could be silently undone. Both now hold the same lock. The write loop in `feed()` also
  gained the deadline check its sibling branch already had.

Left unfixed and recorded here: `PreparedWhistleTranscriber.transcript()` maps any parse failure whose
message contains "no text" to an empty transcript, so a truncated runner response would be reported as
silence rather than an error. The only two messages that path can produce are "needle printed no JSON
result" and "needle returned no text", so in practice only a malformed JSON object that still starts
with `{` would be misread. Tightening it needs the runner's exact no-speech shape, which the shipped
binary does not print on demand.

### Re-verified and rebuilt

- `tests/emulator/dictation-e2e.sh` passes with all of the above, landing a transcript in `field_a`.
- `dist/TT9Whistle64.apk`: 21,360,753 bytes, sha256
  `f7597534fbddab295398b20be77e9907adafaf0bf5719339fcf1c3f99ee039ea`.
- `dist/qin_tt9_whistle-64.0-magisk.zip`: 20,801,080 bytes, sha256
  `2a9f6a32d61190fd76294864b805970d75c2995d074ee3484227796c070497e0`; its staged APK matches the
  published APK and its staged `libneedle.so` matches the source tree.
- Still not installed anywhere, and human-speech accuracy on the Qin is still unmeasured. The owner has
  been using the emulator with the host microphone, which transcribes real speech but is not the
  phone's microphone or its CPU.
