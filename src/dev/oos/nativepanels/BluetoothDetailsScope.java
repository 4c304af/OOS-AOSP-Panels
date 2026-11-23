package dev.oos.nativepanels;

import android.content.Context;
import java.util.List;

/** Scope and apply configuration for the two Bluetooth device-page hosts. */
final class BluetoothDetailsScope extends OptionalSettingsScope {
  static final List<String> PACKAGES = List.of(
      BluetoothDetailsSettings.SETTINGS_PACKAGE, BluetoothDetailsSettings.WIRELESS_PACKAGE);

  BluetoothDetailsScope(Context context) {
    this(context, background("BluetoothSettingsRestart", RootRestart::restartBluetoothSettings));
  }

  BluetoothDetailsScope(Context context, Restarter restarter) {
    super(context, CombinedQsSettings.BLUETOOTH_DETAILS, PACKAGES, restarter);
  }
}
