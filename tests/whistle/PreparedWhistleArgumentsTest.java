package io.github.sspanak.tt9.dictation;

import java.util.Arrays;
import java.util.List;

/**
 * The flags the warm runner is started with, and the depth, thread and language ranges it really
 * accepts. The retained whistle.cact holds eight ladder rungs and its runner takes 2..8 layers,
 * 0 (engine default) or 1..4 threads and seven languages; nothing outside those ranges may reach it.
 */
final class PreparedWhistleArgumentsTest {
	private static final String BINARY = "/data/app/io.github.sspanak.tt9/lib/arm/libneedle.so";
	private static final String MODEL = "/data/user/0/io.github.sspanak.tt9/files/whistle/whistle.cact";
	private static final String FIFO = "/data/user/0/io.github.sspanak.tt9/cache/whistle-1a2b3c.fifo";

	public static void main(String[] args) {
		checkArguments();
		checkDepthRange();
		checkThreadRange();
		checkLanguageRange();
		checkSettingsAgreement();
		WhistleCheck.finish("PreparedWhistleArgumentsTest");
	}

	private static void checkArguments() {
		WhistleCheck.equal(
			Arrays.asList(BINARY, "--model", MODEL, "--audio", FIFO, "--audio-language", "de", "--audio-depth", "4", "--threads", "4"),
			PreparedWhistleTranscriber.arguments(BINARY, MODEL, FIFO, 4, 4, "de"),
			"the tuned configuration is sent in the runner's own flag order"
		);
		WhistleCheck.equal(
			Arrays.asList(BINARY, "--model", MODEL, "--audio", FIFO, "--audio-language", "en"),
			PreparedWhistleTranscriber.arguments(BINARY, MODEL, FIFO, PreparedWhistleTranscriber.FULL_DEPTH, PreparedWhistleTranscriber.AUTO_THREADS, "en"),
			"the whole ladder and the engine's own thread count are sent as no flag at all"
		);
		WhistleCheck.equal(
			Arrays.asList(BINARY, "--model", MODEL, "--audio", FIFO, "--audio-language", "pl", "--audio-depth", "2", "--threads", "1"),
			PreparedWhistleTranscriber.arguments(BINARY, MODEL, FIFO, 2, 1, "pl"),
			"the smallest ladder and one thread are sent explicitly"
		);
	}

	private static void checkDepthRange() {
		WhistleCheck.equal(PreparedWhistleTranscriber.FULL_DEPTH, PreparedWhistleTranscriber.clampDepth(0), "depth 0 stays the model default");
		WhistleCheck.equal(2, PreparedWhistleTranscriber.clampDepth(2), "depth 2 is the smallest ladder and stays");
		WhistleCheck.equal(8, PreparedWhistleTranscriber.clampDepth(8), "depth 8 is the whole ladder and stays");
		WhistleCheck.equal(PreparedWhistleTranscriber.FULL_DEPTH, PreparedWhistleTranscriber.clampDepth(1), "a depth below the minimum reads as the whole ladder");
		WhistleCheck.equal(PreparedWhistleTranscriber.FULL_DEPTH, PreparedWhistleTranscriber.clampDepth(-7), "a negative depth reads as the whole ladder");
		WhistleCheck.equal(PreparedWhistleTranscriber.MAX_DEPTH, PreparedWhistleTranscriber.clampDepth(9), "a depth past the last rung is capped");
		WhistleCheck.equal(PreparedWhistleTranscriber.MAX_DEPTH, PreparedWhistleTranscriber.clampDepth(999), "an absurd depth is capped");

		for (int depth : new int[] { -7, 0, 1, 2, 3, 8, 9, 999 }) {
			Integer sent = flag(PreparedWhistleTranscriber.arguments(BINARY, MODEL, FIFO, depth, 4, "en"), "--audio-depth");
			WhistleCheck.yes(
				sent == null || (sent >= PreparedWhistleTranscriber.MIN_DEPTH && sent <= PreparedWhistleTranscriber.MAX_DEPTH),
				"depth " + depth + " never reaches the runner outside 2..8 [sent=" + sent + "]"
			);
		}
	}

