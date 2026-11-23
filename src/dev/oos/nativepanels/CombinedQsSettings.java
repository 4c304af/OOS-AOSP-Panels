package dev.oos.nativepanels;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

final class CombinedQsSettings {
  static final String AUTHORITY = "dev.oos.nativepanels.combinedqs";
  static final String QUERY = "combined_qs_enabled";
  // Preserve the status bar switch across upgrades from 0.6.19.
  static final String HOME_BATTERY = "native_status_icons";
  static final String PERMISSION_DIALOG = "native_permission_dialog";
  static final String BLUETOOTH_DETAILS = "native_bluetooth_details";
  static final String ABOUT_PHONE = "about_phone_m3e";
  static final String LAUNCHER_ICONS = "launcher_monet_icons";
  static boolean standalone(String key) {
    return HOME_BATTERY.equals(key) || PERMISSION_DIALOG.equals(key)
        || BLUETOOTH_DETAILS.equals(key) || ABOUT_PHONE.equals(key) || LAUNCHER_ICONS.equals(key);
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
    return settings(context).getBoolean(key, false);
  }

  static boolean setEnabled(Context context, String key, boolean enabled) {
    // Persist before a host process starts or creates its next dialog.
    boolean saved = settings(context).edit().putBoolean(key, enabled).commit();
    if (saved && LAUNCHER_ICONS.equals(key)) {
      context.getContentResolver().notifyChange(URI.buildUpon().appendPath(key).build(), null);
    }
    return saved;
  }

  private CombinedQsSettings() {}
}
