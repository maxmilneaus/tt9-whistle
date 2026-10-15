# TT9 Whistle (Qin F22): build-qin.sh and make-module.sh

2026-10-16. Scope: the two packaging scripts, the ignore rules that keep their
output private, and what was actually run. Written by the agent whose write scope
was `scripts/`, `make-module.sh`, `.gitignore` and this file: no Java, resource,
manifest or Gradle file was edited, no commit was made, and no device was touched.

`docs/evidence/package-footprint.md` remains the reference for *why* the assets sit
where they do. Its "Reproducing" section is superseded: the flags it describes
(`--assets-only`, `--verify-only`, `--variant`, the pinned-upstream-APK staging)
belonged to the earlier version of `scripts/build-qin.sh`, which extracted the
generated assets out of the official APK. The assets are already in the tree, so
that whole staging path was removed.

## The two commands

    ./scripts/build-qin.sh      # :app:assembleLiteRelease -> zipalign -> sign -> dist/TT9Whistle64.apk
    ./make-module.sh            # dist/module/ and dist/qin_tt9_whistle-64.0-magisk.zip

Both are run from the repository root and are safe to repeat. Neither installs,
flashes, reboots or talks to a device.

## scripts/build-qin.sh

1. Prerequisites, each failing with the name of what is missing:
   * JDK: `JAVA_HOME` when set, otherwise
     `/Users/maxmilne/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`.
     Must be 17 or newer.
   * Gradle: `GRADLE` when set, otherwise the pinned local copy
     `../../work/toolchains/gradle-9.5.0/bin/gradle`. The wrapper is deliberately
     not used, because it would try to download Gradle 9.5.0.
   * Build tools: the newest `build-tools/*` under `sdk.dir` in `local.properties`
     (or `ANDROID_HOME`/`ANDROID_SDK_ROOT`) that has `zipalign`, `apksigner` and
     `aapt2`. Here that is `build-tools/37.0.0`.
   * Keystore: `KEYSTORE` when set, otherwise `~/.android/debug.keystore`. The
     script never creates or shares a key.
2. Source layout: `app/src/main/assets/whistle/whistle.cact`, the English payloads
   in `app/src/lite/assets/languages/{dictionaries,ngrams}`, the arm engine in
   `app/src/main/jniLibs/armeabi-v7a`, and no payload zip in the shared
   `app/src/main/assets` tree.
3. App identity read out of `app/build.gradle` (with `${PACKAGE_NAME}` resolved
   through `app/constants.gradle`): applicationId `io.github.sspanak.tt9.qin`,
   versionCode 1646, versionName 64.0-qin, a `lite` flavor, and
   `jniLibs { useLegacyPackaging = true }` so `libneedle.so` is extracted into
   `nativeLibraryDir` on the phone.
4. `:app:assembleLiteRelease` with the generated-language tasks skipped, because the
   exact v64.0 generated assets are already committed in the source tree:
   `validateLanguages buildDefinition buildDictionaryDownloads copyDownloadsToAssets
   generateNgramsMeta copyNgramsToAssets copyNgramsToDownloads generateDocs`.
   `updateStatusIcons` and `updateManifest` are skipped as well: they rewrite
   tracked files from the current git state, and the committed values are correct.
   The script hashes `app/src/main/AndroidManifest.xml` and
   `.../ui/StatusIcon.java` before and after the build and refuses to publish if
   either changed.
5. Any APK left in `app/build/outputs/apk/lite/release/` is deleted before the
   build, and the script insists on exactly one APK afterwards: the packager updates
   an existing zip in place, so a stale partial APK is otherwise indistinguishable
   from a fresh one.
6. `zipalign -p -f 4`, then `apksigner sign` with the debug keystore to
   `dist/TT9Whistle64.apk`, then `apksigner verify --print-certs`.
7. Re-reads the result with `aapt2 dump badging` and `unzip -l`: package,
   versionName, versionCode, label, `armeabi-v7a` in `native-code`,
   `lib/armeabi-v7a/libneedle.so`, `assets/whistle/whistle.cact`, exactly one
   dictionary zip and one ngram zip. Prints size, badging summary and sha256.

Useful options: `--dist DIR`, `--no-sign`, `--help`. Extra Gradle arguments go in
`GRADLE_ARGS` (default `--console=plain`); the full Gradle output is always written
to `dist/build-qin-gradle.log`. A Gradle lock error (`Timeout waiting to lock`,
`Could not acquire`) is retried once after 20 seconds.

## make-module.sh

