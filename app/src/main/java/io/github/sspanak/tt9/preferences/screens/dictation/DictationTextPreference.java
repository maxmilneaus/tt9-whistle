package io.github.sspanak.tt9.preferences.screens.dictation;

import android.content.Context;
import android.text.InputType;
import android.util.AttributeSet;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceViewHolder;

import io.github.sspanak.tt9.preferences.items.TextInputPreference;

/**
 * One editable line of the cloud (OpenCode) configuration. The preference key decides what is being
 * edited, so a single class covers the endpoint, the model, the cleanup prompt and the API key.
 *
 * <p>Nothing here is persisted by the preference framework. The values are read from and written
 * through the cloud settings object, which owns the encryption and the fingerprint.</p>
 */
public class DictationTextPreference extends TextInputPreference {
	public static final String KEY_ENDPOINT = "dictation_opencode_endpoint";
	public static final String KEY_MODEL = "dictation_opencode_model";
	public static final String KEY_PROMPT = "dictation_opencode_prompt";
	public static final String KEY_API_KEY = "dictation_opencode_api_key";

	/** How many lines the cleanup prompt gets before it starts scrolling. */
	private static final int PROMPT_MAX_LINES = 4;


	public DictationTextPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);
		init();
	}
	public DictationTextPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		init();
	}
	public DictationTextPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		init();
	}
	public DictationTextPreference(@NonNull Context context) {
		super(context);
		init();
	}


	private void init() {
		setPersistent(false);
	}


	@Override
	public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
		super.onBindViewHolder(holder);

		final EditText textField = getTextField();
		if (textField == null) {
			return;
		}

		if (KEY_API_KEY.equals(getKey())) {
			// The saved key is never displayed. The fingerprint row next to this field is the proof that it was stored.
			textField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
			textField.setText("");
		} else if (KEY_PROMPT.equals(getKey())) {
			// A cleanup prompt is a sentence or two, so it needs more than the single stock line.
			textField.setSingleLine(false);
			textField.setMaxLines(PROMPT_MAX_LINES);
			textField.setInputType(
				InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
			);
		}
	}


	@NonNull
	public String getValue() {
		return getText().toString().trim();
	}


	public void clearValue() {
		setText("");
	}


	/**
	 * Shows or clears a validation message on this row. The screen cannot reach the superclass helper,
	 * because it is protected and lives in another package.
	 */
	public void setFieldError(@Nullable String error) {
		setError(error == null || error.isEmpty() ? null : error);
	}
}
