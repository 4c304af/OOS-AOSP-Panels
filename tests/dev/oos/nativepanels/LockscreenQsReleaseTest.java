package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class LockscreenQsReleaseTest {
  private static LockscreenQsRelease.Curve fling(float remaining, float speed) throws Exception {
    // A deterministic position source for the adapter; production evaluates SystemUI's Compose spec.
    return LockscreenQsRelease.Curve.sample(100 + remaining, 100, 1_000_000_000L,
        t -> 100 + remaining - speed * (t / 1_000_000_000f));
  }

  @Test public void fasterFlingOrShorterRemainingDistanceReachesTheEdgeEarlier() throws Exception {
    var far = fling(640, 4000);
    var near = fling(240, 4000);
    var fast = fling(640, 8000);
    assertEquals(160, far.millis);
    assertEquals(60, near.millis);
    assertEquals(80, fast.millis);
    assertEquals(.5f, far.getInterpolation(.5f), .0001f);
    assertEquals(1f, fast.getInterpolation(1f), 0f);
  }

  @Test public void stopsAtTheFirstBoundAndPreservesTheSpringShapeBetweenSamples() throws Exception {
    var curve = LockscreenQsRelease.Curve.sample(100, 0, 400_000_000L, t -> {
      float ms = t / 1_000_000f;
      return 100 - ms * ms / 4;
    });
    assertEquals(20, curve.millis);
    assertEquals(.16f, curve.getInterpolation(.4f), .00001f);
    assertEquals(.26f, curve.getInterpolation(.5f), .00001f);
    var tail = LockscreenQsRelease.Curve.sample(100, 0, 9_000_000L, t -> 100 - t / 100_000f);
    assertEquals(9, tail.millis);
    assertEquals(.9f, tail.getInterpolation(8.5f / 9f), .00001f);
    assertNull(LockscreenQsRelease.Curve.sample(100, 0, 100_000_000L, t -> Float.NaN));
  }

  @Test public void retimesTheAlreadyStartedHeightAnimatorWithoutReplacingItsUpdatesOrCancellation() throws Exception {
    ValueAnimator animator = ValueAnimator.ofFloat(740f, 100f).setDuration(350);
    float[] height = {0};
    int[] events = {0, 0, 0};
    animator.addUpdateListener(a -> height[0] = (Float)a.getAnimatedValue());
    animator.addListener(new AnimatorListenerAdapter() {
      @Override public void onAnimationStart(Animator a) { events[0]++; }
      @Override public void onAnimationCancel(Animator a) { events[1]++; }
      @Override public void onAnimationEnd(Animator a) { events[2]++; }
    });
    animator.start();
    fling(640, 8000).apply(animator);
    assertEquals(740f, height[0], .001f);
    animator.setCurrentPlayTime(40);
    assertEquals(420f, height[0], .001f);
    animator.cancel();
    assertArrayEquals(new int[]{1, 1, 1}, events);
  }
}
