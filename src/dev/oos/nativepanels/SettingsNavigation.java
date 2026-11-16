package dev.oos.nativepanels;

import android.content.Intent;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;

/** Redirects only settings launches belonging to the module's native internet dialog. */
final class SettingsNavigation {
  private final PanelRuntime r;

  SettingsNavigation(PanelRuntime runtime) {
    r = runtime;
  }

  void install() throws Throwable {
    Class<?> controller =
        r.type("com.android.systemui.qs.tiles.dialog.InternetDetailsContentController");
    Class<?> legacy = r.type("com.android.systemui.qs.tiles.dialog.InternetDialogDelegateLegacy");
    Method start = Reflect.method(controller, "startActivity", Intent.class, View.class);
    r.module
        .hook(start)
        .setId("native-panels:oos-settings")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
        .intercept(
            c -> {
              if (r.internetManager == null) return c.proceed();
              Object callback = Reflect.get(c.getThisObject(), "mCallback");
              if (!legacy.isInstance(callback)
                  || Reflect.get(callback, "mInternetDialogManager") != r.internetManager) {
                return c.proceed();
              }
              Intent original = (Intent) c.getArg(0);
              Intent target = OosSettingsIntents.redirect(original);
              if (target == original) return c.proceed();
              r.note(target.getComponent() == null ? R.string.wifi_settings : R.string.network_settings);
              // Keep the original View, dialog transition controller and keyguard-aware
              // ActivityStarter.
              return c.proceed(new Object[] {target, c.getArg(1)});
            });
    r.navigationHook = true;
  }
}
