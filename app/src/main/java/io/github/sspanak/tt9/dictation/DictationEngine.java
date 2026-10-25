package io.github.sspanak.tt9.dictation;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import io.github.sspanak.tt9.BuildConfig;
import io.github.sspanak.tt9.util.Logger;

import androidx.core.content.ContextCompat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * On-device dictation: records 16 kHz mono PCM from the microphone, runs Cactus Whistle on it and
 * reports the transcript. One session at a time. All listener callbacks run on the main thread.
 *
 * Sessions are guarded by a session number: stop() ends recording and transcribes, cancel()
 * discards everything that is still in flight, and late results from an old session are dropped.
 */
public final class DictationEngine {
	public static final int SAMPLE_RATE_HZ = StoredClip.SAMPLE_RATE_HZ;
	public static final long MAX_RECORD_MS = 60_000L;
	static final int READ_BYTES = 3_200; // 100 ms of PCM16 mono
	/** Peaks below ~0.02 of full scale are treated as silence; Whistle hallucinates "Thank you." on silence. */
	static final int SILENCE_PEAK = 655;
	public static final String NEEDLE_LIB_NAME = "libneedle.so";
	public static final String MODEL_ASSET = "whistle/whistle.cact";
	static final String MODEL_FILE_NAME = "whistle.cact";

	/** Log tag; the value is kept stable so existing log filters keep matching. */
	private static final String LOG_TAG = "TT9Dictation";

	/**
	 * Debug-only hook (set by the debug build receiver): when non-null, the next session reads this
	 * stored asset WAV at real-time pace instead of the microphone. Never set in release builds.
	 */
	public static volatile String debugClipAsset = null;

	public interface Listener {
		void onRecordingStarted();
		void onTranscribing();
		void onResult(String text);
		void onNoSpeech();
		void onError(int code, String debugMessage);
	}

	private enum State { IDLE, RECORDING, TRANSCRIBING }

	private static DictationEngine instance;

	private final Context appContext;
	private final Handler main = new Handler(Looper.getMainLooper());
	private static ExecutorService sharedWorker;
	private final Object lock = new Object();

	private State state = State.IDLE;
	private long sessionId = 0;
	private volatile boolean stopRequested = false;
	private String sessionEngine;
	private ByteArrayOutputStream pcm = null;
	private Listener listener = null;
	private Transcriber activeTranscriber = null;

	private DictationEngine(Context context) {
		appContext = context.getApplicationContext();
	}

	private static synchronized ExecutorService worker() {
		if (sharedWorker == null) {
			sharedWorker = Executors.newSingleThreadExecutor();
		}
		return sharedWorker;
	}


	public static synchronized DictationEngine get(Context context) {
		if (instance == null) {
			instance = new DictationEngine(context);
		}
		return instance;
	}

	public boolean isBusy() {
		synchronized (lock) {
			return state != State.IDLE;
		}
	}

	public boolean isRecording() {
		synchronized (lock) {
			return state == State.RECORDING;
		}
	}

	/** Starts a session. Returns false (and reports via the listener) when it cannot start. */
	public boolean start(final Listener newListener) {
		return start(newListener, null);
	}

	/** Like start(), but reads a stored asset WAV (real-time pace) instead of the microphone when clipAsset is set. */
	public boolean start(final Listener newListener, final String testClipAsset) {
		final long session;
		synchronized (lock) {
			if (state != State.IDLE) {
				report(newListener, SpeechCodes.BUSY, "A dictation session is already running.");
				return false;
			}
			if (testClipAsset == null && !hasMicPermission()) {
				report(newListener, SpeechCodes.NO_PERMISSION, "Microphone permission is missing.");
				return false;
			}
			state = State.RECORDING;
			sessionEngine = new DictationSettings(appContext).getEngine();
			sessionId++;
			session = sessionId;
			stopRequested = false;
			pcm = new ByteArrayOutputStream();
			listener = newListener;
		}

		final String clipAsset = BuildConfig.DEBUG ? (testClipAsset != null ? testClipAsset : debugClipAsset) : null;
		debugClipAsset = null;

		Thread capture = new Thread(new Runnable() {
			@Override
			public void run() {
				capture(session, clipAsset);
			}
		}, "tt9-dictation-capture");
		capture.setDaemon(true);
		capture.start();

		main.post(new Runnable() {
			@Override
			public void run() {
				if (isLive(session)) {
					newListener.onRecordingStarted();
				}
			}
		});
		return true;
	}

