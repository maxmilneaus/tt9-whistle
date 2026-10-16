# OpenCode Go: which models accept audio (2026-10-16)

## Why

The cloud dictation engine in the TT9 fork posts to
`https://opencode.ai/inference/go/v1/chat/completions` with `mimo-v2.6-flash`.
That model now rejects the request with HTTP 400:

```json
{"error":{"param":"messages","type":"invalid_request_error","message":"Upstream request failed: [unsupported_content_type] Messages may contain text, images and video; this model does not read audio or files."}}
```

Re-confirmed today with the exact payload `OpenCodeTranscriber.requestBody()` builds
(see Method below). The user-visible result was the generic "Dictation failed, code 201".

## What was asked

1. List the model ids offered by `GET /inference/go/v1/models`.
2. Find which of them actually accept audio, politely: one small request per candidate,
   at least 5 s apart, stopping at the first real transcript.
3. Record model id, HTTP status, transcript or exact error.
4. Make the "cannot read audio" 400 produce an actionable message in the app.

## Secrets

The API key was read from `/tmp/qk.key` into a shell/Python variable only. It is not
printed, logged, echoed or stored by these probes, and no key material appears in this
document.

## Method

- Ground-truth speech clips rather than arbitrary audio:
  `apps/qin-dictation/tests/clips/` (`short-au.wav`, `numbers-au.wav`, `names-au.wav`).
  Each sentence is published in `tests/clips/clips.txt`, so a reply can be scored instead
  of trusted.
- One `POST` per candidate, sequential, 6 s apart, stopping at the first verified result.
- The payload is byte-for-byte the shape the app sends: a `system` message carrying
  `SYSTEM_INSTRUCTION`, and a `user` content array of
  `[{"type":"text"},{"type":"input_audio","input_audio":{"data":"data:audio/wav;base64,...","format":"wav"}}]`,
  with `stream:false`, `temperature:0.0` and `thinking:{"type":"disabled"}`.
- `User-Agent: tt9-dictation/0.1`, plus the `x-opencode-session` UUID header the app sends.
- Verdict rule: verified only when the transcript matches the published sentence. HTTP 200
  alone proves nothing (see the trap below).

Operational finding: the endpoint requires the `x-opencode-session` header. Without it
every request returns 400 `{"type":"error","error":{"type":"MissingSessionID",...}}`. The
app already sends it; the first probe run did not, and those five requests are excluded
from the table.

## Results

`verified` = transcript matches the known clip. `no audio` = explicit rejection naming
audio. `200, no transcript` = returned text that is not a transcript of the clip.
`other` = unrelated protocol or endpoint failure.

