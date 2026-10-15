package io.github.sspanak.tt9.dictation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory stand-ins for Android preferences and the Keystore. */
final class Fakes {
	private Fakes() {
	}


	static final class Storage implements OpenCodeSettings.Storage {
		final Map<String, String> values = new HashMap<>();
		boolean failCommits = false;


		@Override
		public String get(String key) {
			return values.get(key);
		}


		@Override
		public boolean contains(String key) {
			return values.containsKey(key);
		}


		@Override
		public OpenCodeSettings.Storage.Edit edit() {
			return new Staged();
		}


		private final class Staged implements OpenCodeSettings.Storage.Edit {
			private final Map<String, String> staged = new HashMap<>();
			private final List<String> removed = new ArrayList<>();


			@Override
			public OpenCodeSettings.Storage.Edit put(String key, String value) {
				staged.put(key, value);
				return this;
			}


			@Override
			public OpenCodeSettings.Storage.Edit remove(String key) {
				removed.add(key);
				return this;
			}


			@Override
			public boolean commit() {
				if (failCommits) {
					return false;
				}
				for (String key : removed) {
					values.remove(key);
				}
				values.putAll(staged);
				return true;
			}
		}
	}


	/** Reversible, deterministic stand-in for the Keystore; records every plaintext it is given. */
	static final class KeyProtector implements OpenCodeSettings.KeyProtector {
		final List<String> encryptedPlaintexts = new ArrayList<>();


		@Override
		public String encrypt(String plaintext) {
			encryptedPlaintexts.add(plaintext);
			return "v1:" + Base64.getEncoder().encodeToString(reverse(plaintext.getBytes(StandardCharsets.UTF_8)));
		}


		@Override
		public String decrypt(String encoded) {
			if (encoded == null || !encoded.startsWith("v1:")) {
				throw new IllegalArgumentException("not a wrapped value");
			}
			return new String(reverse(Base64.getDecoder().decode(encoded.substring(3))), StandardCharsets.UTF_8);
		}


		private static byte[] reverse(byte[] input) {
			byte[] output = new byte[input.length];
			for (int i = 0; i < input.length; i++) {
				output[i] = input[input.length - 1 - i];
			}
			return output;
		}
	}


	/** A Keystore that is unavailable or corrupt, to prove the failures surface as IOException. */
	static final class FailingKeyProtector implements OpenCodeSettings.KeyProtector {
		@Override
		public String encrypt(String plaintext) throws Exception {
			throw new IOException("keystore unavailable");
		}


		@Override
		public String decrypt(String encoded) throws Exception {
			throw new IOException("keystore unavailable");
		}
	}
}
