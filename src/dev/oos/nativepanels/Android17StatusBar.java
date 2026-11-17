package dev.oos.nativepanels;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.DrawableWrapper;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class Android17StatusBar {
  private final PanelRuntime runtime;
  private volatile Boolean enabled;
  private volatile boolean clockStyled;
  private volatile boolean wifiStyled;
  private volatile boolean mobileStyled;

  Android17StatusBar(PanelRuntime runtime) { this.runtime = runtime; }

  boolean enabled(Context context) {
    if (Build.VERSION.SDK_INT != 37) return false;
    if (enabled != null) return enabled;
    String key = CombinedQsSettings.ANDROID_17_STATUS_BAR;
    try {
      Bundle result = context.getContentResolver().call(CombinedQsSettings.URI, key, null, null);
      if (result != null && result.containsKey(key)) enabled = result.getBoolean(key);
    } catch (RuntimeException ignored) {
      // The module provider may still be starting. Try again at the next attachment.
    }
    return Boolean.TRUE.equals(enabled);
  }

  String report() {
    return "Android 17 status bar: enabled=" + enabled + " clock=" + clockStyled
        + " wifi=" + wifiStyled + " mobile=" + mobileStyled;
  }

  void installClock() throws ReflectiveOperationException {
    if (Build.VERSION.SDK_INT != 37) return;
    Class<?> bar = runtime.type("com.android.systemui.statusbar.phone.PhoneStatusBarView");
    runtime.module.hook(bar.getDeclaredMethod("onAttachedToWindow"))
        .setId("native-panels:android17-clock-attach")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          Object result = chain.proceed();
          View view = (View) chain.getThisObject();
          if (enabled(view.getContext())) {
            int id = view.getResources().getIdentifier("clock", "id", Protocol.TARGET);
            if (id != 0 && view.findViewById(id) instanceof TextView clock) applyClock(clock);
          }
          return result;
        });

    try {
      Class<?> clock = runtime.type("com.oplus.systemui.statusbar.widget.StatClock");
      Method reload = clock.getDeclaredMethod("reloadStatClockDimens");
      runtime.module.hook(reload).setId("native-panels:android17-clock-dimensions")
          .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            TextView view = (TextView) chain.getThisObject();
            if (inPhoneStatusBar(view, bar) && enabled(view.getContext())) applyClock(view);
            return result;
          });
    } catch (ReflectiveOperationException | RuntimeException error) {
      runtime.module.log(Log.WARN, "OOSNativePanels", "Android 17 clock configuration", error);
    }
  }

  private static boolean inPhoneStatusBar(View clock, Class<?> bar) {
    for (ViewParent parent = clock.getParent(); parent != null; parent = parent.getParent())
      if (bar.isInstance(parent)) return true;
    return false;
  }

  private void applyClock(TextView clock) {
    Typeface face = Typeface.create("variable-label-large-emphasized", Typeface.NORMAL);
    if (face.equals(Typeface.DEFAULT)) face = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    clock.setTypeface(face);
    clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
    int end = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 2f,
        clock.getResources().getDisplayMetrics()));
    clock.setPaddingRelative(0, clock.getPaddingTop(), end, clock.getPaddingBottom());
    clockStyled = true;
  }

  void installWifi() throws ReflectiveOperationException {
    if (Build.VERSION.SDK_INT != 37) return;
    Class<?> extension = runtime.type("com.oplus.systemui.statusbar.pipeline.OplusWifiSignalExImpl");
    Class<?> active = runtime.type("com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel$Active");
    Class<?> wifiIcons = runtime.type("com.android.systemui.statusbar.connectivity.WifiIcons");
    Field fullIcons = wifiIcons.getField("WIFI_FULL_ICONS");
    Field limitedIcons = wifiIcons.getField("WIFI_NO_INTERNET_ICONS");
    Field contextField = Reflect.field(extension, "context");
    Method getLevel = active.getMethod("getLevel");
    Method showExclamation = active.getMethod("getShowExclamation");
    runtime.module.hook(extension.getDeclaredMethod("wifiSignalResId", active))
        .setId("native-panels:android17-wifi")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          Context context = (Context) contextField.get(chain.getThisObject());
          if (enabled(context)) {
            Object model = chain.getArg(0);
            int level = (int) getLevel.invoke(model);
            Field icons = (boolean) showExclamation.invoke(model) ? limitedIcons : fullIcons;
            int[] byLevel = (int[]) icons.get(null);
            if (level >= 0 && level < byLevel.length && byLevel[level] != 0) {
              wifiStyled = true;
              return byLevel[level];
            }
          }
          return chain.proceed();
        });
  }

  void installMobile() throws ReflectiveOperationException {
    if (Build.VERSION.SDK_INT != 37) return;
    Class<?> binding = runtime.type("com.oplus.systemui.statusbar.pipeline.mobile.ui.view.AbstractOplusStatusBarMobileViewBinder$Binding");
    String signal = "com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel";
    Class<?> cellular = runtime.type(signal + "$CellularTypeIconModel$Cellular");
    Method iconView = binding.getMethod("getIconView");
    Method getLevel = cellular.getMethod("getLevel");
    Method getLevels = cellular.getMethod("getNumberOfLevels");
    Method showExclamation = cellular.getMethod("getShowExclamationMark");
    Method carrierChange = cellular.getMethod("getCarrierNetworkChange");
    Class<? extends DrawableWrapper> drawableType =
        runtime.type("com.android.settingslib.graph.SignalDrawable").asSubclass(DrawableWrapper.class);
    Constructor<? extends DrawableWrapper> drawable = drawableType.getConstructor(Context.class);
    Method state = drawableType.getMethod("getState", int.class, int.class, boolean.class);
    runtime.module.hook(binding.getDeclaredMethod("updateSignalIcon", runtime.type(signal)))
        .setId("native-panels:android17-mobile")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          Object result = chain.proceed();
          Object model = chain.getArg(0);
          if (!cellular.isInstance(model)) return result;
          ImageView icon = (ImageView) iconView.invoke(chain.getThisObject());
          if (!enabled(icon.getContext())) return result;
          int levels = (int) getLevels.invoke(model);
          int level = (int) getLevel.invoke(model);
          if (levels < 2 || levels > 6 || level < 0 || level >= levels) return result;

          DrawableWrapper glyph = drawable.newInstance(icon.getContext());
          // Use the AOSP artwork and the host's level decoding and carrier-change animation.
          glyph.setDrawable(runtime.moduleResources(icon.getContext()).getDrawable(R.drawable.ic_mobile_level_list, null));
          int packed = (boolean) carrierChange.invoke(model) ? (3 << 16) | (levels << 8)
              : (int) state.invoke(null, level, levels, (boolean) showExclamation.invoke(model));
          icon.setImageDrawable(glyph);
          glyph.setLevel(packed);
          mobileStyled = true;
          return result;
        });
  }
}
