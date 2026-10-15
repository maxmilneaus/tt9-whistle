# OpenCode adapter harness

Deterministic, off-device checks for the optional cloud dictation engine:

    app/src/main/java/io/github/sspanak/tt9/dictation/OpenCodeSettings.java
    app/src/main/java/io/github/sspanak/tt9/dictation/OpenCodeTranscriber.java

Run it with a plain JDK. Nothing is written inside the repository, no device or emulator is used,
and the credential is always a fake literal.

    bash tests/opencode/run-tests.sh

It compiles the two adapter files against android.jar, then runs three suites on the JVM:

| Suite | What it proves |
|---|---|
| OpenCodeSettingsTest (41 checks) | Defaults match the recorded working configuration; the API key is trimmed before it is encrypted, is never stored in the clear, and is never written when the Keystore fails; a blank key keeps the existing credential; endpoints must be credential-free HTTPS; fingerprints are stable, keyed by the stored secret and never reveal it |
| OpenCodeRequestContractTest (33 checks) | The audio-in-chat request is unchanged (model, non-streaming, temperature 0, thinking disabled, system instruction, prompt plus a WAV data-URI part) and the body never carries the key; only a non-empty final message is accepted, and truncated, reasoning-only, null, structured or non-JSON responses are refused with sanitised messages |
| OpenCodeTransportTest (47 checks) | A real loopback HTTPS round trip: bearer credential, session header, timeouts, bounded responses, status-to-reason mapping, a 3xx refusal that never contacts the redirect target, cancellation that releases the caller, and the lifetime latch: a cancel that lands before transcribe() is entered still refuses the call and opens no connection |

Run mutation-control.sh, in the same directory, for the falsifiability control. It copies the
adapter and the harness into a scratch tree, injects five known defects and requires the harness to
report the checks that cover them. Recorded result: all five defects caught, including a
transcribe() that clears the cancellation latch and loses an early cancel.

## Deliberate test doubles

tests/opencode/android/util/Base64.java is a JVM delegate for the platform helper. The adapter uses
android.util.Base64 rather than java.util.Base64 because the app supports API 24 and the JDK class
needs API 26; android.jar only stubs the platform helper, so the harness supplies a working one.
Fakes.java replaces SharedPreferences and the Keystore with in-memory equivalents.

## What this harness does not cover

- The Android Keystore and SharedPreferences themselves. The JVM proves the adapter uses them
  correctly; the encrypt and decrypt round trip still needs a real device or emulator.
- The live OpenCode Go service. No call to a real provider is made here, by design.
- Whether the provider's HTTP stack follows a redirect. java.net refuses to follow a POST redirect
  and Android's implementation does follow it, so the credential-leak protection stays an on-device
  check rather than something reproduced here.
- The recorder, the microphone, TT9 text insertion and the settings screen, which are owned
  elsewhere in the fork. DictationEngine calls new OpenCodeTranscriber(appContext) and reads
  getMessage() from the thrown exception for its debug text.

## Adapter API

    new OpenCodeTranscriber(Context)              implements Transcriber
    transcriber.transcribe(byte[] wav)            blocking, returns the cleaned transcript
    transcriber.cancel()                          abandons an in-flight request, reusable afterwards
    OpenCodeTranscriber.ApiException extends IOException, with a public Reason enum

    new OpenCodeSettings(Context)
    settings.load()                               Snapshot: public endpoint, model, prompt, apiKey
    settings.save(endpoint, model, prompt, key)   a null or blank key keeps the saved credential
    settings.hasKey() / clearKey() / fingerprint()
    OpenCodeSettings.DEFAULT_ENDPOINT / DEFAULT_MODEL / DEFAULT_PROMPT
