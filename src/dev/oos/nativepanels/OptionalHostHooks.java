package dev.oos.nativepanels;

import android.app.Application;

/** Entry points in scoped processes other than SystemUI. */
final class OptionalHostHooks {
  static void install(ModuleEntry module, String packageName, ClassLoader loader) {
    if (LauncherIconRefresh.PACKAGE.equals(packageName)) {
      if (LauncherIconRefresh.PACKAGE.equals(Application.getProcessName())) {
        module.installPart("launcher-icons", () -> LauncherIconRefresh.install(module, loader));
      }
      return;
    }
    if (PermissionDialogHook.PACKAGE.equals(packageName)) {
      module.installPart("permission-dialogs", () -> new PermissionDialogHook(module, loader).install());
      return;
    }
    if (BluetoothDetailsSettings.SETTINGS_PACKAGE.equals(packageName)) {
      module.installPart("bluetooth-details-theme", () -> new BluetoothDetailsHook(module, loader).install());
      module.installPart("about-phone-m3e", () -> new AboutPhoneHook(module, loader).install());
      return;
    }
    if (BluetoothDetailsSettings.WIRELESS_PACKAGE.equals(packageName)) {
      module.installPart("wireless-bluetooth-details", () -> new WirelessBluetoothDetailsHook(module, loader).install());
      return;
    }
  }

  private OptionalHostHooks() {}
}
