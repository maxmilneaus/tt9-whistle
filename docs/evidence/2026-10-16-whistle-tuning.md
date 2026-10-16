# Whistle tuning for English dictation - measured, 2026-10-16

The question was which decoder depth and thread count this build should ship, and whether the
earlier claims about depth hold up. They mostly do not, in ways that matter.

Recommendation: **keep depth 4 and 4 threads.** Depth 4 has the best measured word accuracy of any
setting (48 errors per 927 reference words, tied with depth 8) and is the cheapest way to get it,
decoding 1.4x faster than depth 8 on this Mac and 1.5x faster on the phone. Depth 2 decodes 1.5x
faster than depth 4 on the phone and makes 31% more word errors, which is the wrong trade for the
keyboard's default. Depth 8 is not the
whole-model safety net it looks like: it wins nothing on words and loses all punctuation and
capitalisation on a 20-second take, every time. Depth 6 is beaten or matched on both axes by depth 4.

Nothing was installed, pushed or rebooted on the phone. Everything below was measured on the Mac
with the same runner and model the phone build ships, except where a row is explicitly attributed
to the earlier phone session.

## What was measured, and with what

Runner `work/whistle-test/bin/macos-arm64/needle`, sha256
`1e7e698b492904a92496e2b168844a3101e671928ffa6372970d897512251c48`; model
`work/whistle-test/models/whistle.cact`, sha256
`b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb`. Host: MacBook Pro 18,4
(M1 Max, 8 performance + 2 efficiency cores), macOS 27.0, python 3.15.0. Recorded in
`work/whistle-test/tune/results/meta.json`.

The runner's JSON carries its own timings, so nothing here guesses from wall clock:

* `text`, `language`, `ttft_ms` and `decode_tps`. `ttft_ms` is milliseconds to the first
  decoded token, so the audio encoder pass is inside it; `decode_tps` is decode tokens per second
  after that first token.
* `--audio-word-timestamps` adds `words[]` with each word's start, end and probability.
* `--audio-stream` adds `received` (seconds of audio consumed), `pending` and `pass_ms` per commit.

Every run is invoked exactly as the phone app invokes it, with no extra flags:

    needle --model whistle.cact --audio <wav> --audio-language en --audio-depth N --threads N

15 clips, deduplicated by content hash: the three in `work/whistle-test/clips` and the twelve
unique speech and silence clips in `work/whistle-test/evidence-2026-10-16/clips` (their
`manifest.json` supplies the sentence each was synthesised from). Depths 2, 4, 6, 8 and "no flag at
all", threads 1, 2, 4 and `0` (auto), three repetitions in randomised order: 900 runs, 603 KB of
raw JSON in `results/matrix.jsonl`. Every transcript was identical across the three repetitions of
its cell; only timings vary.

