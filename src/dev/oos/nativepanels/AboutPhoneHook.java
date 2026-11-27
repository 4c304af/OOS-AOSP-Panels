package dev.oos.nativepanels;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Applies SettingsLib's native list tokens to the retained OOS About Device rows. */
final class AboutPhoneHook {
  static final String FRAGMENT = "com.oplus.settings.feature.deviceinfo.aboutphone.DeviceInfoFragment";
  static final String SCREEN = "my_device_info_pref_screen";
  private static final String SETTINGS = "com.android.settings";
  private static final int FINAL_STYLE_PRIORITY = 10000;
  private final ModuleEntry module;
  private final ClassLoader loader;
  private final Map<Object, Page> pages = Collections.synchronizedMap(new WeakHashMap<>());
  private final Map<Object, Page> adapters = Collections.synchronizedMap(new WeakHashMap<>());
  private final Map<View, Row> rows = Collections.synchronizedMap(new WeakHashMap<>());
  private final Map<View, AboutPhoneRows.CollapsedCategory> collapsed = Collections.synchronizedMap(new WeakHashMap<>());
  private Class<?> cardClass, categoryClass;
  private Method getKey, getTitle, getParent, getPreference, getPreferenceCount;
  private Method getItem, getItemCount, refreshColor, drawAsPath, updatePath;
  private Field holderView, cardPath, horizontalInset;

  AboutPhoneHook(ModuleEntry module, ClassLoader loader) {
    this.module = module;
    this.loader = loader;
  }

