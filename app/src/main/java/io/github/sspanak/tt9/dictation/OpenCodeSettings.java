package io.github.sspanak.tt9.dictation;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Settings for the optional OpenCode dictation engine: endpoint, model, cleanup prompt and API key.
 *
 * Ported from the proven credential implementation in apps/qin-dictation (SettingsStore.java) and
 * scoped to this app: it uses its own preferences file and its own Keystore alias, so it can
 * neither read nor overwrite the API key that the standalone app has already stored.
 */
public class OpenCodeSettings {
	public static final String DEFAULT_ENDPOINT = "https://opencode.ai/inference/go/v1/chat/completions";
	public static final String DEFAULT_MODEL = "mimo-v2.6-flash";
	public static final String DEFAULT_PROMPT =
		"Transcribe the speech accurately, clean up punctuation and remove filler words "
			+ "while preserving the speaker's meaning. Return only the message text.";

	static final String PREFERENCES_NAME = "tt9_dictation_opencode";
	static final String KEY_ENDPOINT = "opencode_endpoint";
	static final String KEY_MODEL = "opencode_model";
	static final String KEY_PROMPT = "opencode_prompt";
	static final String KEY_ENCRYPTED_API_KEY = "opencode_encrypted_api_key";
	static final String KEYSTORE_ALIAS = "tt9_dictation_opencode_api_key";
	static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
	static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
	static final int GCM_TAG_BITS = 128;
	static final int GCM_IV_BYTES = 12;
	static final int KEY_SIZE_BITS = 256;
	static final int FINGERPRINT_BYTES = 8;

	private final Storage storage;
	private final KeyProtector keyProtector;