| # | model id | HTTP | Verdict | Detail |
|---|---|---|---|---|
| 1 | mimo-v2.6-pro | 200 | **verified** | exact transcript, see below |
| 2 | mimo-v2.6-flash | 400 | no audio | `[unsupported_content_type] ... this model does not read audio or files.` (current app default) |
| 3 | glm-5.3-flash | 400 | no audio | `[unsupported_content_type] ... this model does not read audio or files.` |
| 4 | step-5-preview | 400 | no audio | `[model_incompatible] The model you requested doesnt support audio input.` |
| 5 | glm-5.3 | 400 | other | `2 request validation errors: Input should be a valid string, field 'messages[1].content.str'; Extra inputs are not permitted, field 'messages[1].content.list[ChatMessageContent][1].input_audio'` |
| 6 | glm-5.2 | 400 | other | same as glm-5.3 |
| 7 | glm-5.1 | 400 | other | same as glm-5.3 |
| 8 | qwen3.8-max | 400 | other | `InternalError.Algo.InvalidParameter: An incorrect modal `audio` was entered, which may not be supported by the model or was placed in the wrong position (e.g., in system/assistant).` |
| 9 | qwen3.8-flash | 400 | other | same as qwen3.8-max |
| 10 | qwen3.7-plus | 400 | other | same as qwen3.8-max |
| 11 | qwen3.6-plus | 400 | other | same as qwen3.8-max |
| 12 | qwen3.7-max | 400 | other | `The provided messages input is invalid. The error info is [Unexpected item type in content.].` |
| 13 | gemini-4-argon-contributor | 400 | other | `This Go model trains on request data. Allow paid endpoints that train on request data in your workspace's Privacy settings to use it.` |
| 14 | minimax-m3 | 200 | 200, no transcript | refusal `I don't see any audio attached to your message`; an earlier run fabricated a transcript, see the trap |
| 15 | hy3 | 200 | 200, no transcript | fabricated unrelated text: `We need to launch the new feature by next Monday...` |
| 16 | kimi-k3 | 200 | 200, no transcript | refusal `I don't have access to any audio file or attachment in this conversation.` |
| 17 | deepseek-v4-pro | 200 | 200, no transcript | `the provided audio data appears to be incomplete or corrupted` |
| 18 | deepseek-v4.1-flash | 200 | 200, no transcript | `I can't access or transcribe the audio you've attached...` |
| 19 | minimax-m2.7 | 400 | other | `ModelProtocolUnsupported: Model does not support this protocol.` |
| 20 | gpt-6-luna | 400 | other | `ModelProtocolUnsupported` |
| 21 | gpt-5.6-luna | 400 | other | `ModelProtocolUnsupported` |
| 22 | grok-4.7 | 400 | other | `ModelProtocolUnsupported` |
| 23 | grok-4.6 | 400 | other | `ModelProtocolUnsupported` |
| 24 | claude-haiku-5-5 | 400 | other | `ModelProtocolUnsupported` |
| 25 | kimi-k2.7-code | 400 | other | `invalid thinking: only type=enabled is allowed for this model` |
| 26 | omen-alpha | 400 | other | `invalid request body: json: unknown field "thinking"` |
| 27 | cat-jam-free | 400 | other | `Unrecognized request argument supplied: thinking.` |
| 28 | space-bunny | 400 | other | `[invalid_request_error] invalid request` |
| 29 | hy4-preview | 400 | other | `[400001] The request is invalid: the request was rejected by an internal MaaS component.` |
| 30 | minimax-m2.5 | 404 | other | `Upstream request failed: Endpoint is unavailable.` |
| 31 | mimo-v2.5-pro | 404 | other | `Endpoint is unavailable.` |
| 32 | kimi-k2.6 | 404 | other | `Endpoint is unavailable.` |

Not probed, because the sweep stopped at the first verified result as instructed:
`mimo-v2.5`, `muse-spark-1.3-contributor`, `muse-spark-1.2-contributor`,
`longcat-2.5-preview-free`, `longcat-2.0`.

### Verified audio support: mimo-v2.6-pro

Three clips, four requests, no failures. This is the whole audio-capable result set.

