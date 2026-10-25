package io.github.sspanak.tt9.dictation;

import android.content.Context;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Warm Whistle transcriber: keeps one idle needle runner holding the model, and feeds it the
 * finished recording through an app-private FIFO when a dictation stops.
 *
 * The runner loads the model before it waits for a writer, so the delay after the recording ends
 * covers decoding only. See tests/whistle/README.md.
 *
 * The public Needle C API has no depth or thread entry point, so the warm engine is the shipped
 * runner driven with the same flags LocalWhistleTranscriber uses. There is no native worker and
 * no second model runtime.
 *
 * The warm configuration is depth, threads and language. The language comes from DictationSettings,
 * which keeps it inside the seven languages the pinned model supports, and it is part of the match
 * key and of the runner's --audio-language flag. Changing any of them makes the idle runner stale:
 * the next transcribe() reports not ready and the caller falls back to the per-call path until a
 * fresh prepare() matches.
 *
 * Depth and threads are clamped to what the retained model really accepts (0 or 2..8 rungs, and 0 or
 * 1..4 threads) and the language to the seven it knows, so a stale or foreign settings value can
 * never make the prepared runner load something it does not have. A depth below the minimum reads as
 * the whole ladder, like DictationSettings.clampDepth, because that is what every build made before
 * the depths were measured meant by it.
 *
 * Delivery must be byte-exact: a short write on the non-blocking pipe still exits 0 and still
 * prints valid JSON, but the transcript is garbage. Every write is therefore retried until the
 * whole WAV is in the pipe, and any failure inside the deadline throws a sanitized IOException so
 * the caller can fall back to the per-call path. cancel() is a one-way latch, so an instance that
 * was cancelled never starts another job. Nothing here logs audio or transcript text.
 */
public final class PreparedWhistleTranscriber implements Transcriber {
	/** Model default (full ladder). The runner also accepts 8 for the same thing. */
	public static final int FULL_DEPTH = 0;
	/** Engine default thread count. */
	public static final int AUTO_THREADS = 0;
	public static final int MIN_DEPTH = 2;
	/** The retained whistle.cact accepts at most 8 ladder rungs. */
	public static final int MAX_DEPTH = 8;
	public static final int MAX_THREADS = 4;

	static final long DEADLINE_MS = 30_000L;
	static final int FIFO_MODE = 0600;
	static final int WRITE_CHUNK = 32 * 1024;

	private static final long POLL_MS = 5L;
	private static final long REAP_MS = 1_000L;
	/** LocalWhistleTranscriber reports an empty transcript with this wording; it owns the parser. */
	private static final String NO_SPEECH_MARKER = "no text";

