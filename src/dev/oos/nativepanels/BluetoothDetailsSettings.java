package dev.oos.nativepanels;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

/** Read once at a device-page entry; no background observer or polling is needed. */
final class BluetoothDetailsSettings {
  static final String SETTINGS_PACKAGE = "com.android.settings";
  static final String WIRELESS_PACKAGE = "com.oplus.wirelesssettings";
  static final String AUTHORITY = "dev.oos.nativepanels.bluetoothsettings";
  static final Uri URI = Uri.parse("content://" + AUTHORITY);
  static final String QUERY = CombinedQsSettings.BLUETOOTH_DETAILS;

  static boolean readEnabled(Context caller) {
    try {
      Bundle result = caller.getContentResolver().call(URI, QUERY, null, null);
      return result != null && result.getBoolean(QUERY, false);
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private BluetoothDetailsSettings() {}
}
