package dev.oos.nativepanels;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class CombinedBrightnessTest {
  private static final int MAX = 65535;
  private static final double A = .17883277, B = .28466892, C = .55991073;

  public static final class FakeDragging { public int brightness; }
  public static final class FakeStopped { public int brightness; }
  public static final class ExtraState { public int brightness; public boolean tracking; }
  public static final class WrongValue { public float brightness; }
  public static final class NoDefaultConstructor {
    public int brightness;
    public NoDefaultConstructor(int value) { brightness = value; }
  }

  private static final CombinedBrightness.Curve HLG = new CombinedBrightness.Curve() {
    public float linear(int progress) { return (float)nativeLinear(progress); }
    public int gamma(float fraction) { return nativeGamma(fraction); }
  };

  private static double nativeLinear(int progress) {
    double value = progress / (double)MAX;
    double linear = value <= .5 ? Math.pow(value / .5, 2) : Math.exp((value - C) / A) + B;
    return Math.max(0, Math.min(1, linear / 12));
  }

  private static int nativeGamma(double fraction) {
    double linear = Math.max(0, Math.min(1, fraction)) * 12;
    double value = linear <= 1 ? Math.sqrt(linear) * .5 : A * Math.log(linear - B) + C;
    return (int)Math.round(Math.max(0, Math.min(1, value)) * MAX);
  }

  private static CombinedBrightness configured(Object owner) throws ReflectiveOperationException {
    CombinedBrightness brightness = new CombinedBrightness(() -> owner);
    brightness.configure(HLG, FakeDragging.class, FakeStopped.class);
    return brightness;
  }

  private static FakeDragging dragging(int value) {
    FakeDragging event = new FakeDragging();
    event.brightness = value;
    return event;
  }

  private static FakeStopped stopped(int value) {
    FakeStopped event = new FakeStopped();
    event.brightness = value;
    return event;
  }

  @Test public void nativeMidpointIsDisplayedAtItsActualFractionInsteadOfHalfTheTrack() throws Exception {
    Object owner = new Object();
    CombinedBrightness brightness = configured(owner);
    // Native HLG maps a half-position to only 1/12 of an OEM numeric range.
    assertEquals(1d / 12, nativeLinear(MAX / 2), .00001);
    assertEquals(Math.round(MAX / 12f), brightness.display(owner, MAX / 2), 1);
    for (double fraction : new double[]{0, .1, .25, .5, .75, .9, 1}) {
      assertEquals(Math.round(fraction * MAX), brightness.display(owner, nativeGamma(fraction)), 4);
    }
  }

  @Test public void draggingProducesEvenNativeBrightnessAcrossNormalizedAndVendorRanges() throws Exception {
    Object owner = new Object();
    CombinedBrightness brightness = configured(owner);
    for (double[] range : new double[][]{{0, 1}, {16, 8191}, {.03, .65}}) {
      for (int position = 0; position <= MAX; position += 257) {
        FakeDragging original = dragging(position);
        FakeDragging converted = (FakeDragging)brightness.drag(owner, original);
        double expected = range[0] + position / (double)MAX * (range[1] - range[0]);
        double actual = range[0] + nativeLinear(converted.brightness) * (range[1] - range[0]);
        assertEquals("Framework output must follow the visible fraction", expected, actual,
            (range[1] - range[0]) * .0001);
        assertEquals(position, original.brightness);
        assertEquals(position, brightness.display(owner, converted.brightness), 4);
      }
    }
  }

  @Test public void bothDirectionsAreMonotonicAndKeepExactEndpoints() throws Exception {
    Object owner = new Object();
    CombinedBrightness brightness = configured(owner);
    int previousDisplay = -1, previousNative = -1;
    for (int position = 0; position <= MAX; position += 257) {
      int displayed = brightness.display(owner, position);
      int nativePosition = ((FakeDragging)brightness.drag(owner, dragging(position))).brightness;
      assertTrue(displayed >= previousDisplay);
      assertTrue(nativePosition >= previousNative);
      previousDisplay = displayed;
      previousNative = nativePosition;
    }
    assertEquals(0, brightness.display(owner, 0));
    assertEquals(MAX, brightness.display(owner, MAX));
    assertEquals(0, ((FakeDragging)brightness.drag(owner, dragging(0))).brightness);
    assertEquals(MAX, ((FakeDragging)brightness.drag(owner, dragging(MAX))).brightness);
  }

  @Test public void temporaryAndPermanentEventsKeepTheirVariantWithoutMutatingTheInput() throws Exception {
    Object owner = new Object();
    CombinedBrightness brightness = configured(owner);
    FakeDragging temporary = dragging(MAX / 2);
    FakeStopped permanent = stopped(MAX / 2);
    Object convertedTemporary = brightness.drag(owner, temporary);
    Object convertedPermanent = brightness.drag(owner, permanent);
    assertEquals(FakeDragging.class, convertedTemporary.getClass());
    assertEquals(FakeStopped.class, convertedPermanent.getClass());
    assertNotSame(temporary, convertedTemporary);
    assertNotSame(permanent, convertedPermanent);
    assertEquals(((FakeDragging)convertedTemporary).brightness, ((FakeStopped)convertedPermanent).brightness);
    assertEquals(MAX / 2, temporary.brightness);
    assertEquals(MAX / 2, permanent.brightness);
    assertTrue(((FakeDragging)convertedTemporary).brightness > MAX / 2);
  }

  @Test public void sentinelsAndOutOfRangeInputsStayUntouchedWithoutCallingTheCurve() throws Exception {
    Object owner = new Object();
    AtomicInteger calls = new AtomicInteger();
    CombinedBrightness brightness = new CombinedBrightness(() -> owner);
    brightness.configure(new CombinedBrightness.Curve() {
      public float linear(int value) { calls.incrementAndGet(); return .5f; }
      public int gamma(float value) { calls.incrementAndGet(); return MAX / 2; }
    }, FakeDragging.class, FakeStopped.class);
    for (int invalid : new int[]{Integer.MIN_VALUE, -65535, -1, MAX + 1, Integer.MAX_VALUE}) {
      assertEquals(invalid, brightness.display(owner, invalid));
      FakeDragging drag = dragging(invalid);
      FakeStopped stop = stopped(invalid);
      assertSame(drag, brightness.drag(owner, drag));
      assertSame(stop, brightness.drag(owner, stop));
    }
    assertEquals(0, calls.get());
  }

  @Test public void onlyTheCurrentSliderIdentityCanChangeEvenIfAnotherSliderIsEqual() throws Exception {
    Object owner = new String("slider"), equalOwner = new String("slider");
    assertEquals(owner, equalOwner);
    AtomicReference<Object> current = new AtomicReference<>(owner);
    CombinedBrightness brightness = new CombinedBrightness(current::get);
    brightness.configure(HLG, FakeDragging.class, FakeStopped.class);
    FakeDragging event = dragging(MAX / 2);
    assertSame(event, brightness.drag(equalOwner, event));
    assertEquals(MAX / 2, brightness.display(equalOwner, MAX / 2));
    assertNotSame(event, brightness.drag(owner, event));
    current.set(null);
    assertSame(event, brightness.drag(owner, event));
    assertSame(event, brightness.drag(null, event));
    assertEquals(MAX / 2, brightness.display(owner, MAX / 2));
    current.set(equalOwner);
    assertSame(event, brightness.drag(owner, event));
    assertNotSame(event, brightness.drag(equalOwner, event));
  }

  @Test public void unconfiguredAndUnsupportedEventsPreserveNativeBehavior() throws Exception {
    Object owner = new Object();
    CombinedBrightness brightness = new CombinedBrightness(() -> owner);
    FakeDragging event = dragging(MAX / 2);
    assertEquals(MAX / 2, brightness.display(owner, MAX / 2));
    assertSame(event, brightness.drag(owner, event));
    brightness.configure(HLG, FakeDragging.class, FakeStopped.class);
    Object foreign = new Object();
    ExtraState future = new ExtraState();
    future.brightness = MAX / 2;
    future.tracking = true;
    assertSame(foreign, brightness.drag(owner, foreign));
    assertSame(future, brightness.drag(owner, future));
    assertNull(brightness.drag(owner, null));
    assertEquals(MAX / 2, future.brightness);
    assertTrue(future.tracking);
  }

  @Test public void invalidCurveResultsFallBackToTheOriginalValueAndEvent() throws Exception {
    Object owner = new Object();
    for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -.1f, 1.1f}) {
      CombinedBrightness brightness = new CombinedBrightness(() -> owner);
      brightness.configure(new CombinedBrightness.Curve() {
        public float linear(int value) { return invalid; }
        public int gamma(float value) { return -1; }
      }, FakeDragging.class, FakeStopped.class);
      assertEquals(MAX / 2, brightness.display(owner, MAX / 2));
      FakeDragging event = dragging(MAX / 2);
      assertSame(event, brightness.drag(owner, event));
    }
    for (int invalid : new int[]{Integer.MIN_VALUE, -1, MAX + 1, Integer.MAX_VALUE}) {
      CombinedBrightness brightness = new CombinedBrightness(() -> owner);
      brightness.configure(new CombinedBrightness.Curve() {
        public float linear(int value) { return .5f; }
        public int gamma(float value) { return invalid; }
      }, FakeDragging.class, FakeStopped.class);
      FakeStopped event = stopped(MAX / 2);
      assertSame(event, brightness.drag(owner, event));
    }
  }

  @Test public void reflectiveAndRuntimeCurveFailuresNeverConsumeTheNativeEvent() throws Exception {
    Object owner = new Object();
    for (boolean runtime : new boolean[]{false, true}) {
      CombinedBrightness brightness = new CombinedBrightness(() -> owner);
      brightness.configure(new CombinedBrightness.Curve() {
        private void fail() throws ReflectiveOperationException {
          if (runtime) throw new IllegalStateException("ROM conversion unavailable");
          throw new IllegalAccessException("ROM conversion unavailable");
        }
        public float linear(int value) throws ReflectiveOperationException { fail(); return 0; }
        public int gamma(float value) throws ReflectiveOperationException { fail(); return 0; }
      }, FakeDragging.class, FakeStopped.class);
      assertEquals(MAX / 2, brightness.display(owner, MAX / 2));
      FakeDragging drag = dragging(MAX / 2);
      FakeStopped stop = stopped(MAX / 2);
      assertSame(drag, brightness.drag(owner, drag));
      assertSame(stop, brightness.drag(owner, stop));
    }
  }

  @Test public void unknownEventLayoutsAreRejectedBeforeEitherVariantCanBeAdapted() throws Exception {
    Object owner = new Object();
    for (Class<?> unsupported : new Class<?>[]{ExtraState.class, WrongValue.class, NoDefaultConstructor.class}) {
      CombinedBrightness brightness = new CombinedBrightness(() -> owner);
      assertThrows(ReflectiveOperationException.class,
          () -> brightness.configure(HLG, FakeDragging.class, unsupported));
      assertEquals(MAX / 2, brightness.display(owner, MAX / 2));
      FakeDragging event = dragging(MAX / 2);
      assertSame(event, brightness.drag(owner, event));
    }
  }

  @Test public void separateAdaptersCannotBorrowEachOthersOwnershipOrConversion() throws Exception {
    Object firstOwner = new Object(), secondOwner = new Object();
    CombinedBrightness first = configured(firstOwner), second = configured(secondOwner);
    FakeDragging event = dragging(MAX / 2);
    assertNotSame(event, first.drag(firstOwner, event));
    assertSame(event, second.drag(firstOwner, event));
    assertNotSame(event, second.drag(secondOwner, event));
    assertSame(event, first.drag(secondOwner, event));
    assertEquals(first.display(firstOwner, MAX / 2), second.display(secondOwner, MAX / 2));
  }
}
