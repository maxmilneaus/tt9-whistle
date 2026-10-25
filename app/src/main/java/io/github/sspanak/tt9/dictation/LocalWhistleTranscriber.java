package io.github.sspanak.tt9.dictation;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * On-device Transcriber: writes the WAV to a temp file, runs the whistle "needle" CLI
 * on it, and reads the transcript from the JSON on the last line of its output.
 */
public final class LocalWhistleTranscriber implements Transcriber {
	static final long TIMEOUT_SECONDS = 30;

	/** One daemon thread for every deadline this transcriber arms; a timer here only kills a process. */
	private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "tt9-needle-watchdog");
			thread.setDaemon(true);
			return thread;
		}
	});

	private final File cacheDir;
	private final String needleBinary;
	private final String modelFile;
	private final String language;
	private final int depth;
	private final int threads;
	private final String keywordsPath;
	private Process active;
	private volatile boolean cancelled;

	public LocalWhistleTranscriber(File cacheDir, String needleBinary, String modelFile, int depth, int threads, String language, String keywordsPath) {
		this.language = language;
		this.depth = depth;
		this.threads = threads;
		this.keywordsPath = keywordsPath;
		if (cacheDir == null) {
			throw new IllegalArgumentException("cacheDir == null");
		}
		this.cacheDir = cacheDir;
		this.needleBinary = needleBinary;
		this.modelFile = modelFile;
	}

	@Override
	public String transcribe(byte[] wav) throws Exception {
		File audio = File.createTempFile("needle-", ".wav", cacheDir);
		ScheduledFuture<?> timer = null;
		try {
			FileOutputStream out = new FileOutputStream(audio);
			try {
				out.write(wav);
			} finally {
				out.close();
			}

			final Process running = start(audio);
			final AtomicBoolean timedOut = new AtomicBoolean(false);
			timer = WATCHDOG.schedule(new Runnable() {
				@Override
				public void run() {
					timedOut.set(true);
					running.destroy();
				}
			}, TIMEOUT_SECONDS, TimeUnit.SECONDS);

			String output = readAll(running.getInputStream());
			int exitCode = running.waitFor();
			timer.cancel(false);

			if (cancelled) {
				throw new IOException("Transcription was cancelled");
			}
			if (timedOut.get()) {
				throw new IOException("Local transcription timed out");
			}
			if (exitCode != 0) {
				throw new IOException("On-device engine stopped (" + exitCode + "). Try again.");
			}
			return parseLastLine(output);
		} finally {
			if (timer != null) {
				timer.cancel(false);
			}
			synchronized (this) {
				active = null;
			}
			audio.delete();
		}
	}

	@Override
	public void cancel() {
		Process running;
		synchronized (this) {
			cancelled = true;
			running = active;
		}
		if (running != null) {
			running.destroy();
		}
	}

	private synchronized Process start(File audio) throws IOException {
		if (cancelled) {
			throw new IOException("Transcription was cancelled");
		}
		java.util.ArrayList<String> args = new java.util.ArrayList<>(java.util.Arrays.asList(needleBinary, "--model", modelFile, "--audio", audio.getAbsolutePath(), "--audio-language", language));
		if (depth >= 2) { args.add("--audio-depth"); args.add(Integer.toString(depth)); }
		if (threads > 0) { args.add("--threads"); args.add(Integer.toString(threads)); }
		if (keywordsPath != null) { args.add("--audio-keywords"); args.add(keywordsPath); }
		ProcessBuilder builder = new ProcessBuilder(args);
		builder.environment().put("NEEDLE_TELEMETRY", "0");
		builder.environment().put("DO_NOT_TRACK", "1");
		// Merged so a chatty stderr cannot fill its pipe and stall the process.
		builder.redirectErrorStream(true);
		active = builder.start();
		return active;
	}

	private static String readAll(InputStream in) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try {
			byte[] buffer = new byte[4096];
			int count;
			while ((count = in.read(buffer)) != -1) {
				bytes.write(buffer, 0, count);
			}
		} finally {
			in.close();
		}
		return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
	}

	/** Returns the trimmed "text" field of the last non-empty output line (JSON). */
	static String parseLastLine(String output) throws IOException {
		String last = null;
		for (String line : output.split("\n")) {
			if (!line.trim().isEmpty()) {
				last = line.trim();
			}
		}
		if (last == null || !last.startsWith("{")) {
			throw new IOException("needle printed no JSON result");
		}
		String text = new JsonFields(last).textField();
		if (text == null) {
			throw new IOException("needle returned no text");
		}
		return text.trim();
	}

	/** Minimal reader for one flat JSON object; returns its top-level "text" string. */
	private static final class JsonFields {
		private final String json;
		private int pos;

		JsonFields(String json) {
			this.json = json;
		}

		String textField() throws IOException {
			String text = null;
			skipSpace();
			expect('{');
			skipSpace();
			if (peek() == '}') {
				return null;
			}
			while (true) {
				skipSpace();
				String key = readString();
				skipSpace();
				expect(':');
				skipSpace();
				if ("text".equals(key) && peek() == '"') {
					text = readString();
				} else {
					skipValue();
				}
				skipSpace();
				char next = next();
				if (next == '}') {
					return text;
				}
				if (next != ',') {
					throw bad();
				}
			}
		}

		private void skipValue() throws IOException {
			char c = peek();
			if (c == '"') {
				readString();
			} else if (c == '{' || c == '[') {
				int depth = 0;
				do {
					c = peek();
					if (c == '"') {
						readString();
						continue;
					}
					pos++;
					if (c == '{' || c == '[') {
						depth++;
					} else if (c == '}' || c == ']') {
						depth--;
					}
				} while (depth > 0);
			} else {
				while (pos < json.length() && ",}] \t\r\n".indexOf(json.charAt(pos)) < 0) {
					pos++;
				}
			}
		}

		private String readString() throws IOException {
			expect('"');
			StringBuilder value = new StringBuilder();
			while (true) {
				char c = next();
				if (c == '"') {
					return value.toString();
				}
				if (c != '\\') {
					value.append(c);
					continue;
				}
				char escaped = next();
				switch (escaped) {
					case '"':
					case '\\':
					case '/':
						value.append(escaped);
						break;
					case 'b':
						value.append('\b');
						break;
					case 'f':
						value.append('\f');
						break;
					case 'n':
						value.append('\n');
						break;
					case 'r':
						value.append('\r');
						break;
					case 't':
						value.append('\t');
						break;
					case 'u':
						if (pos + 4 > json.length()) {
							throw bad();
						}
						try {
							value.append((char) Integer.parseInt(json.substring(pos, pos + 4), 16));
						} catch (NumberFormatException error) {
							throw bad();
						}
						pos += 4;
						break;
					default:
						throw bad();
				}
			}
		}

		private void skipSpace() {
			while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
				pos++;
			}
		}

		private char peek() throws IOException {
			if (pos >= json.length()) {
				throw bad();
			}
			return json.charAt(pos);
		}

		private char next() throws IOException {
			char c = peek();
			pos++;
			return c;
		}

		private void expect(char wanted) throws IOException {
			if (next() != wanted) {
				throw bad();
			}
		}

		private IOException bad() {
			return new IOException("Malformed needle JSON at offset " + pos);
		}
	}
}
