package dev.oos.nativepanels;

import static org.junit.Assert.*;

import org.junit.Test;

public class NotificationNativeBlurSnapshotTest {
  @Test public void restoresZeroCornersSkippedByTheNativeRadiusSetter() {
    Proxy proxy = new Proxy(0);
    NotificationNativeBlurSnapshot snapshot = NotificationNativeBlurSnapshot.capture(proxy);
    assertNotNull(snapshot);
    proxy.config.nativeUpdate(28, 4);
    assertTrue(snapshot.written());
    proxy.config.nativeUpdate(0, 0);
    assertCorners(proxy.config, 28, 28, 4, 4, 28);

    assertTrue(snapshot.restore());
    assertCorners(proxy.config, 0, 0, 0, 0, 0);
    assertEquals(1, proxy.applies);
    assertFalse(snapshot.restore());
    assertEquals(1, proxy.applies);
  }

  @Test public void captureAndTrackingNeverWriteOrApplyAnything() {
    Proxy proxy = new Proxy(16);
    NotificationNativeBlurSnapshot snapshot = NotificationNativeBlurSnapshot.capture(proxy);
    assertNotNull(snapshot);
    for (int i = 0; i < 120; i++) {
      assertTrue(snapshot.matches(proxy));
      assertTrue(snapshot.beforeWrite());
      assertTrue(snapshot.written());
    }
    assertFalse(snapshot.restore());
    assertEquals(0, proxy.config.setters);
    assertEquals(0, proxy.config.stroke.setters);
    assertEquals(0, proxy.applies);
  }

  @Test public void originalAsymmetricCornersAndStrokeArePreserved() {
    Proxy proxy = new Proxy(16);
    proxy.config.rightTop = 17;
    proxy.config.leftBottom = 18;
    proxy.config.rightBottom = 19;
    proxy.config.stroke.radius = 20;
    NotificationNativeBlurSnapshot snapshot = NotificationNativeBlurSnapshot.capture(proxy);
    proxy.config.nativeUpdate(28, 4);
    assertTrue(snapshot.written());
    assertTrue(snapshot.restore());
    assertCorners(proxy.config, 16, 17, 18, 19, 20);
    assertEquals(1, proxy.applies);
  }

  @Test public void newerExternalValuesBecomeTheBaselineBeforeOurNextWrite() {
    Proxy proxy = new Proxy(16);
    NotificationNativeBlurSnapshot snapshot = NotificationNativeBlurSnapshot.capture(proxy);
    proxy.config.nativeUpdate(28, 4);
    snapshot.written();
    proxy.config.nativeUpdate(20, 20);
    int setters = proxy.config.setters;
    assertTrue(snapshot.beforeWrite());
    assertEquals(setters, proxy.config.setters);
    proxy.config.nativeUpdate(30, 6);
    snapshot.written();
    assertTrue(snapshot.restore());
    assertCorners(proxy.config, 20, 20, 20, 20, 20);
  }

  @Test public void aPartialExternalWriteDoesNotMakeOurUnchangedFieldsNative() {
    Proxy proxy = new Proxy(16);
    NotificationNativeBlurSnapshot snapshot = NotificationNativeBlurSnapshot.capture(proxy);
    proxy.config.nativeUpdate(28, 4);
    snapshot.written();
    proxy.config.setLeftTopCornerRadius(20);
    assertTrue(snapshot.beforeWrite());
    proxy.config.nativeUpdate(30, 6);
    snapshot.written();
    assertTrue(snapshot.restore());
    assertCorners(proxy.config, 20, 16, 16, 16, 16);
  }

  @Test public void nativeRequestsUpdateTheBaselineButBothZeroKeepsNativeEarlyReturn() {
    Proxy proxy = new Proxy(16);
    NotificationNativeBlurSnapshot snapshot = NotificationNativeBlurSnapshot.capture(proxy);
    proxy.config.nativeUpdate(28, 4);
    snapshot.written();
    snapshot.beforeWrite();
    snapshot.nativeRadius(20, 12);
    proxy.config.nativeUpdate(30, 6);
    snapshot.written();
    snapshot.beforeWrite();
    snapshot.nativeRadius(0, 0);
    proxy.config.nativeUpdate(32, 8);
    snapshot.written();
    assertTrue(snapshot.restore());
    assertCorners(proxy.config, 20, 20, 12, 12, 20);
  }

  @Test public void firstNativeRequestIsRetainedBeforeTheFirstOwnedWrite() {
    Proxy proxy = new Proxy(16);
    NotificationNativeBlurSnapshot snapshot = NotificationNativeBlurSnapshot.capture(proxy);
    snapshot.beforeWrite();
    snapshot.nativeRadius(8, 8);
    proxy.config.nativeUpdate(14, 14);
    snapshot.written();
    snapshot.beforeWrite();
    snapshot.nativeRadius(0, 0);
    proxy.config.nativeUpdate(28, 28);
    snapshot.written();
    assertTrue(snapshot.restore());
    assertCorners(proxy.config, 8, 8, 8, 8, 8);
  }

