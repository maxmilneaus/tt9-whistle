# Warm Whistle runner: desktop probe, protocol and evidence

This directory owns the desktop evidence for the warm (prepared) Whistle runtime and the protocol
the app uses to drive it. Everything here is read-only with respect to the phone: the probe runs
the shipped runner on this computer and never calls adb.

## Runtime facts this rests on

Verified by inspecting the retained artifacts and the shipped binaries, not by inference:

| Item | Finding |
| --- | --- |
| Public C API | 11 symbols only: needle_load, needle_models, needle_last_error, needle_init, needle_complete, needle_set_audio, needle_reset, needle_transcribe, needle_stream_transcribe_process, needle_stream_transcribe_stop, needle_embed |
| Depth or thread setter | Absent from the header. whistle::Engine::set_depth and needle::Engine::set_depth exist inside the archive as private weak symbols with no engine handle, so they are not a supported path |
| Depth range | --audio-depth N accepts 2..full. The retained whistle.cact holds 8 rungs: depth 8 output is identical to the default, and 9 and above fail at load with "failed to load .cact speech model" |
| Threads | --threads N has no upper validation. 4 is the documented ceiling ("the device's fast cores, at most 4"); 99 collapses throughput. 0 means engine default |
| --serve | Text only: POST /complete with input and max_tokens, plus POST /reset. No audio route |
| --audio stdin | Not supported: "cannot read - (RIFF WAV)" |
| Repeatable --audio | No: a second --audio replaces the first, so one utterance per process |
| Language | --audio-language takes the seven the pinned model supports: en, de, fr, es, it, nl, pl. The warm configuration includes it, so a language change makes the prepared runner stale |
| Depth is not the same as text depth | --depth N is the Needle text ladder; --audio-depth N is the speech decoder ladder. The platform download and build --depth and --layers flags slice a text checkpoint and are unrelated |

Consequence: a warm runtime that keeps depth and thread control cannot be an in-process C-API
worker. It has to be the shipped runner, held open on a FIFO between dictations.

## probe_fifo.py

Desktop probe that answers, with timestamps and RSS samples:

1. does the runner load the model before it waits for audio?
2. does the ordinary --audio path accept a FIFO, with no --audio-stream?
3. does it emit anything before EOF, or read the whole WAV first?
4. what does an idle prepared runner cost, and how long after EOF is the result?

Usage:

    python3 tests/whistle/probe_fifo.py --runner <needle> --model <whistle.cact> --clip <clip.wav> \
        --depth 4 --threads 4 --pre-eof-window 0.5 --compare-full-file --out <evidence.json>

Phases it times: spawn, model_resident, writer_open, partial_audio_written,
first_output_before_eof, audio_written, eof, first_output_after_eof, exit. Every wait is bounded
by --max-wait, which defaults to 10 seconds. It also samples RSS throughout, keeps the peak, and
compares the FIFO transcript with a plain-file run when --compare-full-file is passed.

## Desktop evidence (2026-10-16, macOS arm64 retained runner)

Clip latency-test.wav, depth 4, threads 4, contiguous delivery:

| Measurement | Value |
| --- | --- |
| Model resident while the FIFO was still untouched | yes, t = 0.85 s, RSS 38,384 KiB |
| FIFO writer accepted in the ordinary --audio path | yes, t = 0.85 s |
| Output before EOF | none |
| EOF | t = 1.39 s (the writer had pushed 4 KiB header plus 149 KiB PCM) |
| Result and exit after EOF | t = 1.50 s, exit code 0 |
| EOF to result | about 0.12 s |
| Peak RSS | 42,880 to 44,800 KiB |

The FLLR-bearing clip from the work directory behaves the same way and its FIFO transcript is
identical to its plain-file transcript. A canonical 44-byte header behaves identically, so the app
does not need to special-case the chunk list.

The runner loads the model before it waits for a writer, so prepare-at-idle hides the load and the
stop-to-result delay covers decoding only. Nothing is emitted before EOF, so this path is
transcribe-after-stop, not live preview.

Hazard worth keeping: when the probe accidentally used a single non-blocking os.write, short
writes corrupted the stream. The runner still exited 0 and still printed valid JSON, but the text
was wrong ("Shit.", then "Chris." on the same clip). Byte-exact delivery is part of the contract,
which is why the Java writer loops on partial writes and EAGAIN.

Evidence files (kept with the work-directory evidence, not in this repo):

- work/whistle-test/evidence-2026-10-16/probe-fifo-canonical-fixed.json - passed run, canonical header
- work/whistle-test/evidence-2026-10-16/probe-fifo-fllr-fixed.json - passed run, FLLR header
- work/whistle-test/evidence-2026-10-16/probe-fifo-canonical.json - negative control, short writes
- work/whistle-test/evidence-2026-10-16/probe-fifo-desktop.json - negative control plus a 10 s mid-WAV stall

