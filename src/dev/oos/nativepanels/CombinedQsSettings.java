package dev.oos.nativepanels;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

final class CombinedQsSettings {
  static final String AUTHORITY = "dev.oos.nativepanels.combinedqs";
  static final String QUERY = "combined_qs_enabled";
  // Preserve the status bar switch across upgrades from 0.6.19.
  static final String HOME_BATTERY = "native_status_icons";
  static final String MOBILE_ORDER = "mobile_type_before_signal";
  static boolean standalone(String key) {
    return HOME_BATTERY.equals(key) || MOBILE_ORDER.equals(key);
  }

  static final Uri URI = Uri.parse("content://" + AUTHORITY);

  private static SharedPreferences settings(Context context) {
    return context.createDeviceProtectedStorageContext()
        .getSharedPreferences("shade", Context.MODE_PRIVATE);
  }

  static boolean enabled(Context context) {
    return enabled(context, QUERY);
  }

  static boolean enabled(Context context, String key) {
    return settings(context).getBoolean(key, MOBILE_ORDER.equals(key));
  }

  static boolean setEnabled(Context context, String key, boolean enabled) {
    // The user can immediately restart System UI from the next button.
    return settings(context).edit().putBoolean(key, enabled).commit();
  }

  private CombinedQsSettings() {}
}
