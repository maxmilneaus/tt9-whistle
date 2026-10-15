package io.github.sspanak.tt9.dictation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Settings contract: defaults, key handling, endpoint validation and fingerprinting. */
final class OpenCodeSettingsTest {
	private static final String FAKE_KEY = "FAKE-JVM-HARNESS-KEY";
	private static final String PADDED_KEY = "\n  " + FAKE_KEY + "\t\n";


	private OpenCodeSettingsTest() {
	}


	public static void main(String[] args) {
		Harness harness = new Harness("OpenCodeSettings");
		Fakes.Storage storage = new Fakes.Storage();
		Fakes.KeyProtector protector = new Fakes.KeyProtector();
		OpenCodeSettings settings = new OpenCodeSettings(storage, protector);

		try {
			defaults(harness, settings);
			keys(harness, settings, storage, protector);
			endpoints(harness, settings);
			failures(harness, storage, settings);
			fingerprints(harness, settings);
		} catch (Exception error) {
			harness.fail("unexpected failure: " + error);
		}

		System.exit(harness.finish());
	}


	private static void defaults(Harness harness, OpenCodeSettings settings) throws Exception {
		harness.check(!settings.hasKey(), "a fresh install has no API key");
		harness.checkEquals(OpenCodeSettings.DEFAULT_ENDPOINT, settings.load().endpoint, "the default endpoint is the OpenCode Go route");
		harness.checkEquals(OpenCodeSettings.DEFAULT_MODEL, settings.load().model, "the default model is the proven one");
		harness.checkEquals(OpenCodeSettings.DEFAULT_PROMPT, settings.load().prompt, "the default cleanup prompt is present");
		harness.checkEquals(null, settings.load().apiKey, "no key is read before one is saved");
		harness.checkEquals(
			"https://opencode.ai/inference/go/v1/chat/completions",
			OpenCodeSettings.DEFAULT_ENDPOINT,
			"the recorded working endpoint is preserved"
		);
	}


	private static void keys(
		Harness harness,
		OpenCodeSettings settings,
		Fakes.Storage storage,
		Fakes.KeyProtector protector
	) throws Exception {
		settings.save(null, null, null, PADDED_KEY);
		harness.check(settings.hasKey(), "saving a key makes hasKey() true");
		harness.checkEquals(1, protector.encryptedPlaintexts.size(), "the key is handed to the protector once");
		harness.checkEquals(FAKE_KEY, protector.encryptedPlaintexts.get(0), "the key is trimmed before it is encrypted");
		harness.checkEquals(FAKE_KEY, settings.load().apiKey, "the stored key is exactly the trimmed key");
		harness.check(
			storage.values.get(OpenCodeSettings.KEY_ENCRYPTED_API_KEY) != null,
			"the key is stored under the encrypted-key entry"
		);
		harness.check(
			!storage.values.get(OpenCodeSettings.KEY_ENCRYPTED_API_KEY).contains(FAKE_KEY),
			"the stored credential does not contain the plaintext key"
		);
		harness.check(
			!storage.values.values().toString().contains(FAKE_KEY),
			"no stored value contains the plaintext key"
		);

		settings.save(null, null, null, "   ");
		harness.check(settings.hasKey(), "a blank key does not delete the saved key");
		harness.checkEquals(1, protector.encryptedPlaintexts.size(), "a blank key is never encrypted");
		harness.checkEquals(FAKE_KEY, settings.load().apiKey, "a blank key keeps the previous credential");

		settings.clearKey();
		harness.check(!settings.hasKey(), "clearing the key removes it");
		harness.checkEquals(null, settings.load().apiKey, "no key is returned after clearing");

		settings.save(" https://example.com/v1/chat/completions ", "", "  ", FAKE_KEY);
		harness.checkEquals(
			"https://example.com/v1/chat/completions",
			settings.load().endpoint,
			"the endpoint is trimmed"
		);
		harness.checkEquals(OpenCodeSettings.DEFAULT_MODEL, settings.load().model, "a blank model falls back to the default");
		harness.checkEquals(OpenCodeSettings.DEFAULT_PROMPT, settings.load().prompt, "a blank prompt falls back to the default");
	}


