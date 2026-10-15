package io.github.sspanak.tt9.dictation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * DEBUG BUILDS ONLY (src/debug). Presses the keyboard's voice key from adb, so a test can start and
 * stop dictation without touching the physical keypad or the on-screen keys.
 *
 *   adb -s emulator-5554 shell am broadcast -a io.github.sspanak.tt9.qin.DEBUG_VOICE_KEY \
 *       -n io.github.sspanak.tt9.qin/io.github.sspanak.tt9.dictation.DebugVoiceKeyReceiver
 *
 * It calls the real voice key (DebugHooks.voiceKey), so it follows exactly the same path as a key
 * press. Nothing is stored: the field is null until the keyboard assigns it, which only a debug
 * build does.
 */
public class DebugVoiceKeyReceiver extends BroadcastReceiver {
	public static final String ACTION = "io.github.sspanak.tt9.qin.DEBUG_VOICE_KEY";

	@Override
	public void onReceive(Context context, Intent intent) {
		if (intent == null || !ACTION.equals(intent.getAction())) {
			return;
		}

		final Runnable voiceKey = DebugHooks.voiceKey;
		if (voiceKey == null) {
			Log.w("TT9Dictation", "Debug voice key ignored: the keyboard has not assigned it yet.");
			return;
		}

		Log.i("TT9Dictation", "Debug voice key pressed.");
		new Handler(Looper.getMainLooper()).post(voiceKey);
	}
}