Reads the signed APK (default `dist/TT9Whistle64.apk`, or `--apk`), refuses anything
that is not package `io.github.sspanak.tt9.qin` 64.0-qin (1646) labelled "TT9
Whistle", signed, with `armeabi-v7a` native code and the lite payload set
(`--any-payloads` relaxes the last check only), and then writes:

    dist/module/module.prop                                          0644
    dist/module/customize.sh                                         0755
    dist/module/META-INF/com/google/android/update-binary            0755
    dist/module/META-INF/com/google/android/updater-script           0644
    dist/module/system/app/TT9Whistle/TT9Whistle.apk                 0644
    dist/module/system/app/TT9Whistle/lib/arm/libneedle.so           0755
    dist/qin_tt9_whistle-64.0-magisk.zip

The engine is extracted from the APK and must be byte-identical to
`app/src/main/jniLibs/armeabi-v7a/libneedle.so`; the zip is built with `zip -X` so no
macOS metadata or resource forks end up in it, and the entry modes are verified with
`zipinfo`. The zip is checked for `module.prop` at its root, the staged APK, the
engine and `customize.sh` before it is reported.

Staged entries are given one fixed timestamp (1980-01-01 00:00) before packing, so
the zip is byte-identical between runs and its sha256 identifies one artifact. Two
consecutive runs produced the same hash; without that step each run differed by the
embedded timestamps alone.

Permissions and SELinux: the modes are set before packing and are stored in the zip
(`-rw-r--r--` for the APK and `module.prop`, `-rwxr-xr-x` for `update-binary` and
`libneedle.so`). macOS has no `chcon`, so the SELinux labels live in `customize.sh`,
where Magisk's `set_perm` applies them on the phone
(`u:object_r:system_file:s0`, and 0755 on the engine because `DictationEngine` execs
it from `nativeLibraryDir`). `module.prop` declares `id=qin_tt9_whistle`,
`version=64.0-qin`, `versionCode=1646`.

Rollback is a comment at the top of the script, in this order: set the stock
keyboard back (`settings put secure default_input_method
io.github.sspanak.tt9/.ime.TraditionalT9`), then
`touch /data/adb/modules/qin_tt9_whistle/disable` (or toggle the module off in the
Magisk app, or Remove), then reboot. Disabling it unmounts
`/system/app/TT9Whistle`, so the fork disappears and the stock keyboard keeps its
data.

## .gitignore

Added on top of the upstream file: `/dist/` and `/dist/module/` plus `/module/` for
the staging tree, `/work/`, `/extract/`, `/upstream/` for local extraction and
toolchain scratch, `*.idsig`, `*.zip` (with `!/app/languages/**` so the tracked
dictionary and ngram inputs stay visible), and `opencode-go.key` plus `*.key` for the
private dictation credential, whose real location is
`~/.config/qin-dictation/opencode-go.key` outside this repository.
`git check-ignore -v` confirms each rule while `app/languages/ngrams/en-ngrams.zip`
stays unignored. Build outputs (`build/`, `app/build`, `.gradle/`, `*.apk`) were
already ignored upstream.

Note: `!RAW/` (18 tracked upstream icon and screenshot sources) is committed, not an
extraction leftover, so no rule was added for it.

## Evidence: the runs behind this note

    python3 -c "import shutil; shutil.rmtree('app/build/outputs', ignore_errors=True)"
    ./scripts/build-qin.sh
    ./scripts/build-qin.sh          # second run, to show the result is reproducible
    ./make-module.sh
    unzip -l dist/qin_tt9_whistle-64.0-magisk.zip
    unzip -Z dist/qin_tt9_whistle-64.0-magisk.zip
    shasum -a 256 dist/TT9Whistle64.apk dist/qin_tt9_whistle-64.0-magisk.zip \
        dist/module/system/app/TT9Whistle/lib/arm/libneedle.so \
        app/src/main/jniLibs/armeabi-v7a/libneedle.so

Both `build-qin.sh` runs finished with `BUILD SUCCESSFUL` and reported the identical
result, so the artifact is reproducible from the same tree:

* `app/build/outputs/apk/lite/release/tt9-v64.0.apk`, 20 MiB, sha256
  `5c2eafff66d7cf9e730e773891be08e9f79b7a2db54b8ecfddfaa81662ab1075` (pre-signing)
* `dist/TT9Whistle64.apk`, 21,360,753 bytes, sha256
  `a2aa6223759a03939dcb0b4a38d6f61dc700c55c150a9f94c9c5708e485d756a`
