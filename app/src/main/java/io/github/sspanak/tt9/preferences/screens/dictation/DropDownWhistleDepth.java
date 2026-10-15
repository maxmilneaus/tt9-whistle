package io.github.sspanak.tt9.preferences.screens.dictation;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.dictation.DictationSettings;
import io.github.sspanak.tt9.preferences.custom.EnhancedDropDownPreference;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;

/**
 * How much of the on-device model to use. The labels carry the measured meaning, so the choice reads
 * as a tradeoff instead of a number: the two options that answer a measured question are named, and
 * the one depth that has not been measured says so.
 */
public class DropDownWhistleDepth extends EnhancedDropDownPreference {
	public static final String NAME = DictationSettings.KEY_DEPTH;

	public DropDownWhistleDepth(@NonNull Context context) { super(context); }
	public DropDownWhistleDepth(@NonNull Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
	public DropDownWhistleDepth(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); }
	public DropDownWhistleDepth(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) { super(context, attrs, defStyleAttr, defStyleRes); }

	@Override
	public EnhancedDropDownPreference populate(@NonNull SettingsStore settings) {
		for (int depth : DictationSettings.DEPTH_OPTIONS) {
			add(depth, getLabel(depth));
		}
		commitOptions();

		setDefaultValue(String.valueOf(DictationSettings.DEPTH_DEFAULT));
		setValue(String.valueOf(new DictationSettings(getContext()).getDepth()));

		return this;
	}

	private String getLabel(int depth) {
		return getContext().getString(switch (depth) {
			case DictationSettings.DEPTH_MIN -> R.string.dictation_depth_fast;
			case DictationSettings.DEPTH_FULL -> R.string.dictation_depth_accuracy;
			case 6 -> R.string.dictation_depth_six;
			default -> R.string.dictation_depth_balanced;
		});
	}

	@Override
	protected String getName() {
		return NAME;
	}
}
