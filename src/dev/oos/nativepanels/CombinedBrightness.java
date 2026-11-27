package dev.oos.nativepanels;

import io.github.libxposed.api.XposedInterface;
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
  private volatile String installation = "not started", feature = "unknown";
  private volatile int lastDisplayNative = -1, lastDisplayUi = -1, lastDragUi = -1, lastDragNative = -1;

  interface Curve {
    float linear(int nativeProgress) throws ReflectiveOperationException;
    int gamma(float displayedFraction) throws ReflectiveOperationException;
  }

  interface Allocator { Object create(Class<?> type) throws ReflectiveOperationException; }

  private record DragType(Class<?> type, Allocator allocator, Field brightness) {
    static DragType read(Class<?> type, Allocator allocator) throws ReflectiveOperationException {
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
      // R8 removed both Drag constructors in this ROM. Its own bytecode creates
      // the subtype, invokes Object.<init>, then writes the single brightness field.
      Object probe = allocator.create(type);
      if (probe == null || probe.getClass() != type) throw new InstantiationException(type.getName());
      brightness.setInt(probe, 1);
      if (brightness.getInt(probe) != 1) throw new IllegalAccessException(type.getName() + ".brightness");
      return new DragType(type, allocator, brightness);
    }
  }

  CombinedBrightness(Supplier<Object> currentSlider) { this.currentSlider = currentSlider; }

  static Allocator allocator(XposedInterface module) throws ReflectiveOperationException {
    XposedInterface.CtorInvoker<Object> parent = module.getInvoker(Object.class.getDeclaredConstructor())
        .setType(XposedInterface.Invoker.Type.ORIGIN);
    return type -> parent.newInstanceSpecial(type);
  }

  void configure(Curve conversion, Allocator allocator, Class<?> dragging, Class<?> stopped)
      throws ReflectiveOperationException {
    activate(conversion, new DragType[]{DragType.read(dragging, allocator), DragType.read(stopped, allocator)});
  }

  private void activate(Curve conversion, DragType[] resolved) {
    types = resolved;
    curve = conversion;
    installation = "ready";
  }

  void install(PanelRuntime runtime) throws ReflectiveOperationException {
    Class<?> features = runtime.type("com.oplus.content.OplusFeatureConfigManager");
    Object config = features.getMethod("getInstance").invoke(null);
    boolean supported = Boolean.TRUE.equals(features.getMethod("hasFeature", String.class)
        .invoke(config, "oplus.software.display.multibits_dimming_support"));
    feature = Boolean.toString(supported);
    if (!supported) { installation = "feature disabled"; return; }

    Class<?> model = runtime.type(VM + "BrightnessSliderViewModel");
    Method read = model.getDeclaredMethod("getCurrentBrightness-EFqf-m4");
    Method onDrag = model.getDeclaredMethod("onDrag", runtime.type(VM + "Drag"),
        runtime.type("kotlin.coroutines.Continuation"));
    Field min = Reflect.field(model, "minBrightness"), max = Reflect.field(model, "maxBrightness");
    Class<?> dragging = runtime.type(VM + "Drag$Dragging"), stopped = runtime.type(VM + "Drag$Stopped");
    Allocator allocator = allocator(runtime.module);
    DragType[] resolved = {DragType.read(dragging, allocator), DragType.read(stopped, allocator)};
    Class<?> utils = runtime.type("com.android.settingslib.display.BrightnessUtils");
    Method linear = utils.getDeclaredMethod("convertGammaToLinearFloat", float.class, float.class, int.class);
    Method gamma = utils.getDeclaredMethod("convertLinearToGammaFloat", float.class, float.class, float.class);
    Class<?> sliders = runtime.type("com.android.systemui.brightness.ui.compose.BrightnessSliderKt");
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
    activate(conversion, resolved);
    deoptimize(runtime, read);
    deoptimize(runtime, onDrag);
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

  void failed(Throwable failure) {
    curve = null;
    installation = "failed: " + failure;
  }

  String report() {
    return "QS brightness: " + installation + " multibits=" + feature + " owner=" + (currentSlider.get() != null)
        + " read(native/ui)=" + lastDisplayNative + "/" + lastDisplayUi
        + " drag(ui/native)=" + lastDragUi + "/" + lastDragNative;
  }

  int display(Object slider, int nativeProgress) {
    Curve conversion = curve;
    if (conversion == null || !owns(slider) || nativeProgress < 0 || nativeProgress > MAX) return nativeProgress;
    try {
      float value = conversion.linear(nativeProgress);
      if (!Float.isFinite(value) || value < 0f || value > 1f) return nativeProgress;
      int displayed = Math.round(value * MAX);
      lastDisplayNative = nativeProgress;
      lastDisplayUi = displayed;
      return displayed;
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
        Object mapped = type.allocator().create(type.type());
        if (mapped == null || mapped.getClass() != type.type() || mapped == event) return event;
        type.brightness().setInt(mapped, nativeValue);
        lastDragUi = displayed;
        lastDragNative = nativeValue;
        return mapped;
      } catch (ReflectiveOperationException | RuntimeException unavailable) { return event; }
    }
    return event;
  }
}
