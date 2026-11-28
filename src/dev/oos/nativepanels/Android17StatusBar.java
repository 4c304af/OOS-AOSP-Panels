package dev.oos.nativepanels;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
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
  private volatile String lastNetworkType = "pending";
  private volatile WeakReference<Context> mobileContext = new WeakReference<>(null);
  private NetworkTypeBinding networkTypeBinding;
  private final AospNetworkTypeArtwork networkTypeArtwork = new AospNetworkTypeArtwork();

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
        + " wifi=" + wifiStyled + " mobile=" + mobileStyled + " type=" + lastNetworkType
        + " font=" + ClockTypeface.report() + " icons=12sp";
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
              if (ClockTypeface.apply(view)) refreshWidth.invoke(view);
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
    installIconConfiguration("com.android.systemui.statusbar.pipeline.wifi.ui.view.ModernStatusBarWifiView", false);
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
            Drawable art = icon.getContext().getDrawable(artId);
            icon.setImageDrawable(StatusIconMetrics.signal(icon, art,
                art.getIntrinsicWidth() / (float)art.getIntrinsicHeight()));
            View group = StatusIconMetrics.namedAncestor(icon, "wifi_group");
            if (group != null) StatusIconMetrics.group(group);
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
    installIconConfiguration("com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView", true);
    Class<?> binding = runtime.type("com.oplus.systemui.statusbar.pipeline.mobile.ui.view.AbstractOplusStatusBarMobileViewBinder$Binding");
    String signal = "com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel";
    Class<?> cellular = runtime.type(signal + "$CellularTypeIconModel$Cellular");
    Method iconView = binding.getMethod("getIconView");
    Method getLevel = cellular.getMethod("getLevel");
    Method getLevels = cellular.getMethod("getNumberOfLevels");
    Method showExclamation = cellular.getMethod("getShowExclamationMark");
    Method carrierChange = cellular.getMethod("getCarrierNetworkChange");
    installNetworkType(binding, cellular, carrierChange);
    Class<? extends DrawableWrapper> drawableType =
        runtime.type("com.android.settingslib.graph.SignalDrawable").asSubclass(DrawableWrapper.class);
    Constructor<? extends DrawableWrapper> drawable = drawableType.getConstructor(Context.class);
    Method state = drawableType.getMethod("getState", int.class, int.class, boolean.class);
    runtime.module.hook(binding.getDeclaredMethod("updateSignalIcon", runtime.type(signal)))
        .setId("native-panels:android17-mobile")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          Object result = chain.proceed();
          Object model = chain.getArg(0);
          // Signal transitions also clear a stale RAT label on no-service and satellite changes.
          networkTypeBinding.refresh(chain.getThisObject());
          if (!cellular.isInstance(model)) return result;
          ImageView icon = (ImageView) iconView.invoke(chain.getThisObject());
          Drawable host = icon.getDrawable();
          if (!enabled(icon.getContext()) || !hasIntrinsicSize(host)) return result;
          int levels = (int) getLevels.invoke(model);
          int level = (int) getLevel.invoke(model);
          if (levels < 2 || levels > 6 || level < 0 || level >= levels) return result;

          DrawableWrapper glyph = drawable.newInstance(icon.getContext());
          // Use the AOSP artwork and the host's level decoding and carrier-change animation.
          Drawable art = runtime.moduleResources(icon.getContext()).getDrawable(R.drawable.ic_mobile_level_list, null);
          glyph.setDrawable(art);
          int packed = (boolean) carrierChange.invoke(model) ? (3 << 16) | (levels << 8)
              : (int) state.invoke(null, level, levels, (boolean) showExclamation.invoke(model));
          Drawable replacement = StatusIconMetrics.mobileSignal(icon, glyph, art, packed);
          icon.setImageDrawable(replacement);
          replacement.setLevel(packed);
          mobileStyled = true;
          return result;
        });
  }

  private void installNetworkType(Class<?> binding, Class<?> cellular, Method carrierChange)
      throws ReflectiveOperationException {
    String models = "com.oplus.systemui.statusbar.pipeline.mobile.ui.viewmodel.";
    Class<?> provider = runtime.type(models + "OplusCellularIconViewModelImpl");
    // Initialize the same process-scoped preference before its stateIn flows start collecting.
    for (Constructor<?> constructor : provider.getDeclaredConstructors()) {
      if (constructor.getParameterCount() == 6 && constructor.getParameterTypes()[0] == Context.class) {
        runtime.module.hook(constructor).setId("native-panels:android17-network-context")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
              Context context = (Context) chain.getArg(0);
              mobileContext = new WeakReference<>(context);
              enabled(context);
              return chain.proceed();
            });
      }
    }
    Class<?> iconModel = runtime.type("com.android.systemui.statusbar.pipeline.mobile.domain.model.NetworkTypeIconModel");
    Method nativeText = iconModel.getMethod("getNetworkTypeTextModel");
    Class<?> transform = runtime.type(models + "OplusCellularIconViewModelImpl$networkTypeText$1");
    Field currentIcon = Reflect.field(transform, "L$0");
    runtime.module.hook(transform.getDeclaredMethod("invokeSuspend", Object.class))
        .setId("native-panels:android17-network-model")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          Object result = chain.proceed();
          Context context = mobileContext.get();
          if (result != null || context == null || !enabled(context)) return result;
          // ColorOS gates this text on dataConnected && mobileSubIsDefault (or alwaysShow).
          // Keep its current carrier-mapped RAT, including actual NR overrides, while Wi-Fi is default.
          Object icon = currentIcon.get(chain.getThisObject());
          return icon == null ? null : nativeText.invoke(icon);
        });

    Class<?> os17 = runtime.type("com.oplus.systemui.statusbar.pipeline.mobile.ui.view.OplusStatusBarMobileViewBinder$Os17Binding");
    Class<?> viewModel = runtime.type(models + "OplusMobileIconViewModel");
    Class<?> textModel = runtime.type("com.android.systemui.statusbar.pipeline.mobile.domain.model.OplusNetworkTypeTextModel");
    Method bind = os17.getDeclaredMethod("bindNetworkTypeText", textModel);
    Method combo = os17.getMethod("getMobileComboReal");
    Method mobileGroup = os17.getMethod("getMobileGroupView");
    Method mobileSignal = os17.getMethod("getIconView");
    MobileNetworkTypeOrder order = new MobileNetworkTypeOrder(
        runtime.type("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams"));
    networkTypeBinding = new NetworkTypeBinding(os17, cellular, binding.getMethod("getOplusViewModel"),
        os17.getMethod("getMobileTypeText"), viewModel.getMethod("getIcon"),
        viewModel.getMethod("getNetworkTypeText"),
        runtime.type("kotlinx.coroutines.flow.StateFlow").getMethod("getValue"),
        Reflect.field(cellular, "isInService"), carrierChange, bind);
    runtime.module.hook(os17.getDeclaredMethod("initViews", int.class))
        .setId("native-panels:android17-network-order")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          Object result = chain.proceed();
          Object owner = chain.getThisObject();
          TextView text = (TextView) networkTypeBinding.text.invoke(owner);
          if (enabled(text.getContext())) {
            order.apply((ViewGroup) combo.invoke(owner), text);
            StatusIconMetrics.mobile((View) mobileGroup.invoke(owner), text, (ImageView) mobileSignal.invoke(owner));
          }
          return result;
        });
    runtime.module.hook(bind).setId("native-panels:android17-network-text")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          Object owner = chain.getThisObject();
          TextView text = (TextView) networkTypeBinding.text.invoke(owner);
          if (!enabled(text.getContext())) return chain.proceed();
          boolean show = networkTypeBinding.allowed(owner, text.getContext());
          Object result = chain.proceed(new Object[]{show ? chain.getArg(0) : null});
          networkTypeArtwork.apply(text, runtime.moduleResources(text.getContext()));
          // New OS17 groups and later binder updates retain the same native-owned chain.
          order.apply((ViewGroup) combo.invoke(owner), text);
          StatusIconMetrics.mobile((View) mobileGroup.invoke(owner), text, (ImageView) mobileSignal.invoke(owner));
          lastNetworkType = text.getVisibility() == View.VISIBLE && text.length() > 0
              ? text.getText().toString() : (show ? "empty-native-model" : "hidden-native-state");
          return result;
        });
  }

  static boolean allowNetworkType(boolean cellular, boolean inService, boolean carrierChange,
      boolean airplane) {
    return cellular && inService && !carrierChange && !airplane;
  }

  private final class NetworkTypeBinding {
    final Class<?> binding;
    final Class<?> cellular;
    final Method viewModel;
    final Method text;
    final Method icon;
    final Method networkType;
    final Method value;
    final Field inService;
    final Method carrierChange;
    final Method bind;

    NetworkTypeBinding(Class<?> binding, Class<?> cellular, Method viewModel, Method text,
        Method icon, Method networkType, Method value, Field inService,
        Method carrierChange, Method bind) {
      this.binding = binding;
      this.cellular = cellular;
      this.viewModel = viewModel;
      this.text = text;
      this.icon = icon;
      this.networkType = networkType;
      this.value = value;
      this.inService = inService;
      this.carrierChange = carrierChange;
      this.bind = bind;
    }

    boolean allowed(Object owner, Context context) throws ReflectiveOperationException {
      Object vm = viewModel.invoke(owner);
      Object signal = value.invoke(icon.invoke(vm));
      boolean isCellular = cellular.isInstance(signal);
      return allowNetworkType(isCellular, isCellular && inService.getBoolean(signal),
          isCellular && (boolean) carrierChange.invoke(signal),
          Settings.Global.getInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0);
    }

    void refresh(Object owner) throws ReflectiveOperationException {
      if (!binding.isInstance(owner)) return;
      TextView view = (TextView) text.invoke(owner);
      if (!enabled(view.getContext())) return;
      Object vm = viewModel.invoke(owner);
      bind.invoke(owner, value.invoke(networkType.invoke(vm)));
    }
  }

  private static boolean hasIntrinsicSize(Drawable drawable) {
    return drawable != null && drawable.getIntrinsicWidth() > 0 && drawable.getIntrinsicHeight() > 0;
  }

  private void installIconConfiguration(String name, boolean mobile) throws ReflectiveOperationException {
    runtime.module.hook(runtime.type(name).getDeclaredMethod("onConfigurationChanged",
            android.content.res.Configuration.class))
        .setId("native-panels:android17-icon-config-" + (mobile ? "mobile" : "wifi"))
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          Object result = chain.proceed();
          View root = (View) chain.getThisObject();
          if (enabled(root.getContext())) {
            StatusIconMetrics.configuration(root, mobile);
            if (mobile) {
              int id = root.getResources().getIdentifier("mobile_type_text", "id", Protocol.TARGET);
              if (id != 0 && root.findViewById(id) instanceof TextView type)
                networkTypeArtwork.apply(type, runtime.moduleResources(root.getContext()));
            }
          }
          return result;
        });
  }

  private record WifiArtwork(WeakReference<Object> icon, int resourceId) {}

}