Phone counterpart, supplied by the main agent: ordinary --audio on a root-created FIFO succeeded
with the exact transcript, VmHWM 40,752 KiB and VmRSS 24,456 KiB while sleeping before any audio.
That is an existence and residency proof only and carries no timing claim: the 5.35 s figure was
taken while the extended screen was running, and the host root-polling probe behind it was discarded
after a Magisk-logging CPU storm. Warm stop-to-result on the phone is still unmeasured; the next
measurement is a real run as the app UID.

Cold per-call screen for reference (work/whistle-test/evidence-2026-10-16/screening.jsonl, 27 runs,
median wall seconds over 3 clips):

| Depth | threads 1 | threads 2 | threads 4 |
| --- | --- | --- | --- |
| full (0) | 3.77 | 2.23 | 1.47 |
| 2 | 2.47 | 1.49 | 1.03 |
| 4 | 2.94 | 1.73 | 1.18 |

Depth 2 is fastest and buys wording errors against depth 4 and full; that is the main agent's
screening call, and this probe does not repeat it.

## Protocol used by PreparedWhistleTranscriber

1. prepare(context, binary, model, depth, threads) in the background: creates an app-private FIFO
   with Os.mkfifo in the cache directory, mode 0600, and starts one runner with --model, --audio
   pointing at that FIFO, --audio-language from DictationSettings.getLanguage(), plus --audio-depth
   and --threads when set. It never opens the pipe, so it never blocks. An existing idle runner with
   the same configuration is reused, and the match key is binary, model, depth, threads and language:
   changing any of them in settings leaves the idle runner stale, so transcribe reports not ready and
   the caller uses the per-call path until the next prepare matches. isReady(context, ...) answers the
   same question as a plain boolean, so the caller can tell "there is no warm runner" from a real
   engine failure without reading an exception message.
2. The runner loads the model and blocks on the FIFO. The user dictates.
3. transcribe(wav) takes the idle runner, opens the FIFO with O_WRONLY | O_NONBLOCK and retries
   ENXIO until the runner's reader is there, writes the whole WAV, closes the descriptor (EOF).
4. The runner processes at EOF, prints one JSON object per line, and exits.
5. The transcript comes from LocalWhistleTranscriber.parseLastLine, so both engines share one
   parser. An empty transcript is returned as an empty string rather than an error.
6. A static watchdog kills the process at the 30 s deadline, which ends the blocked read; the
   transcribing thread reaps the process and deletes the FIFO on every path.
7. After a result the code prepares a replacement runner, so the next dictation starts warm with
   no simultaneous spare while one is active.

Validation: depth is clamped to 0 (the whole ladder, sent as no flag) or 2..8, threads to 0 (the
engine picks) or 1..4, and the language to the seven the pinned model knows, so no value outside the
runner's real limits reaches it from either the settings or a caller. A depth below 2 reads as the
whole ladder, the same rule DictationSettings.clampDepth uses.

## Host harness

tests/whistle/run-tests.sh compiles PreparedWhistleTranscriber.java, LocalWhistleTranscriber.java,
DictationSettings.java and the shared Transcriber interface with a plain JDK plus android.jar and the
androidx jars from the Gradle module cache, then runs three device-free suites: argument construction,
the depth/thread/language ranges, result parsing and the cancellation latch. If the module cache
moves, point TT9_ANDROIDX_ANNOTATION_JAR and TT9_ANDROIDX_PREFERENCE_AAR at the jars by hand.
tests/whistle/mutation-control.sh copies the tree into a scratch directory, injects the defects the
suites claim to catch and checks that every one of them is reported, so a suite that would pass on
broken code fails the script. Neither script touches a device or writes inside the repository.

What these suites cannot prove: the FIFO, the non-blocking open with its ENXIO retry, the EAGAIN
handling and the watchdog need the platform, so they rest on the desktop probe above and on reading,
not on the JVM suites.

## Open items

- FIFO creation as the app UID on the device is unproven. The shell domain was denied creating a
  FIFO in /data/local/tmp and root worked; an app-private cache directory is the case to test. If
  Os.mkfifo fails there, prepare throws and the caller falls back to the per-call path.
- One idle runner costs about 24 MB RSS on the phone and about 44 MB peak while transcribing on
  desktop. On this phone (2 GB, MemAvailable 1.1 GB) one held process is the design; release()
  exists for low-memory.
- release() sets a flag that suppresses the automatic replacement; the next explicit prepare()
  call re-arms it. A cancellation that races a release can therefore leave no idle runner until
  prepare runs again.
- The warm path's stop-to-result time on the phone is not measured yet. Desktop EOF-to-result was
  0.12 s; the cold phone runs at depth 4 and 4 threads were 1.18 to 1.76 s wall including load.