Scoring rules, stated because they decide the answer. Numbers are canonicalised on both sides, so
depth 2's "one, two, three" and depth 4's "1, 2, 3" are the same answer, and digit-versus-word output
is reported separately as formatting. "o'clock", "o' clock" and the reference's "o clock" are one
token, and an ordinal suffix on a digit ("22nd") is dropped - without those two, the reference
itself scored six false errors per depth on the filler clip. What is left is a spoken time ("three
thirty") written as one token ("330") and an ordinal ("twenty second") written as "22": still
counted as errors, at every depth equally, so they cannot reorder the depths. Clips whose reference
is empty (true silence, and the near-silent `max.wav`) have no word error rate at all: any text
they produce is counted as a hallucination, so silence can never flatter a corpus average.

### Where the reference for `clips/clip.wav` came from

That clip had no written reference anywhere in the tree, so a word-error score for it would have been
guesswork. It is a system TTS take, and it reproduces exactly:

    say -v 'Lee (Premium)' -r 175 -o verify.aiff "The quick brown fox jumps over the lazy dog. Please send the message to Anna at 5 o'clock."
    afconvert -f WAVE -d LEI16@16000 -c 1 verify.aiff verify.wav

`verify.wav` is byte-identical to `clips/clip.wav` (md5 `1cd7d800d1987daedc21b762acc09c29`,
178638 bytes), so the sentence above is the file's own input text, not an inference. whisper.cpp
`small.en` reads the same sentence from the audio independently, and "Anna" is also the first line
of `clips/keywords.txt`. The check is reproducible with `tune/verify-references.py`.

## The matrix

Medians over the fifteen clips and three repetitions of each cell, at 4 threads, which is the
shipping setting. The full table over all four thread settings is in `results/summary.md`.

| depth | wall median | ttft median | decode tps median | word errors | WER |
|---|---|---|---|---|---|
| 2 | 0.041s | 9ms | 1493 | 63/927 | 6.80% |
| **4** | 0.052s | 10ms | 1068 | **48/927** | **5.18%** |
| 6 | 0.052s | 10ms | 879 | 51/927 | 5.50% |
| 8 | 0.056s | 10ms | 754 | 48/927 | 5.18% |
| no flag (whole model) | 0.066s | 11ms | 728 | 48/927 | 5.18% |

Threads, same fifteen clips, same three repetitions:

| depth | threads | wall median | ttft median | decode tps median |
|---|---|---|---|---|
| 4 | 1 | 0.074s | 25ms | 655 |
| 4 | 2 | 0.064s | 15ms | 805 |
| 4 | 4 | 0.052s | 10ms | 1068 |
| 4 | 0 (auto) | 0.052s | 10ms | 1087 |
| 8 | 1 | 0.100s | 27ms | 428 |
| 8 | 4 | 0.056s | 10ms | 754 |

Two structural facts fall out of the timing columns, and they are the reason the recommendation is
about depth 4 rather than a smaller number:

* **Depth moves the decoder, not the encoder.** Time to first token is flat from depth 4 to the
  whole model (10, 10, 10, 11ms) and only 1ms better at depth 2. Decode throughput is where depth
  shows: 1493, 1068, 879, 754, 728 tokens per second from depth 2 to the whole model. So on a short
  dictation, where the encoder pass dominates what the user waits for, lowering depth buys almost
  nothing; it only pays on long takes, and those are the ones where the deeper decoder also stops
  punctuating.
* **On this host, wall-clock medians are too coarse to rank depths** (0.052s against 0.056s is
  process start and scheduler noise at 40-60ms per run). `decode_tps`, which is measured inside the
  runner, is the number to trust here, and the phone's own rows agree with it at 1.5x.

Threads: four is the fastest setting at every depth, and one thread costs 1.4-1.8x the wall clock and
2.5-2.7x the time to first token. "Auto" lands within noise of four threads on this host, which is
expected: the runner takes the device's fast cores, at most four, and this host has eight.

`--audio-depth 8` and passing no depth at all are the same decoder: across 52 clip and thread
combinations the transcripts were byte-identical, so the app's deepest setting is the whole model,
not a cheaper approximation of it. (`--audio-depth full` is rejected outright: "takes a layer count
of 2 or more".)

## Accuracy, clip by clip

Word errors per single run at 4 threads (the summary's per-clip table reports the same numbers
summed over three identical repetitions, so multiply by three to match the totals below).

| clip | audio s | ref words | depth 2 | depth 4 | depth 6 | depth 8 |
|---|---|---|---|---|---|---|
| address | 3.99 | 13 | 2 | 2 | 2 | 2 |
| clip | 5.45 | 18 | 3 | 2 | 3 | 2 |
| correction | 5.30 | 13 | 0 | 0 | 0 | 0 |
| fillers-au | 6.37 | 19 | 5 | 3 | 3 | 3 |
| latency-test | 4.64 | 11 | 0 | 0 | 0 | 0 |
| long-au | 19.95 | 71 | 0 | 0 | 0 | 0 |
| long-daniel | 21.90 | 83 | 1 | 1 | 1 | 1 |
| message | 3.78 | 11 | 0 | 0 | 0 | 0 |
| names-au | 5.41 | 19 | 3 | 3 | 2 | 2 |
| negation | 4.08 | 12 | 2 | 0 | 0 | 0 |
| numbers-au | 5.53 | 18 | 5 | 5 | 6 | 6 |
| short-au | 3.34 | 10 | 0 | 0 | 0 | 0 |
| thanks | 3.34 | 11 | 0 | 0 | 0 | 0 |
| **total x3 runs** | | **927** | **63** | **48** | **51** | **48** |

Depth 4 and depth 8 tie exactly on words, and they tie because they trade one clip for another:
depth 4 is better on `numbers-au`, depth 8 is better on `names-au`. Where they differ is what they
get wrong and what they do to formatting.

The earlier session's three claims, checked against these runs:

1. **"Every depth 2-8 hears every word" - refuted, though by less than the raw total suggests.**
   Depth 2 produced 63 word errors against depth 4's 48 over the same 927 words, a 31% higher word
   error rate, stable across repetitions. Five errors per run separate them and three of those five
   are genuine mishearings: "junts" for "jumps" on the pangram clip, "Er" for "uh" and "meat" for
   "meet" on the informal-speech clip. The other two are formatting: depth 2 writes a time as one
   token, "at 1030" where depth 4 writes "at 10.30". Depth 2 is not worse on ten of the thirteen
   clips, so this is a real but narrow deficit, and it lands on informal speech (fillers-au, 5
   errors against 3) and on the pangram.
2. **"Depth 2 writes numbers as words, deeper depths write digits" - half true.** Depth 2 says
   "one, two, three", "before nine", "another ten days". Depth 4 says "1, 2, 3", "10.30", "42",
   "330", "22nd", "15 minutes". But it is not a clean rule: depth 8 wrote "nine" and "ten days", and
   depth 6 wrote "9" alongside "Tuesday, 22 November". Of the settings offered, depth 4 is the most
   consistently digit-shaped.
3. **"Depth 8 was measurably worse on one clip (heard Ram instead of round)" - reproduces exactly,
   but not as an error count.** On `clips/clip.wav` at 4 threads, all three repetitions: depth 4
   returns "The quick round Fox jumps over the lazy dog. Please send the message to Enter at 5
   o'clock.", while depth 8 and the whole model return "The quick **Ram** Fox jumps over the lazy dog.
   Please send the message to **Enrir** at 5 o'clock.", against a truth of "brown" and "Anna". Both
   cost two words, so depth 8 is not numerically worse on that clip; it is worse in kind, since "Ram"
   and "Enrir" are further from "brown" and "Anna" than "round" and "Enter" are.

The measurable depth-8 regression is formatting, and it lands on the long takes. On the 20-second
`long-au` clip, all three depth 8 repetitions and all three whole-model repetitions return the
entire transcript with no capital letters and no punctuation at all, while depths 2, 4 and 6
punctuate and capitalise it normally:

    depth 4: Right, so the plan for next week is that I drive up on Monday and stay overnight, then we do the site inspection on Tuesday morning with the Electrician, and after lunch I need to call the Shire about the permit. Because they said it could take another 10 days, ...
    depth 8: right so the plan for next week is that i drive up on monday and stay overnight then we do the site inspection on tuesday morning with the electrician and after lunch i need to call the shire about the permit because they said it could take another ten days ...

Word error rate cannot see that, which is why it is written here rather than left in the WER column.
For a keyboard that types into a message box, punctuation is the product.

## Best accuracy per millisecond

Depth 4. It matches the best accuracy available (48 errors per 927 words, equal to the whole model
and better than 6 and 2) and it is the fastest setting that reaches it:

| setting | word errors per 927 words | decode tps | ttft | verdict |
|---|---|---|---|---|
| depth 4, 4 threads | 48 | 1068 | 10ms | best accuracy, cheapest way to it |
| depth 8, 4 threads | 48 | 754 | 10ms | same words, 1.4x slower decode, no punctuation on long takes |
| depth 6, 4 threads | 51 | 879 | 10ms | beaten on accuracy and on speed by depth 4 |
| depth 2, 4 threads | 63 | 1493 | 9ms | 1.4x faster decode, 31% more word errors |

## What this means on the phone, and what was not measured here

The phone is far slower than this Mac, so no absolute host number is quoted for it. The relative
cost of depth does carry over, because the runner binds the same arithmetic on both. The earlier
phone session's rows in `work/whistle-test/evidence-2026-10-16/screening.jsonl` (medians over three
clips at 4 threads, where depth 0 means no flag, so the whole model) read:

| setting | 1 thread | 2 threads | 4 threads | ttft at 4 threads | decode tps at 4 threads |
|---|---|---|---|---|---|
| whole model (depth 8) | 3.77s | 2.23s | 1.47s | 400ms | 23.4 |
| depth 4 | 2.94s | 1.73s | 1.18s | 372ms | 35.8 |
| depth 2 | 2.47s | 1.49s | 1.03s | 355ms | 52.4 |

Those rows are the earlier session's measurements on the phone, not today's; re-reading them is what
supports the phone half of the recommendation. They say depth 4 costs 20% less wall clock than the
whole model and decodes 1.53x faster, which is the same 1.4-1.5x ratio this host measured, and that
is the evidence the depth speedup transfers. They also say one thread costs 2.5x the wall clock of
four at depth 4, so 4 threads is not a guess on a 4-core phone. Depth 2 would save a further 13%
(1.18s to 1.03s on that clip set) at the price of the word errors above.

Measured levers on speed, and what each is worth:

* Depth 8 to depth 4: the only depth change that buys speed without giving up measured accuracy.
  Already what the build does.
* 1 thread to 4: 2.5x on the phone, 1.4-1.8x here. Already what the build does.
* Hint words cost nothing measurable: `clip.wav` at depth 4 took 0.052s with and 0.052s without
  `--audio-keywords clips/keywords.txt` (0.050-0.052s across three repetitions either way); at
  depth 8 the plain runs were 0.069-0.083s and the hinted runs 0.061-0.102s, which is noise. They
  are not a speed lever in either direction, but they move words: at depth 4 the hints "Anna" and
  "five" turned a nearly-correct "Enter at 5 o'clock" into "Anna Rat Five O'Clock", while at depth 8
  the same hints fixed the name and the number form to "Anna at five o'clock" and removed the "Ram".
  This extends the handoff's existing warning from one clip to two: hints are a real lever that can
  help and hurt, and they are not an accuracy cure.
* `--audio-stream`, the one untried lever, is measured but **not recommended on this evidence**. On
  the host, 1-second chunks commit their first words after 2-3 seconds of audio instead of at the
  end of the clip: `long-au` (19.95s of audio) first commits at 1.10-1.94s across the runs at depths
  2, 4 and 8. But the host also transcribes the whole 20-second clip in 0.12-0.17s, so streaming is
  strictly slower here, and the phone's own rows show it transcribing a 4.6-second clip in 1.18s,
  which is also faster than real time. Streaming only wins if the runner starts before the user stops
  talking, which means feeding the microphone live: a Java change in `VoiceHandler`, outside this
  task's write scope and unmeasured on the phone. Do not treat it as a speedup until it is measured
  there.

## Silence and hallucination

True silence returns an empty transcript at every depth and thread count. The near-silent `max.wav`
(peak 0.0042 of full scale, a system sound followed by 8 seconds of quiet) returns "Thank you." at
every depth and every thread count, which is the behaviour the handoff describes. It is also the
reason to keep the app's client-side peak gate: 0.0042 is below the 0.02 gate, so the runner never
sees this clip in the real app, but nothing in the model itself suppresses it.

Word probabilities from `--audio-word-timestamps` confirm depth 4 hears better than depth 2 on the
clips depth 2 gets wrong (fillers-au mean 0.902 against 0.838 and minimum 0.457 against 0.133;
negation 0.953 against 0.854; names-au 0.925 against 0.866). They are not a correctness signal
either: the hallucinated "Thank you." scores a mean probability of 0.96, and depth 8's wrong words
score a higher mean on `clip.wav` (0.889) than depth 4's wrong words (0.856). Confidence cannot gate
this model's errors.

## Files

New, all under `work/whistle-test/tune`:

* `run-matrix.sh` - regenerates everything (`REPS`, `DEPTHS`, `THREADS` override the defaults).
* `matrix.py`, `accuracy.py`, `probes.py`, `references.py`, `verify-references.py`,
  `clips-bench.json`.
* `results/matrix.jsonl` (900 raw runs), `results/accuracy.jsonl` (scored), `results/summary.md`,
  `results/summary.csv`, `results/probes.md`, `results/probes.json`, `results/clips.json`,
  `results/clips-reference.json`, `results/reference-verification.txt`, `results/meta.json`,
  `results/needle-help.txt`.

Reproduce with:

    cd "work/whistle-test/tune" && ./run-matrix.sh

## Not verified

* Nothing was measured on the phone today. The phone rows quoted above are from
  `evidence-2026-10-16/screening.jsonl`, written by the earlier phone session; the
  `warm-fifo.jsonl` rows in the same folder are not comparable to them, because that harness polls
  and waits and its wall times include seconds of FIFO hand-rolling.
* The depth speedup ratios agree between host and phone, but the absolute phone cost of any single
  dictation was not measured here.
* The corpus is synthesised TTS, mostly one Australian voice plus two American ones, with no room
  noise. A real-room corpus could reorder the depths; `fillers-au` is the nearest thing in it to
  spontaneous speech, and it is also where depth 2 loses most.
* Hint words were tested on one clip, three repetitions. The direction of the effect is measured; its
  size is not.
* whisper.cpp `small.en` was used only to resolve the name in `clips/clip.wav`, before that name
  was confirmed byte-for-byte by TTS round-trip. It was not used to score anything.
* `HANDOFF.md` was left untouched, along with every file outside `work/whistle-test/tune/` and this
  note, per the task's write scope.
