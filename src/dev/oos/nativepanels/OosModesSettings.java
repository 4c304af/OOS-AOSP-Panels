package dev.oos.nativepanels;

import android.content.ComponentName;
import android.content.Intent;

/** The two OOS entry contracts used by Settings itself in the supplied ROM. */
final class OosModesSettings {
  static final String ACTION = "android.settings.ZEN_MODE_SETTINGS";
  static final String RULE_ACTION = "android.settings.AUTOMATIC_ZEN_RULE_SETTINGS";
  static final String PACKAGE = "com.oplus.notificationmanager";

  static boolean handles(Intent intent) {
    return intent != null
        && (ACTION.equals(intent.getAction()) || RULE_ACTION.equals(intent.getAction()));
  }

  static Intent main() {
    // OplusZenModePreferenceController.getZenModeIntent uses this exact action and package.
    return new Intent(ACTION).setPackage(PACKAGE);
  }

  static Intent explicitMain() {
    // SettingsActivityPlugin.ZenModeSettings uses this fallback component.
    return new Intent(Intent.ACTION_MAIN)
        .setComponent(
            new ComponentName(PACKAGE, "com.oplus.zenmode.zenmodesettings.ZenModeSettingsActivity"))
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
  }
}
