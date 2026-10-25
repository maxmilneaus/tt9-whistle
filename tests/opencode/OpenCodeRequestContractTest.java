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
		byte[] wav = Fixtures.wav(Fixtures.SAMPLES);
		OpenCodeSettings.Snapshot settings =
			new OpenCodeSettings.Snapshot(OpenCodeSettings.DEFAULT_ENDPOINT, "test-model", "Clean this up.", FAKE_KEY);

		request(settings, wav);
		parsing();
		audioGuard(wav);

		WhistleCheck.finish("OpenCodeRequestContract");
	}


	private static void request(OpenCodeSettings.Snapshot settings, byte[] wav) {
		String payload;
		JSONObject body;
		try {
			payload = OpenCodeTranscriber.requestBody(settings, wav);
			body = new JSONObject(payload);
		} catch (Exception error) {
			WhistleCheck.fail("the request could not be built: " + error);
			return;
		}

		try {
			WhistleCheck.equal("test-model", body.getString("model"), "the configured model is sent");
			WhistleCheck.yes(!body.getBoolean("stream"), "the response is requested as one final message");
			WhistleCheck.equal(0.0, body.getDouble("temperature"), "temperature is pinned to 0");
			WhistleCheck.equal("disabled", body.getJSONObject("thinking").getString("type"), "thinking stays disabled");

			JSONArray messages = body.getJSONArray("messages");
			WhistleCheck.equal(2, messages.length(), "one system message and one user message");
			WhistleCheck.equal(
				"system",
				messages.getJSONObject(0).getString("role"),
				"the first message is the system instruction"
			);
			WhistleCheck.equal(
				OpenCodeTranscriber.SYSTEM_INSTRUCTION,
				messages.getJSONObject(0).getString("content"),
				"the system instruction is unchanged"
			);

			JSONObject userMessage = messages.getJSONObject(1);
			WhistleCheck.equal("user", userMessage.getString("role"), "the second message is the user turn");
			JSONArray content = userMessage.getJSONArray("content");
			WhistleCheck.equal(2, content.length(), "the user turn carries the prompt and the audio");
			WhistleCheck.equal("text", content.getJSONObject(0).getString("type"), "the prompt is sent as text");
			WhistleCheck.equal("Clean this up.", content.getJSONObject(0).getString("text"), "the cleanup prompt is sent");

			JSONObject audio = content.getJSONObject(1).getJSONObject("input_audio");
			WhistleCheck.equal("wav", audio.getString("format"), "the audio is declared as WAV");
			WhistleCheck.equal(
				"data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav),
				audio.getString("data"),
				"the WAV bytes are inlined as a data URI"
			);
		} catch (Exception error) {
			WhistleCheck.fail("unexpected failure while inspecting the request: " + error);
		}

		WhistleCheck.yes(!payload.contains(FAKE_KEY), "the request body never carries the API key");
		WhistleCheck.yes(!payload.contains("Authorization"), "the request body never carries an Authorization header");
	}


	private static void parsing() {
		WhistleCheck.equal(
			"Could you call me tomorrow?",
			parsed(Fixtures.reply("Could you call me tomorrow?", "stop")),
			"the final message content is returned"
		);
		WhistleCheck.equal("  spaced  ", parsed(Fixtures.reply("  spaced  ", "stop")), "the transcript is not rewritten");
		WhistleCheck.equal(
			"First line\nSecond line",
			parsed(Fixtures.reply("First line\nSecond line", "stop")),
			"newlines in the transcript survive"
		);
		WhistleCheck.equal("Kept", parsed(Fixtures.reply("Kept", null)), "a null finish_reason is accepted");

		failure(Fixtures.reply("Truncated", "length"), ApiException.Reason.NO_TEXT, "a length-truncated response is rejected");
		failure(Fixtures.reply("Reasoned", "reasoning"), ApiException.Reason.NO_TEXT, "a reasoning-only response is rejected");
		failure(Fixtures.reply("", "stop"), ApiException.Reason.NO_TEXT, "an empty transcript is rejected");
		failure(Fixtures.reply("   ", "stop"), ApiException.Reason.NO_TEXT, "a whitespace-only transcript is rejected");
		failure(Fixtures.replyNullContent(), ApiException.Reason.NO_TEXT, "a null transcript is rejected");
		failure(Fixtures.reply(new JSONArray().put(1).put(2), "stop"), ApiException.Reason.SERVER, "a structured transcript is rejected");
		failure(new JSONObject().put("choices", new JSONArray()).toString(), ApiException.Reason.SERVER, "a response without a choice is rejected");
		failure("not json at all", ApiException.Reason.SERVER, "a non-JSON response is rejected");

		Throwable malformed = WhistleCheck.thrown(() -> OpenCodeTranscriber.parseFinalContent("{" + LEAK + " not json"));
		WhistleCheck.yes(malformed instanceof ApiException, "a malformed response fails with the adapter's own exception");
		WhistleCheck.yes(
			malformed != null && !String.valueOf(malformed.getMessage()).contains(LEAK),
			"failure messages never echo the response body"
		);
	}


	private static void audioGuard(byte[] wav) {
		WhistleCheck.yes(OpenCodeTranscriber.isWav(wav), "a canonical RIFF/WAVE recording is accepted");
		WhistleCheck.yes(!OpenCodeTranscriber.isWav(new byte[0]), "an empty recording is not a WAV file");
		WhistleCheck.yes(!OpenCodeTranscriber.isWav("RIFF".getBytes()), "a truncated header is not a WAV file");
		WhistleCheck.yes(!OpenCodeTranscriber.isWav("0123456789ab".getBytes()), "arbitrary bytes are not a WAV file");
	}


	private static void failure(String response, ApiException.Reason expected, String description) {
		Throwable thrown = WhistleCheck.thrown(() -> OpenCodeTranscriber.parseFinalContent(response));
		if (!(thrown instanceof ApiException)) {
			WhistleCheck.fail(description + " [threw " + thrown + ", expected " + expected + "]");
			return;
		}
		WhistleCheck.equal(expected, ((ApiException) thrown).reason, description);
	}


	private static String parsed(String response) {
		try {
			return OpenCodeTranscriber.parseFinalContent(response);
		} catch (Exception error) {
			WhistleCheck.fail("parsing failed unexpectedly: " + error.getMessage());
			return null;
		}
	}
}
