package io.github.sspanak.tt9.dictation;

/** A transcription failure that carries a SpeechRecognizer-style error code for the UI. */
public final class DictationException extends Exception {
	public final int code;

	public DictationException(String message, int code) {
		super(message);
		this.code = code;
	}
}
