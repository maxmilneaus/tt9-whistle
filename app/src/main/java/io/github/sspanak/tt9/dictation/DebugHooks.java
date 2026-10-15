package io.github.sspanak.tt9.dictation;

/**
 * Debug test seam. This is the only thing in the main source set that exists to make the dictation
 * path testable, and it does nothing by itself: the field stays {@code null} unless a debug build
 * assigns the keyboard's voice key to it, and nothing ever writes to it in a release build.
 *
 * <p>In {@code src/debug} the {@code DebugVoiceKeyReceiver} calls whatever is stored here, so an
 * emulator test can start and stop dictation without simulating the physical keypad.</p>
 */
public final class DebugHooks {
	/** The voice key of the running keyboard, or null when nothing has set it. Debug builds only. */
	public static volatile Runnable voiceKey = null;
}
