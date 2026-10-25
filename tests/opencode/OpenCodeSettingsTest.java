package io.github.sspanak.tt9.dictation;

import java.io.IOException;

/** Settings contract: defaults, key handling, endpoint validation and fingerprinting. */
final class OpenCodeSettingsTest {
	private static final String FAKE_KEY = "FAKE-JVM-HARNESS-KEY";
	/** The pinned SHA-256 prefix of FAKE_KEY; a change to the fingerprinter must fail this. */
	private static final String EXPECTED_FINGERPRINT = "sha256:38270f2440f2316a";
	private static final String PADDED_KEY = "\n  " + FAKE_KEY + "\t\n";


	private OpenCodeSettingsTest() {
	}


	public static void main(String[] args) {
		Fakes.Storage storage = new Fakes.Storage();
		Fakes.KeyProtector protector = new Fakes.KeyProtector();
		OpenCodeSettings settings = new OpenCodeSettings(storage, protector);

		try {
			defaults(settings);
			keys(settings, storage, protector);
			endpoints(settings);
			failures(storage, settings);
			fingerprints(settings);
		} catch (Exception error) {
			WhistleCheck.fail("unexpected failure: " + error);
		}

		WhistleCheck.finish("OpenCodeSettings");
	}


	private static void defaults(OpenCodeSettings settings) throws Exception {
		WhistleCheck.yes(!settings.hasKey(), "a fresh install has no API key");
		WhistleCheck.equal(OpenCodeSettings.DEFAULT_ENDPOINT, settings.load().endpoint, "the default endpoint is the OpenCode Go route");
		WhistleCheck.equal(OpenCodeSettings.DEFAULT_MODEL, settings.load().model, "the default model is the proven one");
		WhistleCheck.equal(OpenCodeSettings.DEFAULT_PROMPT, settings.load().prompt, "the default cleanup prompt is present");
		WhistleCheck.equal(null, settings.load().apiKey, "no key is read before one is saved");
		WhistleCheck.equal(
			"https://opencode.ai/inference/go/v1/chat/completions",
			OpenCodeSettings.DEFAULT_ENDPOINT,
			"the recorded working endpoint is preserved"
		);
	}


	private static void keys(
		OpenCodeSettings settings,
		Fakes.Storage storage,
		Fakes.KeyProtector protector
	) throws Exception {
		settings.save(null, null, null, PADDED_KEY);
		WhistleCheck.yes(settings.hasKey(), "saving a key makes hasKey() true");
		WhistleCheck.equal(1, protector.encryptedPlaintexts.size(), "the key is handed to the protector once");
		WhistleCheck.equal(FAKE_KEY, protector.encryptedPlaintexts.get(0), "the key is trimmed before it is encrypted");
		WhistleCheck.equal(FAKE_KEY, settings.load().apiKey, "the stored key is exactly the trimmed key");
		WhistleCheck.yes(
			storage.values.get(OpenCodeSettings.KEY_ENCRYPTED_API_KEY) != null,
			"the key is stored under the encrypted-key entry"
		);
		WhistleCheck.yes(
			!storage.values.get(OpenCodeSettings.KEY_ENCRYPTED_API_KEY).contains(FAKE_KEY),
			"the stored credential does not contain the plaintext key"
		);
		WhistleCheck.yes(
			!storage.values.values().toString().contains(FAKE_KEY),
			"no stored value contains the plaintext key"
		);

		settings.save(null, null, null, "   ");
		WhistleCheck.yes(settings.hasKey(), "a blank key does not delete the saved key");
		WhistleCheck.equal(1, protector.encryptedPlaintexts.size(), "a blank key is never encrypted");
		WhistleCheck.equal(FAKE_KEY, settings.load().apiKey, "a blank key keeps the previous credential");

		settings.clearKey();
		WhistleCheck.yes(!settings.hasKey(), "clearing the key removes it");
		WhistleCheck.equal(null, settings.load().apiKey, "no key is returned after clearing");

		settings.save(" https://example.com/v1/chat/completions ", "", "  ", FAKE_KEY);
		WhistleCheck.equal(
			"https://example.com/v1/chat/completions",
			settings.load().endpoint,
			"the endpoint is trimmed"
		);
		WhistleCheck.equal(OpenCodeSettings.DEFAULT_MODEL, settings.load().model, "a blank model falls back to the default");
		WhistleCheck.equal(OpenCodeSettings.DEFAULT_PROMPT, settings.load().prompt, "a blank prompt falls back to the default");
	}


