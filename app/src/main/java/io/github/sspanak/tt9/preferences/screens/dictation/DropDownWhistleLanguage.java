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
 * The language the on-device model should listen for. The list is the model's own languages, not the
 * keyboard's, so a language the model cannot transcribe is never offered and never saved. English
 * comes first because it is the one that has been verified.
 */
public class DropDownWhistleLanguage extends EnhancedDropDownPreference {
	public static final String NAME = DictationSettings.KEY_LANGUAGE;

	public DropDownWhistleLanguage(@NonNull Context context) { super(context); }
	public DropDownWhistleLanguage(@NonNull Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
	public DropDownWhistleLanguage(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); }
	public DropDownWhistleLanguage(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) { super(context, attrs, defStyleAttr, defStyleRes); }

	@Override
	public EnhancedDropDownPreference populate(@NonNull SettingsStore settings) {
		for (String code : DictationSettings.SUPPORTED_LANGUAGES) {
			add(code, getLabel(code));
		}
		commitOptions();

		setDefaultValue(DictationSettings.LANGUAGE_ENGLISH);
		setValue(new DictationSettings(getContext()).getLanguage());

		return this;
	}

	private String getLabel(String code) {
		final int label = switch (code) {
			case "de" -> R.string.dictation_language_de;
			case "fr" -> R.string.dictation_language_fr;
			case "es" -> R.string.dictation_language_es;
			case "it" -> R.string.dictation_language_it;
			case "nl" -> R.string.dictation_language_nl;
			case "pl" -> R.string.dictation_language_pl;
			case DictationSettings.LANGUAGE_ENGLISH -> R.string.dictation_language_en;
			default -> 0;
		};

		return label == 0 ? code : getContext().getString(label);
	}

	@Override
	protected String getName() {
		return NAME;
	}
}
