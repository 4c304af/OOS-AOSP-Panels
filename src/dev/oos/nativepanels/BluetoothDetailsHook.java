package dev.oos.nativepanels;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Enables the Settings APK's complete expressive UI only for a selected Bluetooth device. */
final class BluetoothDetailsHook {
  static final String ACTIVITY = "com.android.settings.Settings$BluetoothDeviceDetailActivity";
  static final String SUB_SETTINGS = "com.android.settings.SubSettings";
  static final String FRAGMENT = "com.android.settings.bluetooth.BluetoothDeviceDetailsFragment";
  static final String SHOW_FRAGMENT = ":settings:show_fragment";
  private static final String SETTINGS = "com.android.settings";
  private final ModuleEntry module;
  private final ClassLoader loader;
  // Values never hold the Activity. Reading the switch is restricted to page creation,
  // not the preference adapter's bind/draw path or every isExpressiveTheme query.
  private final Map<Activity, Integer> owned = Collections.synchronizedMap(new WeakHashMap<>());

  BluetoothDetailsHook(ModuleEntry module, ClassLoader loader) {
    this.module = module;
    this.loader = loader;
  }

  void install() throws ReflectiveOperationException {
    Class<?> settings = type("com.android.settings.SettingsActivity");
    Class<?> base = type("com.android.settings.core.SettingsBaseActivity");
    Method create = settings.getDeclaredMethod("onCreate", Bundle.class);
    Method destroy = settings.getDeclaredMethod("onDestroy");
    Method expressive = base.getDeclaredMethod("isExpressiveDesignEnabled");
    Method theme = settings.getDeclaredMethod("onApplyThemeResource", Resources.Theme.class, int.class, boolean.class);
    Method overlays = type("com.oplus.settings.utils.ThemeUtils")
        .getDeclaredMethod("applyActivityThemeOverlays", Activity.class);
    Method helper = type("com.android.settingslib.widget.SettingsThemeHelper")
        .getDeclaredMethod("isExpressiveTheme", Context.class);
    Method setup = type("com.google.android.setupcompat.util.WizardManagerHelper")
        .getDeclaredMethod("isAnySetupWizard", Intent.class);
    if (!Activity.class.isAssignableFrom(settings) || expressive.getReturnType() != boolean.class
        || helper.getReturnType() != boolean.class || setup.getReturnType() != boolean.class)
      throw new NoSuchMethodException("Unsupported Bluetooth Settings theme contract");
    // Validate the native renderers before installing a gate that selects their resources.
    Class<?> buttons = type("com.android.settingslib.widget.ActionButtonsPreference");
    Class<?> adapter = type("com.android.settingslib.widget.SettingsPreferenceGroupAdapter");
    Class<?> fragment = type("com.android.settingslib.widget.SettingsBasePreferenceFragment");

    module.hook(create).setId("native-panels:bluetooth-details-create")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          Activity activity = (Activity)c.getThisObject();
          try {
            if (Build.VERSION.SDK_INT >= 36 && eligible(activity.getClass().getName(), activity.getIntent())
                && !((boolean)setup.invoke(null, activity.getIntent()))
                && BluetoothDetailsSettings.readEnabled(activity)) prepare(activity);
          } catch (ReflectiveOperationException | RuntimeException unavailable) {
            owned.remove(activity);
          }
          return c.proceed();
        });
    module.hook(expressive).setId("native-panels:bluetooth-details-expressive")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c ->
            owned.containsKey(c.getThisObject()) ? true : c.proceed());
    module.hook(helper).setId("native-panels:bluetooth-details-theme-context")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c ->
            owns((Context)c.getArg(0)) ? true : c.proceed());
    module.hook(theme).setId("native-panels:bluetooth-details-theme")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          Object result = c.proceed();
          apply((Activity)c.getThisObject(), (Resources.Theme)c.getArg(0));
          return result;
        });
    module.hook(overlays).setId("native-panels:bluetooth-details-overlay")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          Object result = c.proceed();
          Activity activity = (Activity)c.getArg(0);
          if (owned.containsKey(activity)) apply(activity, activity.getTheme());
          return result;
        });
    module.hook(destroy).setId("native-panels:bluetooth-details-destroy")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          try { return c.proceed(); }
          finally { owned.remove(c.getThisObject()); }
        });

    // The OEM's constant-false provider and small helper can have been inlined
    // before LSPosed loads. Retain the original constructors, adapters and callbacks.
    deoptimize(helper);
    deoptimize(create);
    deoptimize(settings.getDeclaredMethod("createUiFromIntent", Bundle.class, Intent.class));
    deoptimizeNamed(buttons, "init", "updateLayout", "onBindViewHolder");
    deoptimizeNamed(adapter, "updatePreferencesList", "onPreferenceHierarchyChange", "onBindViewHolder");
    deoptimizeNamed(fragment, "onCreateAdapter", "onViewCreated");
    deoptimizeNamed(type("com.android.settings.widget.HighlightablePreferenceGroupAdapter"),
        "getBackgroundRes");
    for (java.lang.reflect.Constructor<?> constructor : buttons.getDeclaredConstructors()) deoptimize(constructor);
    for (java.lang.reflect.Constructor<?> constructor : adapter.getDeclaredConstructors()) deoptimize(constructor);
  }

  private void prepare(Activity activity) {
    Resources resources = activity.getResources();
    int style = resources.getIdentifier("Theme.SubSettings.Expressive", "style", SETTINGS);
    if (style == 0
        || resources.getIdentifier("settingslib_expressive_action_buttons", "layout", SETTINGS) == 0
        || resources.getIdentifier("settingslib_expressive_preference", "layout", SETTINGS) == 0
        || resources.getIdentifier("settingslib_round_background_stateful", "drawable", SETTINGS) == 0) return;
    Resources.Theme prepared = resources.newTheme();
    prepared.setTo(activity.getTheme());
    prepared.applyStyle(style, true);
    activity.getTheme().setTo(prepared);
    owned.put(activity, style);
  }

  private void apply(Activity activity, Resources.Theme theme) {
    Integer style = owned.get(activity);
    if (style != null) theme.applyStyle(style, true);
  }

  private boolean owns(Context context) {
    Activity activity = activity(context);
    return activity != null && owned.containsKey(activity);
  }

  static Activity activity(Context context) {
    for (int depth = 0; depth < 32 && context != null; depth++) {
      if (context instanceof Activity activity) return activity;
      if (!(context instanceof ContextWrapper wrapper)) return null;
      Context next = wrapper.getBaseContext();
      if (next == context) return null;
      context = next;
    }
    return null;
  }

  static boolean eligible(String activityClass, Intent intent) {
    if (intent == null || (!ACTIVITY.equals(activityClass) && !SUB_SETTINGS.equals(activityClass))) return false;
    try {
      String fragment = intent.getStringExtra(SHOW_FRAGMENT);
      if (intent.hasExtra(SHOW_FRAGMENT) && !FRAGMENT.equals(fragment)) return false;
      if (SUB_SETTINGS.equals(activityClass) && !FRAGMENT.equals(fragment)) return false;
      Bundle args = intent.getBundleExtra(OosSettingsIntents.FRAGMENT_ARGS);
      return args != null && BluetoothAdapter.checkBluetoothAddress(args.getString("device_address"));
    } catch (RuntimeException malformed) {
      return false;
    }
  }

  private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }

  private void deoptimizeNamed(Class<?> type, String... names) {
    for (Method method : type.getDeclaredMethods()) {
      for (String name : names) if (name.equals(method.getName())) { deoptimize(method); break; }
    }
  }

  private void deoptimize(Executable method) {
    try { module.deoptimize(method); }
    catch (RuntimeException unavailable) { /* The hooks themselves remain valid. */ }
  }
}
