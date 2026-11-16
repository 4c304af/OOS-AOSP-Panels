package dev.oos.nativepanels;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import java.util.Objects;

/** Settings.Global is authoritative, including a value set manually before installation. */
final class ImeMenuStyle {
  static final String KEY = "ime_switcher_menu_style";
  private static final String PREVIOUS = "ime_style_before_replacement";
  private final ContentResolver resolver;
  private final SharedPreferences prefs;
  private final java.util.function.IntFunction<String> strings;

  ImeMenuStyle(ContentResolver resolver, SharedPreferences prefs, java.util.function.IntFunction<String> strings) {
    this.resolver = resolver;
    this.strings = strings;
    this.prefs = prefs;
  }

  String read() {
    return Settings.Global.getString(resolver, KEY);
  }

  void setEnabled(boolean enable) {
    String current = read();
    if (enable == "1".equals(current)) return;
    String target;
    if (enable) {
      // Persist the snapshot before changing the system value, including across SystemUI restarts.
      if (!prefs.edit().putString(PREVIOUS, current).commit())
        throw new IllegalStateException(strings.apply(R.string.ime_save_error));
      target = "1";
    } else {
      // The manually applied setting predates the module on this ROM. Its original key was absent.
      target = prefs.getString(PREVIOUS, null);
    }
    if (target == null) {
      resolver.call(Settings.Global.CONTENT_URI, "DELETE_global", KEY, Bundle.EMPTY);
    } else if (!Settings.Global.putString(resolver, KEY, target)) {
      throw new IllegalStateException(strings.apply(R.string.ime_write_error));
    }
    if (!Objects.equals(target, read())) throw new IllegalStateException(strings.apply(R.string.ime_verify_error));
    if (!enable) prefs.edit().remove(PREVIOUS).apply();
  }
}
