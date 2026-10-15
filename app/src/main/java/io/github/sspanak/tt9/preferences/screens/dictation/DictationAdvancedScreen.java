package io.github.sspanak.tt9.preferences.screens.dictation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import java.util.Locale;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.dictation.DictationSettings;
import io.github.sspanak.tt9.dictation.OpenCodeSettings;
import io.github.sspanak.tt9.preferences.PreferencesActivity;
import io.github.sspanak.tt9.preferences.screens.BaseScreenFragment;

/**
 * The cloud engine's connection details, on their own screen because they are set up once and then
 * left alone. Edits are held until Save is pressed.
 *
 * <p>The key is never read back into the screen and never shown. An empty key field means "keep the
 * one that is already stored", which is the rule the cloud settings themselves apply, so typing,
 * pasting and the file import all leave an existing credential alone unless a new one is supplied.</p>
 */
public class DictationAdvancedScreen extends BaseScreenFragment {
	public static final String NAME = "DictationAdvanced";

	private static final String ITEM_ENDPOINT = DictationTextPreference.KEY_ENDPOINT;
	private static final String ITEM_MODEL = DictationTextPreference.KEY_MODEL;
	private static final String ITEM_PROMPT = DictationTextPreference.KEY_PROMPT;
	private static final String ITEM_API_KEY = DictationTextPreference.KEY_API_KEY;
	private static final String ITEM_KEY_STATUS = "dictation_cloud_key_status";
	private static final String ITEM_KEY_IMPORT = "dictation_cloud_key_import";
	private static final String ITEM_KEY_CLEAR = "dictation_cloud_key_clear";
	private static final String ITEM_SAVE = "dictation_cloud_save";
	private static final String ITEM_STATUS = "dictation_cloud_status";


	public DictationAdvancedScreen() { super(); }
	public DictationAdvancedScreen(@Nullable PreferencesActivity activity) { super(activity); }

	@Override public String getName() { return NAME; }
	@Override protected int getTitle() { return R.string.dictation_cloud_title; }
	@Override protected int getXml() { return R.xml.prefs_screen_dictation_advanced; }


	@Override
	protected void onCreate() {
		final PreferencesActivity host = activity;
		if (host == null) {
			return;
		}

		loadSavedValues(host);

		Preference save = findPreference(ITEM_SAVE);
		if (save != null) {
			save.setOnPreferenceClickListener(item -> {
				save(host);
				return true;
			});
		}

		Preference importKey = findPreference(ITEM_KEY_IMPORT);
		if (importKey != null) {
			importKey.setOnPreferenceClickListener(item -> {
				importKey(host);
				return true;
			});
		}

		Preference clearKey = findPreference(ITEM_KEY_CLEAR);
		if (clearKey != null) {
			clearKey.setOnPreferenceClickListener(item -> {
				removeKey(host);
				return true;
			});
		}

		refreshKeyStatus(host);
		resetFontSize(false);
	}


	private void loadSavedValues(@NonNull PreferencesActivity host) {
		try {
			final OpenCodeSettings.Snapshot snapshot = new OpenCodeSettings(host).load();
			if (snapshot == null) {
				return;
			}

			setValue(ITEM_ENDPOINT, snapshot.endpoint);
			setValue(ITEM_MODEL, snapshot.model);
			setValue(ITEM_PROMPT, snapshot.prompt);
		} catch (Exception e) {
			setStatus(getString(R.string.dictation_cloud_load_failed, getErrorMessage(e)));
		}
	}


	/** Saves the fields above. An empty key field keeps whatever credential is already stored. */
	private void save(@NonNull PreferencesActivity host) {
		final String endpoint = getValue(ITEM_ENDPOINT);
		final String model = getValue(ITEM_MODEL);
		final String prompt = getValue(ITEM_PROMPT);
		final String typedKey = getValue(ITEM_API_KEY);

		if (!validate(endpoint, model)) {
			return;
		}

		try {
			new OpenCodeSettings(host).save(endpoint, model, prompt, typedKey.isEmpty() ? null : typedKey);
			clearField(ITEM_API_KEY);
			refreshKeyStatus(host);
			setStatus(getString(R.string.dictation_cloud_saved));
		} catch (Exception e) {
			setStatus(getString(R.string.dictation_cloud_save_failed, getErrorMessage(e)));
		}
	}


