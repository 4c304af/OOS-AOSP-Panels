package dev.oos.nativepanels;

import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Removes only the carousel's second rounded clip; each native item keeps its animated mask. */
final class MediaCarouselCorners {
  private static final String MEDIA = "com.android.systemui.media.remedia.ui.";
  private final Supplier<Object> currentOwner;
  private final Bindings bindings = new Bindings();
  private final ThreadLocal<Composition> composing = new ThreadLocal<>();

  private record Composition(Object owner, Object model, Object behavior) {}
  @FunctionalInterface interface Original { Object run() throws Throwable; }
  @FunctionalInterface interface CardCount { int get(Object model) throws ReflectiveOperationException; }
  @FunctionalInterface interface Scrolling { boolean get(Object behavior) throws ReflectiveOperationException; }

  MediaCarouselCorners(Supplier<Object> currentOwner) { this.currentOwner = currentOwner; }

  void install(PanelRuntime runtime, ShadeLayout layout, Class<?> renderer, Class<?> composer)
      throws ReflectiveOperationException {
    Class<?> model = runtime.type(MEDIA + "viewmodel.MediaViewModel");
    Class<?> style = runtime.type(MEDIA + "compose.MediaPresentationStyle");
    Class<?> behavior = runtime.type(MEDIA + "compose.MediaUiBehavior");
    Class<?> function = runtime.type("kotlin.jvm.functions.Function0");
    Class<?> rounded = runtime.type("androidx.compose.foundation.shape.RoundedCornerShape");
    Method content = renderer.getDeclaredMethod("CardCarouselContent", model, style, behavior,
        function, runtime.type("androidx.compose.ui.Modifier"), function, function, composer, int.class);
    Method pager = renderer.getDeclaredMethod("CardCarouselContent$lambda$31$PagerContent",
        runtime.type("androidx.compose.material3.carousel.CarouselState"), boolean.class, behavior,
        model, style, rounded, function, function, composer);
    Class<?> clip = runtime.type(MEDIA + "compose.MediaKt$$ExternalSyntheticLambda45");
    Field kind = Reflect.field(clip, "$r8$classId"), shape = Reflect.field(clip, "f$0");
    Method invoke = clip.getDeclaredMethod("invoke", Object.class);
    Method getCards = model.getMethod("getCards");
    Field scrolling = Reflect.field(behavior, "isCarouselScrollingEnabled");
    Method setShape = runtime.type("androidx.compose.ui.graphics.GraphicsLayerScope")
        .getMethod("setShape", runtime.type("androidx.compose.ui.graphics.Shape"));
    Object rectangle = runtime.type("androidx.compose.ui.graphics.RectangleShapeKt")
        .getMethod("getRectangleShape").invoke(null);
    CardCount count = vm -> ((List<?>)getCards.invoke(vm)).size();
    Scrolling enabled = scrolling::getBoolean;

    runtime.module.hook(content).setId("native-panels:media-corner-composition")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain ->
            compose(layout.compositionOwner(chain.getArg(7)), chain.getArg(0), chain.getArg(2), chain::proceed));
    runtime.module.hook(pager).setId("native-panels:media-corner-owner")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          // The exact pager supplies the remembered shape even when the graphics
          // layer callback runs after composition, or this VM/behavior was replaced.
          bind(chain.getArg(5), layout.compositionOwner(chain.getArg(8)), chain.getArg(3), chain.getArg(2));
          return chain.proceed();
        });
    runtime.module.hook(invoke).setId("native-panels:media-carousel-clip")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          Object result = chain.proceed();
          try {
            if (kind.getInt(chain.getThisObject()) == 2
                && rectangular(shape.get(chain.getThisObject()), count, enabled)) {
              // Keep clip=true, all native transforms, and each item's maskClip.
              // A 32dp outer corner otherwise cuts into the 24dp end of a 48dp item.
              setShape.invoke(chain.getArg(0), rectangle);
            }
          } catch (ReflectiveOperationException | RuntimeException unavailable) {
            // This is cosmetic. Keep the original layer if this ROM differs.
          }
          return result;
        });
    // The pager has a direct call from CardCarouselContent; its deferred branch
    // lives in Lambda65. Avoid missing ownership binding on AOT-compiled builds.
    for (Method method : new Method[]{content, pager, invoke}) deoptimize(runtime, method);
    for (String caller : new String[]{"MediaKt$$ExternalSyntheticLambda61", "MediaKt$$ExternalSyntheticLambda65",
        "MediaKt$$ExternalSyntheticLambda67"}) {
      try {
        for (Method method : runtime.type(MEDIA + "compose." + caller).getDeclaredMethods()) {
          if (method.getName().equals("invoke")) deoptimize(runtime, method);
        }
      } catch (ClassNotFoundException ignored) {}
    }
  }

  private static void deoptimize(PanelRuntime runtime, Method method) {
    try { runtime.module.deoptimize(method); }
    catch (RuntimeException unavailable) { /* The hook remains useful without ART deoptimization. */ }
  }

  Object compose(Object owner, Object model, Object behavior, Original original) throws Throwable {
    Composition previous = composing.get();
    if (owner == null) composing.remove();
    else composing.set(new Composition(owner, model, behavior));
    try { return original.run(); }
    finally {
      if (previous == null) composing.remove();
      else composing.set(previous);
    }
  }

  void bind(Object shape, Object owner, Object model, Object behavior) {
    bindings.bind(shape, owner, model, behavior);
  }

  boolean rectangular(Object shape, CardCount count, Scrolling scrolling) throws ReflectiveOperationException {
    Composition composition = composing.get();
    if (composition != null) bind(shape, composition.owner, composition.model, composition.behavior);
    Bound bound = bindings.find(shape, currentOwner.get());
    if (bound == null) return false;
    Object model = bound.model.get(), behavior = bound.behavior.get();
    // Read the native observable cards delegate inside the layer callback. Compose
    // then invalidates it at 1<->2 cards, even with an unchanged size and callback.
    return model != null && behavior != null && scrolling.get(behavior) && count.get(model) > 1;
  }

  private static final class Bound {
    final WeakReference<Object> shape, owner, model, behavior;
    Bound(Object shape, Object owner, Object model, Object behavior) {
      this.shape = new WeakReference<>(shape);
      this.owner = new WeakReference<>(owner);
      this.model = new WeakReference<>(model);
      this.behavior = new WeakReference<>(behavior);
    }
    boolean expired() { return shape.get() == null || owner.get() == null || model.get() == null || behavior.get() == null; }
  }

  private static final class Bindings {
    private final ArrayList<Bound> values = new ArrayList<>();
    synchronized void bind(Object shape, Object owner, Object model, Object behavior) {
      // RoundedCornerShape.equals compares dimensions. Use identity so another
      // 32dp shape (e.g. lockscreen media) never inherits this composition's owner.
      values.removeIf(Bound::expired);
      for (int i = 0; i < values.size(); i++) {
        Bound value = values.get(i);
        if (value.shape.get() != shape) continue;
        if (value.owner.get() == owner && value.model.get() == model && value.behavior.get() == behavior) return;
        values.remove(i);
        break;
      }
      if (shape != null && owner != null && model != null && behavior != null) {
        values.add(new Bound(shape, owner, model, behavior));
      }
    }
    synchronized Bound find(Object shape, Object owner) {
      values.removeIf(Bound::expired);
      if (shape == null || owner == null) return null;
      for (Bound value : values) {
        if (value.shape.get() == shape && value.owner.get() == owner) return value;
      }
      return null;
    }
  }
}
