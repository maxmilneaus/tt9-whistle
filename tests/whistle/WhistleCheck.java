package io.github.sspanak.tt9.dictation;

/**
 * Minimal assertions for the JVM Whistle harness. It is free of JUnit, Android and every other
 * dependency, so the suites run with a plain JDK against android.jar and never need a device.
 */
final class WhistleCheck {
	interface Action {
		void run() throws Exception;
	}

	private static int checks;
	private static int failures;

	private WhistleCheck() {
	}

	static void equal(Object expected, Object actual, String description) {
		yes(
			expected == null ? actual == null : expected.equals(actual),
			description + " [expected=" + expected + ", actual=" + actual + "]"
		);
	}

	static void yes(boolean condition, String description) {
		checks++;
		if (condition) {
			System.out.println("  PASS  " + description);
		} else {
			failures++;
			System.out.println("  FAIL  " + description);
		}
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
		Throwable thrown = thrown(action);
		if (thrown == null) {
			yes(false, description + " [nothing was thrown, expected " + type.getSimpleName() + "]");
			return;
		}
		String message = thrown.getMessage() == null ? "" : thrown.getMessage();
		yes(
			type.isInstance(thrown) && message.contains(text),
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
