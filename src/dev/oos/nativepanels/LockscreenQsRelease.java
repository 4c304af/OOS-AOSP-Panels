package dev.oos.nativepanels;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Use the SystemUI scene swipe's bounded decay or spring for the legacy QS height animator. */
final class LockscreenQsRelease {
  private final Object springSpec, converter;
  private final Constructor<?> spline;
  private final Method density, generateDecay, makeDecay, makeSpring, preferDecay;
  private final Method target, duration, value;
  private Object floatDecay, decaySpec;
  private float displayDensity;

  LockscreenQsRelease(PanelRuntime runtime) throws ReflectiveOperationException {
    String core = "androidx.compose.animation.core.";
    Class<?> animations = runtime.type(core + "AnimationKt");
    Class<?> spec = runtime.type(core + "AnimationSpec");
    Class<?> scalar = runtime.type(core + "FloatDecayAnimationSpec");
    Class<?> decay = runtime.type(core + "DecayAnimationSpec");
    Class<?> twoWay = runtime.type(core + "TwoWayConverter");
    Class<?> unit = runtime.type("androidx.compose.ui.unit.Density");
    density = Reflect.method(runtime.type("androidx.compose.ui.unit.DensityKt"), "Density", float.class, float.class);
    spline = runtime.type("androidx.compose.animation.SplineBasedFloatDecayAnimationSpec").getConstructor(unit);
    generateDecay = Reflect.method(runtime.type(core + "DecayAnimationSpecKt"), "generateDecayAnimationSpec", scalar);
    makeDecay = Reflect.method(animations, "DecayAnimation", scalar, float.class, float.class);
    makeSpring = Reflect.method(animations, "TargetBasedAnimation", spec, twoWay, Object.class, Object.class, Object.class);
    preferDecay = Reflect.method(runtime.type("com.android.compose.gesture.ScrollOffsetTargetAnimationKt"),
        "willDecayFasterThanAnimating", spec, decay, float.class, float.class, float.class);
    Class<?> animation = runtime.type(core + "Animation");
    duration = animation.getMethod("getDurationNanos");
    value = animation.getMethod("getValueFromNanos", long.class);
    target = animation.getMethod("getTargetValue");
    Class<?> companion = runtime.type("kotlin.jvm.internal.FloatCompanionObject");
    converter = Reflect.method(runtime.type(core + "VectorConvertersKt"), "getVectorConverter", companion)
        .invoke(null, companion.getField("INSTANCE").get(null));
    Object scheme = Reflect.field(runtime.type("com.android.compose.theme.PlatformThemeKt"), "ExpressiveMotionScheme").get(null);
    springSpec = runtime.type("androidx.compose.material3.MotionScheme").getMethod("slowSpatialSpec").invoke(scheme);
  }

  Curve closing(float from, float to, float velocity, float dpiScale) throws ReflectiveOperationException {
    if (!Float.isFinite(from) || !Float.isFinite(to) || from <= to || !Float.isFinite(velocity)
        || velocity > 0 || !Float.isFinite(dpiScale) || dpiScale <= 0) return null;
    if (floatDecay == null || displayDensity != dpiScale) {
      Object next = spline.newInstance(density.invoke(null, dpiScale, 1f));
      decaySpec = generateDecay.invoke(null, next);
      floatDecay = next;
      displayDensity = dpiScale;
    }
    Object fling = makeDecay.invoke(null, floatDecay, from, velocity);
    boolean useDecay = (Float)target.invoke(fling) <= to
        && (Boolean)preferDecay.invoke(null, springSpec, decaySpec, from, to, velocity);
    Object motion = useDecay ? fling : makeSpring.invoke(null, springSpec, converter, from, to, velocity);
    return Curve.sample(from, to, (Long)duration.invoke(motion), t -> (Float)value.invoke(motion, t));
  }

  interface Position { float at(long nanos) throws ReflectiveOperationException; }

  static final class Curve implements TimeInterpolator {
    // Sample once per release, at finer resolution than a 120 Hz display.
    private static final int STEP_MS = 4;
    private final float[] progress;
    final int millis;

    private Curve(float[] progress, int millis) { this.progress = progress; this.millis = millis; }

    static Curve sample(float from, float to, long naturalNanos, Position position) throws ReflectiveOperationException {
      if (from <= to || naturalNanos <= 0) return null;
      float[] points = new float[64];
      // A decay can last much longer than the time it takes to reach the edge. Only
      // keep samples up to that edge, just as Animatable.updateBounds does in SystemUI.
      for (int i = 1; i <= 500; i++) {
        long nanos = Math.min(i * STEP_MS * 1_000_000L, naturalNanos);
        float p = (from - position.at(nanos)) / (from - to);
        if (!Float.isFinite(p)) return null;
        if (i == points.length) points = Arrays.copyOf(points, points.length * 2);
        if (p >= 1f || nanos == naturalNanos) {
          points[i] = 1f;
          return new Curve(Arrays.copyOf(points, i + 1), (int)((nanos + 999_999L) / 1_000_000L));
        }
        points[i] = Math.max(0f, p);
      }
      return null; // Unrecognized motion; keep the ROM's existing animator.
    }

    @Override public float getInterpolation(float input) {
      if (input <= 0f) return 0f;
      if (input >= 1f) return 1f;
      float t = input * millis;
      int last = progress.length - 1;
      int i = Math.min((int)(t / STEP_MS), last - 1);
      int interval = Math.min(STEP_MS, millis - i * STEP_MS);
      return progress[i] + (progress[i + 1] - progress[i]) * ((t - i * STEP_MS) / interval);
    }

    void apply(ValueAnimator animator) {
      animator.setInterpolator(this);
      animator.setDuration(millis);
    }
  }
}
