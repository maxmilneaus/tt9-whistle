package io.github.sspanak.tt9.preferences.screens.dictation;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.preference.Preference;

import java.io.File;
import java.io.IOException;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.dictation.DictationEngine;
import io.github.sspanak.tt9.dictation.DictationSettings;
import io.github.sspanak.tt9.dictation.OpenCodeSettings;
import io.github.sspanak.tt9.preferences.PreferencesActivity;
import io.github.sspanak.tt9.preferences.custom.EnhancedDropDownPreference;
import io.github.sspanak.tt9.preferences.screens.BaseScreenFragment;

/**
 * Voice input: which engine transcribes, and the controls that belong to the selected engine.
 *
 * <p>Every engine's controls are always present in the screen, because a hidden preference is what
 * keeps each engine's saved values intact while the user tries another one. Only the selected engine's
 * section is shown.</p>
 *
 * <p>Rows are deliberately left selectable, including the ones that only report something. AndroidX
 * makes a row focusable exactly when it is selectable, and the keypad counts visible rows, so a row
 * that could not take focus would put the numbering out of step with what is on the screen.</p>
 */
public class DictationScreen extends BaseScreenFragment {
	public static final String NAME = "Dictation";

	private static final String ITEM_STATUS = "dictation_status";
	private static final String ITEM_CLOUD_SETTINGS = "screen_dictation_cloud";
	private static final String ITEM_KEYWORDS = "dictation_whistle_keywords";
	private static final String CATEGORY_ON_DEVICE = "category_dictation_on_device";
	private static final String CATEGORY_CLOUD = "category_dictation_cloud";
	private static final String CATEGORY_SYSTEM = "category_dictation_system";


	public DictationScreen() { super(); }
	public DictationScreen(@Nullable PreferencesActivity activity) { super(activity); }

	@Override public String getName() { return NAME; }
	@Override protected int getTitle() { return R.string.dictation_screen_title; }
	@Override protected int getXml() { return R.xml.prefs_screen_dictation; }


	/**
	 * The engine's name, for the summary of the row that opens this screen.
	 */
	public static int getEngineTitle(@NonNull String engine) {
		return switch (engine) {
			case DictationSettings.ENGINE_OPENCODE -> R.string.dictation_engine_opencode;
			case DictationSettings.ENGINE_SYSTEM -> R.string.dictation_engine_system;
			default -> R.string.dictation_engine_whistle;
		};
	}


	@Override
	protected void onCreate() {
		final PreferencesActivity host = activity;
		if (host == null) {
			return;
		}

		DropDownDictationEngine engine = findPreference(DropDownDictationEngine.NAME);
		if (engine != null) {
			engine.populate(host.getSettings()).preview();
			engine.setOnChangeListener(this::onEngineChange);
		}

		EnhancedDropDownPreference[] onDeviceControls = {
			findPreference(DropDownWhistleDepth.NAME),
			findPreference(DropDownWhistleThreads.NAME),
			findPreference(DropDownWhistleLanguage.NAME)
		};
		for (EnhancedDropDownPreference item : onDeviceControls) {
			if (item != null) {
				item.populate(host.getSettings()).preview();
			}
		}
	
		// The hint words are saved as they are typed, so the field only needs the stored value.
		DictationKeywordsPreference keywords = findPreference(ITEM_KEYWORDS);
		if (keywords != null) {
			keywords.setText(new DictationSettings(host).getKeywords());
		}

		Preference cloudSettings = findPreference(ITEM_CLOUD_SETTINGS);
		if (cloudSettings != null) {
			cloudSettings.setOnPreferenceClickListener(item -> {
				host.displayScreen(DictationAdvancedScreen.NAME);
				return true;
			});
		}

		// Re-checking on demand is what makes this row worth selecting.
		Preference status = findPreference(ITEM_STATUS);
		if (status != null) {
			status.setOnPreferenceClickListener(item -> {
				applyEngine(new DictationSettings(host).getEngine());
				return true;
			});
		}

		applyEngine(new DictationSettings(host).getEngine());
		resetFontSize(false);
	}


	private void onEngineChange(String engine) {
		if (activity != null) {
			new DictationSettings(activity).setEngine(engine);
		}

		applyEngine(engine);
	}


	private void applyEngine(@NonNull String engine) {
		setVisible(CATEGORY_ON_DEVICE, DictationSettings.ENGINE_WHISTLE.equals(engine));
		setVisible(CATEGORY_CLOUD, DictationSettings.ENGINE_OPENCODE.equals(engine));
		setVisible(CATEGORY_SYSTEM, DictationSettings.ENGINE_SYSTEM.equals(engine));

		Preference status = findPreference(ITEM_STATUS);
		if (status == null) {
			return;
		}

		if (DictationSettings.ENGINE_OPENCODE.equals(engine)) {
			status.setSummary(getCloudStatus());
		} else if (DictationSettings.ENGINE_SYSTEM.equals(engine)) {
			status.setSummary(R.string.dictation_status_system);
		} else {
			status.setSummary(getOnDeviceStatus());
		}
	}


	/**
	 * Whether this build really carries what on-device dictation needs. The model and the runner ship
	 * as an asset and a native library, so both can be checked before anything is recorded.
	 */
	private String getOnDeviceStatus() {
		final PreferencesActivity host = activity;
		if (host == null) {
			return "";
		}

		try {
			host.getAssets().open(DictationEngine.MODEL_ASSET).close();
		} catch (IOException e) {
			return getString(R.string.dictation_status_model_missing);
		}

		final String engineFile = host.getApplicationInfo().nativeLibraryDir + "/" + DictationEngine.NEEDLE_LIB_NAME;
		if (!new File(engineFile).canExecute()) {
			return getString(R.string.dictation_status_no_engine, Build.SUPPORTED_ABIS[0]);
		}

		return getString(R.string.dictation_status_on_device);
	}


	private String getCloudStatus() {
		final PreferencesActivity host = activity;
		if (host == null) {
			return "";
		}

		return getCloudStatusSummary(host, R.string.dictation_status_cloud_no_key);
	}


	/**
	 * The cloud key summary, shared by the engine row here and the key row on the cloud screen. Both
	 * run the same isCloudAvailable/hasKey/fingerprint ladder and only differ in the wording used when
	 * no key is saved yet, so the caller passes that string id.
	 */
	public static String getCloudStatusSummary(@NonNull Context context, @StringRes int noKeyMessage) {
		if (!DictationSettings.isCloudAvailable(context)) {
			return context.getString(R.string.dictation_status_cloud_no_network);
		}

		final OpenCodeSettings cloud = new OpenCodeSettings(context);
		try {
			if (!cloud.hasKey()) {
				return context.getString(noKeyMessage);
			}

			final String fingerprint = cloud.fingerprint();
			return fingerprint == null || fingerprint.isEmpty()
				? context.getString(R.string.dictation_status_cloud_key_saved)
				: context.getString(R.string.dictation_status_cloud_key_saved_fingerprint, fingerprint);
		} catch (Exception e) {
			return context.getString(noKeyMessage);
		}
	}


	private void setVisible(@NonNull String key, boolean visible) {
		Preference item = findPreference(key);
		if (item != null) {
			item.setVisible(visible);
		}
	}
}
