package dev.oos.nativepanels;

import android.content.Context;
import java.util.List;

/** About phone shares Settings with Bluetooth, but needs no other package scope. */
final class AboutPhoneScope extends OptionalSettingsScope {
  static final List<String> PACKAGES = List.of(AboutPhoneSettings.SETTINGS_PACKAGE);

  AboutPhoneScope(Context context) {
    this(context, (allowed, completion) -> {
      Thread worker = new Thread(() -> {
        RootRestart.Result result;
        try { result = RootRestart.restartSettings(allowed); }
        catch (java.io.IOException | RuntimeException failure) { result = RootRestart.Result.FAILED; }
        completion.completed(result);
      }, "AboutPhoneSettingsRestart");
      worker.setDaemon(true);
      worker.start();
    });
  }

  AboutPhoneScope(Context context, Restarter restarter) {
    super(context, CombinedQsSettings.ABOUT_PHONE, PACKAGES, restarter);
  }
}
