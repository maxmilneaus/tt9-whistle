package io.github.sspanak.tt9.dictation;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import io.github.sspanak.tt9.util.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Which voice input engine the keyboard uses, and how the on-device one is tuned.
 *
 * <p>Everything is kept in the same SharedPreferences file as the rest of the TT9 settings, so the
 * IME, the runtime and the preferences screen all read one source of truth. Each engine keeps its own
 * values, which means switching between them never overwrites the other one's configuration.</p>
 *
 * <p>The getters clamp, not only the settings screen, because a value written by an older build is
 * still in the file after an upgrade. The retained model is 8 layers deep and its runner accepts 2 to
 * 8, so nothing outside that range is ever handed to it.</p>
 */
public final class DictationSettings {
	public static final String ENGINE_WHISTLE = "whistle";
	public static final String ENGINE_OPENCODE = "opencode";
	public static final String ENGINE_SYSTEM = "system";

	public static final String KEY_ENGINE = "pref_voice_engine";
	public static final String KEY_DEPTH = "pref_voice_depth";
	public static final String KEY_THREADS = "pref_voice_threads";
	public static final String KEY_LANGUAGE = "pref_voice_language";
	/** Hint words, one per line or separated by commas: names, jargon, product words. */
	public static final String KEY_KEYWORDS = "pref_voice_keywords";

	/** Keep the loaded model resident between dictations, instead of unloading it on keyboard close. */
	public static final String KEY_KEEP_WARM = "pref_voice_keep_warm";

	/** Reloading the model costs more than the memory it holds, so keeping it is the default. */
	public static final boolean KEEP_WARM_DEFAULT = true;

	/** The whole model. Also the largest depth the runner accepts. */
	public static final int DEPTH_FULL = 8;
	/** The smallest depth the runner accepts. */
	public static final int DEPTH_MIN = 2;
	/** Measured faster than the full model with no observed loss, so it is the provisional default. */
	public static final int DEPTH_DEFAULT = 4;
	/** Every depth the settings offer, in the runner's own units. */
	public static final int[] DEPTH_OPTIONS = { DEPTH_MIN, DEPTH_DEFAULT, 6, DEPTH_FULL };

	/** Let the runner pick the thread count. */
	public static final int THREADS_AUTO = 0;
	public static final int THREADS_MAX = 4;
	/** The provisional default, from the on-phone benchmark. */
	public static final int THREADS_DEFAULT = 4;
	public static final int[] THREAD_OPTIONS = { THREADS_AUTO, 1, 2, 3, THREADS_MAX };

	public static final String LANGUAGE_ENGLISH = "en";
	/** The languages the pinned on-device model supports. Another one would only fail at run time. */
	public static final String[] SUPPORTED_LANGUAGES = { "en", "de", "fr", "es", "it", "nl", "pl" };

	/** Log tag; the value is kept stable so existing log filters keep matching. */
	private static final String LOG_TAG = "TT9Dictation";

	@NonNull private final SharedPreferences prefs;

	/** Guards the keyword-file memo below: the words are read and written from more than one thread. */
	private static final Object KEYWORDS_CACHE_LOCK = new Object();
	/** The list the remembered keyword file belongs to, and that file; both null until one is resolved. */
	private static String cachedKeywords;
	private static File cachedKeywordsFile;


	public DictationSettings(@NonNull Context context) {
		prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
	}


	@NonNull
	public String getEngine() {
		final String engine = prefs.getString(KEY_ENGINE, ENGINE_WHISTLE);
		return isEngine(engine) ? engine : ENGINE_WHISTLE;
	}


	public void setEngine(@Nullable String engine) {
		prefs.edit().putString(KEY_ENGINE, isEngine(engine) ? engine : ENGINE_WHISTLE).apply();
	}


	public static boolean isEngine(@Nullable String engine) {
		return ENGINE_WHISTLE.equals(engine) || ENGINE_OPENCODE.equals(engine) || ENGINE_SYSTEM.equals(engine);
	}


	/** Whether the loaded on-device model stays in memory between dictations. */
	public boolean getKeepWarm() { return prefs.getBoolean(KEY_KEEP_WARM, KEEP_WARM_DEFAULT); }


	public int getDepth() {
		return clampDepth(getStringifiedInt(KEY_DEPTH, DEPTH_DEFAULT));
	}


	/**
	 * Moves a stored depth to the nearest value the settings offer, always rounding deeper, so a setting
	 * can never be quietly made less accurate by the clamp. Below the runner's minimum it reads as the
	 * full depth, because in every build made before the depths were measured, anything under 2 meant
	 * "pass no depth at all", which is the whole model.
	 */
	public static int clampDepth(int depth) {
		if (depth < DEPTH_MIN) {
			return DEPTH_FULL;
		}

		int clamped = DEPTH_FULL;

		for (int option : DEPTH_OPTIONS) {
			if (depth <= option) {
				clamped = option;
				break;
			}
		}

		return clamped;
	}


	public int getThreads() {
		return clampThreads(getStringifiedInt(KEY_THREADS, THREADS_DEFAULT));
	}


	/** 0 keeps meaning "let the runner decide"; anything above the maximum is capped. */
	public static int clampThreads(int threads) {
		if (threads <= THREADS_AUTO) {
			return THREADS_AUTO;
		}

		return Math.min(threads, THREADS_MAX);
	}


	@NonNull
	public String getLanguage() {
		final String language = prefs.getString(KEY_LANGUAGE, LANGUAGE_ENGLISH);
		return isSupportedLanguage(language) ? language : LANGUAGE_ENGLISH;
	}


