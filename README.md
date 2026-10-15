# TT9 Whistle v64

A personal fork of [Traditional T9](https://github.com/sspanak/tt9) (upstream TT9) **v64.0** with
speech-to-text built into the keyboard. It installs beside stock TT9 as its own app: package
`io.github.sspanak.tt9.qin`, label **TT9 Whistle**, versionName `64.0-qin`, versionCode 1646.

Everything upstream v64.0 does — typing, the languages, keypad and hotkey behaviour, the clipboard,
the settings tree, Help and Privacy — is inherited unchanged. The addition is a Voice input section
in Settings, a voice key that records the microphone, and one dictation pipeline with three
selectable engines.

The source lives in this directory (`apps/tt9-whistle-v64`), a separate clone on branch
`feat/qin-v64-dictation` based on the pinned upstream commit
`e9f37e49ef9698cc78a61def709c191661e2fa25` (the v64.0 release commit). The older v34 fork in
`apps/tt9-fork` is preserved and untouched, and is the only one of the two that has ever been
deployed to a phone.

## What it adds to stock TT9

Settings gains one entry, **Voice input**, whose summary shows the engine in use. The screen holds an
engine chooser, a status line for the selected engine, and only that engine's controls. The other
engines' controls stay in the preferences file, so switching engines never loses a saved value.

- **On-device (Whistle)** — the default. Records 16 kHz mono PCM from the microphone, runs the
  bundled Cactus Whistle speech model through the bundled `needle` runner, and inserts the
  transcript. Works offline and sends nothing off the phone.
- **Cloud (OpenCode)** — optional. Sends the recording as a base64 WAV inside a chat request to a
  configured HTTPS endpoint together with a cleanup prompt, and inserts the returned text. It is off
  unless selected, and nothing falls back to it automatically.
- **System voice input** — TT9's original pathway, unchanged: whichever recognition service Android
  has selected. It may go online and has no cleanup prompt.

Under the on-device engine the screen also exposes Depth and speed (Fast, 2 layers; Balanced,
4 layers; 6 layers; Accuracy, full model), Processor threads (automatic, 1-4, default 4) and
Language (English, German, French, Spanish, Italian, Dutch and Polish, the seven languages the
pinned model supports). The cloud engine's endpoint, model, cleanup prompt and API key live on a
separate advanced screen; the key is encrypted with a fork-owned Keystore alias, is never shown
again, and can also be imported from `files/opencode-go.key`, which is deleted after import.

## The voice key

Voice input is **function key 3**: hard key 3 while the function palette is open, and the voice
palette key. Press it to start recording, press it again to stop and transcribe. Recording also ends
at 60 seconds.

The status bar reads "Recording · press voice to stop" while recording, then "Transcribing · on this
phone" or "Transcribing · OpenCode". The finished transcript appears in the status bar in square
brackets and is inserted at the cursor of the field that was focused when the recording started.
Nothing is submitted.

A session inserts nothing when it is cancelled, when the keyboard is hidden, or when the focused
editor object has changed; a result that arrives after its session ended is dropped. Audio whose
peak is below roughly 2% of full scale counts as silence and is not transcribed.

## Languages and packaging

This is the **lite** flavour. The English dictionary and English word-pair data are bundled, so
English predictive typing and the on-device engine work with no network. Shared language metadata
for all dictionary languages ships in every flavour, so every other language is still offered and
downloads once from upstream when selected, after which it works offline.

What makes the build compact is where the payloads sit: `app/src/main/assets` holds shared
metadata, the Whistle model and the native engines; `app/src/full/assets` holds every language
payload; `app/src/lite/assets` holds English only. The lite APK carries the 16.9 MB speech model,
both engine ABIs and English, and no other language payload.

Generated assets come from the official v64.0 release APK, `tt9-v64.0-full.apk` (250,786,662
bytes, SHA-256 `b13b93a129d945996b5c2b9dec7a9fd660cd26bf8855cfdb7a4780624d349bda`). That
provenance is recorded in `docs/evidence/upstream-v64-assets.json` and checked on every asset run;
the placement and its verification are written up in `docs/evidence/package-footprint.md`.

## Build

Needs a JDK 17, the Android SDK with build-tools (zipalign, apksigner, aapt2), an unpacked pinned
Gradle 9.5.0, and a personal signing keystore (`~/.android/debug.keystore` by default). Nothing is
downloaded and nothing is installed.

```sh
cd apps/tt9-whistle-v64
scripts/build-qin.sh                    # -> dist/TT9Whistle64.apk, aligned and signed
scripts/build-qin.sh --verify-only      # checks provenance and asset placement, writes nothing
```

`scripts/build-qin.sh` is the supported way to produce the artifact that `make-module.sh`
packages. It runs `:app:assembleLiteRelease` with the generated-language tasks skipped
(`validateLanguages`, `buildDefinition`, `buildDictionaryDownloads`, `copyDownloadsToAssets`,
`generateNgramsMeta`, `copyNgramsToAssets`, `copyNgramsToDownloads`, `generateDocs`), because
the pinned v64.0 assets are already committed and those tasks would regenerate the asset trees and
`downloads/`. It also skips `updateStatusIcons` and `updateManifest`, which rewrite tracked files
from the current git state, and it fails if `AndroidManifest.xml` or `StatusIcon.java` changed
during the build.

The Gradle wrapper pins 9.5.0 (`distributionSha256Sum 553c78f5...d6b746`); when the wrapper
distribution is not unpacked, the script uses an unpacked 9.5.0 already in the workspace instead of
downloading it. Only the skipped generator tasks need a newer JDK; JDK 17 compiles this project.

Status on 2026-10-16: `:app:assembleLiteRelease` fails in `:app:packageLiteRelease` with
"Zip file ... already contains entry 'assets/dexopt/baseline.prof', cannot overwrite", so
`dist/TT9Whistle64.apk` does not exist yet. `:app:assembleLiteDebug` builds cleanly and produces
a 27.5 MB APK. See `CURRENT_STATE.md` for the current detail.

## Install

The Qin F22 does not accept a plain `adb install`, so the app goes on as a single-app Magisk
module. `make-module.sh` stages the module; it never flashes, installs or reboots anything.

```sh
./make-module.sh        # -> dist/qin_tt9_whistle.zip
```

Before staging, it verifies the APK's package, versionCode, versionName and armeabi-v7a code, checks
that the engine inside the APK matches `app/src/main/jniLibs/armeabi-v7a/libneedle.so`, and
requires exactly one bundled dictionary payload and one ngram payload (the lite layout;
`--any-payloads` relaxes only that last check). The module contains:

- `system/app/TT9Whistle/TT9Whistle.apk`
- `system/app/TT9Whistle/lib/arm/libneedle.so` — a preinstalled app's own native library directory
  is where the app executes the engine from, so it has to be staged beside the APK
- `module.prop` with id `qin_tt9_whistle`, version `64.0-qin` (1646)

Then, by hand on the phone: Magisk -> Modules -> Install from storage -> `qin_tt9_whistle.zip`,
reboot, unlock, and select **TT9 Whistle** as the keyboard. The first launch can fail for a while as
ART re-verifies the dex; retry it. The first dictation asks for the microphone permission.

Stock TT9 is not touched by this module and stays selectable on the phone.

## Rollback

Select stock Traditional T9 as the keyboard first, then disable the `qin_tt9_whistle` module in
Magisk and reboot; disabling is enough to make the app disappear. Removing the module directory
uninstalls it. Deleting the app's data is a separate, deliberate step.

The v34 fork used the same module id and the same install path, so this module replaces that entry
if it is still present rather than sitting beside it.

## Tests and evidence

- `tests/opencode/` — a JVM harness for the cloud adapter: settings, the wire contract, and a real
  loopback HTTPS round trip. 121 checks, plus `mutation-control.sh`, which injects five known
  defects and requires the harness to catch them. Run it with `bash tests/opencode/run-tests.sh`.
- `tests/whistle/probe_fifo.py` — the desktop probe for the warm runner protocol: model residency
  before audio, FIFO delivery, output only after EOF, and peak RSS. Its measurements and the
  runner's real limits are written up in `tests/whistle/README.md`.
- `tests/java/.../LocalWhistleTranscriberTest.java` — a standalone check for the transcript parser.
- `docs/evidence/` — the pinned-asset provenance, the packaging footprint, the emulator startup
  crash log that led to the current lifecycle fix, and emulator screenshots at the Qin's display
  size.
- Benchmark runs and probe JSON live at `work/whistle-test/evidence-2026-10-16/` in the workspace,
  outside this repository.

The debug source set (`app/src/debug/`) adds a two-field test activity and two broadcast receivers
so dictation can be driven without the physical keypad. None of it exists in a release build.

## Known limits

- Real human speech has not been verified end to end. Every benchmark fixture is synthetic
  text-to-speech, and microphone-to-inserted-text on the phone is still the outstanding acceptance
  item.
- The keyboard uses the per-call transcriber, which loads the model for each dictation. The warm
  prepared-runner path exists in the source and is measured on the desktop only; it is not wired
  into the keyboard, and its phone timing has not been measured.
- Depth 6 is selectable but unmeasured.
- The Android 11 emulator resets the selected IME to Gboard on restart, so the keyboard has to be
  re-selected after each emulator boot.

## Licences

- Traditional T9 source, logo and icons: Apache-2.0 — see `LICENSE.txt` and `CONTRIBUTING.md`.
  Upstream's own README is unchanged in git at the pinned commit (`git show e9f37e49:README.md`).
- The `needle` runtime (`cactus-compute/needle`): Apache-2.0 — see
  `work/whistle-test/needle/LICENSE` in the workspace.
- The Cactus engine and the Whistle speech model: Cactus Compute's own terms, retained at
  `work/whistle-test/cactus/LICENSE` — free for personal, educational, research and
  non-commercial use, with a funding and revenue threshold for organisations. The model is the
  `Cactus-Compute/whistle` checkpoint.
- Consequence: this is a personal build. Do not redistribute the APK or the module zip.
- Bundled dictionaries keep their upstream licences — see `docs/dictionaries/`.
