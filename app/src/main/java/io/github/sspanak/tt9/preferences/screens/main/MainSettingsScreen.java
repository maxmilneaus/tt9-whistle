package io.github.sspanak.tt9.preferences.screens.main;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import java.util.ArrayList;
import java.util.Arrays;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.dictation.DictationSettings;
import io.github.sspanak.tt9.preferences.HelpActivity;
import io.github.sspanak.tt9.preferences.HelpFile;
import io.github.sspanak.tt9.preferences.PreferencesActivity;
import io.github.sspanak.tt9.preferences.PrivacyPolicyActivity;
import io.github.sspanak.tt9.preferences.screens.BaseScreenFragment;
import io.github.sspanak.tt9.preferences.screens.dictation.DictationScreen;
import io.github.sspanak.tt9.util.sys.SystemSettings;

public class MainSettingsScreen extends BaseScreenFragment {
	final public static String NAME = "Main";

	public MainSettingsScreen() { super(); }
	public MainSettingsScreen(@Nullable PreferencesActivity activity) { super(activity); }

	@Override public String getName() { return NAME; }
	@Override protected int getTitle() { return R.string.app_settings;}
	@Override protected int getXml() { return R.xml.prefs; }


	@Override
	public void onCreate() {
		boolean isTT9On = SystemSettings.isTT9Enabled(activity);
		createSettingsSection(isTT9On);
		createAboutSection(isTT9On);
		retargetThisAppScreens();
		updateHelpButtonDescription();
		resetFontSize(false);
	}


	@Override
	public void onResume() {
		super.onResume();

		boolean isTT9On = SystemSettings.isTT9Enabled(activity);
		createSettingsSection(isTT9On);
		createAboutSection(isTT9On);
		retargetThisAppScreens();
		resetFontSize(false);
	}


	private void createAboutSection(boolean isTT9On) {
		(new ItemVersionInfo(findPreference(ItemVersionInfo.NAME), activity)).populate().enableClickHandler();
		ProfitPreference profPref = findPreference(ProfitPreference.NAME);
		if (profPref != null && activity != null) {
			profPref.populate(activity, isTT9On);
		}
	}


	private void updateHelpButtonDescription() {
		Preference help = findPreference("screen_help");
		if (help == null || activity == null) {
			return;
		}

		String systemLanguage = SystemSettings.getLocale().replaceAll("_\\w+$", "");
		if (new HelpFile(activity, systemLanguage).exists()) {
			help.setSummary("");
		} else {
			help.setSummary(R.string.english_only);
		}
	}


	private void createSettingsSection(boolean isTT9On) {
		if (activity == null) {
			return;
		}

		Preference gotoSetup = findPreference("screen_setup");
		if (gotoSetup != null) {
			gotoSetup.setSummary(isTT9On ? "" : activity.getString(R.string.setup_click_here_to_enable));
		}

		// Show which engine is in use, so the choice is visible without opening the screen.
		Preference gotoDictation = findPreference("screen_dictation");
		if (gotoDictation != null) {
			gotoDictation.setSummary(DictationScreen.getEngineTitle(new DictationSettings(activity).getEngine()));
		}

		ArrayList<Preference> screens = new ArrayList<>(Arrays.asList(
			findPreference("screen_appearance"),
			findPreference("screen_keypad"),
			findPreference("screen_languages"),
			findPreference("screen_dictation")
		));

		for (Preference goToScreen : screens) {
			if (goToScreen != null) {
				goToScreen.setEnabled(isTT9On || activity.getSettings().getDemoMode());
			}
		}
	}


	/**
	 * The Help and Privacy rows open their activities by package name, and the package written into
	 * the layout is the upstream one. This fork installs beside stock TT9 under its own application
	 * ID, so the same classes have to be addressed inside this app instead, or the rows would open
	 * the other copy, or nothing at all.
	 */
	private void retargetThisAppScreens() {
		retargetScreen("screen_help", HelpActivity.class.getName());
		retargetScreen("screen_privacy_policy", PrivacyPolicyActivity.class.getName());
	}


	private void retargetScreen(@NonNull String key, @NonNull String className) {
		Preference item = findPreference(key);
		if (item == null || activity == null) {
			return;
		}

		item.setIntent(new Intent(Intent.ACTION_VIEW).setClassName(activity, className));
	}
}
