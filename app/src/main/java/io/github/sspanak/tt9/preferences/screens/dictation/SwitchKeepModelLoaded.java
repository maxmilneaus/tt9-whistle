package io.github.sspanak.tt9.preferences.screens.dictation;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.SwitchPreferenceCompat;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.dictation.DictationSettings;

/**
 * Whether the loaded on-device model stays in memory between dictations.
 *
 * <p>Keeping it makes the next dictation start immediately; releasing it when the keyboard closes
 * gives the memory back, at the cost of loading the model again before the next one. Keeping it is
 * the default, because the reload costs more than the memory it holds on a phone with room to spare.</p>
 */
public class SwitchKeepModelLoaded extends SwitchPreferenceCompat {
	public static final String NAME = DictationSettings.KEY_KEEP_WARM;

	public SwitchKeepModelLoaded(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes); init(context);
	}

	public SwitchKeepModelLoaded(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr); init(context);
	}

	public SwitchKeepModelLoaded(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs); init(context);
	}

	public SwitchKeepModelLoaded(@NonNull Context context) {
		super(context); init(context);
	}

	private void init(@NonNull Context context) {
		setKey(NAME);
		setTitle(R.string.dictation_keep_warm_title);
		setSummary(R.string.dictation_keep_warm_summary);
		setDefaultValue(DictationSettings.KEEP_WARM_DEFAULT);
		setChecked(new DictationSettings(context).getKeepWarm());
	}
}