	private static final Object LOCK = new Object();
	private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "tt9-whistle-watchdog");
			thread.setDaemon(true);
			return thread;
		}
	});

	private static Context appContext;
	private static Process idleProcess;
	private static String idleFifoPath;
	private static String idleKey;
	private static volatile boolean released;

	private final String binary;
	private final String model;
	private final int depth;
	private final int threads;
	private final String language;
	/** Path to the --audio-keywords file, or null when the user has set no words. */
	private final String keywords;
	private final String key;
	private final AtomicBoolean cancelled = new AtomicBoolean(false);
	private volatile Process activeProcess;

	public PreparedWhistleTranscriber(Context context, String binary, String model, int depth, int threads, String keywords) {
		this(binary, model, depth, threads, language(context), keywords);
	}

	/** Explicit settings, used by the off-device protocol harness. */
	PreparedWhistleTranscriber(String binary, String model, int depth, int threads, String language) {
		this(binary, model, depth, threads, language, null);
	}
	
	/** Explicit settings plus the hint-word file, used by the keyboard. */
	PreparedWhistleTranscriber(String binary, String model, int depth, int threads, String language, String keywords) {
		this.binary = binary;
		this.model = model;
		this.depth = clampDepth(depth);
		this.threads = clampThreads(threads);
		this.language = clampLanguage(language);
		this.keywords = keywords;
		this.key = key(binary, model, this.depth, this.threads, this.language, this.keywords);
	}

	/**
	 * Starts one idle runner for the current settings if the warm slot is empty, dead or configured
	 * differently. Cheap and non-blocking: it creates the FIFO and starts the process, and never
	 * opens the pipe. Call it from a background thread, before the recording starts.
	 */
	public static void prepare(Context context, String binary, String model, int depth, int threads, String keywords) throws IOException {
		if (context == null) {
			throw new IllegalArgumentException("context == null");
		}
		int wantedDepth = clampDepth(depth);
		int wantedThreads = clampThreads(threads);
		String wantedLanguage = language(context);
		String wanted = key(binary, model, wantedDepth, wantedThreads, wantedLanguage, keywords);

		synchronized (LOCK) {
			released = false;
			if (idleProcess != null && wanted.equals(idleKey) && isRunning(idleProcess)) {
				return;
			}
			stopIdleLocked();

			File cacheDir = context.getCacheDir();
			if (cacheDir == null) {
				throw new IOException("The warm engine has no cache directory.");
			}
			if (cacheDir.isDirectory() && !cacheDir.canWrite()) {
				throw new IOException("The warm engine cannot write its pipe directory.");
			}
			Context application = context.getApplicationContext();
			appContext = application == null ? context : application;

			File fifo = new File(cacheDir, "whistle-" + Long.toHexString(System.nanoTime()) + ".fifo");
			try {
				Os.mkfifo(fifo.getAbsolutePath(), FIFO_MODE);
			} catch (ErrnoException error) {
				throw new IOException("The warm engine cannot create its pipe.", error);
			}
			try {
				Process process = start(binary, model, fifo.getAbsolutePath(), wantedDepth, wantedThreads, wantedLanguage, keywords);
				idleProcess = process;
				idleFifoPath = fifo.getAbsolutePath();
				idleKey = wanted;
			} catch (IOException error) {
				fifo.delete();
				throw error;
			}
		}
	}

	/**
	 * True when an idle runner for these settings is alive and ready to take a recording. Cheap and
	 * thread-safe, and it never starts anything: it only reports on the slot prepare() filled.
	 */
	public static boolean isReady(Context context, String binary, String model, int depth, int threads, String keywords) {
		if (context == null) {
			throw new IllegalArgumentException("context == null");
		}
		String wanted = key(binary, model, clampDepth(depth), clampThreads(threads), language(context), keywords);

		synchronized (LOCK) {
			return !released
				&& idleProcess != null
				&& wanted.equals(idleKey)
				&& isRunning(idleProcess);
		}
	}

	/** Kills the idle runner and removes its pipe, for low-memory. Safe to call at any time. */
	public static void release() {
		synchronized (LOCK) {
			released = true;
			stopIdleLocked();
		}
	}

	/**
	 * Converts the finished recording into text. Blocking, one job at a time. The instance stays
	 * cancelled once cancel() has been called, so a later call never silently starts a second job.
	 */
	@Override
	public String transcribe(byte[] wav) throws Exception {
		if (wav == null || wav.length == 0) {
			throw new IOException("The recording is empty.");
		}
		if (cancelled.get()) {
			throw new TranscriptionCancelledException();
		}

		final Process process;
		final String fifoPath;
		synchronized (LOCK) {
			process = idleProcess;
			fifoPath = idleFifoPath;
			if (process == null || fifoPath == null || !key.equals(idleKey) || !isRunning(process)) {
				stopIdleLocked();
				throw new IOException("The warm engine is not ready.");
			}
			idleProcess = null;
			idleFifoPath = null;
			idleKey = null;
		}
		activeProcess = process;

		final long deadline = System.currentTimeMillis() + DEADLINE_MS;
		final AtomicBoolean timedOut = new AtomicBoolean(false);
		ScheduledFuture<?> watchdog = WATCHDOG.schedule(new Runnable() {
			@Override
			public void run() {
				timedOut.set(true);
				kill(process);
			}
		}, DEADLINE_MS, TimeUnit.MILLISECONDS);

		try {
			feed(fifoPath, wav, process, deadline);
			String output = readOutput(process);
			int exitCode = waitForExit(process, deadline);
			if (cancelled.get()) {
				throw new TranscriptionCancelledException();
			}
			if (timedOut.get()) {
				throw new IOException("The on-device engine did not finish in time.");
			}
			if (exitCode != 0) {
				throw new IOException("The on-device engine stopped with an error.");
			}
			return transcript(output);
		} finally {
			watchdog.cancel(false);
			kill(process);
			activeProcess = null;
			new File(fifoPath).delete();
			prepareReplacement();
		}
	}

	@Override
	public void cancel() {
		cancelled.set(true);
		Process process = activeProcess;
		if (process != null) {
			// Ask the process to die now; the transcribing thread reaps it and cleans the pipe.
			process.destroy();
		}
	}

	/** Writes the complete WAV into the pipe and closes it, which is the runner's EOF. */
	private void feed(String fifoPath, byte[] wav, Process process, long deadline) throws Exception {
		FileDescriptor pipe = openWriter(fifoPath, process, deadline);
		try {
			int offset = 0;
			while (offset < wav.length) {
				if (cancelled.get()) {
					throw new TranscriptionCancelledException();
				}
				int count = Math.min(WRITE_CHUNK, wav.length - offset);
				int written;
				try {
					written = Os.write(pipe, wav, offset, count);
				} catch (ErrnoException error) {
					if (error.errno == OsConstants.EAGAIN) {
						if (System.currentTimeMillis() > deadline) {
							throw new IOException("The on-device engine stopped reading the recording.");
						}
						Thread.sleep(POLL_MS);
						continue;
					}
					throw new IOException("The on-device engine closed the recording pipe.", error);
				}
				if (written <= 0) {
					if (System.currentTimeMillis() > deadline) {
						throw new IOException("The on-device engine stopped reading the recording.");
					}
					Thread.sleep(POLL_MS);
					continue;
				}
				offset += written;
			}
		} finally {
			try {
				Os.close(pipe);
			} catch (ErrnoException ignored) {
				// The pipe is closed on every path; a failure here changes nothing.
			}
		}
	}

	private FileDescriptor openWriter(String fifoPath, Process process, long deadline) throws Exception {
		while (true) {
			if (cancelled.get()) {
				throw new TranscriptionCancelledException();
			}
			if (!isRunning(process)) {
				throw new IOException("The warm engine stopped before the recording arrived.");
			}
			try {
				return Os.open(fifoPath, OsConstants.O_WRONLY | OsConstants.O_NONBLOCK, 0);
			} catch (ErrnoException error) {
				if (error.errno != OsConstants.ENXIO) {
					throw new IOException("The warm engine pipe cannot be opened.", error);
				}
			}
			if (System.currentTimeMillis() > deadline) {
				throw new IOException("The warm engine did not open its pipe in time.");
			}
			Thread.sleep(POLL_MS);
		}
	}

	/** Reads everything the runner prints; a watchdog kill ends the read. */
	private static String readOutput(Process process) {
		InputStream stream = process.getInputStream();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		try {
			while (true) {
				int count = stream.read(buffer);
				if (count == -1) {
					break;
				}
				bytes.write(buffer, 0, count);
			}
		} catch (IOException ignored) {
			// Killed by the deadline or by cancel(); whatever arrived is parsed below.
		} finally {
			try {
				stream.close();
			} catch (IOException ignored) {
				// Nothing to recover from a closed process stream.
			}
		}
		return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
	}

	private static int waitForExit(Process process, long deadline) {
		long giveUp = deadline + REAP_MS;
		while (System.currentTimeMillis() < giveUp) {
			try {
				return process.exitValue();
			} catch (IllegalThreadStateException running) {
				if (System.currentTimeMillis() > deadline) {
					kill(process);
				}
				try {
					Thread.sleep(POLL_MS);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return -1;
				}
			}
		}
		kill(process);
		return -1;
	}

	/** Turns the runner's output into text; an empty transcript is not an error. */
	static String transcript(String output) throws IOException {
		try {
			return LocalWhistleTranscriber.parseLastLine(output);
		} catch (IOException error) {
			String message = error.getMessage();
			if (message != null && message.contains(NO_SPEECH_MARKER)) {
				return "";
			}
			throw new IOException("The on-device engine returned no usable result.");
		}
	}

	private void prepareReplacement() {
		Context context = appContext;
		if (context == null) {
			return;
		}
		// Held together with release() so a release cannot be undone by a replacement: either
		// release() marks the slot released first and this returns, or this refills the slot and
		// release() then kills the runner it just started.
		synchronized (LOCK) {
			if (released) {
				return;
			}
			try {
				prepare(context, binary, model, depth, threads, keywords);
			} catch (IOException error) {
				// The next dictation falls back to the per-call path; typing is unaffected.
			}
		}
	}

	private static Process start(String binary, String model, String fifoPath, int depth, int threads, String language, String keywords) throws IOException {
		ProcessBuilder builder = new ProcessBuilder(arguments(binary, model, fifoPath, depth, threads, language, keywords));
		builder.environment().put("NEEDLE_TELEMETRY", "0");
		builder.environment().put("DO_NOT_TRACK", "1");
		// Merged so a chatty stderr cannot fill its pipe and stall the process.
		builder.redirectErrorStream(true);
		return builder.start();
	}

	/**
	 * The runner's flags, in the order LocalWhistleTranscriber uses. The clamps are applied here as
	 * well as in the constructor, so neither path can hand the runner a value the model does not have.
	 */
	static List<String> arguments(String binary, String model, String audioPath, int depth, int threads, String language, String keywords) {
		int wantedDepth = clampDepth(depth);
		int wantedThreads = clampThreads(threads);

		List<String> arguments = new ArrayList<>();
		arguments.add(binary);
		arguments.add("--model");
		arguments.add(model);
		arguments.add("--audio");
		arguments.add(audioPath);
		arguments.add("--audio-language");
		arguments.add(clampLanguage(language));
		if (wantedDepth >= MIN_DEPTH) {
			arguments.add("--audio-depth");
			arguments.add(Integer.toString(wantedDepth));
		}
		if (wantedThreads > 0) {
			arguments.add("--threads");
			arguments.add(Integer.toString(wantedThreads));
		}
		if (keywords != null) {
			arguments.add("--audio-keywords");
			arguments.add(keywords);
		}

		return arguments;
	}

	private static void stopIdleLocked() {
		Process process = idleProcess;
		String path = idleFifoPath;
		idleProcess = null;
		idleFifoPath = null;
		idleKey = null;
		kill(process);
		if (path != null) {
			new File(path).delete();
		}
	}

	private static void kill(Process process) {
		if (process == null) {
			return;
		}
		process.destroy();
		if (waitForDeath(process, REAP_MS)) {
			return;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			process.destroyForcibly();
		} else {
			process.destroy();
		}
		waitForDeath(process, REAP_MS);
	}

	private static boolean waitForDeath(Process process, long timeoutMs) {
		long giveUp = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < giveUp) {
			if (!isRunning(process)) {
				return true;
			}
			try {
				Thread.sleep(POLL_MS);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return !isRunning(process);
			}
		}
		return !isRunning(process);
	}

	private static boolean isRunning(Process process) {
		if (process == null) {
			return false;
		}
		try {
			process.exitValue();
			return false;
		} catch (IllegalThreadStateException running) {
			return true;
		}
	}

	/** The model language to send, always one of the seven the pinned model supports. */
	private static String language(Context context) {
		if (context == null) {
			throw new IllegalArgumentException("context == null");
		}
		return clampLanguage(new DictationSettings(context).getLanguage());
	}

	/**
	 * Moves a depth into what the retained model accepts: 0 (no flag, the whole ladder) or 2..8. A
	 * value below the minimum reads as the whole ladder, like DictationSettings.clampDepth, so a
	 * stale or foreign value can never make the warm engine quieter than the per-call one.
	 */
	static int clampDepth(int depth) {
		if (depth < MIN_DEPTH) {
			return FULL_DEPTH;
		}
		return Math.min(depth, MAX_DEPTH);
	}

	/** 0 keeps meaning "let the runner decide"; anything above the documented ceiling is capped. */
	static int clampThreads(int threads) {
		if (threads <= AUTO_THREADS) {
			return AUTO_THREADS;
		}
		return Math.min(threads, MAX_THREADS);
	}

	/** The pinned model only knows these seven; another one would only fail once it is run. */
	static String clampLanguage(String language) {
		return DictationSettings.isSupportedLanguage(language) ? language : DictationSettings.LANGUAGE_ENGLISH;
	}

	private static String key(String binary, String model, int depth, int threads, String language, String keywords) {
		return binary + "|" + model + "|" + depth + "|" + threads + "|" + language + "|" + (keywords == null ? "-" : keywords);
	}
}