| clip | published sentence | returned transcript |
|---|---|---|
| short-au.wav | Remind me to call the dentist tomorrow morning before nine. | identical, verbatim |
| numbers-au.wav | Meet me at three thirty at the cafe on Smith Street, that's Tuesday the twenty second of November. | `Meet me at 3:30 at the cafe on Smith Street. That's Tuesday, the 22nd of November.` |
| names-au.wav | Ask Donna at Southern Cross to send the invoice for the Karratha job, the ABN is on the file. | `Ask Donna at Southern Cross to send the invoice for the Carratha job. The ABN is on the file.` |

`Carratha` for `Karratha` is a proper-noun spelling slip, not a failure to hear the audio.
`short-au.wav` was sent twice, once in the sweep and once in the three-clip pass, and came
back exactly right both times.

## The trap: HTTP 200 does not mean the audio was read

`minimax-m3` answered the 5.45 s `clip.wav` with a fluent 350-word transcript about work
travel deductions. Five seconds of audio cannot hold that much speech, so the text was
fabricated. The same model then refused the next clip with "I don't see any audio attached".
`hy3` likewise returned invented project-planning text, and `kimi-k3`,
`deepseek-v4-pro` and `deepseek-v4.1-flash` returned refusals as ordinary 200 responses.

Any check of this endpoint that treats "200 and non-empty content" as success will report a
working model that is inventing text. Score against a clip whose words you already know.

## Code change

`app/src/main/java/io/github/sspanak/tt9/dictation/OpenCodeTranscriber.java`

- line 19 - `import java.util.Locale;`
- lines 56-67 - `AUDIO_UNSUPPORTED_MARKERS`, the narrow list of upstream wordings that mean the model does not accept audio
- lines 223-225 - in `execute()`, a 400 consults the rejection body before the generic status mapping
- lines 295-336 - `rejectUnsupportedAudioModel(HttpURLConnection)`, `readErrorBody(HttpURLConnection)` and `refusesAudio(String)`
- line 531 - `ApiException.Reason.MODEL_NO_AUDIO`

`MODEL_NO_AUDIO` carries:

> This cloud model cannot read audio. Pick a model that accepts audio, or switch to the on-device engine.

It reaches the user unchanged. `DictationEngine` passes the exception message as the debug
message, `VoiceInputError(Context, int, String)` uses a non-empty detail as its message, and
`VoiceHandler.onVoiceInputError` shows `error.toString()` on the status bar. Code 201
(`SpeechCodes.ENGINE`) is not in `VoiceInputError.isIrrelevantToUser()`, so the message is
not swallowed as an ignorable error.

Anything outside `AUDIO_UNSUPPORTED_MARKERS` keeps the existing generic wording, so a
genuinely malformed request is not reported as a model limitation. The Qwen "incorrect modal
audio" wording is deliberately not matched: it says "may not be supported by the model or was
placed in the wrong position", which is not proof of a capability limit.

### Validation

`gradle assembleLiteDebug` with the exclusions from the task: BUILD SUCCESSFUL. The first
run reported javac as UP-TO-DATE, so the check was repeated after touching the source. The
compiled `app/build/intermediates/javac/liteDebug/.../OpenCodeTranscriber.class` contains
both `MODEL_NO_AUDIO` and the new message string, which confirms the edited file is what
was compiled.

No on-device or emulator test was run. `emulator-5554` belongs to another worker.

The marker list was then exercised against the real rejection bodies captured above, by
calling the compiled package-private `OpenCodeTranscriber.refusesAudio(...)` from a small
throwaway class on the same classpath. All eight cases behaved as intended:

```
PASS  mimo-v2.6-flash -> true          PASS  qwen3.8-max (ambiguous) -> false
PASS  glm-5.3-flash -> true            PASS  gemini privacy -> false
PASS  step-5-preview -> true           PASS  protocol -> false
PASS  empty -> false                   PASS  null -> false
ALL PASS
```

## Verified vs assumed

Verified by direct request:

- the 37 model ids the list endpoint returned on 2026-10-16
- `mimo-v2.6-flash`, the current app default, rejects audio with 400 `unsupported_content_type`
- `mimo-v2.6-pro` transcribes all three ground-truth clips correctly
- the HTTP status and error text recorded for every model in the table
- the endpoint rejects a request that carries no `x-opencode-session` header
- the code change compiles and lands in the build output
- `refusesAudio` matches the three real "cannot read audio" bodies and does not match the
  ambiguous Qwen body, the Gemini privacy rejection, or the protocol rejection

Assumed, not verified:

- whether any other model accepts audio. The sweep stopped at the first verified result and
  five ids never received an audio request. `mimo-v2.6-pro` is proved; "it is the only one"
  is not.
- `gemini-4-argon-contributor` failed on a workspace privacy setting rather than on audio, so
  its audio capability is unknown. Finding out would need "allow paid endpoints that train on
  request data" turned on, which is a workspace change and was out of scope.
- whether the 200-with-fabrication models silently drop the audio part, or answer from the
  prompt alone. Either way they do not transcribe.
- nothing here was exercised through the keyboard, the microphone, or the phone.

## Re-running the sweep

The scripts used are in `/tmp/qk-audio-probe-2026-10-16/` (`probe.py`, `verify-clips.py`);
`/tmp` does not survive a reboot, so the essentials are: read the key from `/tmp/qk.key`,
base64 a 16 kHz mono WAV from `apps/qin-dictation/tests/clips/`, send the payload described
under Method with a fresh `x-opencode-session` UUID, sleep 6 s between candidates, and score
the reply against the sentence in `clips.txt`. An earlier burst probe drew a Cloudflare 403,
so keep the pace at one request per candidate.

