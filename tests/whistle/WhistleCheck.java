package io.github.sspanak.tt9.dictation;

/**
 * Minimal assertions for the JVM harnesses. One shared copy is used by the Whistle and the
 * OpenCode suites: it is free of JUnit, Android and every other dependency, so both run with a
 * plain JDK and never need a device.
 *
 * The counters are static because each suite is started by its own runner in its own JVM, so only
 * one suite ever counts in a process.
 */
final class WhistleCheck {
	interface Action {
		void run() throws Exception;
	}

	private static int checks;
	private static int failures;

	private WhistleCheck() {
	}


	/** The one place a check is counted: a PASS, or a FAIL and one more failure. */
	static void yes(boolean condition, String description) {
		checks++;
		if (condition) {
			System.out.println("  PASS  " + description);
		} else {
			failures++;
			System.out.println("  FAIL  " + description);
		}
	}


	/** The OpenCode spelling of yes(); both suites share the same counter. */
	static void check(boolean condition, String description) {
		yes(condition, description);
	}


	static void equal(Object expected, Object actual, String description) {
		yes(
			expected == null ? actual == null : expected.equals(actual),
			description + " [expected=" + expected + ", actual=" + actual + "]"
		);
	}


	/** The OpenCode spelling of equal(). */
	static void checkEquals(Object expected, Object actual, String description) {
		equal(expected, actual, description);
	}


	static void fail(String description) {
		yes(false, description);
	}


	/** Runs the action and returns what it threw, or null when it returned normally. */
	static Throwable thrown(Action action) {
		try {
			action.run();
			return null;
		} catch (Throwable error) {
			return error;
		}
	}


	/** Requires an exception of the given type whose message contains the given text. */
	static void throwsMentioning(Class<? extends Throwable> type, String text, Action action, String description) {
		requireThrown(type, text, action, description);
	}


	/** Requires an exception of the given type, whatever its message. */
	static void checkThrows(Class<? extends Throwable> expected, Action action, String description) {
		requireThrown(expected, null, action, description);
	}


	private static void requireThrown(Class<? extends Throwable> type, String text, Action action, String description) {
		Throwable thrown = thrown(action);
		if (thrown == null) {
			yes(false, description + " [nothing was thrown, expected " + type.getSimpleName() + "]");
			return;
		}
		String message = thrown.getMessage() == null ? "" : thrown.getMessage();
		yes(
			type.isInstance(thrown) && (text == null || message.contains(text)),
			description + " [threw " + thrown.getClass().getSimpleName() + ": " + message + "]"
		);
	}


	static void finish(String suite) {
		System.out.println(suite + ": " + (checks - failures) + "/" + checks + " checks passed");
		if (failures > 0) {
			System.exit(1);
		}
	}
}
