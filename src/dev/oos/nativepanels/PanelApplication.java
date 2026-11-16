package dev.oos.nativepanels;

import android.app.Application;

/** Owns the process-wide framework connection without retaining a settings Activity. */
public final class PanelApplication extends Application {
  private PermissionDialogScope permissionScope;

  PermissionDialogScope permissionScope() {
    if (permissionScope == null) {
      permissionScope = new PermissionDialogScope(this);
      permissionScope.connect();
    }
    return permissionScope;
  }
}
