package dev.oos.nativepanels;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Supplier;

/** Adapts only our slider to OOS's multibit progress scale, keeping its native brightness pipeline. */
final class CombinedBrightness {
  private static final int MAX = 65535;
  private static final String VM = "com.android.systemui.brightness.ui.viewmodel.";
  private final Supplier<Object> currentSlider;
  private volatile Curve curve;
  private DragType[] types;

  interface Curve {
    float linear(int nativeProgress) throws ReflectiveOperationException;
    int gamma(float displayedFraction) throws ReflectiveOperationException;
  }

  private record DragType(Class<?> type, Constructor<?> create, Field brightness) {
    static DragType read(Class<?> type) throws ReflectiveOperationException {
      // These ROM value types contain exactly one field. Fail closed if a future
      // branch adds event data that a brightness-only copy would otherwise lose.
      if (type.getSuperclass() != Object.class) throw new NoSuchFieldException(type.getName());
      Field brightness = null;
      for (Field field : type.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers())) continue;
        if (brightness != null || !field.getName().equals("brightness") || field.getType() != int.class)
          throw new NoSuchFieldException(type.getName() + ".brightness-only event");
        brightness = field;
      }
      if (brightness == null) throw new NoSuchFieldException(type.getName() + ".brightness");
      brightness.setAccessible(true);
      Constructor<?> create = type.getDeclaredConstructor();
      create.setAccessible(true);
      return new DragType(type, create, brightness);
    }
  }

  CombinedBrightness(Supplier<Object> currentSlider) { this.currentSlider = currentSlider; }

  void configure(Curve conversion, Class<?> dragging, Class<?> stopped) throws ReflectiveOperationException {
    DragType[] resolved = {DragType.read(dragging), DragType.read(stopped)};
    types = resolved;
    curve = conversion;
  }

  void install(PanelRuntime runtime) throws ReflectiveOperationException {
    Class<?> features = runtime.type("com.oplus.content.OplusFeatureConfigManager");
    Object config = features.getMethod("getInstance").invoke(null);
    if (!Boolean.TRUE.equals(features.getMethod("hasFeature", String.class)
        .invoke(config, "oplus.software.display.multibits_dimming_support"))) return;

    Class<?> model = runtime.type(VM + "BrightnessSliderViewModel");
    Method read = model.getDeclaredMethod("getCurrentBrightness-EFqf-m4");
    Method onDrag = model.getDeclaredMethod("onDrag", runtime.type(VM + "Drag"),
        runtime.type("kotlin.coroutines.Continuation"));
    Field min = Reflect.field(model, "minBrightness"), max = Reflect.field(model, "maxBrightness");
    Class<?> dragging = runtime.type(VM + "Drag$Dragging"), stopped = runtime.type(VM + "Drag$Stopped");
    DragType.read(dragging);
    DragType.read(stopped);
    Class<?> utils = runtime.type("com.android.settingslib.display.BrightnessUtils");
    Method linear = utils.getDeclaredMethod("convertGammaToLinearFloat", float.class, float.class, int.class);
    Method gamma = utils.getDeclaredMethod("convertLinearToGammaFloat", float.class, float.class, float.class);
    Curve conversion = new Curve() {
      public float linear(int value) throws ReflectiveOperationException {
        return (Float)linear.invoke(null, 0f, 1f, value);
      }
      public int gamma(float value) throws ReflectiveOperationException {
        return (Integer)gamma.invoke(null, value, 0f, 1f);
      }
    };

    runtime.module.hook(read).setId("native-panels:brightness-progress")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          int nativeValue = (Integer)chain.proceed();
          Object slider = chain.getThisObject();
          return owns(slider) && compatible(slider, min, max) ? display(slider, nativeValue) : nativeValue;
        });
    runtime.module.hook(onDrag).setId("native-panels:brightness-input")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          Object event = chain.getArg(0);
          Object slider = chain.getThisObject();
          Object mapped = owns(slider) && compatible(slider, min, max) ? drag(slider, event) : event;
          // The continuation and temporary/permanent event kind stay native. A
          // suspension resumes inside the interactor; it cannot convert this input twice.
          return mapped == event ? chain.proceed() : chain.proceed(new Object[]{mapped, chain.getArg(1)});
        });
    configure(conversion, dragging, stopped);
    deoptimize(runtime, read);
    deoptimize(runtime, onDrag);
    Class<?> sliders = runtime.type("com.android.systemui.brightness.ui.compose.BrightnessSliderKt");
    for (Method method : sliders.getDeclaredMethods()) {
      if (method.getName().equals("BrightnessSliderContainer")) deoptimize(runtime, method);
    }
    for (String suffix : new String[]{"$BrightnessSliderContainer$4$5$1$1", "$BrightnessSliderContainer$4$6$1$1"}) {
      try {
        deoptimize(runtime, runtime.type(sliders.getName() + suffix).getDeclaredMethod("invokeSuspend", Object.class));
      } catch (ReflectiveOperationException unavailable) { /* Synthetic names vary between ROMs. */ }
    }
  }

  private static void deoptimize(PanelRuntime runtime, Method method) {
    try { runtime.module.deoptimize(method); }
    catch (RuntimeException unavailable) { /* Installed hooks remain available without ART deoptimization. */ }
  }

  private static boolean compatible(Object slider, Field min, Field max) {
    try { return min.getInt(slider) == 0 && max.getInt(slider) == MAX; }
    catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
  }

  private boolean owns(Object slider) { return slider != null && currentSlider.get() == slider; }

  int display(Object slider, int nativeProgress) {
    Curve conversion = curve;
    if (conversion == null || !owns(slider) || nativeProgress < 0 || nativeProgress > MAX) return nativeProgress;
    try {
      float value = conversion.linear(nativeProgress);
      return Float.isFinite(value) && value >= 0f && value <= 1f ? Math.round(value * MAX) : nativeProgress;
    } catch (ReflectiveOperationException | RuntimeException unavailable) { return nativeProgress; }
  }

  Object drag(Object slider, Object event) {
    Curve conversion = curve;
    if (conversion == null || !owns(slider) || event == null) return event;
    for (DragType type : types) {
      if (event.getClass() != type.type()) continue;
      try {
        int displayed = type.brightness().getInt(event);
        if (displayed < 0 || displayed > MAX) return event;
        int nativeValue = conversion.gamma(displayed / (float)MAX);
        if (nativeValue < 0 || nativeValue > MAX) return event;
        Object mapped = type.create().newInstance();
        type.brightness().setInt(mapped, nativeValue);
        return mapped;
      } catch (ReflectiveOperationException | RuntimeException unavailable) { return event; }
    }
    return event;
  }
}
