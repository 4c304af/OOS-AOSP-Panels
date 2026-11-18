package dev.oos.nativepanels;

import android.content.Context;
import java.util.List;

/** Scope and apply configuration for the two Bluetooth device-page hosts. */
final class BluetoothDetailsScope extends OptionalSettingsScope {
  static final List<String> PACKAGES = List.of(
      BluetoothDetailsSettings.SETTINGS_PACKAGE, BluetoothDetailsSettings.WIRELESS_PACKAGE);

  BluetoothDetailsScope(Context context) {
    this(context, (allowed, completion) -> {
      Thread worker = new Thread(() -> {
        RootRestart.Result result;
        try { result = RootRestart.restartBluetoothSettings(allowed); }
        catch (java.io.IOException | RuntimeException failure) { result = RootRestart.Result.FAILED; }
        completion.completed(result);
      }, "BluetoothSettingsRestart");
      worker.setDaemon(true);
      worker.start();
    });
  }

  BluetoothDetailsScope(Context context, Restarter restarter) {
    super(context, CombinedQsSettings.BLUETOOTH_DETAILS, PACKAGES, restarter);
  }
}
