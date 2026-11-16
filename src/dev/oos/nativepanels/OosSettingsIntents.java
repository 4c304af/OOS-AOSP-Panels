package dev.oos.nativepanels;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

/** External navigation contract verified against the supplied OOS Wireless Settings APK. */
final class OosSettingsIntents {
  static final String PACKAGE = "com.oplus.wirelesssettings";
  static final String WIFI_EDITOR = PACKAGE + ".wifi.detail.WifiEditor";
  static final String DETAILS_ACTION = "android.settings.WIFI_DETAILS_SETTINGS";
  static final String NETWORK_ACTION = "android.settings.NETWORK_PROVIDER_SETTINGS";
  static final String FRAGMENT_ARGS = ":settings:show_fragment_args";
  static final String ENTRY_KEY = "key_chosen_wifientry_key";
  static final String SHOW_DETAILS = "wifi.details.extra.config";

  private OosSettingsIntents() {}

  static Intent redirect(Intent original) {
    if (original == null) return null;
    if (NETWORK_ACTION.equals(original.getAction())) return wifiList(original.getFlags());
    if (!DETAILS_ACTION.equals(original.getAction())) return original;

    Bundle args = original.getBundleExtra(FRAGMENT_ARGS);
    String key = args == null ? null : args.getString(ENTRY_KEY);
    // An absent key must never select a different/previously connected network.
    if (key == null || key.isEmpty()) return wifiList(original.getFlags());

    // WifiEditor.onCreate reads these two extras directly. With SHOW_DETAILS=true,
    // it opens OOS's WifiNetworkDetailsFragment subclass and sets key_change_data=false.
    // The WifiEntry key is opaque: preserve SSID, security types and owner information.
    return new Intent("wireless.settings.WIFI_EDITOR")
        .setClassName(PACKAGE, WIFI_EDITOR)
        .addFlags(original.getFlags() | Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(ENTRY_KEY, key)
        .putExtra(SHOW_DETAILS, true);
  }

  private static Intent wifiList(int flags) {
    // The OEM's own Wi-Fi tile uses this action. Restrict it to the OOS handler,
    // since com.android.settings also exports a lower-priority AOSP implementation.
    return new Intent(Settings.ACTION_WIFI_SETTINGS)
        .setPackage(PACKAGE)
        .addFlags(flags | Intent.FLAG_ACTIVITY_NEW_TASK);
  }
}