	public OpenCodeSettings(Context context) {
		if (context == null) {
			throw new IllegalArgumentException("context == null");
		}

		storage = new PreferencesStorage(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE));
		keyProtector = new KeystoreProtector();
	}


	/** Explicit storage and key protection, used by the off-device contract harness. */
	OpenCodeSettings(Storage storage, KeyProtector keyProtector) {
		if (storage == null || keyProtector == null) {
			throw new IllegalArgumentException("storage and key protector must not be null");
		}

		this.storage = storage;
		this.keyProtector = keyProtector;
	}


	/** The current settings; never null. Missing values fall back to the defaults. */
	public Snapshot load() throws Exception {
		return new Snapshot(
			valueOrDefault(storage.get(KEY_ENDPOINT), DEFAULT_ENDPOINT),
			valueOrDefault(storage.get(KEY_MODEL), DEFAULT_MODEL),
			valueOrDefault(storage.get(KEY_PROMPT), DEFAULT_PROMPT),
			decryptApiKey()
		);
	}


	/**
	 * Saves the settings. A null, empty or whitespace-only key keeps the saved credential, so the
	 * setup screen can change the endpoint or the prompt without asking for the key again.
	 */
	public void save(String endpoint, String model, String prompt, String key) throws Exception {
		String checkedEndpoint = requireHttpsEndpoint(endpoint);

		// The key is trimmed before it is encrypted, so the stored credential is exactly the string
		// that will be sent in the Authorization header.
		String trimmedKey = trimmedKey(key);
		String encryptedKey = null;
		if (trimmedKey != null) {
			try {
				encryptedKey = keyProtector.encrypt(trimmedKey);
			} catch (Exception error) {
				throw new IOException("Unable to protect the dictation API key.", error);
			}
		}

		Storage.Edit edit = storage
			.edit()
			.put(KEY_ENDPOINT, checkedEndpoint)
			.put(KEY_MODEL, valueOrDefault(model, DEFAULT_MODEL))
			.put(KEY_PROMPT, valueOrDefault(prompt, DEFAULT_PROMPT));
		if (encryptedKey != null) {
			edit.put(KEY_ENCRYPTED_API_KEY, encryptedKey);
		}

		if (!edit.commit()) {
			throw new IOException("Unable to save the dictation settings.");
		}
	}


	/** Whether a credential is stored. It is never read just to answer this. */
	public boolean hasKey() {
		return storage.contains(KEY_ENCRYPTED_API_KEY);
	}


	public void clearKey() throws Exception {
		if (!storage.edit().remove(KEY_ENCRYPTED_API_KEY).commit()) {
			throw new IOException("Unable to clear the dictation API key.");
		}
	}


	/**
	 * A short, non-reversible identifier of the saved key, so a screen can show which credential
	 * is in use without showing the credential itself. Null when nothing is saved.
	 */
	public String fingerprint() throws Exception {
		String key = decryptApiKey();
		return key == null ? null : fingerprintOf(key);
	}


	static String fingerprintOf(String key) throws Exception {
		if (key == null) {
			return null;
		}

		byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
		StringBuilder fingerprint = new StringBuilder("sha256:");
		for (int i = 0; i < FINGERPRINT_BYTES; i++) {
			fingerprint.append(String.format(Locale.ROOT, "%02x", digest[i]));
		}
		return fingerprint.toString();
	}


	/** Returns the trimmed key, or null when there is nothing worth storing. */
	static String trimmedKey(String key) {
		if (key == null) {
			return null;
		}

		String trimmed = key.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}


	/** Requires a credential-free HTTPS endpoint; an empty value means the default. */
	static String requireHttpsEndpoint(String value) {
		String endpoint = value == null ? "" : value.trim();
		if (endpoint.isEmpty()) {
			return DEFAULT_ENDPOINT;
		}

		final URI uri;
		try {
			uri = new URI(endpoint);
		} catch (Exception malformed) {
			throw new IllegalArgumentException("The endpoint must be an HTTPS URL.");
		}

		if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isEmpty()) {
			throw new IllegalArgumentException("The endpoint must be an HTTPS URL.");
		}
		if (uri.getUserInfo() != null) {
			throw new IllegalArgumentException("The endpoint must not contain credentials.");
		}

		return uri.toASCIIString();
	}


	private static String valueOrDefault(String value, String defaultValue) {
		return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
	}


	private String decryptApiKey() throws Exception {
		String encrypted = storage.get(KEY_ENCRYPTED_API_KEY);
		if (encrypted == null || encrypted.isEmpty()) {
			return null;
		}

		try {
			return keyProtector.decrypt(encrypted);
		} catch (Exception error) {
			throw new IOException("Unable to read the saved dictation API key.", error);
		}
	}


	/** Preference access, so the credential handling can be exercised without Android. */
	interface Storage {
		String get(String key);

		boolean contains(String key);

		Edit edit();


		interface Edit {
			Edit put(String key, String value);

			Edit remove(String key);

			boolean commit();
		}
	}


	/** Key encryption, so the credential handling can be exercised without a Keystore. */
	interface KeyProtector {
		String encrypt(String plaintext) throws Exception;

		String decrypt(String encrypted) throws Exception;
	}


	public static final class Snapshot {
		public final String endpoint;
		public final String model;
		public final String prompt;
		public final String apiKey;


		public Snapshot(String endpoint, String model, String prompt, String apiKey) {
			this.endpoint = endpoint;
			this.model = model;
			this.prompt = prompt;
			this.apiKey = apiKey;
		}
	}


	private static final class PreferencesStorage implements Storage {
		private final SharedPreferences preferences;


		PreferencesStorage(SharedPreferences preferences) {
			this.preferences = preferences;
		}


		@Override
		public String get(String key) {
			return preferences.getString(key, null);
		}


		@Override
		public boolean contains(String key) {
			return preferences.contains(key);
		}


		@Override
		public Storage.Edit edit() {
			return new PreferencesEdit(preferences.edit());
		}
	}


	private static final class PreferencesEdit implements Storage.Edit {
		private final SharedPreferences.Editor editor;


		PreferencesEdit(SharedPreferences.Editor editor) {
			this.editor = editor;
		}


		@Override
		public Storage.Edit put(String key, String value) {
			editor.putString(key, value);
			return this;
		}


		@Override
		public Storage.Edit remove(String key) {
			editor.remove(key);
			return this;
		}


		@Override
		public boolean commit() {
			return editor.commit();
		}
	}


	/** AES/GCM under a key that never leaves this app's Keystore entry. */
	private static final class KeystoreProtector implements KeyProtector {
		@Override
		public String encrypt(String plaintext) throws Exception {
			Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());

			byte[] iv = cipher.getIV();
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			byte[] packed = new byte[iv.length + ciphertext.length];
			System.arraycopy(iv, 0, packed, 0, iv.length);
			System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);
			return Base64.encodeToString(packed, Base64.NO_WRAP);
		}


		@Override
		public String decrypt(String encrypted) throws Exception {
			byte[] packed = Base64.decode(encrypted, Base64.NO_WRAP);
			if (packed.length <= GCM_IV_BYTES) {
				throw new IOException("The saved dictation API key is unreadable.");
			}

			byte[] iv = Arrays.copyOfRange(packed, 0, GCM_IV_BYTES);
			byte[] ciphertext = Arrays.copyOfRange(packed, GCM_IV_BYTES, packed.length);
			Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
			return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
		}


		private static SecretKey getOrCreateKey() throws Exception {
			KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
			keyStore.load(null);

			KeyStore.Entry existing = keyStore.getEntry(KEYSTORE_ALIAS, null);
			if (existing instanceof KeyStore.SecretKeyEntry) {
				return ((KeyStore.SecretKeyEntry) existing).getSecretKey();
			}

			KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
			generator.init(new KeyGenParameterSpec.Builder(
					KEYSTORE_ALIAS,
					KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(KEY_SIZE_BITS)
				.build());
			return generator.generateKey();
		}
	}
}
