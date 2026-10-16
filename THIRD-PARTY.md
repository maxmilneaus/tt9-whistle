# Third-party notices and licence findings

This repository is a fork of Traditional T9 that adds on-device speech-to-text. It
redistributes three third-party components. All three are under the Apache License,
Version 2.0, which permits redistribution and modification provided the licence text
and the existing notices are kept, changed files are marked as changed, and no further
restrictions are added. Nothing in this repository forbids redistribution.

Every statement below was checked against the upstream source on 2026-10-16. File
identity was checked by SHA-256, not by filename or version label.

## 1. Traditional T9 (upstream)

- Source: <https://github.com/sspanak/tt9>
- Licence: Apache License 2.0. The full text is in [LICENSE.txt](LICENSE.txt).
- Base commit of this fork: `e9f37e49ef9698cc78a61def709c191661e2fa25`, the upstream
  v64.0 release commit.
- Upstream ships no `NOTICE` file, so there is no separate notice file to reproduce.
- Obligations met here: the Apache-2.0 text is kept in `LICENSE.txt`; upstream's
  copyright, attribution and link notices are retained in the files this fork
  modified; and the fork's changes are recorded as ordinary commits on this branch,
  which marks the modified files as changed.

## 2. Whistle speech model (Cactus Compute)

- File in this repository: `app/src/main/assets/whistle/whistle.cact`,
  16,919,407 bytes, SHA-256
  `b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb`.
- Source: Hugging Face, `Cactus-Compute/whistle`,
  <https://huggingface.co/Cactus-Compute/whistle>.
- Licence: Apache License 2.0. The model card declares `license: apache-2.0`, and the
  repository ships the Apache-2.0 text as its `LICENSE` file (SHA-256
  `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`).
- Identity check: the file here is byte-for-byte the published model file. Its SHA-256
  equals the LFS object id Hugging Face reports for `whistle.cact` in that repository,
  and its size matches exactly. Every revision of that repository's `LICENSE` that
  could be fetched is the same Apache-2.0 text.

## 3. Needle runtime (Cactus Compute)

- Files in this repository:
  - `app/src/main/jniLibs/armeabi-v7a/libneedle.so`, 917,312 bytes, SHA-256
    `eca6987649fd4f39b14b8f0c64a7be31ea70a41b959b6a1a698810d499835c6c`
  - `app/src/main/jniLibs/arm64-v8a/libneedle.so`, 1,499,296 bytes, SHA-256
    `504a7f2be1be55f4040f575b5239969241f033b72a2e68aa50225f930f603e0a`
- Source: Hugging Face, `Cactus-Compute/needle3`,
  <https://huggingface.co/Cactus-Compute/needle3>, revision
  `93e88ede867e4311c39151285e74df513e4b835b`, files `android-armv7/needle` and
  `android-arm64/needle`. Project documentation:
  <https://github.com/cactus-compute/needle>.
- Licence: Apache License 2.0. That revision's `LICENSE` is the same Apache-2.0 text
  (`cfc7749b...`), and its model card declares `license: apache-2.0`. The
  `cactus-compute/needle` source repository is Apache-2.0 as well.
- Identity check: both files here are byte-for-byte the published engine binaries. Their
  SHA-256 values equal the object ids Hugging Face reports for those two files at that
  revision, and their sizes match exactly.

## 4. A licence that does not apply here

Cactus Compute publishes a separate product, the Cactus framework, at
<https://github.com/cactus-compute/cactus> (checked at v2.2.2). That repository's
`LICENSE` is **not** Apache-2.0. It reads "Copyright (c) 2025 Cactus Compute, Inc. All
Rights Reserved", grants use, copying, modification, publication and distribution free
of charge only to individuals for personal, educational, research or non-commercial use,
to organisations with both less than USD 2,000,000 in total funding and less than USD
2,000,000 in gross annual revenue, to educational institutions and students, and to
US 501(c)(3) non-profits, and requires a separate commercial licence otherwise.

**No file from that repository is redistributed here.** The executable engine this fork
runs is the Needle runtime's published Android build (section 3); the speech weights are
the published Whistle checkpoint (section 2). The Cactus framework builds a differently
named artefact (`libcactus_engine.so`), which is not in this repository.

An earlier revision of this repository's README described the Whistle model as carrying
that non-commercial framework licence and concluded that the APK must not be
redistributed. That was a misattribution: the two Cactus Compute projects carry
different licences, and the model and runtime that are actually bundled are both
Apache-2.0. The earlier claim is withdrawn, with the evidence above replacing it.

Because upstream terms can change, anyone redistributing this fork should re-check the
two Hugging Face licence pages rather than relying on this file.

## 5. Dictionaries, n-grams and other bundled data

The language data under `app/languages/` and `docs/dictionaries/` keeps its own
licences, which are listed in the `*WordlistReadme.txt` files in `docs/dictionaries/`
(for example the English word list README records the sources, copyright notices and the
licence of each contributing word list). Those notices are not restated here; they travel
with the files.

