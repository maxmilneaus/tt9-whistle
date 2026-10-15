# TT9 Whistle (Qin) packaging footprint

2026-10-16. Scope: which files ship in which flavour, how they are reproduced, and
what was verified. No application code, Gradle configuration, resource or manifest
file was changed as part of this work; the change is asset placement plus two new
scripts.

## Why the payload sits where it does

The dictionary loader is flavour blind: WordFile / NgramsFile extend
RemoteAssetFile, and its reader takes the bundled asset when it exists and only
falls back to the network otherwise:

    InputStream stream = exists() ? getStream() : getRemoteStream();
    (app/src/main/java/io/github/sspanak/tt9/util/RemoteAssetFile.java)

exists() is just AssetManager.open(path) on languages/dictionaries/<file>.zip, and
the download URL comes from the "revision" line of the .props.yml that sits next to
the payload in assets. So the flavour only decides what is bundled:

* a metadata file must be bundled for a language to be selectable and downloadable;
* a payload zip bundled for that flavour makes that language work offline;
* BuildConfig.LITE has exactly one consumer, the download-size suffix in the
  language-selection summary (PreferenceSwitchLanguage). It does not gate loading.

The layout below therefore keeps all metadata shared, keeps the payload in the
full flavour, and lets the lite flavour carry English only.

## Current placement (measured 2026-10-16)

