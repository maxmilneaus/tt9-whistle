package io.github.sspanak.tt9.dictation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * What the warm runner prints, and what is done with it. The transcript comes from the shared
 * parser, so a result that cannot be read is a sanitised IOException that carries no transcript,
 * while an empty or text-less result reads as no speech.
 */
final class PreparedWhistleResultTest {
	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			throw new IllegalArgumentException("usage: PreparedWhistleResultTest <PreparedWhistleTranscriber.java>");
		}

		checkResults();
		checkRejections();
		checkSharedParserWording();
		checkNothingIsLogged(args[0]);
		WhistleCheck.finish("PreparedWhistleResultTest");
	}

	private static void checkResults() throws Exception {
		WhistleCheck.equal("hello", PreparedWhistleTranscriber.transcript("loading the model\n{\"text\":\"hello\",\"ttft_ms\":12}\n"), "a success line yields its text");
		WhistleCheck.equal("hello", PreparedWhistleTranscriber.transcript("{\"text\":\"first\"}\n{\"text\":\"hello\"}"), "the last JSON line is the result");
		WhistleCheck.equal("Thanks, Max.\nNext line", PreparedWhistleTranscriber.transcript("{\"text\":\"Thanks, Max.\\nNext line\"}"), "escapes inside the text are decoded");
		WhistleCheck.equal("caf\u00e9", PreparedWhistleTranscriber.transcript("{\"text\":\"caf\\u00e9\"}"), "unicode escapes are decoded");
		WhistleCheck.equal("", PreparedWhistleTranscriber.transcript("{\"text\":\"\"}"), "an empty transcript is empty text, not a failure");
		WhistleCheck.equal("", PreparedWhistleTranscriber.transcript("{\"reason\":\"no speech\",\"text\":null}"), "a result with no text field reads as no speech");
		WhistleCheck.equal("spaced", PreparedWhistleTranscriber.transcript("  {\"text\":\"spaced\"}  "), "the line is trimmed before it is parsed");
		WhistleCheck.equal("", PreparedWhistleTranscriber.transcript("{\"text\":null}"), "an explicit null text reads as no speech");
	}

	private static void checkRejections() {
		WhistleCheck.throwsMentioning(IOException.class, "no usable result", () -> PreparedWhistleTranscriber.transcript(""), "output with nothing in it is rejected");
		WhistleCheck.throwsMentioning(IOException.class, "no usable result", () -> PreparedWhistleTranscriber.transcript("needle: cannot read - (RIFF WAV)"), "output with no JSON line at all is rejected");
		WhistleCheck.throwsMentioning(IOException.class, "no usable result", () -> PreparedWhistleTranscriber.transcript("{\"text\":\"trunc"), "a truncated JSON line is rejected");
		WhistleCheck.throwsMentioning(IOException.class, "no usable result", () -> PreparedWhistleTranscriber.transcript("{\"text\":\"a\",}"), "a JSON line with a trailing comma is rejected");
		WhistleCheck.throwsMentioning(IOException.class, "no usable result", () -> PreparedWhistleTranscriber.transcript("{\"text\":\"a\"}\n{\"text\""), "a malformed last line is rejected even when an earlier line was good");
	}

	/**
	 * transcript() treats one wording from the shared parser as no speech, so the coupling is pinned
	 * here: if LocalWhistleTranscriber ever rewords it, this suite fails instead of the phone path.
	 */
	private static void checkSharedParserWording() {
		Throwable missingText = WhistleCheck.thrown(() -> LocalWhistleTranscriber.parseLastLine("{\"reason\":\"no speech\"}"));
		WhistleCheck.yes(
			missingText instanceof IOException && missingText.getMessage() != null && missingText.getMessage().contains("no text"),
			"the shared parser still reports a missing text field as \"no text\" [threw " + missingText + "]"
		);
		Throwable noJson = WhistleCheck.thrown(() -> LocalWhistleTranscriber.parseLastLine("RIFF"));
		WhistleCheck.yes(
			noJson instanceof IOException && noJson.getMessage() != null && !noJson.getMessage().contains("no text"),
			"output with no JSON is not mistaken for no speech [threw " + noJson + "]"
		);
	}

	/** The class must never write the audio or the transcript anywhere but back to its caller. */
	private static void checkNothingIsLogged(String sourcePath) throws IOException {
		String source = new String(Files.readAllBytes(Paths.get(sourcePath)), StandardCharsets.UTF_8);
		for (String tell : new String[] { "android.util.Log", "System.out", "System.err", "printStackTrace" }) {
			WhistleCheck.yes(!source.contains(tell), "the transcriber never writes to " + tell);
		}
	}
}
