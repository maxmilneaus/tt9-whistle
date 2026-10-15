package io.github.sspanak.tt9.preferences.screens.dictation;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.dictation.DictationSettings;
import io.github.sspanak.tt9.preferences.custom.EnhancedDropDownPreference;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;

public class DropDownWhistleThreads extends EnhancedDropDownPreference {
	public static final String NAME = DictationSettings.KEY_THREADS;

	public DropDownWhistleThreads(@NonNull Context context) { super(context); }
	public DropDownWhistleThreads(@NonNull Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
	public DropDownWhistleThreads(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); }
	public DropDownWhistleThreads(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) { super(context, attrs, defStyleAttr, defStyleRes); }

	@Override
	public EnhancedDropDownPreference populate(@NonNull SettingsStore settings) {
		for (int threads : DictationSettings.THREAD_OPTIONS) {
			add(threads, getLabel(threads));
		}
		commitOptions();

		setDefaultValue(String.valueOf(DictationSettings.THREADS_DEFAULT));
		setValue(String.valueOf(new DictationSettings(getContext()).getThreads()));

		return this;
	}

	private String getLabel(int threads) {
		if (threads == DictationSettings.THREADS_AUTO) {
			return getContext().getString(R.string.dictation_threads_auto);
		}

		if (threads == DictationSettings.THREADS_MAX) {
			return getContext().getString(R.string.dictation_threads_default, threads);
		}

		return threads == 1
			? getContext().getString(R.string.dictation_threads_one)
			: getContext().getString(R.string.dictation_threads_many, threads);
	}

	@Override
	protected String getName() {
		return NAME;
	}
}
