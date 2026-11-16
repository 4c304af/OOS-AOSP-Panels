package dev.oos.nativepanels;

import android.view.HapticFeedbackConstants;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Deliver feedback from confirmed QS actions through the attached SystemUI view. */
final class CombinedHaptics {
  private static final String TILE = "com.android.systemui.haptics.msdl.qs.TileHapticsViewModel";
  private static final String SLIDER = "com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel";
  private static final String BRIGHTNESS = "com.android.systemui.brightness.ui.compose.BrightnessSliderKt";
  private static final String GRID = "com.android.systemui.qs.panels.ui.compose.infinitegrid.TileKt";

  // Use progress, not the number of pointer events. The two exact endpoints have
  // their own values, so a small move near the end does not produce an end-stop tick.
  static int band(float progress) {
    if (!Float.isFinite(progress)) return -1;
    if (progress <= 0f) return 0;
    if (progress >= 1f) return 21;
    return 1 + Math.min(19, (int)(progress * 20));
  }

  static int sliderEffect(int previous, int next, long elapsed) {
    if (next < 0 || next == previous) return -1;
    if (next == 0 || next == 21) return HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE;
    if (previous >= 0 && elapsed < 45) return -1;
    return HapticFeedbackConstants.SEGMENT_TICK;
  }

  static void install(PanelRuntime r) throws Exception {
    ShadeLayout layout = r.combinedQs.layout;
    Class<?> tile = r.type(TILE);
    Class<?> slider = r.type(SLIDER);
    Class<?> provider = r.type("com.android.systemui.haptics.slider.SliderHapticFeedbackProvider");
    Field sliderProvider = Reflect.field(slider, "sliderHapticFeedbackProvider");
    Class<?> creator = r.type(BRIGHTNESS + "$$ExternalSyntheticLambda9");
    Method remember = r.type("com.android.systemui.lifecycle.SysUiViewModelKt").getDeclaredMethod("rememberViewModel",
        String.class, Object.class, r.type("kotlin.coroutines.CoroutineContext"), r.type("kotlin.jvm.functions.Function0"),
        r.type("androidx.compose.runtime.Composer"), int.class, int.class);
    // rememberViewModel is shared by the lock screen, volume panel and media controls.
    // Record only the tile and brightness models created by this fragment.
    r.module.hook(remember).setId("native-panels:qs-haptic-models").intercept(c -> {
      boolean tileModel = "TileHapticsViewModel".equals(c.getArg(0));
      boolean brightnessModel = "SliderHapticsViewModel".equals(c.getArg(0)) && creator.isInstance(c.getArg(3));
      if (!tileModel && !brightnessModel) return c.proceed();
      ShadeDecor owner = layout.compositionOwner(c.getArg(4));
      Object vm = c.proceed();
      if (owner != null && tileModel && tile.isInstance(vm)) owner.hapticTiles.put(vm, true);
      if (owner != null && brightnessModel && slider.isInstance(vm)) {
        owner.brightnessHaptics = vm;
        owner.brightnessHapticProvider = sliderProvider.get(vm);
      }
      return vm;
    });
    Field tileVm = Reflect.field(tile, "tileViewModel");
    Method current = r.type("com.android.systemui.qs.panels.ui.viewmodel.TileViewModel").getMethod("getCurrentState");
    Class<?> state = r.type("com.android.systemui.plugins.qs.QSTile$State");
    Field availability = state.getField("state"), policy = state.getField("disabledByPolicy");
    Method interaction = tile.getMethod("setTileInteractionState", r.type(TILE + "$TileInteractionState"));
    r.module.hook(interaction).setId("native-panels:qs-tile-haptic").intercept(c -> {
      Object vm = c.getThisObject();
      ShadeDecor owner = layout.hapticOwner();
      if (owner == null || !owner.hapticTiles.containsKey(vm)) return c.proceed();
      String event = ((Enum<?>) c.getArg(0)).name();
      boolean longPress = "LONG_CLICKED".equals(event);
      if (!longPress && !"CLICKED".equals(event)) return c.proceed();
      Object value = current.invoke(tileVm.get(vm));
      if (availability.getInt(value) != 0 && !policy.getBoolean(value)) {
        owner.feedback(longPress ? HapticFeedbackConstants.LONG_PRESS : HapticFeedbackConstants.CONTEXT_CLICK);
      }
      // This setter feeds only the MSDL haptic collector. Use one output for the action.
      return null;
    });

    Method normalize = slider.getMethod("normalize", float.class);
    for (String name : new String[]{"onProgress", "onLowerBookend", "onUpperBookend"}) {
      Method output = name.equals("onProgress") ? provider.getMethod(name, float.class) : provider.getMethod(name);
      r.module.hook(output).setId("native-panels:qs-haptic-" + name).intercept(c -> {
        ShadeDecor owner = layout.hapticOwner();
        if (owner != null && c.getThisObject() == owner.brightnessHapticProvider) return null;
        return c.proceed();
      });
    }
    r.module.hook(slider.getMethod("onValueChange", float.class)).setId("native-panels:qs-slider-tick").intercept(c -> {
      ShadeDecor owner = layout.hapticOwner();
      if (owner != null && c.getThisObject() == owner.brightnessHaptics) {
        int next = band((Float) normalize.invoke(c.getThisObject(), c.getArg(0)));
        long now = android.os.SystemClock.uptimeMillis();
        int effect = sliderEffect(owner.brightnessBand, next, now - owner.lastTick);
        if (effect >= 0) {
          owner.brightnessBand = next;
          owner.lastTick = now;
          owner.feedback(effect);
        }
      }
      return c.proceed();
    });
    r.module.hook(slider.getMethod("onValueChangeEnded")).setId("native-panels:qs-slider-finished").intercept(c -> {
      ShadeDecor owner = layout.hapticOwner();
      if (owner != null && c.getThisObject() == owner.brightnessHaptics) owner.brightnessBand = -1;
      return c.proceed();
    });
    try {
      Class<?> tracker = r.type("com.android.systemui.haptics.slider.SliderStateTracker");
      r.module.deoptimize(tracker.getDeclaredMethod("executeOnBookend"));
      r.module.deoptimize(tracker.getDeclaredMethod("executeOnState", r.type("com.android.systemui.haptics.slider.SliderState")));
    } catch (ReflectiveOperationException ignored) { /* Some branches deliver feedback through a listener. */ }
    for (String type : new String[]{GRID, BRIGHTNESS}) {
      for (Method caller : r.type(type).getDeclaredMethods()) {
        if (caller.getName().equals("Tile") || caller.getName().equals("BrightnessSlider")) r.module.deoptimize(caller);
      }
    }
    for (int suffix : new int[]{13, 14}) {
      try {
        Class<?> callback = r.type(BRIGHTNESS + "$$ExternalSyntheticLambda" + suffix);
        r.module.deoptimize(suffix == 13 ? callback.getDeclaredMethod("invoke", Object.class) : callback.getDeclaredMethod("invoke"));
      } catch (ReflectiveOperationException ignored) { }
    }
  }
}
