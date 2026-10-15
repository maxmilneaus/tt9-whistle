package io.github.sspanak.tt9.preferences.screens.dictation;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.sspanak.tt9.dictation.DictationSettings;

/**
 * The words the on-device engine should expect, saved as they are typed.
 *
 * <p>The runner wants one phrase per line, but this phone types on a numpad, so the field is a single
 * line and the words are separated by commas. {@link DictationSettings} turns that into the file.</p>
 */
public class DictationKeywordsPreference extends DictationTextPreference {
	public DictationKeywordsPreference(@NonNull Context context) { super(context); }
	public DictationKeywordsPreference(@NonNull Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
	public DictationKeywordsPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }
	public DictationKeywordsPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) { super(context, attrs, defStyleAttr, defStyleRes); }

	@Override
	protected void onTextChange() {
		new DictationSettings(getContext()).setKeywords(getText().toString());
	}
}
