package dev.oos.nativepanels;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

/** Read only when Settings opens the About phone page. */
final class AboutPhoneSettings {
  static final String SETTINGS_PACKAGE = "com.android.settings";
  static final String AUTHORITY = "dev.oos.nativepanels.aboutphone";
  static final Uri URI = Uri.parse("content://" + AUTHORITY);
  static final String QUERY = CombinedQsSettings.ABOUT_PHONE;

  static boolean readEnabled(Context caller) {
    try {
      Bundle result = caller.getContentResolver().call(URI, QUERY, null, null);
      return result != null && result.getBoolean(QUERY, false);
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private AboutPhoneSettings() {}
}