	private static void endpoints(OpenCodeSettings settings) throws Exception {
		String before = settings.load().endpoint;

		WhistleCheck.checkThrows(
			IllegalArgumentException.class,
			() -> settings.save("http://example.com/v1/chat/completions", null, null, null),
			"plain HTTP endpoints are refused"
		);
		WhistleCheck.checkThrows(
			IllegalArgumentException.class,
			() -> settings.save("https://user:secret@example.com/v1/chat/completions", null, null, null),
			"endpoints carrying credentials are refused"
		);
		WhistleCheck.checkThrows(
			IllegalArgumentException.class,
			() -> settings.save("ftp://example.com/v1/chat/completions", null, null, null),
			"non-HTTPS schemes are refused"
		);
		WhistleCheck.checkThrows(
			IllegalArgumentException.class,
			() -> settings.save("not a url", null, null, null),
			"malformed endpoints are refused"
		);
		WhistleCheck.equal(before, settings.load().endpoint, "a refused endpoint does not change the saved endpoint");

		settings.save("", null, null, null);
		WhistleCheck.equal(
			OpenCodeSettings.DEFAULT_ENDPOINT,
			settings.load().endpoint,
			"an empty endpoint falls back to the default"
		);
	}


	private static void failures(Fakes.Storage storage, OpenCodeSettings settings) {
		storage.failCommits = true;
		WhistleCheck.checkThrows(
			IOException.class,
			() -> settings.save("https://example.com/v1/chat/completions", null, null, FAKE_KEY),
			"a failed write is reported as an IOException"
		);
		WhistleCheck.checkThrows(IOException.class, settings::clearKey, "a failed clear is reported as an IOException");
		storage.failCommits = false;

		Fakes.Storage unprotected = new Fakes.Storage();
		OpenCodeSettings withoutKeystore = new OpenCodeSettings(unprotected, new Fakes.FailingKeyProtector());
		WhistleCheck.checkThrows(
			IOException.class,
			() -> withoutKeystore.save(null, null, null, FAKE_KEY),
			"a key that cannot be protected is reported as an IOException"
		);
		WhistleCheck.yes(unprotected.values.isEmpty(), "nothing is written when the key cannot be protected");

		Fakes.Storage broken = new Fakes.Storage();
		broken.values.put(OpenCodeSettings.KEY_ENCRYPTED_API_KEY, "v1:unreadable");
		OpenCodeSettings unreadable = new OpenCodeSettings(broken, new Fakes.FailingKeyProtector());
		WhistleCheck.yes(unreadable.hasKey(), "hasKey() reads the stored entry without decrypting it");
		WhistleCheck.checkThrows(IOException.class, unreadable::load, "an unreadable credential is reported as an IOException");
		WhistleCheck.checkThrows(IOException.class, unreadable::fingerprint, "an unreadable credential has no fingerprint");
	}


	private static void fingerprints(OpenCodeSettings settings) throws Exception {
		settings.clearKey();
		WhistleCheck.equal(null, settings.fingerprint(), "there is no fingerprint without a key");

		settings.save(null, null, null, FAKE_KEY);
		String first = settings.fingerprint();
		WhistleCheck.yes(first != null && first.startsWith("sha256:"), "the fingerprint is a labelled SHA-256 prefix");
		WhistleCheck.yes(first != null && !first.contains(FAKE_KEY), "the fingerprint does not contain the key");
		WhistleCheck.equal(EXPECTED_FINGERPRINT, first, "the fingerprint matches the pinned SHA-256 digest of the stored key");

		settings.save(null, null, null, "  " + FAKE_KEY + " ");
		WhistleCheck.equal(first, settings.fingerprint(), "the same key keeps the same fingerprint across saves");

		settings.save(null, null, null, "FAKE-OTHER-KEY");
		WhistleCheck.yes(first != null && !first.equals(settings.fingerprint()), "a different key changes the fingerprint");

		settings.clearKey();
		WhistleCheck.equal(null, settings.fingerprint(), "clearing the key removes the fingerprint");
	}

}
