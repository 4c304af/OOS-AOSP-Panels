package dev.oos.nativepanels;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** The direct lockscreen-to-expanded-QS scene, rendered by OOS's legacy QS fragment. */
final class LockscreenQsMotion {
  // ShadeHeader.Dimensions and BrightnessSliderDimensions.Default in ToQuickSettingsTransition.
  static final int CLOCK_DP = 48, HEADER_DP = 120 - CLOCK_DP, GRID_DP = 120 + 52;
  private final ShadeLayout layout;
  private final Method forceQs, split, overscroll, stackOverscroll, panel;
  private final Method then, any;
  private final Object base, sliderLayer, matchesSliderLayer;

  LockscreenQsMotion(PanelRuntime runtime, ShadeLayout layout, Class<?> vm)
      throws ReflectiveOperationException {
    this.layout = layout;
    forceQs = Reflect.method(vm, "getForceQs");
    split = Reflect.method(vm, "isInSplitShade");
    overscroll = Reflect.method(vm, "getOverScrollAmount");
    stackOverscroll = Reflect.method(vm, "isStackScrollerOverscrolling");
    panel = Reflect.method(vm, "getPanelExpansionFraction");
    Class<?> modifier = runtime.type("androidx.compose.ui.Modifier");
    Class<?> function = runtime.type("kotlin.jvm.functions.Function1");
    base = modifier.getField("Companion").get(null);
    then = modifier.getMethod("then", modifier);
    any = modifier.getMethod("any", function);
    Method layer = Reflect.method(runtime.type("androidx.compose.ui.graphics.GraphicsLayerModifierKt"),
        "graphicsLayer", modifier, function);
    Method setAlpha = runtime.type("androidx.compose.ui.graphics.GraphicsLayerScope").getMethod("setAlpha", float.class);
    Object unit = runtime.type("kotlin.Unit").getField("INSTANCE").get(null);
    sliderLayer = layer.invoke(null, base, function(function, scope -> {
      // Read the existing Compose snapshot at draw time; dragging doesn't recompose the slider.
      try { setAlpha.invoke(scope, chromeAlpha(progress(layout.selectedModel()))); }
      catch (ReflectiveOperationException unavailable) { layout.warn(unavailable); }
      return unit;
    }));
    matchesSliderLayer = function(function, element -> element == sliderLayer);

    Method scroll = Reflect.method(vm, "getQsScrollTranslationY");
    Method common = Reflect.method(vm, "getViewTranslationY");
    runtime.module.hook(scroll).setId("native-panels:locked-qs-content-travel")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          float original = (Float)chain.proceed();
          Object target = chain.getThisObject();
          if (target != layout.selectedModel()) return original;
          float p = progress(target);
          ShadeDecor owner = layout.hapticOwner();
          if (!Float.isFinite(p) || owner == null) return original;
          return offset(p, GRID_DP * owner.frame.getResources().getDisplayMetrics().density, (Float)common.invoke(target));
        });
  }

  float progress(Object vm) throws ReflectiveOperationException {
    // forceQs is SystemUI's own keyguard-only expanded scene selector. A notification
    // pull, bypass or overscroll must keep the geometry of its own scene.
    if (vm == null || !(Boolean)forceQs.invoke(vm) || (Boolean)split.invoke(vm)
        || (Boolean)stackOverscroll.invoke(vm) || (Integer)overscroll.invoke(vm) != 0) return Float.NaN;
    float p = (Float)panel.invoke(vm);
    return p >= 0f && p <= 1f ? p : Float.NaN;
  }

  static float offset(float progress, float distancePx, float parentTranslation) {
    return (progress - 1f) * distancePx - parentTranslation;
  }

  interface DirectDrag { boolean active(Object controller) throws ReflectiveOperationException; }

  static XposedInterface.Hooker dragHook(Class<?> translation, Class<?> callback, Class<?> controller,
      DirectDrag direct) throws ReflectiveOperationException {
    Field owner = Reflect.field(callback, "f$0");
    if (owner.getType() != controller) throw new NoSuchFieldException("Unexpected QS drag callback owner");
    Method tracking = Reflect.method(controller, "isTracking");
    Field animating = Reflect.field(controller, "mAnimating");
    Method setHeight = Reflect.method(controller, "setExpansionHeight", float.class);
    Method getHeight = Reflect.method(controller, "getExpansionHeight");
    Method getCallback = Reflect.method(translation, "getMblock");
    Method clearCallback = Reflect.method(translation, "setMblock", getCallback.getReturnType());
    Method spring = Reflect.method(translation, "getKeyguardHAnimation");
    Method running = Reflect.method(spring.getReturnType(), "isRunning");
    Method cancel = Reflect.method(spring.getReturnType(), "cancel");
    Method startValue = Reflect.method(spring.getReturnType(), "setStartValue", float.class);
    return chain -> {
      Object values = chain.getThisObject();
      Object listener = getCallback.invoke(values);
      if (!callback.isInstance(listener)) return chain.proceed();
      Object host = owner.get(listener);
      float height = (Float)chain.getArg(0);
      if (host == null || !Float.isFinite(height) || animating.getBoolean(host)
          || !(Boolean)tracking.invoke(host) || !direct.active(host)) return chain.proceed();

      // OOS has already accepted the touch and calculated the target. Its update listener
      // would feed setExpansionHeight the delayed spring value. Apply the drag synchronously
      // and hand the bounded height back to OOS for release or interruption.
      Object animation = spring.invoke(values);
      if ((Boolean)running.invoke(animation)) cancel.invoke(animation);
      clearCallback.invoke(values, (Object)null);
      setHeight.invoke(host, height);
      startValue.invoke(animation, getHeight.invoke(host));
      return null;
    };
  }

  static float chromeAlpha(float progress) {
    if (!Float.isFinite(progress)) return 1f;
    if (progress <= .58f) return 0f;
    // The host already fades with progress. Account for its opacity so the rendered
    // header and slider run from zero at 58% to fully opaque at the end.
    return Math.min(1f, (progress - .58f) / (1f - .58f) / progress);
  }

  Object sliderModifier(Object original) throws ReflectiveOperationException {
    Object source = original == null ? base : original;
    // A Compose restart may pass back the modifier from the preceding call.
    return (Boolean)any.invoke(source, matchesSliderLayer) ? source : then.invoke(source, sliderLayer);
  }

  private interface Invoke { Object apply(Object argument) throws ReflectiveOperationException; }
  private static Object function(Class<?> type, Invoke block) {
    return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> switch (method.getName()) {
      case "invoke" -> block.apply(args[0]);
      case "equals" -> proxy == args[0];
      case "hashCode" -> System.identityHashCode(proxy);
      case "toString" -> "Lockscreen QS brightness";
      default -> throw new UnsupportedOperationException(method.getName());
    });
  }
}
