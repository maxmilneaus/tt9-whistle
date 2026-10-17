# Installing TT9 Whistle

TT9 Whistle is an Android input method (keyboard), so installing it means installing an
app and then making it your active keyboard. There are two install paths: an ordinary
app install that works without root, and a Magisk module for devices that will not take
the first one.

## What you need

- Android 7.0 (API 24) or newer. This build is compiled against API 37.
- An ARM device: `arm64-v8a` (64-bit) or `armeabi-v7a` (32-bit). There is no x86
  build, so it will not run on an Intel or AMD emulator image.
- The APK, `TT9Whistle64.apk`, from the release.
- A phone with a physical number pad is what TT9 is designed for, and the voice key is
  function key 3 on that pad. The same keys are also on the on-screen keypad and in the
  function palette, which is what the emulator runs on.

## 32-bit and 64-bit phones

Use the same [TT9Whistle64.apk](https://github.com/maxmilneaus/tt9-whistle/releases/download/v64.0-qin/TT9Whistle64.apk)
for 32-bit ARM (`armeabi-v7a`) and 64-bit ARM (`arm64-v8a`) Android phones. Both engines
and the speech model are already inside this download; you do not need to build the
app or download Cactus components separately. Android selects the matching engine at
installation. Android 7.0 or newer is required; x86 is not supported.

On a 64-bit phone, try **Path A** below. Enable TT9 Whistle as the keyboard and grant
microphone access when prompted. Architecture support is not a guarantee for every
phone or firmware; physical-handset results so far are from the Qin F22 non-Pro.

The released **Magisk ZIP is a different route**: it stages the native engine under
`system/app/TT9Whistle/lib/arm/` for the original 32-bit F22. It is not a generic
64-bit Magisk module. If a different phone also blocks normal app installs, its
architecture, firmware and system-app library layout need checking before adapting
the module. Rooting or unlocking that phone is separate from installing this app.

For developers using Whistle in another project, Cactus supplies the model and the
[Android ARM64 engine](https://huggingface.co/Cactus-Compute/needle3/tree/main/android-arm64)
through [its Whistle page](https://cactuscompute.com/blog/whistle). These are engine
components, not a separate TT9 Whistle keyboard installer.

## Path A - install as an ordinary app (no root)

Try this first; it is the normal Android install path.

1. Get the APK onto the phone: copy it across and tap it in a file manager, or from a
   computer run `adb install TT9Whistle64.apk`.
2. If Android blocks it, allow installs from that source (Settings -> Apps -> Special
   access -> Install unknown apps).
3. Open TT9 Whistle once from the app list.

This path works without root because the APK carries the engine itself, at
`lib/arm64-v8a/libneedle.so` and `lib/armeabi-v7a/libneedle.so`, and is built with
`jniLibs { useLegacyPackaging = true }`. Android therefore extracts those libraries
into the app's own native library directory at install time, as real files the app can
execute. The app looks for the engine at
`getApplicationInfo().nativeLibraryDir + "/libneedle.so"`, and on first use it copies
the 16.9 MB speech model out of the APK into its own private files directory. Nothing is
installed outside the app's storage and nothing needs root.

That path is not a guess: it was exercised on a stock, non-rooted Android 11 emulator.
The APK was installed with `adb install`, TT9 Whistle was selected as the input method,
a recording was dictated, and the transcript landed in a text field; the engine was seen
running as a process owned by the app's own UID. The release APK ships the same engine
and model layout.

One exception worth knowing about: the Qin F22 non-Pro that this fork was developed
against refuses installs at the ROM level. Its vendor build of
`PackageManagerShellCommand` answers `adb install` and `pm install` with
"Error: adb install not allowed!!", and its vendor installer app enforces a separate
app-count limit. That is a property of that phone's firmware rather than of this APK, and
it is why Path B exists.

## Path B - Magisk module (rooted 32-bit Qin F22)

The released module zip installs the app as a preinstalled system app. It contains:

- `system/app/TT9Whistle/TT9Whistle.apk`
- `system/app/TT9Whistle/lib/arm/libneedle.so` - a preinstalled app's native library
  directory is where this app executes the engine from, so the engine is staged beside
  the APK
- `module.prop`, id `qin_tt9_whistle`, version `64.0-qin` (1646)

To install it, on the phone: Magisk -> Modules -> Install from storage ->
`qin_tt9_whistle-64.0-magisk.zip`, then reboot and unlock. The first launch can fail
for a while while ART re-verifies the dex; just retry it.

If an older TT9 Whistle module is already installed, it uses the same module id and the
same install path, so this one replaces it rather than sitting alongside it.

## First-run steps (both paths)

1. Enable the keyboard: Settings -> System -> Languages & input -> On-screen keyboard ->
   TT9 Whistle -> turn it on. The exact menu path varies by Android version.
2. Select it as the input method. Use the keyboard switcher in the navigation bar, or
   the input-method notification, and pick **TT9 Whistle**.
3. Open any text field and press the voice key - function hard key 3 with the function
   palette open, or the voice palette key. The status bar shows
   "Recording · press voice to stop"; press the key again to stop and transcribe, and a
   recording that reaches 60 seconds stops by itself.
4. Grant the microphone permission when the first recording asks for it. If it was
   denied, enable it again under Settings -> Apps -> TT9 Whistle -> Permissions ->
   Microphone.
5. Check the audio depth in Settings -> Voice input -> Depth and speed and set it to
   **Balanced (4 layers)**. Balanced is already the default and it is the measured best
   trade-off; set it back if it has been changed.

The transcript is inserted at the cursor of the field that was focused when recording
started. Nothing is submitted, and a recording that is cancelled or that arrives after
the field changed inserts nothing.

## Rollback

Path A: switch back to another keyboard first with the keyboard switcher, then uninstall
TT9 Whistle like any other app. Its settings and the extracted model go with it.

Path B: switch back to the stock keyboard first, then disable or remove the
`qin_tt9_whistle` module in Magisk and reboot. Disabling it is enough to make the app
disappear, and the stock keyboard keeps its data. From a shell on the phone that is:

```sh
settings put secure default_input_method io.github.sspanak.tt9/.ime.TraditionalT9
touch /data/adb/modules/qin_tt9_whistle/disable
```

then reboot.

## If you are upgrading

These builds are signed with a debug keystore (see the release notes). An APK signed
with a different key will not install over one that is already there, so if a later
release changes signing identity you have to uninstall first and reinstall.

## Verifying the download

```sh
shasum -a 256 TT9Whistle64.apk qin_tt9_whistle-64.0-magisk.zip
```

The expected values for this release are on the release page and in the release notes
there, alongside the commit the artifacts were built from.

## Building from source

```sh
scripts/build-qin.sh    # -> dist/TT9Whistle64.apk, aligned and signed
make-module.sh          # -> dist/qin_tt9_whistle-64.0-magisk.zip
```

Both scripts only read and build; neither installs anything or talks to a device. They
expect a JDK 17, an Android SDK with `zipalign`, `apksigner` and `aapt2`, and a
signing keystore (`~/.android/debug.keystore` by default). Two things to know before
you try it: the scripts deliberately skip the Gradle tasks that generate the language
and documentation assets, because the pinned v64.0 generated assets are expected to be in
the tree already, and building also expects the speech model to be present at
`app/src/main/assets/whistle/whistle.cact` (it is tracked in this repository). See
`docs/evidence/packaging.md` for the full detail.

