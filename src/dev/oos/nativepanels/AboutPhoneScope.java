package dev.oos.nativepanels;

import android.content.Context;
import java.util.List;

/** About phone shares Settings with Bluetooth, but needs no other package scope. */
final class AboutPhoneScope extends OptionalSettingsScope {
  static final List<String> PACKAGES = List.of(AboutPhoneSettings.SETTINGS_PACKAGE);

  AboutPhoneScope(Context context) {
    this(context, background("AboutPhoneSettingsRestart", RootRestart::restartSettings));
  }

  AboutPhoneScope(Context context, Restarter restarter) {
    super(context, CombinedQsSettings.ABOUT_PHONE, PACKAGES, restarter);
  }
}