* signer: certificate SHA-256
  `73ab723f7b96a17785cd988584a6af76b6ddc147938133a3704de0e09a6d3ee0`
  ("C=US, O=Android, CN=Android Debug" from `~/.android/debug.keystore`);
  `apksigner verify -v` says v2 and v3 verified, v1 false (minSdk 24)
* badging: `io.github.sspanak.tt9.qin 64.0-qin (1646)`, label `TT9 Whistle`,
  `native-code: 'arm64-v8a' 'armeabi-v7a'`, payloads: one dictionary and one ngram
  (English only)
* `dist/qin_tt9_whistle-64.0-magisk.zip`, 20,799,741 bytes, sha256
  `2dda3aafe982c531f8a2088477aa717bdec3adec4c00367381d86f3b2afef8a4`, 15 entries:
  `module.prop`, `customize.sh`, `META-INF/com/google/android/update-binary` and
  `updater-script`, `system/app/TT9Whistle/TT9Whistle.apk` (21,360,753 bytes) and
  `system/app/TT9Whistle/lib/arm/libneedle.so` (917,312 bytes, `-rwxr-xr-x`)
* the staged engine and `app/src/main/jniLibs/armeabi-v7a/libneedle.so` both hash to
  `eca6987649fd4f39b14b8f0c64a7be31ea70a41b959b6a1a698810d499835c6c`; the staged APK
  hashes to `a2aa6223...` exactly like `dist/TT9Whistle64.apk`
* each build reported `AndroidManifest.xml and StatusIcon.java unchanged`, and
  `app/languages` was not rewritten by either run

Negative checks, run on purpose: `JAVA_HOME=/nonexistent ./scripts/build-qin.sh`
stops with "no JDK at '/nonexistent'" and
`GRADLE=/nonexistent ./scripts/build-qin.sh` stops with "no gradle launcher at
'/nonexistent'" plus the location of the pinned copy. Both exit non-zero.

## Blockers found by running it, and their resolution

1. **`packageLiteRelease`: "Zip file .../tt9-v64.0.apk already contains entry
   'assets/dexopt/baseline.prof', cannot overwrite".** The shared asset tree carried
   a copy of `assets/dexopt/baseline.prof` + `.profm` that had been extracted from
   the official v64.0 APK, but those entries are generated by the release build from
   the merged AndroidX baseline profiles, so AGP tried to write the same path twice.
   Debug builds never hit it, which is why the earlier debug APK built fine. The
   extraction leftover was removed from `app/src/main/assets/dexopt/` (by the
   coordinator, outside this agent's write scope) and must not come back; the
   release APK now carries the generated profile (1,921 bytes) instead.
2. **Signing verification looked like a failure.** Build-tools 37 prints the signer
   digest as `V3.0 Signer: certificate SHA-256 digest: ...`, not
   `Signer #1 certificate SHA-256 digest: ...`; both scripts now check `apksigner`'s
   exit status and accept either line, instead of reporting a signed APK as unsigned.
3. **A transient compile error in `app/src`.** One run failed with
   `cannot find symbol: method clampDepth(int)` in
   `app/src/main/java/io/github/sspanak/tt9/dictation/PreparedWhistleTranscriber.java`
   while that file was being edited concurrently by another worker. The script
   failed loudly and named the Gradle log, which is the intended behaviour; a later
   run compiled and packaged normally. Nothing in `app/src` was touched to "fix" it.

## Not tested, and the limits of this note

* Nothing was installed. No ADB, no Magisk, no reboot: the module's behaviour on the
  Qin (mount at `/system/app/TT9Whistle`, package registration, IME registration,
  execution of `libneedle.so` from `nativeLibraryDir`, dictionary import) is
  unverified. `qin_tt9_whistle-64.0-magisk.zip` is a packaging artifact, not an
  installed result.
* `chcon` cannot run on macOS, so the SELinux contexts are only declared in
  `customize.sh`; whether Magisk applies them as written was not observed. The
  earlier v34 module was installed from a zip with the same layout, which is
  supporting precedent, not proof for this one.
* Entry modes were read back from the zip with `zipinfo` on the host; the modes after
  Magisk's own extraction were not observed.
* Only host-side checks were run. The APK's runtime behaviour (dictation, English
  offline, the arm64-v8a engine that is still bundled but useless on the 32-bit F22)
  is out of scope here.
* The release APK is signed with the machine's debug keystore, so it is installable
  and updatable within this fork but is not a distributable build.
* The build depends on the local SDK (platform 37.0, build-tools 37.0.0), the pinned
  Gradle 9.5.0 copy and JDK 17; nothing installs or downloads on your behalf, so an
  absent tool is a stop, not a fetch.
