package dev.oos.nativepanels;

import static org.junit.Assert.*;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Proxy;
import org.junit.Test;

public class LockscreenQsDragTest {
  static class Controller {
    boolean tracking = true, mAnimating;
    float height;
    boolean isTracking() { return tracking; }
    float getExpansionHeight() { return height; }
    void setExpansionHeight(float value) { height = Math.min(600f, Math.max(0f, value)); }
  }

  record Move(Controller f$0) {}

  static class Spring {
    boolean running;
    float start;
    boolean isRunning() { return running; }
    void cancel() { running = false; }
    void setStartValue(float value) { assertFalse(running); start = value; }
  }

  static class Values {
    final Spring spring = new Spring();
    Object listener;
    Object getMblock() { return listener; }
    void setMblock(Object value) { listener = value; }
    Spring getKeyguardHAnimation() { return spring; }
  }

  final Controller host = new Controller();
  final Values values = new Values();
  final Move move = new Move(host);
  boolean direct = true;
  int stockCalls;
  final XposedInterface.Hooker hook;

  public LockscreenQsDragTest() throws Exception {
    hook = LockscreenQsMotion.dragHook(Values.class, Move.class, Controller.class,
        controller -> direct && controller == host);
  }

  void drag(float height, Object listener) throws Throwable {
    values.listener = listener;
    var chain = (XposedInterface.Chain)Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> switch (method.getName()) {
          case "getThisObject" -> values;
          case "getArg" -> height;
          case "proceed" -> { stockCalls++; yield null; }
          default -> throw new AssertionError(method);
        });
    hook.intercept(chain);
  }

  @Test public void tracksMovesAndReversalsImmediatelyAndHandsClampedHeightToRelease() throws Throwable {
    values.spring.running = true;
    for (float h : new float[]{40, 60, 590, 680, 570, 0}) {
      drag(h, move);
      assertEquals(Math.min(h, 600f), host.height, 0f);
      assertEquals(host.height, values.spring.start, 0f);
      assertNull(values.listener);
    }
    assertEquals(0, stockCalls);
  }

  @Test public void releaseAndUnrelatedTouchPathsStayWithTheSystem() throws Throwable {
    values.spring.running = true;
    host.tracking = false;
    drag(500, move);
    host.tracking = true;
    host.mAnimating = true;
    drag(500, move);
    host.mAnimating = false;
    direct = false;
    drag(500, move);
    direct = true;
    drag(500, new Move(new Controller()));
    drag(500, new Object());
    drag(Float.NaN, move);
    assertEquals(6, stockCalls);
    assertEquals(0f, host.height, 0f);
    assertTrue(values.spring.running);
    assertSame(move, values.listener);
  }
}
