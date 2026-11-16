package dev.oos.nativepanels;

import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.icu.util.ULocale;
import android.os.Build;
import android.os.LocaleList;
import java.util.LinkedHashSet;
import java.util.Locale;

/** Respects Android's per-app locale list, then the user's full ordered system list. */
final class PanelLanguages {
  private PanelLanguages() {}

  static LocaleList forApp(Context app) {
    LocaleList system = app.getResources().getConfiguration().getLocales();
    if (Build.VERSION.SDK_INT >= 33) {
      LocaleManager manager = app.getSystemService(LocaleManager.class);
      if (manager != null) {
        LocaleList preferred = manager.getSystemLocales();
        // Some test contexts and transient configurations have no system list yet.
        if (preferred != null && !preferred.isEmpty()) system = preferred;
        return resolve(manager.getApplicationLocales(), system);
      }
    }
    return resolve(LocaleList.getEmptyLocaleList(), system);
  }

  static LocaleList resolve(LocaleList perApp, LocaleList system) {
    LinkedHashSet<Locale> supported = new LinkedHashSet<>();
    appendSupported(supported, perApp);
    appendSupported(supported, system);
    if (supported.isEmpty()) supported.add(Locale.ENGLISH);
    return new LocaleList(supported.toArray(new Locale[0]));
  }

  private static void appendSupported(LinkedHashSet<Locale> result, LocaleList list) {
    if (list == null) return;
    for (int i = 0; i < list.size(); i++) {
      Locale locale = list.get(i);
      switch (locale.getLanguage()) {
        case "en", "ja" -> result.add(locale);
        case "zh" -> {
          // Explicit script wins even when it differs from the country's usual writing system.
          String script = ULocale.addLikelySubtags(ULocale.forLocale(locale)).getScript();
          if ("Hans".equals(script) || "Hant".equals(script)) result.add(locale);
        }
      }
    }
  }

  static Context withLocales(Context context, LocaleList locales) {
    Configuration override = new Configuration();
    override.setLocales(locales);
    return context.createConfigurationContext(override);
  }
}