	private static void endpoints(Harness harness, OpenCodeSettings settings) throws Exception {
		String before = settings.load().endpoint;

		harness.checkThrows(
			IllegalArgumentException.class,
			() -> settings.save("http://example.com/v1/chat/completions", null, null, null),
			"plain HTTP endpoints are refused"
		);
		harness.checkThrows(
			IllegalArgumentException.class,
			() -> settings.save("https://user:secret@example.com/v1/chat/completions", null, null, null),
			"endpoints carrying credentials are refused"
		);
		harness.checkThrows(
			IllegalArgumentException.class,
			() -> settings.save("ftp://example.com/v1/chat/completions", null, null, null),
			"non-HTTPS schemes are refused"
		);
		harness.checkThrows(
			IllegalArgumentException.class,
			() -> settings.save("not a url", null, null, null),
			"malformed endpoints are refused"
		);
		harness.checkEquals(before, settings.load().endpoint, "a refused endpoint does not change the saved endpoint");

		settings.save("", null, null, null);
		harness.checkEquals(
			OpenCodeSettings.DEFAULT_ENDPOINT,
			settings.load().endpoint,
			"an empty endpoint falls back to the default"
		);
	}


	private static void failures(Harness harness, Fakes.Storage storage, OpenCodeSettings settings) {
		storage.failCommits = true;
		harness.checkThrows(
			IOException.class,
			() -> settings.save("https://example.com/v1/chat/completions", null, null, FAKE_KEY),
			"a failed write is reported as an IOException"
		);
		harness.checkThrows(IOException.class, settings::clearKey, "a failed clear is reported as an IOException");
		storage.failCommits = false;

		Fakes.Storage unprotected = new Fakes.Storage();
		OpenCodeSettings withoutKeystore = new OpenCodeSettings(unprotected, new Fakes.FailingKeyProtector());
		harness.checkThrows(
			IOException.class,
			() -> withoutKeystore.save(null, null, null, FAKE_KEY),
			"a key that cannot be protected is reported as an IOException"
		);
		harness.check(unprotected.values.isEmpty(), "nothing is written when the key cannot be protected");

		Fakes.Storage broken = new Fakes.Storage();
		broken.values.put(OpenCodeSettings.KEY_ENCRYPTED_API_KEY, "v1:unreadable");
		OpenCodeSettings unreadable = new OpenCodeSettings(broken, new Fakes.FailingKeyProtector());
		harness.check(unreadable.hasKey(), "hasKey() reads the stored entry without decrypting it");
		harness.checkThrows(IOException.class, unreadable::load, "an unreadable credential is reported as an IOException");
		harness.checkThrows(IOException.class, unreadable::fingerprint, "an unreadable credential has no fingerprint");
	}


	private static void fingerprints(Harness harness, OpenCodeSettings settings) throws Exception {
		settings.clearKey();
		harness.checkEquals(null, settings.fingerprint(), "there is no fingerprint without a key");

		settings.save(null, null, null, FAKE_KEY);
		String first = settings.fingerprint();
		harness.check(first != null && first.startsWith("sha256:"), "the fingerprint is a labelled SHA-256 prefix");
		harness.check(first != null && !first.contains(FAKE_KEY), "the fingerprint does not contain the key");
		harness.checkEquals(expectedFingerprint(FAKE_KEY), first, "the fingerprint matches the SHA-256 of the stored key");

		settings.save(null, null, null, "  " + FAKE_KEY + " ");
		harness.checkEquals(first, settings.fingerprint(), "the same key keeps the same fingerprint across saves");

		settings.save(null, null, null, "FAKE-OTHER-KEY");
		harness.check(first != null && !first.equals(settings.fingerprint()), "a different key changes the fingerprint");

		settings.clearKey();
		harness.checkEquals(null, settings.fingerprint(), "clearing the key removes the fingerprint");
	}


	private static String expectedFingerprint(String key) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
		StringBuilder fingerprint = new StringBuilder("sha256:");
		for (int i = 0; i < OpenCodeSettings.FINGERPRINT_BYTES; i++) {
			fingerprint.append(String.format(Locale.ROOT, "%02x", digest[i]));
		}
		return fingerprint.toString();
	}
}
