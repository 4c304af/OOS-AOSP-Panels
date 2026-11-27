package dev.oos.nativepanels;

import static org.junit.Assert.*;

import io.github.libxposed.api.XposedInterface;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    public NoDefaultConstructor(int value) { throw new AssertionError("Subclass constructor must not run"); }
  }

  // Host JVM equivalent of the framework's parent-constructor allocation. This
  // is test infrastructure only; production uses libxposed newInstanceSpecial.
  private static final Map<Class<?>, Constructor<?>> SERIAL_CONSTRUCTORS = new ConcurrentHashMap<>();
  private static final CombinedBrightness.Allocator ALLOCATE = type -> {
    Constructor<?> constructor = SERIAL_CONSTRUCTORS.get(type);
    if (constructor == null) {
      Class<?> factoryType = Class.forName("sun.reflect.ReflectionFactory");
      Object factory = factoryType.getMethod("getReflectionFactory").invoke(null);
      constructor = (Constructor<?>)factoryType.getMethod("newConstructorForSerialization", Class.class, Constructor.class)
          .invoke(factory, type, Object.class.getDeclaredConstructor());
      SERIAL_CONSTRUCTORS.put(type, constructor);
    }
    return constructor.newInstance();
  };

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
    brightness.configure(HLG, ALLOCATE, FakeDragging.class, FakeStopped.class);
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
    }, ALLOCATE, FakeDragging.class, FakeStopped.class);
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
    brightness.configure(HLG, ALLOCATE, FakeDragging.class, FakeStopped.class);
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
    brightness.configure(HLG, ALLOCATE, FakeDragging.class, FakeStopped.class);
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
      }, ALLOCATE, FakeDragging.class, FakeStopped.class);
      assertEquals(MAX / 2, brightness.display(owner, MAX / 2));
      FakeDragging event = dragging(MAX / 2);
      assertSame(event, brightness.drag(owner, event));
    }
    for (int invalid : new int[]{Integer.MIN_VALUE, -1, MAX + 1, Integer.MAX_VALUE}) {
      CombinedBrightness brightness = new CombinedBrightness(() -> owner);
      brightness.configure(new CombinedBrightness.Curve() {
        public float linear(int value) { return .5f; }
        public int gamma(float value) { return invalid; }
      }, ALLOCATE, FakeDragging.class, FakeStopped.class);
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
      }, ALLOCATE, FakeDragging.class, FakeStopped.class);
      assertEquals(MAX / 2, brightness.display(owner, MAX / 2));
      FakeDragging drag = dragging(MAX / 2);
      FakeStopped stop = stopped(MAX / 2);
      assertSame(drag, brightness.drag(owner, drag));
      assertSame(stop, brightness.drag(owner, stop));
    }
  }

  @Test public void unknownEventLayoutsAreRejectedBeforeEitherVariantCanBeAdapted() throws Exception {
    Object owner = new Object();
    for (Class<?> unsupported : new Class<?>[]{ExtraState.class, WrongValue.class}) {
      CombinedBrightness brightness = new CombinedBrightness(() -> owner);
      assertThrows(ReflectiveOperationException.class,
          () -> brightness.configure(HLG, ALLOCATE, FakeDragging.class, unsupported));
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

  @Test public void originObjectConstructorAllocatesActualConstructorStrippedEvents() throws Exception {
    Class<?> dragging = strippedEvent("StrippedDragging"), stopped = strippedEvent("StrippedStopped");
    assertEquals(0, dragging.getDeclaredConstructors().length);
    assertEquals(0, stopped.getDeclaredConstructors().length);
    assertThrows(NoSuchMethodException.class, () -> dragging.getDeclaredConstructor());
    List<Class<?>> allocations = new ArrayList<>();
    AtomicInteger parents = new AtomicInteger(), origins = new AtomicInteger();
    Object invoker = Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[]{XposedInterface.CtorInvoker.class}, (proxy, method, args) -> {
          if (method.getName().equals("setType")) {
            assertSame(XposedInterface.Invoker.Type.ORIGIN, args[0]);
            origins.incrementAndGet();
            return proxy;
          }
          assertEquals("newInstanceSpecial", method.getName());
          assertEquals(0, ((Object[])args[1]).length);
          Class<?> subtype = (Class<?>)args[0];
          allocations.add(subtype);
          return ALLOCATE.create(subtype);
        });
    XposedInterface module = (XposedInterface)Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
          assertEquals("getInvoker", method.getName());
          assertEquals(Object.class.getDeclaredConstructor(), args[0]);
          parents.incrementAndGet();
          return invoker;
        });
    Object owner = new Object();
    CombinedBrightness brightness = new CombinedBrightness(() -> owner);
    brightness.configure(HLG, CombinedBrightness.allocator(module), dragging, stopped);
    assertEquals(List.of(dragging, stopped), allocations); // Both allocation paths preflight first.
    for (Class<?> type : new Class<?>[]{dragging, stopped}) {
      Object original = ALLOCATE.create(type);
      type.getField("brightness").setInt(original, MAX / 2);
      Object mapped = brightness.drag(owner, original);
      assertNotSame(original, mapped);
      assertSame(type, mapped.getClass());
      assertEquals(MAX / 2, type.getField("brightness").getInt(original));
      assertEquals(MAX / 2, brightness.display(owner, type.getField("brightness").getInt(mapped)), 4);
    }
    assertEquals(List.of(dragging, stopped, dragging, stopped), allocations);
    assertEquals(1, parents.get());
    assertEquals(1, origins.get());
  }

  @Test public void existingSubclassConstructorIsBypassedAsInTheRom() throws Exception {
    Object owner = new Object();
    CombinedBrightness brightness = new CombinedBrightness(() -> owner);
    brightness.configure(HLG, ALLOCATE, NoDefaultConstructor.class, FakeStopped.class);
    NoDefaultConstructor original = (NoDefaultConstructor)ALLOCATE.create(NoDefaultConstructor.class);
    original.brightness = MAX / 2;
    NoDefaultConstructor mapped = (NoDefaultConstructor)brightness.drag(owner, original);
    assertNotSame(original, mapped);
    assertEquals(MAX / 2, original.brightness);
    assertEquals(MAX / 2, brightness.display(owner, mapped.brightness), 4);
  }

  @Test public void allocationPreflightFailureCannotEnableHalfOfTheMapping() throws Exception {
    Object owner = new Object();
    for (boolean wrongType : new boolean[]{false, true}) {
      CombinedBrightness brightness = new CombinedBrightness(() -> owner);
      AtomicInteger attempts = new AtomicInteger();
      assertThrows(InstantiationException.class, () -> brightness.configure(HLG, type -> {
        attempts.incrementAndGet();
        if (type == FakeStopped.class) {
          if (wrongType) return new Object();
          throw new InstantiationException("framework allocation unavailable");
        }
        return ALLOCATE.create(type);
      }, FakeDragging.class, FakeStopped.class));
      assertEquals(2, attempts.get());
      assertEquals(MAX / 2, brightness.display(owner, MAX / 2));
      FakeDragging original = dragging(MAX / 2);
      assertSame(original, brightness.drag(owner, original));
      assertFalse(brightness.report().contains("ready"));
    }
  }

  @Test public void diagnosticSnapshotKeepsInstallationAndLastMappingsAcrossOwnerDisposal() throws Exception {
    Object owner = new Object();
    AtomicReference<Object> active = new AtomicReference<>(owner);
    CombinedBrightness brightness = new CombinedBrightness(active::get);
    assertTrue(brightness.report().contains("not started"));
    brightness.configure(HLG, ALLOCATE, FakeDragging.class, FakeStopped.class);
    int displayed = brightness.display(owner, MAX / 2);
    FakeDragging mapped = (FakeDragging)brightness.drag(owner, dragging(MAX / 2));
    String report = brightness.report();
    assertTrue(report.contains("ready"));
    assertTrue(report.contains("read(native/ui)=" + MAX / 2 + "/" + displayed));
    assertTrue(report.contains("drag(ui/native)=" + MAX / 2 + "/" + mapped.brightness));
    active.set(null);
    assertTrue(brightness.report().contains("ready"));
    assertTrue(brightness.report().contains("owner=false"));
    brightness.failed(new NoSuchMethodException("missing hook"));
    assertTrue(brightness.report().contains("failed: java.lang.NoSuchMethodException: missing hook"));
    active.set(owner);
    assertEquals(MAX / 2, brightness.display(owner, MAX / 2));
    FakeDragging original = dragging(MAX / 2);
    assertSame(original, brightness.drag(owner, original));
  }

  private static Class<?> strippedEvent(String name) throws Exception {
    // Minimal valid Java class with Object superclass, one int field, and ZERO
    // methods. javac fixtures cannot reproduce the constructor removal in OOS DEX.
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeInt(0xcafebabe); out.writeShort(0); out.writeShort(52);
    out.writeShort(7); // Constant-pool count, including the unused index zero.
    out.writeByte(1); out.writeUTF("dev/oos/nativepanels/" + name); // #1 name
    out.writeByte(7); out.writeShort(1); // #2 this class
    out.writeByte(1); out.writeUTF("java/lang/Object"); // #3 superclass name
    out.writeByte(7); out.writeShort(3); // #4 superclass
    out.writeByte(1); out.writeUTF("brightness"); // #5 field name
    out.writeByte(1); out.writeUTF("I"); // #6 field descriptor
    out.writeShort(0x0011); out.writeShort(2); out.writeShort(4); // public final class
    out.writeShort(0); out.writeShort(1); // no interfaces, one field
    out.writeShort(0x0001); out.writeShort(5); out.writeShort(6); out.writeShort(0);
    out.writeShort(0); out.writeShort(0); // no methods, no class attributes
    byte[] data = bytes.toByteArray();
    return new ClassLoader(CombinedBrightnessTest.class.getClassLoader()) {
      Class<?> define() { return defineClass(null, data, 0, data.length); }
    }.define();
  }
}
