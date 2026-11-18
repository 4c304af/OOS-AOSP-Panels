package dev.oos.nativepanels;

import android.app.Application;

/** Owns the process-wide framework connection without retaining a settings Activity. */
public final class PanelApplication extends Application {
  private final ScopeServiceConnection scopeService = new ScopeServiceConnection();
  private PermissionDialogScope permissionScope;
  private BluetoothDetailsScope bluetoothDetailsScope;

  ScopeServiceConnection scopeService() { return scopeService; }

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
