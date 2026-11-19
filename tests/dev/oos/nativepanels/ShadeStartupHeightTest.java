package dev.oos.nativepanels;

import static org.junit.Assert.assertEquals;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Proxy;
import org.junit.Test;

public class ShadeStartupHeightTest {
  static class Stack {
    Object mEmptyShadeView;
    Object mShelf;
    int minimumCalls, originalCalls;

    int getMinExpansionHeight() {
      if (mShelf == null) throw new AssertionError("Shelf has not been attached");
      minimumCalls++;
      return 72;
    }

    float getAppearEndPosition() {
      if (mEmptyShadeView == null) throw new NullPointerException("Empty view has not been bound");
      originalCalls++;
      return 164.5f;
    }
  }

  @Test public void keyguardCollapseAndFirstLayoutCanPrecedeEmptyViewBinding() throws Throwable {
    Stack stack = new Stack();
    XposedInterface.Hooker hook = ShadePullMotion.appearEndHook(Stack.class);
    XposedInterface.Chain chain = (XposedInterface.Chain)Proxy.newProxyInstance(
        getClass().getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getThisObject" -> stack;
          case "proceed" -> stack.getAppearEndPosition();
          default -> throw new AssertionError(method);
        });

    assertEquals(0f, (Float)hook.intercept(chain), 0f);
    assertEquals(0, stack.minimumCalls);
    stack.mShelf = new Object();
    assertEquals(72f, (Float)hook.intercept(chain), 0f);
    assertEquals(0, stack.originalCalls);
    stack.mEmptyShadeView = new Object();
    assertEquals(164.5f, (Float)hook.intercept(chain), 0f);
    assertEquals(1, stack.originalCalls);
    assertEquals(1, stack.minimumCalls);

    stack.mShelf = null;
    assertEquals(0f, (Float)hook.intercept(chain), 0f);
    assertEquals(1, stack.originalCalls);
    assertEquals(1, stack.minimumCalls);
  }
}
