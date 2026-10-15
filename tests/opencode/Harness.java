package io.github.sspanak.tt9.dictation;

/**
 * Minimal assertion helper for the JVM OpenCode harness.
 *
 * It is free of Android, JUnit and any other dependency: the harness is compiled and run with a
 * plain JDK against android.jar, so it runs anywhere without a device.
 */
final class Harness {
	interface Action {
		void run() throws Exception;
	}

	private final String suite;
	private final boolean quiet;
	private int checks;
	private int failures;


	Harness(String suite) {
		this(suite, false);
	}


	Harness(String suite, boolean quiet) {
		this.suite = suite;
		this.quiet = quiet;
	}


	void check(boolean condition, String description) {
		checks++;
		if (condition) {
			report("  PASS  " + description);
		} else {
			failures++;
			report("  FAIL  " + description);
		}
	}


	void checkEquals(Object expected, Object actual, String description) {
		boolean same = expected == null ? actual == null : expected.equals(actual);
		check(same, description + " [expected=" + expected + ", actual=" + actual + "]");
	}


	void checkThrows(Class<? extends Throwable> expected, Action action, String description) {
		Throwable thrown = thrown(action);
		if (thrown == null) {
			check(false, description + " [nothing was thrown, expected " + expected.getSimpleName() + "]");
		} else {
			check(expected.isInstance(thrown), description + " [threw " + describe(thrown) + "]");
		}
	}


	Throwable thrown(Action action) {
		try {
			action.run();
			return null;
		} catch (Throwable error) {
			return error;
		}
	}


	void fail(String description) {
		check(false, description);
	}


	int failureCount() {
		return failures;
	}


	int finish() {
		if (!quiet) {
			System.out.println(suite + ": " + (checks - failures) + "/" + checks + " checks passed");
		}
		return failures == 0 ? 0 : 1;
	}


	private void report(String line) {
		if (!quiet) {
			System.out.println(line);
		}
	}


	private static String describe(Throwable error) {
		String message = error.getMessage();
		return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
	}
}
