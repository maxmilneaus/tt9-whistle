package io.github.sspanak.tt9.dictation;

/**
 * The caller abandoned the recording. This is not an engine fault, so the warm path must not treat
 * it as one and re-run the same audio on the per-call engine.
 */
final class TranscriptionCancelledException extends Exception {
	TranscriptionCancelledException() {
		super("Transcription was cancelled.");
	}
}