  @Test public void writtenRecordsActualValuesWhenNativeThresholdSkipsAnUpdate() {
    Proxy proxy = new Proxy(16);
    NotificationNativeBlurSnapshot snapshot = NotificationNativeBlurSnapshot.capture(proxy);
    proxy.config.nativeUpdate(28, 4);
    snapshot.written();
    snapshot.beforeWrite();
    snapshot.nativeRadius(16.2f, 12.2f);
    proxy.config.nativeUpdate(28.2f, 4.2f);
    snapshot.written();
    assertCorners(proxy.config, 28, 28, 4, 4, 28);
    assertTrue(snapshot.restore());
    assertCorners(proxy.config, 16.2f, 16.2f, 12.2f, 12.2f, 16.2f);
  }

  @Test public void releaseDoesNotOverwriteAnyNewerExternalWrite() {
    Proxy proxy = new Proxy(16);
    NotificationNativeBlurSnapshot snapshot = NotificationNativeBlurSnapshot.capture(proxy);
    proxy.config.nativeUpdate(28, 4);
    snapshot.written();
    proxy.config.setLeftTopCornerRadius(20);
    int setters = proxy.config.setters;
    assertFalse(snapshot.restore());
    assertCorners(proxy.config, 20, 28, 4, 4, 28);
    assertEquals(setters, proxy.config.setters);
    assertEquals(0, proxy.applies);
  }

  @Test public void replacedConfigOrStrokeCannotBeRestoredThroughAnOldSnapshot() {
    Proxy proxy = new Proxy(16);
    NotificationNativeBlurSnapshot old = NotificationNativeBlurSnapshot.capture(proxy);
    Config original = proxy.config;
    original.nativeUpdate(28, 4);
    old.written();
    proxy.config = new Config(20);
    assertFalse(old.matches(proxy));
    assertFalse(old.beforeWrite());
    assertFalse(old.written());
    assertFalse(old.restore());
    assertCorners(original, 28, 28, 4, 4, 28);
    assertCorners(proxy.config, 20, 20, 20, 20, 20);

    NotificationNativeBlurSnapshot rebound = NotificationNativeBlurSnapshot.capture(proxy);
    proxy.config.nativeUpdate(30, 6);
    rebound.written();
    proxy.config.stroke = new Stroke(22);
    assertFalse(rebound.matches(proxy));
    assertFalse(rebound.restore());
    assertEquals(0, proxy.applies);
    assertFalse(rebound.matches(new Proxy(20)));
  }

  @Test public void unavailableVendorApisFailWithoutMutationOrExceptions() {
    assertNull(NotificationNativeBlurSnapshot.capture(null));
    assertNull(NotificationNativeBlurSnapshot.capture(new Object()));
    Proxy proxy = new Proxy(16);
    NotificationNativeBlurSnapshot snapshot = NotificationNativeBlurSnapshot.capture(proxy);
    proxy.config.nativeUpdate(28, 4);
    snapshot.written();
    proxy.failGetter = true;
    assertFalse(snapshot.matches(proxy));
    assertFalse(snapshot.beforeWrite());
    assertFalse(snapshot.written());
    assertFalse(snapshot.restore());
    assertEquals(0, proxy.applies);
    assertNull(NotificationNativeBlurSnapshot.capture(proxy));
  }

  private static void assertCorners(Config config, float lt, float rt, float lb, float rb, float stroke) {
    assertEquals(lt, config.leftTop, 0);
    assertEquals(rt, config.rightTop, 0);
    assertEquals(lb, config.leftBottom, 0);
    assertEquals(rb, config.rightBottom, 0);
    assertEquals(stroke, config.stroke.radius, 0);
  }

  public static final class Proxy {
    Config config;
    int applies;
    boolean failGetter;
    Proxy(float radius) { config = new Config(radius); }
    public Config getBlurConfig() {
      if (failGetter) throw new IllegalStateException("unavailable native getter");
      return config;
    }
    public void applyBlurConfig() { applies++; }
  }

  public static final class Config {
    float leftTop, rightTop, leftBottom, rightBottom;
    Stroke stroke;
    int setters;
    Config(float radius) {
      leftTop = rightTop = leftBottom = rightBottom = radius;
      stroke = new Stroke(radius);
    }
    void nativeUpdate(float top, float bottom) {
      if ((top == 0 && bottom == 0)
          || (top != 0 && bottom != 0 && Math.max(Math.abs(top - leftTop), Math.abs(bottom - leftBottom)) < .5f)) return;
      setLeftTopCornerRadius(top);
      setRightTopCornerRadius(top);
      setLeftBottomCornerRadius(bottom);
      setRightBottomCornerRadius(bottom);
      stroke.setRadius(top);
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
