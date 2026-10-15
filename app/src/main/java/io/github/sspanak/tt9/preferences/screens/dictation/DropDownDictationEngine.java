package io.github.sspanak.tt9.preferences.screens.dictation;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.dictation.DictationSettings;
import io.github.sspanak.tt9.preferences.custom.EnhancedDropDownPreference;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;

public class DropDownDictationEngine extends EnhancedDropDownPreference {
	public static final String NAME = DictationSettings.KEY_ENGINE;

	public DropDownDictationEngine(@NonNull Context context) { super(context); }
	public DropDownDictationEngine(@NonNull Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
	public DropDownDictationEngine(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); }
	public DropDownDictationEngine(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) { super(context, attrs, defStyleAttr, defStyleRes); }

	@Override
	public EnhancedDropDownPreference populate(@NonNull SettingsStore settings) {
		add(DictationSettings.ENGINE_WHISTLE, R.string.dictation_engine_whistle);
		add(DictationSettings.ENGINE_OPENCODE, getCloudLabel());
		add(DictationSettings.ENGINE_SYSTEM, R.string.dictation_engine_system);
		commitOptions();

		setDefaultValue(DictationSettings.ENGINE_WHISTLE);
		setValue(new DictationSettings(getContext()).getEngine());

		return this;
	}

	private int getCloudLabel() {
		return DictationSettings.isCloudAvailable(getContext())
			? R.string.dictation_engine_opencode
			: R.string.dictation_engine_opencode_no_network;
	}

	@Override
	protected String getName() {
		return NAME;
	}
}
