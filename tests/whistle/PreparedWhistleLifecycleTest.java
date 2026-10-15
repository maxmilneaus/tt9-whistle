package io.github.sspanak.tt9.dictation;

import java.io.IOException;

/**
 * The cancellation latch and the guards in front of a job, proved on an instance built with the
 * explicit-argument constructor so no Android context is needed. There is no prepared runner in
 * this JVM, which is exactly the state in which the guards must hold.
 */
final class PreparedWhistleLifecycleTest {
	private static final String BINARY = "/data/app/io.github.sspanak.tt9/lib/arm/libneedle.so";
	private static final String MODEL = "/data/user/0/io.github.sspanak.tt9/files/whistle/whistle.cact";
	private static final byte[] WAV = { 'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E' };

	public static void main(String[] args) {
		checkEmptyRecording();
		checkMissingRunner();
		checkReadinessProbe();
		checkCancelLatch();
		WhistleCheck.finish("PreparedWhistleLifecycleTest");
	}

	private static void checkEmptyRecording() {
		PreparedWhistleTranscriber transcriber = new PreparedWhistleTranscriber(BINARY, MODEL, 4, 4, "en");
		WhistleCheck.throwsMentioning(IOException.class, "empty", () -> transcriber.transcribe(new byte[0]), "an empty recording is refused");
		WhistleCheck.throwsMentioning(IOException.class, "empty", () -> transcriber.transcribe(null), "a missing recording is refused");
	}

	private static void checkMissingRunner() {
		PreparedWhistleTranscriber transcriber = new PreparedWhistleTranscriber(BINARY, MODEL, 4, 4, "en");
		WhistleCheck.throwsMentioning(IOException.class, "not ready", () -> transcriber.transcribe(WAV), "without a prepared runner the caller is told to fall back");
	}

	/** The boolean the caller now uses instead of reading an exception message. */
	private static void checkReadinessProbe() {
		WhistleCheck.throwsMentioning(
			IllegalArgumentException.class,
			"context",
			() -> PreparedWhistleTranscriber.isReady(null, BINARY, MODEL, 4, 4),
			"the readiness probe rejects a missing context"
		);
	}

	/**
	 * A cancel() that lands before transcribe() must still be honoured, and it is one-way: a later
	 * transcribe() on the same instance must not silently clear it and start a second job.
	 */
	private static void checkCancelLatch() {
		PreparedWhistleTranscriber transcriber = new PreparedWhistleTranscriber(BINARY, MODEL, 4, 4, "en");
		transcriber.cancel();
		WhistleCheck.throwsMentioning(IOException.class, "cancelled", () -> transcriber.transcribe(WAV), "a cancel before transcribe() is honoured");
		WhistleCheck.throwsMentioning(IOException.class, "cancelled", () -> transcriber.transcribe(WAV), "the latch survives a later transcribe() on the same instance");
		transcriber.cancel();
		WhistleCheck.throwsMentioning(IOException.class, "cancelled", () -> transcriber.transcribe(WAV), "cancelling twice is safe");

		PreparedWhistleTranscriber other = new PreparedWhistleTranscriber(BINARY, MODEL, 4, 4, "en");
		WhistleCheck.throwsMentioning(IOException.class, "not ready", () -> other.transcribe(WAV), "a cancelled instance does not cancel the class");
	}
}
