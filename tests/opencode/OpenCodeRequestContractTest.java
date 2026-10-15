package io.github.sspanak.tt9.dictation;

import java.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.sspanak.tt9.dictation.OpenCodeTranscriber.ApiException;

/** Request and response contract of the proven chat-completions transcription route. */
final class OpenCodeRequestContractTest {
	private static final String FAKE_KEY = "FAKE-JVM-HARNESS-KEY";
	private static final String LEAK = "leaked-response-body";


	private OpenCodeRequestContractTest() {
	}


	public static void main(String[] args) {
		Harness harness = new Harness("OpenCodeRequestContract");
		byte[] wav = Fixtures.wav(Fixtures.SAMPLES);
		OpenCodeSettings.Snapshot settings =
			new OpenCodeSettings.Snapshot(OpenCodeSettings.DEFAULT_ENDPOINT, "test-model", "Clean this up.", FAKE_KEY);

		request(harness, settings, wav);
		parsing(harness);
		audioGuard(harness, wav);

		System.exit(harness.finish());
	}


	private static void request(Harness harness, OpenCodeSettings.Snapshot settings, byte[] wav) {
		String payload;
		JSONObject body;
		try {
			payload = OpenCodeTranscriber.requestBody(settings, wav);
			body = new JSONObject(payload);
		} catch (Exception error) {
			harness.fail("the request could not be built: " + error);
			return;
		}

		try {
			harness.checkEquals("test-model", body.getString("model"), "the configured model is sent");
			harness.check(!body.getBoolean("stream"), "the response is requested as one final message");
			harness.checkEquals(0.0, body.getDouble("temperature"), "temperature is pinned to 0");
			harness.checkEquals("disabled", body.getJSONObject("thinking").getString("type"), "thinking stays disabled");

			JSONArray messages = body.getJSONArray("messages");
			harness.checkEquals(2, messages.length(), "one system message and one user message");
			harness.checkEquals(
				"system",
				messages.getJSONObject(0).getString("role"),
				"the first message is the system instruction"
			);
			harness.checkEquals(
				OpenCodeTranscriber.SYSTEM_INSTRUCTION,
				messages.getJSONObject(0).getString("content"),
				"the system instruction is unchanged"
			);

			JSONObject userMessage = messages.getJSONObject(1);
			harness.checkEquals("user", userMessage.getString("role"), "the second message is the user turn");
			JSONArray content = userMessage.getJSONArray("content");
			harness.checkEquals(2, content.length(), "the user turn carries the prompt and the audio");
			harness.checkEquals("text", content.getJSONObject(0).getString("type"), "the prompt is sent as text");
			harness.checkEquals("Clean this up.", content.getJSONObject(0).getString("text"), "the cleanup prompt is sent");

			JSONObject audio = content.getJSONObject(1).getJSONObject("input_audio");
			harness.checkEquals("wav", audio.getString("format"), "the audio is declared as WAV");
			harness.checkEquals(
				"data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav),
				audio.getString("data"),
				"the WAV bytes are inlined as a data URI"
			);
		} catch (Exception error) {
			harness.fail("unexpected failure while inspecting the request: " + error);
		}

		harness.check(!payload.contains(FAKE_KEY), "the request body never carries the API key");
		harness.check(!payload.contains("Authorization"), "the request body never carries an Authorization header");
	}


	private static void parsing(Harness harness) {
		harness.checkEquals(
			"Could you call me tomorrow?",
			parsed(harness, reply("Could you call me tomorrow?", "stop")),
			"the final message content is returned"
		);
		harness.checkEquals("  spaced  ", parsed(harness, reply("  spaced  ", "stop")), "the transcript is not rewritten");
		harness.checkEquals(
			"First line\nSecond line",
			parsed(harness, reply("First line\nSecond line", "stop")),
			"newlines in the transcript survive"
		);
		harness.checkEquals("Kept", parsed(harness, reply("Kept", null)), "a null finish_reason is accepted");

		failure(harness, reply("Truncated", "length"), ApiException.Reason.NO_TEXT, "a length-truncated response is rejected");
		failure(harness, reply("Reasoned", "reasoning"), ApiException.Reason.NO_TEXT, "a reasoning-only response is rejected");
		failure(harness, reply("", "stop"), ApiException.Reason.NO_TEXT, "an empty transcript is rejected");
		failure(harness, reply("   ", "stop"), ApiException.Reason.NO_TEXT, "a whitespace-only transcript is rejected");
		failure(harness, replyNullContent(), ApiException.Reason.NO_TEXT, "a null transcript is rejected");
		failure(harness, reply(new JSONArray().put(1).put(2), "stop"), ApiException.Reason.SERVER, "a structured transcript is rejected");
		failure(harness, new JSONObject().put("choices", new JSONArray()).toString(), ApiException.Reason.SERVER, "a response without a choice is rejected");
		failure(harness, "not json at all", ApiException.Reason.SERVER, "a non-JSON response is rejected");

		Throwable malformed = harness.thrown(() -> OpenCodeTranscriber.parseFinalContent("{" + LEAK + " not json"));
		harness.check(malformed instanceof ApiException, "a malformed response fails with the adapter's own exception");
		harness.check(
			malformed != null && !String.valueOf(malformed.getMessage()).contains(LEAK),
			"failure messages never echo the response body"
		);
	}


	private static void audioGuard(Harness harness, byte[] wav) {
		harness.check(OpenCodeTranscriber.isWav(wav), "a canonical RIFF/WAVE recording is accepted");
		harness.check(!OpenCodeTranscriber.isWav(new byte[0]), "an empty recording is not a WAV file");
		harness.check(!OpenCodeTranscriber.isWav("RIFF".getBytes()), "a truncated header is not a WAV file");
		harness.check(!OpenCodeTranscriber.isWav("0123456789ab".getBytes()), "arbitrary bytes are not a WAV file");
	}


	private static void failure(Harness harness, String response, ApiException.Reason expected, String description) {
		Throwable thrown = harness.thrown(() -> OpenCodeTranscriber.parseFinalContent(response));
		if (!(thrown instanceof ApiException)) {
			harness.fail(description + " [threw " + thrown + ", expected " + expected + "]");
			return;
		}
		harness.checkEquals(expected, ((ApiException) thrown).reason, description);
	}


	private static String parsed(Harness harness, String response) {
		try {
			return OpenCodeTranscriber.parseFinalContent(response);
		} catch (Exception error) {
			harness.fail("parsing failed unexpectedly: " + error.getMessage());
			return null;
		}
	}


	private static String reply(String content, String finishReason) {
		return reply((Object) content, finishReason);
	}


	private static String reply(Object content, String finishReason) {
		try {
			return new JSONObject()
				.put("choices", new JSONArray().put(new JSONObject()
					.put("finish_reason", finishReason == null ? JSONObject.NULL : finishReason)
					.put("message", new JSONObject()
						.put("role", "assistant")
						.put("content", content))))
				.toString();
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}


	private static String replyNullContent() {
		try {
			return new JSONObject()
				.put("choices", new JSONArray().put(new JSONObject()
					.put("finish_reason", "stop")
					.put("message", new JSONObject().put("role", "assistant").put("content", JSONObject.NULL))))
				.toString();
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}
}