| location | contents | count | bytes |
| --- | --- | --- | --- |
| app/src/main/assets/languages/definitions.yml | full language list | 1 | 19,682 |
| app/src/main/assets/languages/dictionaries/*.props.yml | hash, revision, size, words | 54 | 11,506 |
| app/src/main/assets/languages/ngrams/*.props.yml | revision only | 38 | 1,900 |
| app/src/main/assets/docs/ | help.*.html + privacy.html | 10 | 544,510 |
| app/src/main/assets/dexopt/ | baseline.prof, baseline.profm | 2 | 2,168 |
| app/src/main/assets/languages/definitions/ | per-language yml, untouched | 29 | 8,439 |
| app/src/main/assets/whistle/whistle.cact | on-device ASR model, all flavours | 1 | 16,919,407 |
| app/src/main/jniLibs/{armeabi-v7a,arm64-v8a}/libneedle.so | on-device ASR engine, all flavours | 2 | 2,416,608 |
| app/src/full/assets/languages/dictionaries/*.zip | dictionary payload, full only | 54 | 253,112,019 |
| app/src/full/assets/languages/ngrams/*.zip | ngram payload, full only | 38 | 1,620,227 |
| app/src/lite/assets/languages/dictionaries/en-utf8.zip | offline English, lite only | 1 | 1,181,419 |
| app/src/lite/assets/languages/ngrams/en-ngrams.zip | offline English mind reader, lite only | 1 | 43,949 |
| app/src/debug/assets/clips/latency-test.wav | dictation fixture, debug source set only | 1 | 152,702 |

Shared total 17,507,612 bytes, full payload 254,732,246 bytes, lite payload
1,225,368 bytes. The full flavour merges both trees, so it looks exactly like the
upstream full asset tree; the lite flavour merges main + lite and gets metadata for
every language plus English payloads. The clip fixture used to sit in the shared
tree (main/assets/clips) and is now only in the debug source set, which is where its
only reader lives (DebugClipReceiver, debug java source set).

## Provenance

Everything above that is not the Whistle model or the JNI engine is extracted from
the pinned official release:

* source: https://github.com/sspanak/tt9/releases/download/v64.0/tt9-v64.0-full.apk
* size: 250,786,662 bytes
* sha256: b13b93a129d945996b5c2b9dec7a9fd660cd26bf8855cfdb7a4780624d349bda
* cached at: work/toolchains/tt9-v64-upstream-full.apk (outside this repo)
* recorded by: docs/evidence/upstream-v64-assets.json (checked on every asset run)

Verified on 2026-10-16 after the move: all 195 APK members that we ship (54
dictionary props, 38 ngram props, 54 dictionary zips, 38 ngram zips,
definitions.yml, 10 docs files) are byte-identical (sha256) to their copies in the
source tree, the lite tree holds exactly the two English payloads, and the shared
tree holds no payload zips.

## Reproducing

scripts/build-qin.sh does the whole job and never installs anything:

* --verify-only  checks the pinned APK hash, the provenance file and the current
  placement, writes nothing, exit 0 means the tree matches this document;
* --assets-only  re-extracts the metadata, docs and payloads from the pinned APK and
  rebuilds the placement (default when building);
* default        assets, then :app:assembleLiteDebug and :app:assembleLiteRelease,
  then zipalign/apksigner into dist/ (dist/TT9Whistle64.apk, TT9Whistle64-debug.apk);
* --no-assets, --variant debug|release, --no-sign, --dry-run for the other cases.

The build always skips the asset generator tasks, because they would overwrite the
pinned assets: validateLanguages, buildDefinition, buildDictionaryDownloads,
copyDownloadsToAssets, generateNgramsMeta, copyNgramsToAssets, copyNgramsToDownloads,
generateDocs. It also skips updateStatusIcons and updateManifest, which rewrite
tracked files (StatusIcon.java, AndroidManifest.xml) from the current git state; the
script hashes both before and after the build and fails if they changed.

Toolchain notes: the wrapper pins Gradle 9.5.0
(distributionSha256Sum 553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746).
When the wrapper's own distribution is not unpacked, the script uses the matching
unpacked 9.5.0 distribution in work/toolchains after checking the cached
gradle-9.5.0-bin.zip against that same hash. A JDK 17 is enough: only the generator
tasks (always skipped here) need JDK 21. TASK_JAVA_HOME, then JAVA_HOME, then the
work/toolchains zulu21 JDK are tried in that order; nothing is installed.

Signing is personal-build only: the existing ~/.android/debug.keystore (alias
androiddebugkey) signs the release APK. If it is absent the script stops instead of
generating a key, and dist/ is git-ignored (".gitignore: /dist/").

## Effect on APK size

Measured from the lite/debug APK that the main build produced before this change
(app/build/outputs/apk/lite/debug/tt9-v64.0-debug.apk, 274,641,892 bytes): the APK
stored 90 payload entries for non-English languages = 247,145,511 bytes, and 2
English entries = 1,223,100 bytes. With the payloads now in the full flavour and the
English ones in the lite flavour, that APK becomes about 27.5 MB
(274,641,892 - 247,145,511). It still contains the 16.9 MB Whistle model, both ABI
engines (2.4 MB) and the English payloads. Build configuration was not touched, so
the arm64-v8a engine can still be dropped from the APK later (1.5 MB) if only the
phone matters. The release variant is minified and should come out smaller than the
debug number; the main build's assembleLiteRelease will confirm both.

## Lite vs full behaviour

Beyond which payloads are bundled there is one visible difference: the language
selection screen shows the download size in lite and hides it in full
(PreferenceSwitchLanguage). Everything else is identical code: lite's java source
set is a symlink to app/src/full/java, there are no flavour-specific resources or
manifests, and no runtime path branches on LITE. A language that is not bundled
imports from raw.githubusercontent.com using the revision in its shared metadata
file, which needs INTERNET (declared in the shared manifest and again in
app/src/lite/AndroidManifest.xml); after import the words live in SQLite and work
offline. Premium is a separate source set and flavour (PREMIUM=true plus the billing
dependency), absent from both lite and full, so choosing lite neither adds nor
removes premium code.

## System-app module

make-module.sh stages (never installs) a Magisk module from dist/TT9Whistle64.apk:
package io.github.sspanak.tt9.qin as system/app/TT9Whistle/TT9Whistle.apk, the
armeabi-v7a engine extracted from the APK into
system/app/TT9Whistle/lib/arm/libneedle.so (the path ApplicationInfo.nativeLibraryDir
points at for a preinstalled app, which is where DictationEngine execs it), module
version 64.0-qin / versionCode 1646, plus the stock Magisk META-INF installer stub.
It refuses an APK whose package, versionCode, versionName, engine or Whistle asset
do not match, and by default requires the lite layout (one dictionary and one ngram
payload); --any-payloads relaxes only that last check, for a full-flavour build.

## Flagged, not touched

* app/src/main/assets/languages/definitions/ (29 files, 8,439 bytes) is bundled in
  every flavour but has no runtime reader in app/src: only languages/definitions.yml
  is parsed, and upstream's APK does not contain the directory at all. Left in place
  because it is metadata, and removing it is a packaging decision, not a fix.
* downloads/ at the repo root is still in a mixed state from earlier generator runs
  (some zips deleted, some modified). scripts/build-qin.sh never reads or writes it;
  re-enabling the generator tasks would rewrite it and app/src/main/assets.
* The Whistle model and engine are unchanged and shared: the same
  app/src/main/assets/whistle/whistle.cact (16,919,407 bytes) and the same
  jniLibs engines are in every flavour, so the on-device engine is available in the
  compact build.

## Needs the main build to confirm

* :app:assembleLiteDebug and :app:assembleLiteRelease produce an APK of roughly
  27.5 MB (debug) containing assets/languages/dictionaries/en-utf8.zip,
  assets/languages/ngrams/en-ngrams.zip, assets/whistle/whistle.cact and
  lib/armeabi-v7a/libneedle.so;
* make-module.sh against that release APK (strict lite check should pass);
* on device, that (a) English works with no network, (b) another language downloads
  and then works offline, (c) the voice key still reaches the on-device engine from
  /system/app/TT9Whistle/lib/arm.
