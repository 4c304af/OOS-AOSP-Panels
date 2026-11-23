package dev.oos.nativepanels;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PatternMatcher;
import io.github.libxposed.api.XposedInterface;
import java.io.File;
import java.lang.reflect.Method;

/** Refreshes the OOS launcher's rendered icons after the icon pack's color inputs change. */
final class LauncherIconRefresh implements ComponentCallbacks {
  static final String PACKAGE = "com.android.launcher";
  // Earlier releases marked the palette applied without invalidating the source drawable.
  private static final String LAST = "applied_source_palette";
  private static final int[] COLORS = {
      android.R.color.system_accent1_100, android.R.color.system_accent1_500, android.R.color.system_accent1_900,
      android.R.color.system_accent2_100, android.R.color.system_accent2_500, android.R.color.system_accent2_900,
      android.R.color.system_accent3_100, android.R.color.system_accent3_500, android.R.color.system_accent3_900,
      android.R.color.system_neutral1_100, android.R.color.system_neutral1_500, android.R.color.system_neutral1_900,
      android.R.color.system_neutral2_100, android.R.color.system_neutral2_500, android.R.color.system_neutral2_900
  };

  interface Icons {
    String pack() throws ReflectiveOperationException;
    boolean reload() throws ReflectiveOperationException;
  }

  private static class OosIcons implements Icons {
    private final Method instance, model, hasCallbacks, resetIcons, refresh, config, theme, packName;

    OosIcons(ClassLoader loader) throws ReflectiveOperationException {
      Class<?> state = loader.loadClass("com.android.launcher3.LauncherAppState");
      instance = Reflect.method(state, "getInstanceNoCreate");
      model = Reflect.method(state, "getModel");
      hasCallbacks = Reflect.method(model.getReturnType(), "hasCallbacks");
      resetIcons = Reflect.method(loader.loadClass("com.android.launcher.theme.OplusUIEngine"), "resetAppsIconsCtrl");
      refresh = Reflect.method(state, "refreshAndReloadLauncher");
      Class<?> iconConfig = loader.loadClass("com.android.launcher.theme.LauncherIconConfig");
      config = Reflect.method(iconConfig, "getInstance");
      theme = Reflect.method(iconConfig, "getTheme");
      packName = Reflect.method(iconConfig, "getPackName");
    }

    public String pack() throws ReflectiveOperationException {
      Object selected = config.invoke(null);
      if (selected == null || !Integer.valueOf(5).equals(theme.invoke(selected))) return null;
      String name = (String) packName.invoke(selected);
      return name == null ? "" : name; // The theme can be selected before the pack name is populated.
    }

    public boolean reload() throws ReflectiveOperationException {
      Object state = instance.invoke(null);
      Object currentModel = state == null ? null : model.invoke(state);
      if (currentModel == null || !Boolean.TRUE.equals(hasCallbacks.invoke(currentModel))) return false;
      resetIcons.invoke(null);
      refresh.invoke(state);
      return true;
    }
  }

  static void install(ModuleEntry module, ClassLoader loader) throws ReflectiveOperationException {
    OosIcons icons = new OosIcons(loader);
    Method resume = loader.loadClass("com.android.launcher.Launcher").getDeclaredMethod("onResume");
    LauncherIconSource source = new LauncherIconSource(new File("/data/oplus/uxicons/choose"));
    source.install(module, loader);
    module.hook(resume).setId("native-panels:launcher-icon-refresh").intercept(new XposedInterface.Hooker() {
      private LauncherIconRefresh watcher;
      @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        try {
          if (watcher == null) {
            Context app = ((Activity) chain.getThisObject()).getApplicationContext();
            watcher = new LauncherIconRefresh(app, icons, source);
            try {
              app.getContentResolver().registerContentObserver(CombinedQsSettings.LAUNCHER_URI, false, watcher.settingObserver);
              watcher.observing = true;
            } catch (RuntimeException unavailable) {
              // A resume can still read the current setting when observation is unavailable.
            }
          }
          // Also catches changes made while the launcher or the settings provider was unavailable.
          if (watcher.observing && watcher.settingKnown) watcher.schedule();
          else watcher.readSetting();
        } catch (RuntimeException | LinkageError unavailable) {
          // A launcher without the optional provider access keeps its normal lifecycle.
        }
        return result;
      }
    });
  }

  private final Context app;
  private final Icons icons;
  private final LauncherIconSource source;
  private final SharedPreferences prefs;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Runnable refresh = this::refreshIfNeeded;
  private boolean enabled;
  private boolean settingKnown;
  private boolean observing;
  private final ContentObserver settingObserver = new ContentObserver(main) {
    @Override public void onChange(boolean selfChange) { readSetting(); }
  };
  private final BroadcastReceiver overlays = new BroadcastReceiver() {
    @Override public void onReceive(Context context, Intent intent) { schedule(); }
  };

  LauncherIconRefresh(Context app, Icons icons, LauncherIconSource source) {
    this.app = app;
    this.icons = icons;
    this.source = source;
    prefs = app.getSharedPreferences("dev.oos.nativepanels.launcher_icons", Context.MODE_PRIVATE);
  }

  private void readSetting() {
    try {
      Bundle setting = app.getContentResolver().call(CombinedQsSettings.LAUNCHER_URI, CombinedQsSettings.LAUNCHER_ICONS, null, null);
      setEnabled(setting != null && setting.getBoolean(CombinedQsSettings.LAUNCHER_ICONS));
      settingKnown = setting != null;
    } catch (RuntimeException unavailable) {
      settingKnown = false;
      setEnabled(false);
    }
  }

  void setEnabled(boolean next) {
    if (next != enabled) {
      if (next) {
        IntentFilter filter = new IntentFilter("android.intent.action.OVERLAY_CHANGED");
        filter.addDataScheme("package");
        filter.addDataSchemeSpecificPart("android", PatternMatcher.PATTERN_LITERAL);
        app.registerReceiver(overlays, filter, Context.RECEIVER_NOT_EXPORTED);
        app.registerComponentCallbacks(this);
      } else {
        main.removeCallbacks(refresh);
        source.setPalette(null);
        app.unregisterComponentCallbacks(this);
        app.unregisterReceiver(overlays);
        prefs.edit().remove(LAST).apply();
      }
      enabled = next;
    }
    schedule();
  }

  @Override public void onConfigurationChanged(Configuration config) { schedule(); }
  @Override public void onLowMemory() {}

  private void schedule() {
    if (!enabled) return;
    main.removeCallbacks(refresh);
    main.postDelayed(refresh, 300);
  }

  void refreshIfNeeded() {
    if (!enabled) return;
    try {
      Resources res = app.getResources();
      Configuration config = res.getConfiguration();
      String pack = icons.pack();
      if (pack == null && !source.hasEdits()) {
        source.setPalette(null);
        if (prefs.contains(LAST)) prefs.edit().remove(LAST).apply();
        return;
      }
      StringBuilder key = new StringBuilder(pack == null ? "individual" : "pack:" + pack)
          .append('/').append(config.uiMode & Configuration.UI_MODE_NIGHT_MASK);
      for (int id : COLORS) key.append('/').append(res.getColor(id, null));
      String signature = key.toString();
      source.setPalette(signature);
      if (!signature.equals(prefs.getString(LAST, null)) && icons.reload()) {
        prefs.edit().putString(LAST, signature).apply();
      }
    } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
      // Leave the previous signature in place so a later resume can retry.
    }
  }
}