  void install() throws ReflectiveOperationException {
    Class<?> fragment = type(FRAGMENT);
    Class<?> baseFragment = type("androidx.fragment.app.Fragment");
    Class<?> preference = type("androidx.preference.Preference");
    Class<?> screen = type("androidx.preference.PreferenceScreen");
    Class<?> holder = type("androidx.preference.PreferenceViewHolder");
    Class<?> adapter = type("com.oplus.settings.feature.deviceinfo.aboutphone.OplusHighlightablePreferenceGroupAdapter");
    cardClass = type("com.coui.appcompat.cardlist.COUICardListSelectedItemLayout");
    categoryClass = type("androidx.preference.PreferenceCategory");
    getKey = preference.getMethod("getKey");
    getTitle = preference.getMethod("getTitle");
    getParent = preference.getMethod("getParent");
    Class<?> group = type("androidx.preference.PreferenceGroup");
    getPreference = group.getMethod("getPreference", int.class);
    getPreferenceCount = group.getMethod("getPreferenceCount");
    getItem = adapter.getMethod("getItem", int.class);
    getItemCount = adapter.getMethod("getItemCount");
    holderView = type("androidx.recyclerview.widget.RecyclerView$ViewHolder").getField("itemView");
    cardPath = field(cardClass, "mPath");
    horizontalInset = field(cardClass, "mHorizontalMargin");
    refreshColor = cardClass.getDeclaredMethod("refreshCardBg", int.class);
    drawAsPath = cardClass.getDeclaredMethod("changeDrawCanvasType", boolean.class);
    updatePath = cardClass.getDeclaredMethod("updatePath");
    updatePath.setAccessible(true);
    Method getContext = baseFragment.getMethod("getContext");
    Method getActivity = baseFragment.getMethod("getActivity");
    Method getList = fragment.getMethod("getListView");
    Method setup = type("com.google.android.setupcompat.util.WizardManagerHelper")
        .getDeclaredMethod("isAnySetupWizard", Intent.class);
    Method createAdapter = fragment.getDeclaredMethod("onCreateAdapter", screen);
    Method bind = adapter.getDeclaredMethod("onBindViewHolder", holder, int.class);
    Method created = fragment.getDeclaredMethod("onViewCreated", View.class, Bundle.class);
    Method configuration = fragment.getDeclaredMethod("onConfigurationChanged", Configuration.class);
    Method destroyed = baseFragment.getDeclaredMethod("onDestroyView");
    Method specPosition = type("com.oplus.settings.feature.deviceinfo.AboutPhoneSpecCardListSelectedItemLayout")
        .getDeclaredMethod("setPositionInGroup", int.class);
    Field backdrop = field(fragment, "mDeviceInfoListBgImage");
    if (cardPath.getType() != Path.class || horizontalInset.getType() != int.class
        || !View.class.isAssignableFrom(cardClass) || setup.getReturnType() != boolean.class)
      throw new NoSuchMethodException("Unsupported About Device card contract");

    module.hook(createAdapter).setId("native-panels:about-phone-adapter")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          Object result = c.proceed();
          Object owner = c.getThisObject();
          if (!matchesScreen(owner.getClass().getName(), (String)getKey.invoke(c.getArg(0)))) return result;
          Page previous = pages.remove(owner);
          if (previous != null) previous.active = false;
          Context context = (Context)getContext.invoke(owner);
          Activity activity = (Activity)getActivity.invoke(owner);
          if (Build.VERSION.SDK_INT < 36 || context == null || activity == null || result == null
              || (boolean)setup.invoke(null, activity.getIntent()) || !AboutPhoneSettings.readEnabled(context))
            return result;
          Metrics metrics = Metrics.read(context);
          if (metrics != null && adapter.isInstance(result)) {
            Page page = new Page(metrics);
            pages.put(owner, page);
            adapters.put(result, page);
          }
          return result;
        });
    module.hook(bind).setId("native-panels:about-phone-bind").setPriority(FINAL_STYLE_PRIORITY)
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          // Restore before native binding: a category holder can next represent a
          // titled legal/version section, whose normal height must not stay zero.
          View itemView = (View)holderView.get(c.getArg(0));
          AboutPhoneRows.CollapsedCategory original = collapsed.remove(itemView);
          if (original != null) original.restore(itemView);
          Object result = c.proceed();
          Object source = c.getThisObject();
          Page page = adapters.get(source);
          if (page != null && page.active) bind(source, c.getArg(0), (int)c.getArg(1), page);
          return result;
        });
    module.hook(updatePath).setId("native-panels:about-phone-path").setPriority(FINAL_STYLE_PRIORITY)
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          Object result = c.proceed();
          View view = (View)c.getThisObject();
          Row row = rows.get(view);
          if (row != null && row.page.active) shape(view, row);
          return result;
        });
    module.hook(created).setId("native-panels:about-phone-created")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          Object result = c.proceed();
          Page page = pages.get(c.getThisObject());
          if (page != null) {
            applyListFallback((View)getList.invoke(c.getThisObject()), (View)backdrop.get(c.getThisObject()), page.metrics.surface);
          }
          return result;
        });
    module.hook(configuration).setId("native-panels:about-phone-configuration")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          Object result = c.proceed();
          Page page = pages.get(c.getThisObject());
          View list = page == null ? null : (View)getList.invoke(c.getThisObject());
          if (list != null) {
            Metrics metrics = Metrics.read(list.getContext());
            if (metrics != null) {
              page.metrics = metrics;
              applyListFallback(list, (View)backdrop.get(c.getThisObject()), metrics.surface);
              synchronized (rows) {
                for (Map.Entry<View, Row> entry : rows.entrySet()) {
                  if (entry.getValue().page == page) style(entry.getKey(), entry.getValue());
                }
              }
            }
          }
          return result;
        });
    module.hook(destroyed).setId("native-panels:about-phone-destroy")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          try { return c.proceed(); }
          finally {
            Page page = pages.remove(c.getThisObject());
            if (page != null) page.active = false;
          }
        });
    // The OEM inlines updatePath into its draw/size/group routines. Deoptimize only
    // these native callers; drawing does one weak lookup, with no preference queries.
    for (String name : new String[]{"draw", "onSizeChanged", "setPositionInGroup", "setRadius"}) {
      for (Method method : cardClass.getDeclaredMethods()) if (name.equals(method.getName())) deoptimize(method);
    }
    deoptimize(specPosition);
    deoptimize(bind);
  }

  private void bind(Object adapter, Object holder, int position, Page page) throws ReflectiveOperationException {
    View view = (View)holderView.get(holder);
    AboutPhoneRows.Item current = item(adapter, position);
    if (current != null && current.hardwareContainer()) {
      rows.remove(view);
      AboutPhoneRows.CollapsedCategory original = AboutPhoneRows.CollapsedCategory.collapse(view);
      if (original != null) collapsed.put(view, original);
      return;
    }
    if (!cardClass.isInstance(view) || !AboutPhoneRows.card(current)) {
      rows.remove(view);
      return;
    }
    // Always replace state: holders may be recycled from a first row into a last/single row.
    AboutPhoneRows.Item before = neighbor(adapter, position, -1);
    AboutPhoneRows.Item after = neighbor(adapter, position, 1);
    Row row = new Row(page, AboutPhoneRows.position(before, current, after),
        AboutPhoneRows.sectionBreak(current, after));
    rows.put(view, row);
    style(view, row);
  }

  private AboutPhoneRows.Item item(Object adapter, int position) throws ReflectiveOperationException {
    if (position < 0 || position >= (int)getItemCount.invoke(adapter)) return null;
    Object preference = getItem.invoke(adapter, position);
    if (preference == null) return null;
    Object parent = getParent.invoke(preference);
    if (isHardwareContainer(parent)) parent = getParent.invoke(parent);
    return new AboutPhoneRows.Item((String)getKey.invoke(preference), parent,
        categoryClass.isInstance(preference), isHardwareContainer(preference));
  }

  private AboutPhoneRows.Item neighbor(Object adapter, int position, int direction) throws ReflectiveOperationException {
    AboutPhoneRows.Item item;
    do { item = item(adapter, position += direction); }
    while (item != null && item.hardwareContainer());
    return item;
  }

  private boolean isHardwareContainer(Object preference) throws ReflectiveOperationException {
    if (!categoryClass.isInstance(preference)) return false;
    Object parent = getParent.invoke(preference);
    if (parent == null || !SCREEN.equals(getKey.invoke(parent))) return false;
    int count = (int)getPreferenceCount.invoke(preference);
    if (count < 1 || count > 2) return false;
    ArrayList<String> keys = new ArrayList<>(count);
    for (int i = 0; i < count; i++) keys.add((String)getKey.invoke(getPreference.invoke(preference, i)));
    return AboutPhoneRows.hardwareContainer((String)getKey.invoke(preference),
        (CharSequence)getTitle.invoke(preference), keys);
  }

  private void style(View view, Row row) throws ReflectiveOperationException {
    AboutPhoneRows.radii(row.radii, row.position, row.page.metrics.outer, row.page.metrics.inner);
    AboutPhoneRows.spacing(view, row.sectionEnd ? row.page.metrics.sectionGap : row.page.metrics.gap);
    refreshColor.invoke(view, row.page.metrics.card);
    // Keep the OEM background, press effects, storage bar and all listeners. Clipping
    // the final native path also constrains another module's full-round ripple mask.
    drawAsPath.invoke(view, false);
    updatePath.invoke(view);
    view.invalidateOutline();
    view.invalidate();
  }

  private void shape(View view, Row row) throws IllegalAccessException {
    Path path = (Path)cardPath.get(view);
    if (path != null) AboutPhoneRows.path(path, row.bounds, row.radii, view.getWidth(), view.getHeight(),
        horizontalInset.getInt(view));
  }

  static void applyListFallback(View list, View backdrop, int surface) {
    // DeviceInfoFragment installs the artwork behind a transparent RecyclerView.
    // Its visibility decision is already available before the views are attached.
    if (list != null && (backdrop == null || backdrop.getVisibility() != View.VISIBLE)) {
      list.setBackgroundColor(surface);
    }
  }

  static boolean matchesScreen(String fragment, String key) { return FRAGMENT.equals(fragment) && SCREEN.equals(key); }

  private static final class Page {
    Metrics metrics;
    boolean active = true;
    Page(Metrics metrics) { this.metrics = metrics; }
  }

  private static final class Row {
    final Page page;
    final int position;
    final boolean sectionEnd;
    final float[] radii = new float[8];
    final RectF bounds = new RectF();
    Row(Page page, int position, boolean sectionEnd) {
      this.page = page;
      this.position = position;
      this.sectionEnd = sectionEnd;
    }
  }

  record Metrics(float outer, float inner, int gap, int sectionGap, int card, int surface) {
    static Metrics read(Context context) {
      Resources r = context.getResources();
      int outer = r.getIdentifier("settingslib_preference_corner_radius", "dimen", SETTINGS);
      int inner = r.getIdentifier("settingslib_expressive_radius_extrasmall2", "dimen", SETTINGS);
      int gap = r.getIdentifier("settingslib_expressive_space_extrasmall1", "dimen", SETTINGS);
      // Same token as settingslib_expressive_untitled_preference_category's top margin.
      int sectionGap = r.getIdentifier("settingslib_expressive_space_extrasmall6", "dimen", SETTINGS);
      int card = r.getIdentifier("settingslib_materialColorSurfaceBright", "color", SETTINGS);
      // For a plain light page use the Settings Expressive window color: Surface
      // itself has the same tone as the bright rows. Keep the existing night palette.
      boolean dark = (r.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
      int surface = r.getIdentifier(dark ? "settingslib_materialColorSurface" : "settingslib_materialColorSurfaceContainer",
          "color", SETTINGS);
      if (outer == 0 || inner == 0 || gap == 0 || sectionGap == 0 || card == 0 || surface == 0) return null;
      try {
        // These are exactly the resources used by SettingsLib's v36 grouped list
        // drawables (20dp outer, 4dp inner on the inspected OOS build) and decoration.
        return new Metrics(r.getDimension(outer), r.getDimension(inner), r.getDimensionPixelSize(gap),
            r.getDimensionPixelSize(sectionGap), context.getColor(card), context.getColor(surface));
      } catch (Resources.NotFoundException unavailable) { return null; }
    }
  }

  private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
  private static Field field(Class<?> type, String name) throws NoSuchFieldException {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }
  private void deoptimize(Method method) {
    try { module.deoptimize(method); } catch (RuntimeException ignored) {}
  }
}
