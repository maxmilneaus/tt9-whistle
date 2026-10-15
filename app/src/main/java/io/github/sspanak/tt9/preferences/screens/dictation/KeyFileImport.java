package io.github.sspanak.tt9.preferences.screens.dictation;

import android.content.Context;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads the cloud credential that was staged in this app's private files directory, so a long key can
 * be imported without typing it on a hardware keypad.
 *
 * <p>It reads only this app's own directory: no storage permission, no caller-supplied path and no
 * clipboard. The file is bounded, the key is trimmed, and the caller deletes the file once the key has
 * been saved. The contents are never logged and never shown.</p>
 */
public final class KeyFileImport {
	public static final String FILE_NAME = "opencode-go.key";
	/** Roomy for a long token, small enough that a wrong file fails instead of being imported. */
	public static final int MAX_BYTES = 4 * 1024;

	private KeyFileImport() {}


	public enum Failure { MISSING, TOO_LARGE, EMPTY, UNREADABLE }


	public static final class ImportException extends IOException {
		@NonNull public final Failure failure;

		public ImportException(@NonNull Failure failure, @NonNull String message) {
			super(message);
			this.failure = failure;
		}
	}


	public static boolean exists(@NonNull Context context) {
		return getFile(context).isFile();
	}


	@NonNull
	public static String read(@NonNull Context context) throws ImportException {
		final File file = getFile(context);
		if (!file.isFile()) {
			throw new ImportException(Failure.MISSING, "No key file at " + FILE_NAME);
		}

		// One byte more than the limit, so a file that is too big is detected instead of silently cut short.
		final byte[] buffer = new byte[MAX_BYTES + 1];
		int total = 0;

		try (InputStream in = new FileInputStream(file)) {
			while (total < buffer.length) {
				final int read = in.read(buffer, total, buffer.length - total);
				if (read <= 0) {
					break;
				}
				total += read;
			}
		} catch (IOException e) {
			throw new ImportException(Failure.UNREADABLE, e.getMessage() != null ? e.getMessage() : "unreadable");
		}

		if (total > MAX_BYTES) {
			throw new ImportException(Failure.TOO_LARGE, "larger than " + (MAX_BYTES / 1024) + " KB");
		}

		final String key = new String(buffer, 0, total, StandardCharsets.UTF_8).trim();
		if (key.isEmpty()) {
			throw new ImportException(Failure.EMPTY, "empty");
		}

		return key;
	}


	/** Best effort. The caller reports it, because a leftover plaintext key is worth knowing about. */
	public static boolean delete(@NonNull Context context) {
		final File file = getFile(context);
		return !file.exists() || file.delete();
	}


	@NonNull
	private static File getFile(@NonNull Context context) {
		return new File(context.getFilesDir(), FILE_NAME);
	}
}
