package io.github.sspanak.tt9.dictation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * DEBUG BUILDS ONLY (src/debug). Makes the next dictation read a stored WAV asset instead of the
 * microphone, so the emulator can test the voice key end to end without live audio.
 *
 *   adb -s emulator-5554 shell am broadcast -a io.github.sspanak.tt9.qin.DEBUG_DICTATE_CLIP \
 *       --es clip clips/latency-test.wav
 */
public class DebugClipReceiver extends BroadcastReceiver {
	public static final String ACTION = "io.github.sspanak.tt9.qin.DEBUG_DICTATE_CLIP";

	@Override
	public void onReceive(Context context, Intent intent) {
		if (intent == null || !ACTION.equals(intent.getAction())) {
			return;
		}
		String clip = intent.getStringExtra("clip");
		if (clip == null || clip.isEmpty()) {
			clip = "clips/latency-test.wav";
		}
		DictationEngine.debugClipAsset = clip;
		Log.i("TT9Dictation", "Debug clip armed: " + clip);
	}
}
