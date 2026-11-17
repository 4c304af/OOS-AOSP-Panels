package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class NotificationOverlayCornersTest {
  private NotificationOverlayCorners corners;
  private OverlayView target;
  private BlurWrapper wrapper;
  private GradientDrawable fill;

  @Before public void setUp() {
    Context context = RuntimeEnvironment.getApplication();
    corners = new NotificationOverlayCorners();
    target = new OverlayView(context);
    fill = gradient(16);
    wrapper = new BlurWrapper(fill, new Proxy(new BlurConfig(16)));
    target.setBackground(wrapper);
    target.layout(10, 20, 210, 120);
  }

  @Test public void onePolicyUpdatesOutlineFallbackAndHardwareBlurWithoutChangingContentOrBounds() {
    target.setAlpha(.6f);
    target.clipTop = 7;
    target.clipBottom = 13;
    fill.setAlpha(117);
    fill.setBounds(2, 4, 190, 90);
    wrapper.setBounds(3, 5, 195, 95);
    TextView child = new TextView(target.getContext());
    child.setText("Notification title");
    child.setTextColor(Color.GREEN);
    target.addView(child);

    assertTrue(corners.apply(target, 28, 4));

    assertEquals(28, target.top, 0);
    assertEquals(4, target.bottom, 0);
    assertArrayEquals(radii(28, 4), fill.getCornerRadii(), 0);
    assertConfig(wrapper.proxy.config, 28, 28, 4, 4, 28);
    assertEquals(1, wrapper.proxy.applies);
    assertArrayEquals(new float[]{28, 28, 4, 4}, wrapper.proxy.applied, 0);
    assertEquals(.6f, target.getAlpha(), 0);
    assertEquals(200, target.getWidth());
    assertEquals(100, target.getHeight());
    assertEquals(7, target.clipTop);
    assertEquals(13, target.clipBottom);
    assertEquals(117, fill.getAlpha());
    assertEquals(new Rect(2, 4, 190, 90), fill.getBounds());
    assertEquals(new Rect(3, 5, 195, 95), wrapper.getBounds());
    assertEquals("Notification title", child.getText().toString());
    assertEquals(Color.GREEN, child.getCurrentTextColor());
  }

  @Test public void anIdleOverlaySchedulesNoMoreDrawsOrBlurUpdates() {
    assertTrue(corners.apply(target, 28, 4));
    int invalidations = target.invalidations;
    int outlines = target.outlines;
    int setters = target.setters;
    int configSetters = wrapper.proxy.config.setters;
    int strokeSetters = wrapper.proxy.config.stroke.setters;
    int applies = wrapper.proxy.applies;
    for (int i = 0; i < 200; i++) assertTrue(corners.apply(target, 28, 4));
    assertEquals(invalidations, target.invalidations);
    assertEquals(outlines, target.outlines);
    assertEquals(setters, target.setters);
    assertEquals(configSetters, wrapper.proxy.config.setters);
    assertEquals(strokeSetters, wrapper.proxy.config.stroke.setters);
    assertEquals(applies, wrapper.proxy.applies);
  }

  @Test public void releaseRestoresAllOriginalValuesOnce() {
    assertTrue(corners.apply(target, 28, 4));
    corners.clear();
    assertEquals(16, target.top, 0);
    assertEquals(16, target.bottom, 0);
    assertNull(fill.getCornerRadii());
    assertEquals(16, fill.getCornerRadius(), 0);
    assertConfig(wrapper.proxy.config, 16, 16, 16, 16, 16);
    assertEquals(2, wrapper.proxy.applies);
    int invalidations = target.invalidations;
    int outlines = target.outlines;
    for (int i = 0; i < 120; i++) {
      corners.clear();
      corners.restore(target);
    }
    assertEquals(invalidations, target.invalidations);
    assertEquals(outlines, target.outlines);
    assertEquals(2, wrapper.proxy.applies);
  }

  @Test public void nativeChangesBetweenFramesBecomeTheNewRestoreValues() {
    assertTrue(corners.apply(target, 28, 4));
    target.setTopCornerRadius(20);
    wrapper.proxy.config.setLeftTopCornerRadius(21);
    wrapper.proxy.config.stroke.setRadius(22);
    fill.setCornerRadius(23);
    assertTrue(corners.apply(target, 30, 6));
    corners.restore(target);
    assertEquals(20, target.top, 0);
    assertEquals(16, target.bottom, 0);
    assertConfig(wrapper.proxy.config, 21, 16, 16, 16, 22);
    assertNull(fill.getCornerRadii());
    assertEquals(23, fill.getCornerRadius(), 0);
  }

  @Test public void releaseDoesNotOverwriteNewerExternalValues() {
    assertTrue(corners.apply(target, 28, 4));
    target.setBottomCornerRadius(40);
    wrapper.proxy.config.setRightBottomCornerRadius(41);
    fill.setCornerRadius(42);
    corners.restore(target);
    assertEquals(16, target.top, 0);
    assertEquals(40, target.bottom, 0);
    assertConfig(wrapper.proxy.config, 16, 16, 16, 41, 16);
    assertEquals(42, fill.getCornerRadius(), 0);
  }

  @Test public void wrapperAndConfigReplacementRestoreOldObjectsAndAdoptTheNewNativeState() {
    assertTrue(corners.apply(target, 28, 4));
    BlurWrapper oldWrapper = wrapper;
    GradientDrawable oldFill = fill;
    fill = gradient(18);
    wrapper = new BlurWrapper(fill, new Proxy(new BlurConfig(18)));
    target.setBackground(wrapper);
    assertTrue(corners.apply(target, 30, 6));
    assertConfig(oldWrapper.proxy.config, 16, 16, 16, 16, 16);
    assertEquals(16, oldFill.getCornerRadius(), 0);
    assertNull(oldFill.getCornerRadii());

    BlurConfig previous = wrapper.proxy.config;
    wrapper.proxy.config = new BlurConfig(20);
    int applies = wrapper.proxy.applies;
    assertTrue(corners.apply(target, 32, 8));
    assertConfig(previous, 18, 18, 18, 18, 18);
    assertEquals("Do not apply the stale config through its reused proxy", applies + 1, wrapper.proxy.applies);
    corners.clear();
    assertConfig(wrapper.proxy.config, 20, 20, 20, 20, 20);
    assertEquals(18, fill.getCornerRadius(), 0);
    assertNull(fill.getCornerRadii());
    assertEquals(16, target.top, 0);
    assertEquals(16, target.bottom, 0);
  }

  @Test public void unavailableNativeGetterReleasesPreviousOwnershipWithoutCrashing() {
    assertTrue(corners.apply(target, 28, 4));
    wrapper.failGetter = true;
    assertFalse(corners.apply(target, 30, 6));
    assertEquals(16, target.top, 0);
    assertEquals(16, target.bottom, 0);
    assertConfig(wrapper.proxy.config, 16, 16, 16, 16, 16);
    assertEquals(16, fill.getCornerRadius(), 0);
    int invalidations = target.invalidations;
    assertFalse(corners.apply(target, 30, 6));
    assertEquals(invalidations, target.invalidations);
  }

  @Test public void unrelatedOrUnownedBackgroundsAreNeverChanged() {
    target.setBackgroundColor(Color.RED);
    int invalidations = target.invalidations;
    assertFalse(corners.apply(target, 28, 4));
    assertEquals(16, target.top, 0);
    assertEquals(16, target.bottom, 0);
    assertEquals(invalidations, target.invalidations);
    target.setBackground(wrapper);
    fill.setCallback(null);
    assertFalse(corners.apply(target, 28, 4));
    assertEquals(16, target.top, 0);
    assertEquals(0, wrapper.proxy.applies);
    assertFalse(corners.apply(target, Float.NaN, 4));
    assertFalse(corners.apply(target, 28, -1));
  }

  @Test public void nativeFlushedCloneUsesSourceRadiiOnlyForFallbackRestoration() {
    fill.setCornerRadii(radii(28, 4));
    target.setTopCornerRadius(18);
    target.setBottomCornerRadius(20);
    int invalidations = target.invalidations;
    int outlines = target.outlines;
    int setters = target.setters;

    corners.nativeFallbackSource(target, 28, 4, 16, 12);
    // Seeding the clone is bookkeeping: nothing visible changes before the normal apply.
    assertArrayEquals(radii(28, 4), fill.getCornerRadii(), 0);
    assertEquals(invalidations, target.invalidations);
    assertEquals(outlines, target.outlines);
    assertEquals(setters, target.setters);
    assertEquals(0, wrapper.proxy.applies);

    assertTrue(corners.apply(target, 28, 4));
    corners.clear();
    assertArrayEquals(radii(16, 12), fill.getCornerRadii(), 0);
    assertEquals(18, target.top, 0);
    assertEquals(20, target.bottom, 0);
    assertConfig(wrapper.proxy.config, 16, 16, 16, 16, 16);
  }

  @Test public void duplicateFlushNotificationCannotReplaceExistingFallbackBaseline() {
    fill.setCornerRadii(radii(28, 4));
    corners.nativeFallbackSource(target, 28, 4, 16, 12);
    corners.nativeFallbackSource(target, 28, 4, 22, 20);
    assertTrue(corners.apply(target, 28, 4));
    corners.clear();
    assertArrayEquals(radii(16, 12), fill.getCornerRadii(), 0);

    fill.setCornerRadii(radii(28, 4));
    corners.nativeFallbackSource(target, 28, 4, 16, 12);
    assertTrue(corners.apply(target, 28, 4));
    fill.setCornerRadii(radii(22, 20));
    corners.nativeFallbackSource(target, 22, 20, 10, 8);
    assertTrue(corners.apply(target, 30, 6));
    corners.clear();
    assertArrayEquals("An external native update remains the new baseline", radii(22, 20), fill.getCornerRadii(), 0);
  }

  @Test public void aNewFlushedWrapperReceivesItsOwnFallbackSource() {
    fill.setCornerRadii(radii(28, 4));
    corners.nativeFallbackSource(target, 28, 4, 16, 12);
    assertTrue(corners.apply(target, 28, 4));
    GradientDrawable previousFill = fill;
    BlurWrapper previousWrapper = wrapper;
    fill = gradient(18);
    fill.setCornerRadii(radii(30, 6));
    wrapper = new BlurWrapper(fill, new Proxy(new BlurConfig(18)));
    target.setBackground(wrapper);

    corners.nativeFallbackSource(target, 30, 6, 18, 14);
    assertArrayEquals(radii(16, 12), previousFill.getCornerRadii(), 0);
    assertConfig(previousWrapper.proxy.config, 16, 16, 16, 16, 16);
    assertArrayEquals(radii(30, 6), fill.getCornerRadii(), 0);
    assertEquals(28, target.top, 0);
    assertEquals(4, target.bottom, 0);
    assertTrue(corners.apply(target, 30, 6));
    corners.clear();
    assertArrayEquals(radii(18, 14), fill.getCornerRadii(), 0);
    assertConfig(wrapper.proxy.config, 18, 18, 18, 18, 18);
    assertEquals(16, target.top, 0);
    assertEquals(16, target.bottom, 0);
  }

  @Test public void fallbackSourceRequiresExactRadiiAndVerifiedCallbacks() {
    fill.setCornerRadii(radii(19, 11));
    corners.nativeFallbackSource(target, 28, 4, 16, 12);
    assertTrue(corners.apply(target, 28, 4));
    corners.clear();
    assertArrayEquals(radii(19, 11), fill.getCornerRadii(), 0);

    fill.setCornerRadii(radii(28, 4));
    fill.setCallback(null);
    int invalidations = target.invalidations;
    corners.nativeFallbackSource(target, 28, 4, 16, 12);
    corners.clear();
    assertArrayEquals(radii(28, 4), fill.getCornerRadii(), 0);
    assertEquals(invalidations, target.invalidations);
  }

  private static GradientDrawable gradient(float radius) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(Color.RED);
    drawable.setCornerRadius(radius);
    return drawable;
  }

  private static float[] radii(float top, float bottom) {
    return new float[]{top, top, top, top, bottom, bottom, bottom, bottom};
  }

  private static void assertConfig(BlurConfig config, float lt, float rt, float lb, float rb, float stroke) {
    assertEquals(lt, config.leftTop, 0);
    assertEquals(rt, config.rightTop, 0);
    assertEquals(lb, config.leftBottom, 0);
    assertEquals(rb, config.rightBottom, 0);
    assertEquals(stroke, config.stroke.radius, 0);
  }

  public static final class OverlayView extends FrameLayout {
    float top = 16, bottom = 16;
    int invalidations, outlines, setters, clipTop, clipBottom;
    OverlayView(Context context) { super(context); }
    public float getTopCornerRadius() { return top; }
    public float getBottomCornerRadius() { return bottom; }
    public void setTopCornerRadius(float value) { top = value; setters++; }
    public void setBottomCornerRadius(float value) { bottom = value; setters++; }
    @Override public void invalidate() { invalidations++; super.invalidate(); }
    @Override public void invalidateOutline() { outlines++; super.invalidateOutline(); }
  }

  public static final class BlurWrapper extends Drawable implements Drawable.Callback {
    final GradientDrawable inner;
    final Proxy proxy;
    boolean failGetter;
    BlurWrapper(GradientDrawable inner, Proxy proxy) {
      this.inner = inner;
      this.proxy = proxy;
      inner.setCallback(this);
    }
    public Drawable getDefaultDrawable() {
      if (failGetter) throw new IllegalStateException("native getter unavailable");
      return inner;
    }
    public Proxy getViewBlurProxy() { return proxy; }
    @Override public void draw(Canvas canvas) { inner.draw(canvas); }
    @Override public void setAlpha(int value) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
    @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
    @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
  }

  public static final class Proxy {
    BlurConfig config;
    int applies;
    float[] applied;
    Proxy(BlurConfig config) { this.config = config; }
    public BlurConfig getBlurConfig() { return config; }
    public void applyBlurConfig() {
      applies++;
      applied = new float[]{config.leftTop, config.rightTop, config.leftBottom, config.rightBottom};
    }
  }

  public static final class BlurConfig {
    float leftTop, rightTop, leftBottom, rightBottom;
    final Stroke stroke;
    int setters;
    BlurConfig(float radius) {
      leftTop = rightTop = leftBottom = rightBottom = radius;
      stroke = new Stroke(radius);
    }
    public float getLeftTopCornerRadius() { return leftTop; }
    public float getRightTopCornerRadius() { return rightTop; }
    public float getLeftBottomCornerRadius() { return leftBottom; }
    public float getRightBottomCornerRadius() { return rightBottom; }
    public void setLeftTopCornerRadius(float value) { leftTop = value; setters++; }
    public void setRightTopCornerRadius(float value) { rightTop = value; setters++; }
    public void setLeftBottomCornerRadius(float value) { leftBottom = value; setters++; }
    public void setRightBottomCornerRadius(float value) { rightBottom = value; setters++; }
    public Stroke getGradientStrokeCornerParam() { return stroke; }
  }

  public static final class Stroke {
    float radius;
    int setters;
    Stroke(float radius) { this.radius = radius; }
    public float getRadius() { return radius; }
    public void setRadius(float value) { radius = value; setters++; }
  }
}