	/**
	 * Reads the key staged in this app's private files directory, saves it and then removes the file.
	 * Nothing is displayed apart from the fingerprint, and the plaintext file is reported on if it
	 * could not be deleted.
	 */
	private void importKey(@NonNull PreferencesActivity host) {
		final String endpoint = valueOrDefault(ITEM_ENDPOINT, OpenCodeSettings.DEFAULT_ENDPOINT);
		final String model = valueOrDefault(ITEM_MODEL, OpenCodeSettings.DEFAULT_MODEL);
		final String prompt = valueOrDefault(ITEM_PROMPT, OpenCodeSettings.DEFAULT_PROMPT);

		if (!validate(endpoint, model)) {
			return;
		}

		final String importedKey;
		try {
			importedKey = KeyFileImport.read(host);
		} catch (KeyFileImport.ImportException e) {
			setStatus(getString(getImportFailureMessage(e.failure)));
			return;
		}

		try {
			new OpenCodeSettings(host).save(endpoint, model, prompt, importedKey);
		} catch (Exception e) {
			setStatus(getString(R.string.dictation_cloud_key_import_failed, getErrorMessage(e)));
			return;
		}

		final boolean deleted = KeyFileImport.delete(host);
		clearField(ITEM_API_KEY);
		refreshKeyStatus(host);
		setStatus(getString(deleted
			? R.string.dictation_cloud_key_imported
			: R.string.dictation_cloud_key_imported_not_deleted
		));
	}


	private int getImportFailureMessage(@NonNull KeyFileImport.Failure failure) {
		return switch (failure) {
			case MISSING -> R.string.dictation_cloud_key_import_missing;
			case TOO_LARGE -> R.string.dictation_cloud_key_import_too_large;
			case EMPTY -> R.string.dictation_cloud_key_import_empty;
			case UNREADABLE -> R.string.dictation_cloud_key_import_unreadable;
		};
	}


	private void removeKey(@NonNull PreferencesActivity host) {
		try {
			new OpenCodeSettings(host).clearKey();
			clearField(ITEM_API_KEY);
			refreshKeyStatus(host);
			setStatus(getString(R.string.dictation_cloud_key_removed));
		} catch (Exception e) {
			setStatus(getString(R.string.dictation_cloud_save_failed, getErrorMessage(e)));
		}
	}


	private boolean validate(@NonNull String endpoint, @NonNull String model) {
		boolean isValid = true;

		if (endpoint.isEmpty()) {
			setFieldError(ITEM_ENDPOINT, R.string.dictation_cloud_error_endpoint_missing);
			isValid = false;
		} else if (!endpoint.toLowerCase(Locale.ROOT).startsWith("https://")) {
			setFieldError(ITEM_ENDPOINT, R.string.dictation_cloud_error_endpoint_insecure);
			isValid = false;
		}

		if (model.isEmpty()) {
			setFieldError(ITEM_MODEL, R.string.dictation_cloud_error_model_missing);
			isValid = false;
		}

		if (!isValid) {
			setStatus(getString(R.string.dictation_cloud_incomplete));
		}

		return isValid;
	}


	private void refreshKeyStatus(@NonNull PreferencesActivity host) {
		Preference status = findPreference(ITEM_KEY_STATUS);
		if (status == null) {
			return;
		}

		if (!DictationSettings.isCloudAvailable(host)) {
			status.setSummary(R.string.dictation_status_cloud_no_network);
			return;
		}

		final OpenCodeSettings cloud = new OpenCodeSettings(host);

		try {
			if (!cloud.hasKey()) {
				status.setSummary(R.string.dictation_cloud_key_missing);
				return;
			}

			final String fingerprint = cloud.fingerprint();
			status.setSummary(fingerprint == null || fingerprint.isEmpty()
				? getString(R.string.dictation_status_cloud_key_saved)
				: getString(R.string.dictation_status_cloud_key_saved_fingerprint, fingerprint)
			);
		} catch (Exception e) {
			status.setSummary(R.string.dictation_cloud_key_missing);
		}
	}


	private void setValue(@NonNull String key, @Nullable String value) {
		DictationTextPreference item = findPreference(key);
		if (item != null) {
			item.setText(value != null ? value : "");
		}
	}


	private void clearField(@NonNull String key) {
		DictationTextPreference item = findPreference(key);
		if (item != null) {
			item.clearValue();
		}
	}


	@NonNull
	private String getValue(@NonNull String key) {
		DictationTextPreference item = findPreference(key);
		return item != null ? item.getValue() : "";
	}


	@NonNull
	private String valueOrDefault(@NonNull String key, @NonNull String fallback) {
		final String value = getValue(key);
		return value.isEmpty() ? fallback : value;
	}


	private void setFieldError(@NonNull String key, int message) {
		DictationTextPreference item = findPreference(key);
		if (item != null) {
			item.setFieldError(getString(message));
		}
	}


	private void setStatus(@NonNull String message) {
		Preference status = findPreference(ITEM_STATUS);
		if (status != null) {
			status.setSummary(message);
		}
	}


	@NonNull
	private String getErrorMessage(@Nullable Exception e) {
		final String message = e != null ? e.getMessage() : null;
		return message != null && !message.isEmpty() ? message : getString(R.string.dictation_cloud_error_unknown);
	}
}