	private static void checkThreadRange() {
		WhistleCheck.equal(PreparedWhistleTranscriber.AUTO_THREADS, PreparedWhistleTranscriber.clampThreads(-3), "a negative thread count means the engine default");
		WhistleCheck.equal(PreparedWhistleTranscriber.AUTO_THREADS, PreparedWhistleTranscriber.clampThreads(0), "thread count 0 means the engine default");
		WhistleCheck.equal(4, PreparedWhistleTranscriber.clampThreads(4), "the documented ceiling of four threads stays");
		WhistleCheck.equal(PreparedWhistleTranscriber.MAX_THREADS, PreparedWhistleTranscriber.clampThreads(5), "a thread count past the ceiling is capped");
		WhistleCheck.equal(PreparedWhistleTranscriber.MAX_THREADS, PreparedWhistleTranscriber.clampThreads(99), "99 threads, which collapses throughput, is capped");

		for (int threads : new int[] { -3, 0, 1, 2, 4, 5, 99 }) {
			Integer sent = flag(PreparedWhistleTranscriber.arguments(BINARY, MODEL, FIFO, 4, threads, "en"), "--threads");
			WhistleCheck.yes(
				sent == null || (sent >= 1 && sent <= PreparedWhistleTranscriber.MAX_THREADS),
				"threads " + threads + " never reaches the runner outside 1..4 [sent=" + sent + "]"
			);
		}
	}

	private static void checkLanguageRange() {
		for (String language : DictationSettings.SUPPORTED_LANGUAGES) {
			WhistleCheck.equal(language, PreparedWhistleTranscriber.clampLanguage(language), "the supported language " + language + " is sent as it is");
		}
		WhistleCheck.equal("en", PreparedWhistleTranscriber.clampLanguage(null), "a missing language falls back to English");
		WhistleCheck.equal("en", PreparedWhistleTranscriber.clampLanguage(""), "an empty language falls back to English");
		WhistleCheck.equal("en", PreparedWhistleTranscriber.clampLanguage("zh"), "a language the model does not know falls back to English");
		WhistleCheck.equal("en", PreparedWhistleTranscriber.clampLanguage("DE"), "the runner's language flags are lower case");
		WhistleCheck.equal(
			"en",
			flag(PreparedWhistleTranscriber.arguments(BINARY, MODEL, FIFO, 4, 4, "zh"), "--audio-language", true),
			"an unsupported language never reaches the runner"
		);
	}

	/** Whatever the settings can produce must already be inside the warm engine's accepted range. */
	private static void checkSettingsAgreement() {
		for (int depth : DictationSettings.DEPTH_OPTIONS) {
			WhistleCheck.equal(depth, PreparedWhistleTranscriber.clampDepth(depth), "the settings depth " + depth + " survives the warm clamp");
		}
		for (int threads : DictationSettings.THREAD_OPTIONS) {
			WhistleCheck.equal(threads, PreparedWhistleTranscriber.clampThreads(threads), "the settings thread count " + threads + " survives the warm clamp");
		}
		for (int raw : new int[] { -10, 0, 1, 2, 3, 4, 5, 6, 8, 9, 99 }) {
			int storedDepth = DictationSettings.clampDepth(raw);
			int storedThreads = DictationSettings.clampThreads(raw);
			WhistleCheck.equal(storedDepth, PreparedWhistleTranscriber.clampDepth(storedDepth), "the stored depth from " + raw + " survives the warm clamp");
			WhistleCheck.equal(storedThreads, PreparedWhistleTranscriber.clampThreads(storedThreads), "the stored thread count from " + raw + " survives the warm clamp");
		}
	}

	/** The value of a flag, or null when the flag is absent. */
	private static Integer flag(List<String> arguments, String name) {
		int index = arguments.indexOf(name);
		if (index < 0) {
			return null;
		}
		return Integer.valueOf(arguments.get(index + 1));
	}

	/** The language value is a word, not a number; this overload keeps the language checks readable. */
	private static String flag(List<String> arguments, String name, boolean textual) {
		int index = arguments.indexOf(name);
		return index < 0 ? null : arguments.get(index + 1);
	}
}
