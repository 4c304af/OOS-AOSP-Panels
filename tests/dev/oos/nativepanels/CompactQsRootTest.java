package dev.oos.nativepanels;

import static org.junit.Assert.*;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Proxy;
import org.junit.Test;

public class CompactQsRootTest {
  public record Modifier(String chain) {}

  public static Modifier fillMaxHeight(Modifier original, float fraction) {
    assertEquals(1f, fraction, 0f);
    return new Modifier(original.chain() + ",fillMaxHeight");
  }

  final CompactQsRoot root = new CompactQsRoot();
  int nativeCalls, applied;

  Modifier semanticAction() throws Throwable {
    var hook = root.heightHook(CompactQsRootTest.class.getMethod("fillMaxHeight", Modifier.class, float.class),
        () -> applied++);
    var chain = (XposedInterface.Chain)Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> switch (method.getName()) {
          case "proceed" -> { nativeCalls++; yield new Modifier("nativeSemanticAction"); }
          default -> throw new AssertionError(method);
        });
    return (Modifier)hook.intercept(chain);
  }

  @Test public void onlyExpandedOuterColumnFillsHeightAndPreservesTheNativeAction() throws Throwable {
    Boolean previous = root.enter(true);
    try {
      assertEquals("nativeSemanticAction,fillMaxHeight", semanticAction().chain());
      assertEquals("nativeSemanticAction", semanticAction().chain());
    } finally { root.leave(previous); }
    assertEquals("nativeSemanticAction", semanticAction().chain());
    assertEquals(3, nativeCalls);
    assertEquals(1, applied);
  }

  @Test public void qqsAndForeignCompositionsCannotConsumeTheOwnedExpandedRoot() throws Throwable {
    Boolean previous = root.enter(true);
    try {
      Boolean unrelated = root.enter(false);
      try { assertEquals("nativeSemanticAction", semanticAction().chain()); }
      finally { root.leave(unrelated); }
      assertEquals("nativeSemanticAction,fillMaxHeight", semanticAction().chain());
    } finally { root.leave(previous); }
    for (int composition = 0; composition < 2; composition++) {
      Boolean unowned = root.enter(false);
      try { assertEquals("nativeSemanticAction", semanticAction().chain()); }
      finally { root.leave(unowned); }
    }
    assertEquals(4, nativeCalls);
    assertEquals(1, applied);
  }
}