	public void setLanguage(@Nullable String language) {
		prefs.edit().putString(KEY_LANGUAGE, isSupportedLanguage(language) ? language : LANGUAGE_ENGLISH).apply();
	}
	
	
	/** The hint words exactly as they were typed. Empty means the runner gets no hints. */
	@NonNull
	public String getKeywords() {
		final String words = prefs.getString(KEY_KEYWORDS, "");
		return words == null ? "" : words.trim();
	}
	
	
	public void setKeywords(@Nullable String words) {
		prefs.edit().putString(KEY_KEYWORDS, words == null ? "" : words.trim()).apply();
	}
	
	
	/**
	 * Writes the hint words where the runner can read them, as {@code --audio-keywords}. The file is
	 * named after its own contents, so an edited list is a different path: the warm engine keys on the
	 * path, which makes a changed list start a fresh runner with no other invalidation.
	 *
	 * An unchanged list is remembered, so the common case skips the hashing and the folder scan, and the
	 * memo is re-checked against the file so one that was cleaned up is written again.
	 *
	 * @return the file to pass to the runner, or null when no words are set.
	 */
	@Nullable
	public static File writeKeywordsFile(@NonNull Context context) throws IOException {
		final String words = normaliseKeywords(new DictationSettings(context).getKeywords());
		if (words.isEmpty()) {
			return null;
		}
	
		final File remembered = rememberedKeywordsFile(words);
		if (remembered != null) {
			return remembered;
		}

		final File dir = new File(context.getFilesDir(), "whistle");
		final File target = new File(dir, "keywords-" + shortDigest(words) + ".txt");
		// Drop any earlier list. Deleting a file the engine still holds open is safe here.
		final File[] stale = dir.listFiles((parent, name) -> name.startsWith("keywords-") && !name.equals(target.getName()));
		if (stale != null) {
			for (File old : stale) {
				if (!old.delete()) {
					Logger.d(LOG_TAG, "Could not remove the old keyword list " + old.getName());
				}
			}
		}
	
		if (target.isFile() && target.length() > 0) {
			rememberKeywordsFile(words, target);
			return target;
		}
		if (!dir.isDirectory() && !dir.mkdirs()) {
			throw new IOException("Cannot create the keyword folder.");
		}
	
		final File partial = new File(dir, target.getName() + ".part");
		try (FileOutputStream out = new FileOutputStream(partial)) {
			out.write(words.getBytes(StandardCharsets.UTF_8));
			out.write('\n');
		}
		if (!partial.renameTo(target)) {
			throw new IOException("Cannot install the keyword list.");
		}
		rememberKeywordsFile(words, target);
		return target;
	}

	/**
	 * The file this exact list resolved to last time, or null when it must be worked out again. The
	 * entry is dropped as soon as the file stops existing or stops having content, so a cleaned-up
	 * file is written again instead of being handed to the runner as a path that is not there.
	 */
	@Nullable
	private static File rememberedKeywordsFile(@NonNull String words) {
		synchronized (KEYWORDS_CACHE_LOCK) {
			if (!words.equals(cachedKeywords)) {
				return null;
			}

			final File file = cachedKeywordsFile;
			if (file == null || !file.isFile() || file.length() == 0) {
				cachedKeywords = null;
				cachedKeywordsFile = null;
				return null;
			}

			return file;
		}
	}

	private static void rememberKeywordsFile(@NonNull String words, @NonNull File file) {
		synchronized (KEYWORDS_CACHE_LOCK) {
			cachedKeywords = words;
			cachedKeywordsFile = file;
		}
	}
	
	
	/** One hint per line, whatever punctuation the user separated them with. */
	@NonNull
	private static String normaliseKeywords(@NonNull String raw) {
		final List<String> words = new ArrayList<>();
		for (String piece : raw.split("[,\\n\\r]") ) {
			final String word = piece.trim();
			if (!word.isEmpty() && !words.contains(word)) {
				words.add(word);
			}
		}
	
		final StringBuilder joined = new StringBuilder();
		for (String word : words) {
			if (joined.length() > 0) {
				joined.append('\n');
			}
			joined.append(word);
		}
		return joined.toString();
	}
	
	
	/** A short stable name for the list, so equal lists reuse one file. */
	@NonNull
	private static String shortDigest(@NonNull String words) {
		try {
			final byte[] hash = MessageDigest.getInstance("SHA-256").digest(words.getBytes(StandardCharsets.UTF_8));
			final StringBuilder hex = new StringBuilder();
			for (int i = 0; i < 6; i++) {
				hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
				hex.append(Character.forDigit(hash[i] & 0xF, 16));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException alwaysPresent) {
			return Integer.toHexString(words.hashCode());
		}
	}


	public static boolean isSupportedLanguage(@Nullable String language) {
		if (language == null) {
			return false;
		}

		for (String supported : SUPPORTED_LANGUAGES) {
			if (supported.equals(language)) {
				return true;
			}
		}

		return false;
	}


	/**
	 * Whether this build is allowed to make outbound connections at all. The cloud engine sends audio
	 * to a remote endpoint, so the settings label that engine as unusable when the answer is "no",
	 * instead of letting the user select it and fail later.
	 */
	public static boolean isCloudAvailable(@NonNull Context context) {
		return context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED;
	}


	private int getStringifiedInt(@NonNull String key, int defaultValue) {
		try {
			return Integer.parseInt(prefs.getString(key, String.valueOf(defaultValue)));
		} catch (NumberFormatException ignored) {
			return defaultValue;
		}
	}
}
