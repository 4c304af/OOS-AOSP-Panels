package dev.oos.nativepanels;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

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
    Class<?> clock = runtime.type("com.oplus.systemui.statusbar.widget.StatClock");
    Method refreshWidth = clock.getMethod("refreshWidth");
    for (String lifecycle : new String[]{"onAttachedToWindow", "reloadStatClockDimens"}) {
      runtime.module.hook(clock.getDeclaredMethod(lifecycle)).setId("native-panels:android17-clock-" + lifecycle)
          .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            TextView view = (TextView) chain.getThisObject();
            if (inPhoneStatusBar(view, bar) && enabled(view.getContext())) {
              // StatClock measures with firmware dimensions and caches the minute width for transitions.
              Typeface face = Typeface.create("variable-label-large-emphasized", Typeface.NORMAL);
              if (face.equals(Typeface.DEFAULT)) face = Typeface.create("sans-serif-medium", Typeface.NORMAL);
              if (!face.equals(view.getTypeface())) {
                view.setTypeface(face);
                refreshWidth.invoke(view);
              }
              clockStyled = true;
            }
            return result;
          });
    }
  }

  private static boolean inPhoneStatusBar(View clock, Class<?> bar) {
    for (ViewParent parent = clock.getParent(); parent != null; parent = parent.getParent())
      if (bar.isInstance(parent)) return true;
    return false;
  }

  void installWifi() throws ReflectiveOperationException {
    if (Build.VERSION.SDK_INT != 37) return;
    String model = "com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel";
    String wifiIcon = "com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon";
    Class<?> active = runtime.type(model + "$Active");
    Class<?> visible = runtime.type(wifiIcon + "$Visible");
    Class<?> wifiIcons = runtime.type("com.android.systemui.statusbar.connectivity.WifiIcons");
    Class<?> binder = runtime.type("com.android.systemui.common.ui.binder.IconViewBinder");
    Method bind = binder.getDeclaredMethod("bind",
        runtime.type("com.android.systemui.common.shared.model.Icon"), ImageView.class);
    Method fromModel = runtime.type(wifiIcon + "$Companion").getDeclaredMethod(
        "fromModel", runtime.type(model), Context.class, boolean.class);
    Field fullIcons = wifiIcons.getField("WIFI_FULL_ICONS");
    Field limitedIcons = wifiIcons.getField("WIFI_NO_INTERNET_ICONS");
    Method getIcon = visible.getMethod("getIcon");
    Method getLevel = active.getMethod("getLevel");
    Method showExclamation = active.getMethod("getShowExclamation");
    Method hotspotType = active.getMethod("getHotspotDeviceType");
    List<WifiArtwork> replacements = new ArrayList<>();
    runtime.module.hook(bind).setId("native-panels:android17-wifi-bind")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          Object result = chain.proceed();
          int artId = 0;
          synchronized (replacements) {
            for (WifiArtwork replacement : replacements) {
              if (replacement.icon().get() == chain.getArg(0)) {
                artId = replacement.resourceId();
                break;
              }
            }
          }
          if (artId == 0) return result;
          ImageView icon = (ImageView) chain.getArg(1);
          int signalId = icon.getResources().getIdentifier("wifi_signal", "id", Protocol.TARGET);
          Drawable host = icon.getDrawable();
          if (signalId != 0 && icon.getId() == signalId && hasIntrinsicSize(host)) {
            icon.setImageDrawable(new SlotDrawable(icon.getContext().getDrawable(artId), host));
            wifiStyled = true;
          }
          return result;
        });
    runtime.module.hook(fromModel).setId("native-panels:android17-wifi-model")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          Object result = chain.proceed();
          Object network = chain.getArg(0);
          if (!visible.isInstance(result) || !active.isInstance(network)
              || !enabled((Context) chain.getArg(1))) return result;
          if (Boolean.TRUE.equals(chain.getArg(2))
              && !"NONE".equals(((Enum<?>) hotspotType.invoke(network)).name())) return result;
          int level = (int) getLevel.invoke(network);
          Field icons = (boolean) showExclamation.invoke(network) ? limitedIcons : fullIcons;
          int[] byLevel = (int[]) icons.get(null);
          if (level >= 0 && level < byLevel.length && byLevel[level] != 0) {
            WifiArtwork replacement = new WifiArtwork(new WeakReference<>(getIcon.invoke(result)), byLevel[level]);
            synchronized (replacements) {
              replacements.removeIf(previous -> previous.icon().get() == null);
              replacements.add(replacement);
            }
          }
          return result;
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
          Drawable host = icon.getDrawable();
          if (!enabled(icon.getContext()) || !hasIntrinsicSize(host)) return result;
          int levels = (int) getLevels.invoke(model);
          int level = (int) getLevel.invoke(model);
          if (levels < 2 || levels > 6 || level < 0 || level >= levels) return result;

          DrawableWrapper glyph = drawable.newInstance(icon.getContext());
          // Use the AOSP artwork and the host's level decoding and carrier-change animation.
          glyph.setDrawable(runtime.moduleResources(icon.getContext()).getDrawable(R.drawable.ic_mobile_level_list, null));
          int packed = (boolean) carrierChange.invoke(model) ? (3 << 16) | (levels << 8)
              : (int) state.invoke(null, level, levels, (boolean) showExclamation.invoke(model));
          Drawable replacement = new SlotDrawable(glyph, host);
          icon.setImageDrawable(replacement);
          replacement.setLevel(packed);
          mobileStyled = true;
          return result;
        });
  }

  private static boolean hasIntrinsicSize(Drawable drawable) {
    return drawable != null && drawable.getIntrinsicWidth() > 0 && drawable.getIntrinsicHeight() > 0;
  }

  private record WifiArtwork(WeakReference<Object> icon, int resourceId) {}

  // The vendor uses wrap_content for both ImageViews, so intrinsic size also controls neighboring positions.
  static final class SlotDrawable extends DrawableWrapper {
    private final int width;
    private final int height;

    SlotDrawable(Drawable art, Drawable host) {
      super(art);
      width = host.getIntrinsicWidth();
      height = host.getIntrinsicHeight();
    }

    @Override public int getIntrinsicWidth() { return width; }
    @Override public int getIntrinsicHeight() { return height; }

    @Override protected void onBoundsChange(Rect bounds) {
      Drawable art = getDrawable();
      if (!hasIntrinsicSize(art) || bounds.isEmpty()) {
        super.onBoundsChange(bounds);
        return;
      }
      float scale = Math.min(1f, Math.min(bounds.width() / (float) art.getIntrinsicWidth(),
          bounds.height() / (float) art.getIntrinsicHeight()));
      int artWidth = Math.round(art.getIntrinsicWidth() * scale);
      int artHeight = Math.round(art.getIntrinsicHeight() * scale);
      int left = bounds.left + (bounds.width() - artWidth) / 2;
      int top = bounds.top + (bounds.height() - artHeight) / 2;
      art.setBounds(left, top, left + artWidth, top + artHeight);
    }
  }
}
