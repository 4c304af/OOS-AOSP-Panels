package dev.oos.nativepanels;

import android.app.Application;

/** Owns the process-wide framework connection without retaining a settings Activity. */
public final class PanelApplication extends Application {
  private final ScopeServiceConnection scopeService = new ScopeServiceConnection();
  private PermissionDialogScope permissionScope;
  private BluetoothDetailsScope bluetoothDetailsScope;
  private AboutPhoneScope aboutPhoneScope;
  private OptionalSettingsScope launcherScope;

  ScopeServiceConnection scopeService() { return scopeService; }

  OptionalSettingsScope launcherScope() {
    if (launcherScope == null) {
      launcherScope = new OptionalSettingsScope(this, CombinedQsSettings.LAUNCHER_ICONS,
          java.util.List.of(LauncherIconRefresh.PACKAGE),
          OptionalSettingsScope.background("LauncherRestart", RootRestart::restartLauncher), false);
      launcherScope.connect();
    }
    return launcherScope;
  }

  AboutPhoneScope aboutPhoneScope() {
    if (aboutPhoneScope == null) {
      aboutPhoneScope = new AboutPhoneScope(this);
      aboutPhoneScope.connect();
    }
    return aboutPhoneScope;
  }

  BluetoothDetailsScope bluetoothDetailsScope() {
    if (bluetoothDetailsScope == null) {
      bluetoothDetailsScope = new BluetoothDetailsScope(this);
      bluetoothDetailsScope.connect();
    }
    return bluetoothDetailsScope;
  }

  PermissionDialogScope permissionScope() {
    if (permissionScope == null) {
      permissionScope = new PermissionDialogScope(this);
      permissionScope.connect();
    }
    return permissionScope;
  }
}