	/** Ends recording and transcribes. While transcribing, this cancels the transcription. */
	public void stop() {
		boolean cancelNow;
		synchronized (lock) {
			cancelNow = state == State.TRANSCRIBING;
			if (state == State.RECORDING) {
				stopRequested = true;
			}
		}
		if (cancelNow) {
			cancel();
		}
	}

	/** Discards the current session. Nothing is delivered to the listener afterwards. */
	public void cancel() {
		Transcriber toCancel;
		synchronized (lock) {
			sessionId++;
			stopRequested = true;
			toCancel = activeTranscriber;
			activeTranscriber = null;
			state = State.IDLE;
			pcm = null;
			listener = null;
		}
		if (toCancel != null) {
			toCancel.cancel();
		}
	}

	private void capture(final long session, String clipAsset) {
		AudioRecord recorder = null;
		InputStream clip = null;
		try {
			if (clipAsset != null) {
				android.util.Log.i(LOG_TAG, "Reading stored clip (debug/test only): " + clipAsset);
				byte[] clipPcm = StoredClip.readPcm16Mono(appContext.getAssets().open(clipAsset));
				clip = new ByteArrayInputStream(clipPcm);
			} else {
				int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
				recorder = new AudioRecord(
					MediaRecorder.AudioSource.MIC,
					SAMPLE_RATE_HZ,
					AudioFormat.CHANNEL_IN_MONO,
					AudioFormat.ENCODING_PCM_16BIT,
					Math.max(minBuffer, READ_BYTES * 4)
				);
				if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
					throw new IOException("Microphone is not available.");
				}
				recorder.startRecording();
				android.util.Log.i(LOG_TAG, "Recording from microphone (MIC source, " + SAMPLE_RATE_HZ + " Hz, mono, PCM16)");
			}

			byte[] buffer = new byte[READ_BYTES];
			long startedAt = System.currentTimeMillis();
			while (true) {
				if (!isLive(session) || stopRequested) {
					break;
				}
				if (System.currentTimeMillis() - startedAt >= MAX_RECORD_MS) {
					break;
				}

				int count;
				if (clip != null) {
					count = readFully(clip, buffer);
					if (count <= 0) {
						break; // end of the stored clip acts like the user pressing stop
					}
					Thread.sleep(100);
				} else {
					count = recorder.read(buffer, 0, buffer.length);
					if (count < 0) {
						throw new IOException("Microphone read failed (" + count + ").");
					}
				}

				synchronized (lock) {
					if (sessionId == session && pcm != null) {
						pcm.write(buffer, 0, count);
					}
				}
			}
		} catch (Exception error) {
			fail(session, SpeechCodes.AUDIO, "Recording failed: " + error.getMessage());
			return;
		} finally {
			if (recorder != null) {
				try {
					recorder.stop();
				} catch (Exception ignore) {
				}
				recorder.release();
			}
			if (clip != null) {
				try {
					clip.close();
				} catch (IOException ignore) {
				}
			}
		}

