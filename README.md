# TT9 Whistle

TT9 Whistle is a fork of [Traditional T9](https://github.com/sspanak/tt9) (TT9) v64.0
with speech-to-text built into the keyboard. It installs beside stock TT9 as its own app
rather than replacing it: package `io.github.sspanak.tt9.qin`, label **TT9 Whistle**,
version `64.0-qin` (versionCode 1646).

It was written for the Qin F22, a small Android candybar phone with a physical number
pad, but nothing in it is specific to that phone.

## What it does

Everything upstream TT9 v64.0 does - typing, the languages, keypad and hotkey behaviour,
the clipboard, the settings tree, Help and Privacy - is inherited unchanged. Settings
gains one entry, **Voice input**, which holds the engine chooser and that engine's
controls. There are three engines:

- **On-device (Whistle)** - the default. Records 16 kHz mono PCM from the microphone, runs
  the bundled Whistle speech model through the bundled `needle` runner, and inserts the
  transcript at the cursor of the focused field. It works offline and sends nothing off
  the phone.
- **Cloud (OpenCode)** - optional, and off unless you select it. Sends the recording as a
  base64 WAV inside a chat request to an HTTPS endpoint you configure, with your own API
  key, and inserts the returned text. Nothing falls back to it automatically.
- **System voice input** - TT9's original pathway, unchanged: whichever recognition
  service Android has selected.

The on-device engine also exposes the decoder depth and speed (Fast 2 layers, Balanced 4,
6, Accuracy full), processor threads (default 4) and the model's language (English by
default; German, French, Spanish, Italian, Dutch and Polish are available).

The voice key is **function key 3**: hard key 3 while the function palette is open, and
the voice palette key. Press it to start recording, press it again to stop and
transcribe. The finished transcript is inserted at the cursor and is never submitted on
your behalf.

## Hardware and Android version

- Android 7.0 (API 24) or newer; compiled against API 37.
- `arm64-v8a` or `armeabi-v7a`. There is no x86 build.
- A device with a physical number pad is what TT9 is designed for. The on-screen keypad
  works too, which is how it runs on an emulator.
- The APK is about 21.4 MB, of which 16.9 MB is the speech model.

## Limits

These are measured or observed, not impressions:

- **English only in this build.** The lite flavour bundles the English dictionary and
  English word-pair data. Other languages are still offered and download from upstream TT9
  the first time they are selected, after which they work offline. The speech model itself
  covers the seven languages listed above.
- **A deeper decoder is not a better one.** Depth 4 and depth 8 made the same number of
  word errors (48 per 927 reference words), but depth 8 decoded about 1.4x slower on the
  reference Mac and about 1.5x slower on the phone. Worse, on 20-second takes depth 8
  returned the whole transcript with no capitalisation and no punctuation, every time.
  Depth 4 is the default for both reasons. Depth 6 is selectable but was only measured
  once, where depth 4 beat or matched it on accuracy and speed.
- **A recording is capped at 60 seconds.**
- **Unusual proper nouns are often misheard.** A keyword-hints field can recover some
  single names; it does not fix multi-word phrases, and it made no useful difference to an
  ordinary name in the one test taken.
- **Real speech through the phone's microphone has not been verified end to end.** The
  accuracy and latency figures come from synthetic text-to-speech fixtures, measured on a
  desktop and on the phone. The engine itself was confirmed running and transcribing on
  the emulator, and a live recording was dictated there from the host microphone.
- The cloud engine needs your own endpoint and key, and one of the endpoint models tested
  refused audio outright.

## Install

**32-bit or 64-bit ARM Android phone?** Download the same
[TT9Whistle64.apk](https://github.com/maxmilneaus/tt9-whistle/releases/download/v64.0-qin/TT9Whistle64.apk).
It includes both engine versions and the speech model. Start with the ordinary app
install in [INSTALL.md](INSTALL.md#path-a---install-as-an-ordinary-app-no-root);
no separate Cactus download or root is needed for that route. Android 7.0 or newer is
required. Bundled architecture support does not mean every handset has been tested.

The alternative Magisk ZIP was prepared for the **32-bit Qin F22 non-Pro** when its
firmware refuses normal installs. It assumes root is already working and must not be
used unchanged as a generic 64-bit module. See
[the download guide](INSTALL.md#32-bit-and-64-bit-phones) before choosing a file.

## Licence

TT9, the bundled Whistle speech model and the bundled `needle` engine are all Apache
License 2.0, and all three may be redistributed. See [LICENSE.txt](LICENSE.txt) and
[THIRD-PARTY.md](THIRD-PARTY.md) - the latter records each source, its licence and the
SHA-256 that ties the bundled file to it, and explains why an earlier revision of this
README incorrectly described the speech model as non-commercial.

## Repository layout

- `app/src/main/java/io/github/sspanak/tt9/dictation/` - the dictation pipeline: the
  shared controller, the on-device transcriber, the warm prepared runner, and the optional
  cloud adapter with its encrypted settings.
- `app/src/main/jniLibs/` - the engine, one build per ABI.
- `app/src/main/assets/whistle/whistle.cact` - the speech model.
- `app/src/debug/` - debug-only test activity and broadcast receivers used to drive
  dictation without the physical keypad. None of it is in a release build.
- `tests/` - the cloud adapter harness, the desktop probe for the warm runner protocol,
  and an emulator end-to-end script.
- `docs/` - planning, evidence and packaging notes from the port.
- `CURRENT_STATE.md` - what is done, verified and still open, with artifact hashes.

## Building

```sh
scripts/build-qin.sh    # -> dist/TT9Whistle64.apk, aligned and signed
make-module.sh          # -> dist/qin_tt9_whistle-64.0-magisk.zip
```

Neither script installs anything or talks to a device. They need a JDK 17, an Android SDK
with `zipalign`, `apksigner` and `aapt2`, and a signing keystore
(`~/.android/debug.keystore` by default). See [INSTALL.md](INSTALL.md) for the caveats
about the generated language assets before you build.

