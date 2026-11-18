package dev.oos.nativepanels;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
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
  // A strong value here would retain its View, and thereby its weak Activity key.
  private final Map<Activity, WeakReference<BluetoothDetailsBottomPadding>> bottomPadding =
      Collections.synchronizedMap(new WeakHashMap<>());

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
    Class<?> oosFragment = type("com.oplus.settings.SettingsBaseFragment");
    Method spacing = oosFragment.getDeclaredMethod("isPreferenceSpacingEnabled");
    Method fragmentContext = type("androidx.fragment.app.Fragment").getMethod("getContext");
    if (spacing.getReturnType() != boolean.class)
      throw new NoSuchMethodException("Unsupported Bluetooth Settings spacing contract");

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
    module.hook(spacing).setId("native-panels:bluetooth-details-spacing")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          Object screen = c.getThisObject();
          if (!FRAGMENT.equals(screen.getClass().getName())) return c.proceed();
          return nativePreferenceSpacing(screen.getClass().getName(), (Context)fragmentContext.invoke(screen))
              ? true : c.proceed();
        });
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
          finally {
            clearBottomPadding((Activity)c.getThisObject());
            owned.remove(c.getThisObject());
          }
        });

    installBottomPadding(base, adapter, fragmentContext);

    // The OEM's constant-false provider and small helper can have been inlined
    // before LSPosed loads. Retain the original constructors, adapters and callbacks.
    deoptimize(helper);
    deoptimize(create);
    deoptimize(settings.getDeclaredMethod("createUiFromIntent", Bundle.class, Intent.class));
    deoptimizeNamed(buttons, "init", "updateLayout", "onBindViewHolder");
    deoptimizeNamed(adapter, "updatePreferencesList", "onPreferenceHierarchyChange", "onBindViewHolder");
    deoptimizeNamed(fragment, "onCreateAdapter", "onViewCreated");
    // OOS separately disables the native MarginItemDecoration with a constant-false
    // getter. Let SettingsLib install it once: ordinary rows use its 2dp token,
    // while ChainedMixin items retain the native zero-gap rule.
    deoptimizeNamed(oosFragment, "onViewCreated");
    deoptimizeNamed(type("com.android.settings.widget.HighlightablePreferenceGroupAdapter"),
        "getBackgroundRes");
    for (java.lang.reflect.Constructor<?> constructor : buttons.getDeclaredConstructors()) deoptimize(constructor);
    for (java.lang.reflect.Constructor<?> constructor : adapter.getDeclaredConstructors()) deoptimize(constructor);
  }

  private void installBottomPadding(Class<?> base, Class<?> adapter, Method fragmentContext)
      throws ReflectiveOperationException {
    Class<?> details = type(FRAGMENT);
    Method created = details.getDeclaredMethod("onViewCreated", View.class, Bundle.class);
    Method destroyed = Reflect.method(details, "onDestroyView");
    Method getList = Reflect.method(details, "getListView");
    Method getAdapter = type("androidx.recyclerview.widget.RecyclerView").getMethod("getAdapter");
    Method position = adapter.getMethod("getPreferenceAdapterPosition", String.class);
    Method footerHeight = type("com.oplus.settings.utils.SettingsUtils").getDeclaredMethod("getFootViewHeight", Activity.class);
    Method insets = base.getDeclaredMethod("lambda$onCreate$0", View.class, WindowInsets.class);
    Method hierarchy = adapter.getDeclaredMethod("onPreferenceHierarchyChange", type("androidx.preference.Preference"));
    Method preferenceContext = type("androidx.preference.Preference").getMethod("getContext");
    if (footerHeight.getReturnType() != int.class || position.getReturnType() != int.class
        || insets.getReturnType() != WindowInsets.class)
      throw new NoSuchMethodException("Unsupported Bluetooth Settings footer contract");

    module.hook(created).setId("native-panels:bluetooth-details-scroll-end")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          Object result = c.proceed();
          Object screen = c.getThisObject();
          Context context = (Context)fragmentContext.invoke(screen);
          if (!nativePreferenceSpacing(screen.getClass().getName(), context)) return result;
          Activity activity = activity(context);
          View list = (View)getList.invoke(screen);
          if (list == null) return result;
          clearBottomPadding(activity);
          WeakReference<Activity> owner = new WeakReference<>(activity);
          BluetoothDetailsBottomPadding padding = new BluetoothDetailsBottomPadding(list, () -> {
            Activity current = owner.get();
            if (current == null || !owned.containsKey(current)) return 0;
            try {
              Object currentAdapter = getAdapter.invoke(list);
              if (currentAdapter == null) return -1;
              // The flattened adapter accounts for invisible parent categories.
              // Some device layouts retain the OEM footer; never add it twice.
              if ((int)position.invoke(currentAdapter, "oplus_preference_footer") >= 0) return 0;
              // This OEM method includes navigation height only for gestures.
              // Three-button navigation is already handled by the parent listener;
              // IME height and the original inset dispatch remain untouched.
              return Math.max(0, (int)footerHeight.invoke(null, current));
            } catch (ReflectiveOperationException | RuntimeException unavailable) { return -1; }
          });
          bottomPadding.put(activity, new WeakReference<>(padding));
          return result;
        });
    module.hook(destroyed).setId("native-panels:bluetooth-details-scroll-end-release")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          if (FRAGMENT.equals(c.getThisObject().getClass().getName())) {
            Activity activity = activity((Context)fragmentContext.invoke(c.getThisObject()));
            WeakReference<BluetoothDetailsBottomPadding> reference = bottomPadding.get(activity);
            BluetoothDetailsBottomPadding padding = reference == null ? null : reference.get();
            if (padding != null && padding.forList((View)getList.invoke(c.getThisObject())))
              clearBottomPadding(activity);
          }
          return c.proceed();
        });
    module.hook(insets).setId("native-panels:bluetooth-details-scroll-end-insets")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          Object result = c.proceed();
          refreshBottomPadding((Activity)c.getThisObject());
          return result;
        });
    module.hook(hierarchy).setId("native-panels:bluetooth-details-scroll-end-layout")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          Object result = c.proceed();
          refreshBottomPadding(activity((Context)preferenceContext.invoke(c.getArg(0))));
          return result;
        });
    deoptimize(created);
    deoptimize(destroyed);
    deoptimize(insets);
    // Verified direct caller of the synthetic inset method in this OOS APK.
    try { deoptimizeNamed(type("com.android.settings.core.j"), "onApplyWindowInsets"); }
    catch (ClassNotFoundException renamed) { /* The named native method remains hooked. */ }
    deoptimize(hierarchy);
  }

  private void refreshBottomPadding(Activity activity) {
    WeakReference<BluetoothDetailsBottomPadding> reference = bottomPadding.get(activity);
    BluetoothDetailsBottomPadding padding = reference == null ? null : reference.get();
    if (padding != null) padding.schedule();
  }

  private void clearBottomPadding(Activity activity) {
    WeakReference<BluetoothDetailsBottomPadding> reference = bottomPadding.remove(activity);
    BluetoothDetailsBottomPadding padding = reference == null ? null : reference.get();
    if (padding != null) padding.dispose();
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

  boolean nativePreferenceSpacing(String fragmentClass, Context context) {
    return FRAGMENT.equals(fragmentClass) && owns(context);
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