		finishRecording(session);
	}

	private void finishRecording(final long session) {
		final byte[] audio;
		final Listener current;
		synchronized (lock) {
			if (!isLive(session) || state != State.RECORDING) {
				return;
			}
			audio = pcm != null ? pcm.toByteArray() : new byte[0];
			pcm = null;
			current = listener;
			state = State.TRANSCRIBING;
		}

		if (StoredClip.peak(audio) < SILENCE_PEAK) {
			finishWithNoSpeech(session, current);
			return;
		}

		main.post(new Runnable() {
			@Override
			public void run() {
				if (isLive(session)) {
					current.onTranscribing();
				}
			}
		});

		final byte[] wav = StoredClip.encodeWav(audio, SAMPLE_RATE_HZ);
		worker().submit(new Runnable() {
			@Override
			public void run() {
				transcribe(session, wav, current);
			}
		});
	}

	private void transcribe(final long session, byte[] wav, Listener current) {
		try {
			String provider;
			synchronized (lock) { provider = sessionEngine; }
			Transcriber transcriber = DictationSettings.ENGINE_OPENCODE.equals(provider)
				? new OpenCodeTranscriber(appContext) : createWhistleTranscriber();
			synchronized (lock) {
				if (!isLive(session)) {
					return;
				}
				activeTranscriber = transcriber;
			}

			String text = transcriber.transcribe(wav);
			final String trimmed = text == null ? "" : text.trim();
			if (trimmed.isEmpty()) {
				finishWithNoSpeech(session, current);
				return;
			}
			finishWithResult(session, trimmed, current);
		} catch (Exception error) {
			int code = SpeechCodes.ENGINE;
			if (error instanceof OpenCodeTranscriber.ApiException) {
				OpenCodeTranscriber.ApiException.Reason reason = ((OpenCodeTranscriber.ApiException) error).reason;
				if (reason == OpenCodeTranscriber.ApiException.Reason.NO_TEXT) { finishWithNoSpeech(session, current); return; }
				if (reason == OpenCodeTranscriber.ApiException.Reason.TIMEOUT) code = android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT;
				if (reason == OpenCodeTranscriber.ApiException.Reason.NETWORK) code = android.speech.SpeechRecognizer.ERROR_NETWORK;
			}
			fail(session, code, error.getMessage() == null ? "Could not transcribe. Try again." : error.getMessage());
		}
	}

	Transcriber createWhistleTranscriber() throws IOException {
		return new OnDeviceTranscriber();
	}


	/**
	 * On-device dictation. Uses the warm prepared runner when one is already holding the model,
	 * and otherwise falls back to the per-call runner that starts the engine for this recording.
	 * Either way the transcript is the same; only the wait changes.
	 */
	private final class OnDeviceTranscriber implements Transcriber {
		private volatile Transcriber active;

		@Override
		public String transcribe(byte[] wav) throws Exception {
			final String binary = appContext.getApplicationInfo().nativeLibraryDir + "/" + NEEDLE_LIB_NAME;
			if (!new File(binary).canExecute()) {
				throw new IOException("This build has no on-device engine for this CPU.");
			}

			final File model = ensureModelFile();
			final String modelPath = model.getAbsolutePath();
			final DictationSettings options = new DictationSettings(appContext);
			final int depth = options.getDepth();
			final int threads = options.getThreads();
			final String language = options.getLanguage();
			final String keywords = keywordPath(appContext);

			if (PreparedWhistleTranscriber.isReady(appContext, binary, modelPath, depth, threads, keywords)) {
				Transcriber warm = new PreparedWhistleTranscriber(appContext, binary, modelPath, depth, threads, keywords);
				active = warm;
				try {
					return warm.transcribe(wav);
				} catch (TranscriptionCancelledException cancelled) {
					// The caller abandoned this recording. Retrying it on the per-call engine would
					// spend a whole model load producing a result nobody is waiting for any more.
					throw cancelled;
				} catch (IOException warmFailed) {
					// The runner replaces itself as it is used, so a failure here is a real fault
					// rather than a stale slot. One per-call retry still beats an error message.
					Logger.d(LOG_TAG, "Warm engine failed, retrying per call. " + warmFailed.getMessage());
				} finally {
					active = null;
				}
			}

			Transcriber perCall = new LocalWhistleTranscriber(appContext.getCacheDir(), binary, modelPath, depth, threads, language, keywords);
			active = perCall;
			try {
				String text = perCall.transcribe(wav);
				prepareNext(binary, modelPath, depth, threads, keywords);
				return text;
			} finally {
				active = null;
			}
		}

		@Override
		public void cancel() {
			Transcriber running = active;
			if (running != null) {
				running.cancel();
			}
		}
	}


	/** Starts an idle warm engine for the next dictation. Never blocks the caller. */
	private void prepareNext(final String binary, final String modelPath, final int depth, final int threads, final String keywords) {
		worker().submit(new Runnable() {
			@Override
			public void run() {
				try {
					PreparedWhistleTranscriber.prepare(appContext, binary, modelPath, depth, threads, keywords);
				} catch (Exception ignored) {
					// The warm slot is an optimisation; the per-call engine still works without it.
				}
			}
		});
	}


	/**
	 * The --audio-keywords file for the list the user has typed, or null when there is none. The
	 * file is written off the main thread, because writing it is the only part of this that touches
	 * the disk. A failure is not fatal: the transcript is simply produced without hints.
	 */
	private static String keywordPath(Context context) {
		if (context == null) {
			return null;
		}
		try {
			File keywords = DictationSettings.writeKeywordsFile(context);
			return keywords == null ? null : keywords.getAbsolutePath();
		} catch (IOException error) {
			Logger.d(LOG_TAG, "Could not write the keyword list. " + error.getMessage());
			return null;
		}
	}
	/**
	 * Frees the idle warm engine. Safe to call at any time, and deliberately does not wait: stopping
	 * the runner kills a process and waits for it to die, and the callers are IME lifecycle callbacks
	 * on the main thread. Waiting there holds up hiding the keyboard, so the work is handed to the
	 * same serial worker that starts the runner, which also keeps the two from interleaving.
	 */
	public static void releaseResources() {
		worker().submit(new Runnable() {
			@Override
			public void run() {
				PreparedWhistleTranscriber.release();
			}
		});
	}



	/**
	 * Loads the on-device engine in the background, so the first dictation of a session does not
	 * pay for the model load. Best effort: everything still works when this does nothing.
	 */
	public static void warmUpAsync(Context context) {
		if (context == null) {
			return;
		}
		final Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
		worker().submit(new Runnable() {
			@Override
			public void run() {
				try {
					DictationSettings options = new DictationSettings(app);
					if (!DictationSettings.ENGINE_WHISTLE.equals(options.getEngine())) {
						return;
					}
					String binary = app.getApplicationInfo().nativeLibraryDir + "/" + NEEDLE_LIB_NAME;
					if (!new File(binary).canExecute()) {
						return;
					}
					File model = get(app).ensureModelFile();
					PreparedWhistleTranscriber.prepare(app, binary, model.getAbsolutePath(), options.getDepth(), options.getThreads(), keywordPath(app));
				} catch (Exception ignored) {
					// Warming is an optimisation; the per-call engine still works without it.
				}
			}
		});
	}

	/** Copies the bundled model out of the APK once; needle needs a real file path. */
	private File ensureModelFile() throws IOException {
		File dir = new File(appContext.getFilesDir(), "whistle");
		File target = new File(dir, MODEL_FILE_NAME);
		if (target.isFile() && target.length() > 0) {
			return target;
		}
		if (!dir.isDirectory() && !dir.mkdirs()) {
			throw new IOException("Cannot create the model folder.");
		}
		File partial = new File(dir, MODEL_FILE_NAME + ".part");
		InputStream in = appContext.getAssets().open(MODEL_ASSET);
		try (FileOutputStream out = new FileOutputStream(partial)) {
			byte[] chunk = new byte[64 * 1024];
			int count;
			while ((count = in.read(chunk)) != -1) {
				out.write(chunk, 0, count);
			}
		} finally {
			in.close();
		}
		if (!partial.renameTo(target)) {
			throw new IOException("Cannot install the speech model.");
		}
		return target;
	}

	private void finishWithResult(final long session, final String text, final Listener current) {
		synchronized (lock) {
			if (!isLive(session)) {
				return;
			}
			state = State.IDLE;
			activeTranscriber = null;
			listener = null;
		}
		main.post(new Runnable() {
			@Override
			public void run() {
				if (isCurrent(session)) current.onResult(text);
			}
		});
	}

	private void finishWithNoSpeech(final long session, final Listener current) {
		synchronized (lock) {
			if (!isLive(session)) {
				return;
			}
			state = State.IDLE;
			activeTranscriber = null;
			listener = null;
		}
		main.post(new Runnable() {
			@Override
			public void run() {
				if (isCurrent(session)) current.onNoSpeech();
			}
		});
	}

	private void fail(final long session, final int code, final String debugMessage) {
		final Listener current;
		synchronized (lock) {
			if (!isLive(session)) {
				return;
			}
			current = listener;
			state = State.IDLE;
			activeTranscriber = null;
			pcm = null;
			listener = null;
		}
		if (current != null) {
			main.post(new Runnable() {
				@Override
				public void run() {
					if (isCurrent(session)) current.onError(code, debugMessage);
				}
			});
		}
	}

	private void report(final Listener target, final int code, final String debugMessage) {
		main.post(new Runnable() {
			@Override
			public void run() {
				target.onError(code, debugMessage);
			}
		});
	}

	private boolean isLive(long session) {
		synchronized (lock) {
			return sessionId == session && state != State.IDLE;
		}
	}

	private boolean isCurrent(long session) {
		synchronized (lock) {
			return sessionId == session;
		}
	}

	private boolean hasMicPermission() {
		return ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
			== PackageManager.PERMISSION_GRANTED;
	}

	private static int readFully(InputStream in, byte[] buffer) throws IOException {
		int total = 0;
		while (total < buffer.length) {
			int count = in.read(buffer, total, buffer.length - total);
			if (count < 0) {
				break;
			}
			total += count;
		}
		return total;
	}

	/** SpeechRecognizer-compatible error codes used by the TT9 voice UI. */
	public static final class SpeechCodes {
		public static final int AUDIO = android.speech.SpeechRecognizer.ERROR_AUDIO;
		public static final int ENGINE = 201; // VoiceInputError.ERROR_ON_DEVICE
		public static final int NO_PERMISSION = android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS;
		public static final int BUSY = android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY;

		private SpeechCodes() {
		}
	}
}
