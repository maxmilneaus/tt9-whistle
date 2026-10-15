package io.github.sspanak.tt9.dictation;

/** Turns one recorded WAV utterance into transcript text. */
public interface Transcriber {
    /** Blocking. Throws on failure; returns the transcript. */
    String transcribe(byte[] wav) throws Exception;

    /** Abandons an in-flight transcription. Safe to call more than once. */
    void cancel();
}
