package dev.oos.nativepanels;

import android.content.res.Resources;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/** Match only the OEM's large-type dual-SIM strategy. The signal stack is a single Box. */
final class StackedMobileTypeOrder {
  private final PanelRuntime runtime;
  private final Class<?> coord, rowPolicy, arrangement;
  private final Method node, parent, children, policy, setPolicy, copy;
  private final Field horizontal, vertical;
  private boolean warned;
  private Object lastDimensions, compactDimensions;
  private float lastLimit;

  StackedMobileTypeOrder(PanelRuntime r) throws Exception {
    runtime = r;
    coord = r.type("androidx.compose.ui.node.NodeCoordinator");
    Class<?> layout = r.type("androidx.compose.ui.node.LayoutNode");
    node = coord.getMethod("getLayoutNode");
    parent = layout.getMethod("getParent$ui");
    children = layout.getMethod("getChildren$ui");
    policy = layout.getMethod("getMeasurePolicy");
    setPolicy = layout.getMethod("setMeasurePolicy", policy.getReturnType());
    rowPolicy = r.type("androidx.compose.foundation.layout.RowMeasurePolicy");
    horizontal = Reflect.field(rowPolicy, "horizontalArrangement");
    vertical = Reflect.field(rowPolicy, "verticalAlignment");
    arrangement = horizontal.getType();
    copy = rowPolicy.getMethod("copy", arrangement, vertical.getType());
  }

  void install(Resources resources) throws Exception {
    String pkg = "com.android.systemui.statusbar.pipeline.shared.ui.composable.";
    Class<?> modifier = runtime.type("androidx.compose.ui.Modifier");
    Class<?> fn = runtime.type("kotlin.jvm.functions.Function1");
    Object unit = runtime.type("kotlin.Unit").getField("INSTANCE").get(null);
    Object callback = Proxy.newProxyInstance(runtime.loader, new Class<?>[]{fn}, (proxy, method, args) -> {
      if (method.getDeclaringClass() == Object.class) return identity(proxy, method, args);
      try { attach(args[0]); } catch (ReflectiveOperationException | RuntimeException e) { warn(e); }
      return unit;
    });
    Method placed = runtime.type("androidx.compose.ui.layout.OnPlacedModifierKt").getMethod("onPlaced", modifier, fn);
    Object marker = placed.invoke(null, modifier.getField("Companion").get(null), callback);
    Method then = modifier.getMethod("then", modifier);
    Class<?> strategy = runtime.type(pkg + "BigTypeStackedMobileIconLayoutStrategy");
    for (Method m : strategy.getDeclaredMethods()) {
      if (!m.getName().startsWith("SignalIcon-") || m.getParameterCount() != 7 || m.getParameterTypes()[4] != modifier) continue;
      runtime.module.hook(m).setId("native-panels:stacked-mobile-order")
          .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object[] args = chain.getArgs().toArray();
            try { args[4] = then.invoke(args[4], marker); }
            catch (ReflectiveOperationException e) { warn(e); }
            return chain.proceed(args);
          });
      for (Method caller : runtime.type(pkg + "OplusStackedMobileIconLayoutStrategy$ComposeDefaultImpls").getDeclaredMethods())
        if (caller.getName().startsWith("SignalIcon$default-")) runtime.module.deoptimize(caller);
      installSpacing(strategy, resources);
      return;
    }
    throw new NoSuchMethodException(strategy.getName() + ".SignalIcon");
  }

  // Only the large-type strategy receives this copy, so the regular stacked icon and
  // roaming dimensions continue to come from OOS. Layout measures the compacted width.
  private void installSpacing(Class<?> strategy, Resources res) throws Exception {
    Method dimensions = strategy.getMethod("getDimensions");
    Class<?> type = dimensions.getReturnType();
    Method copyDimensions = type.getDeclaredMethod("copy-EiCO8V4", float.class, float.class, long.class,
        long.class, float.class, float.class, float.class, float.class, long.class, float.class, float.class, float.class);
    String[] names = {"contentStartPadding", "mobileTextMinWidth", "networkTypeTextPrefixFontSize",
        "networkTypeTextSuffixFontSize", "networkTypeTextStartPadding", "networkTypeTextEndPadding",
        "networkTypeTextBottomPadding", "mobileSignalIconStartPadding", "roamingTextFontSize",
        "roamingTextStartPadding", "roamingTextEndPadding", "roamingContentBottomPadding"};
    Field[] values = new Field[names.length];
    for (int i = 0; i < names.length; i++) values[i] = type.getField(names[i]);
    runtime.module.hook(dimensions).setId("native-panels:stacked-mobile-spacing")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          Object stock = chain.proceed();
          float limit = MobileTypeOrder.oneSp(res) / res.getDisplayMetrics().density;
          if (stock.equals(lastDimensions) && limit == lastLimit) return compactDimensions;
          float end = values[5].getFloat(stock), start = values[7].getFloat(stock);
          if (end + start <= limit) return stock;
          Object[] args = new Object[values.length];
          for (int i = 0; i < values.length; i++) args[i] = values[i].get(stock);
          args[7] = Math.min(start, limit);
          args[5] = Math.max(0f, limit - start);
          Object compact = copyDimensions.invoke(stock, args);
          compactDimensions = compact;
          lastLimit = limit;
          lastDimensions = stock;
          return compact;
        });
    for (Method method : strategy.getDeclaredMethods())
      if (method.getName().equals("StackedMobileIcon")) runtime.module.deoptimize(method);
  }

  private void attach(Object coordinates) throws ReflectiveOperationException {
    if (!coord.isInstance(coordinates)) return;
    Object signal = node.invoke(coordinates);
    Object row = parent.invoke(signal);
    if (row == null) return;
    Object originalPolicy = policy.invoke(row);
    if (!rowPolicy.isInstance(originalPolicy)) return;
    Object original = horizontal.get(originalPolicy);
    if (Proxy.isProxyClass(original.getClass()) && Proxy.getInvocationHandler(original) instanceof Order order) {
      order.signal = signal;
      return;
    }
    Object ordered = Proxy.newProxyInstance(runtime.loader, new Class<?>[]{arrangement}, new Order(original, row, signal));
    setPolicy.invoke(row, copy.invoke(originalPolicy, ordered, vertical.get(originalPolicy)));
  }

  private final class Order implements InvocationHandler {
    final Object original, row;
    Object signal;
    Order(Object original, Object row, Object signal) {
      this.original = original;
      this.row = row;
      this.signal = signal;
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if (method.getDeclaringClass() == Object.class) return identity(proxy, method, args);
      Object result = Reflect.invoke(method, original, args);
      if (method.getName().equals("arrange")) {
        try {
          List<?> nodes = (List<?>) children.invoke(row);
          int[] widths = (int[]) args[2];
          if (nodes.size() == widths.length)
            MobileTypeOrder.swapPositions(widths, (int[]) args[4], nodes.indexOf(signal), ((Enum<?>)args[3]).name().equals("Rtl"));
        } catch (ReflectiveOperationException | RuntimeException e) { warn(e); }
      }
      return result;
    }
  }

  private static Object identity(Object proxy, Method method, Object[] args) {
    return switch (method.getName()) {
      case "equals" -> proxy == args[0];
      case "hashCode" -> System.identityHashCode(proxy);
      default -> "StackedMobileTypeOrder";
    };
  }

  private void warn(Exception e) {
    if (!warned) {
      warned = true;
      runtime.module.log(Log.WARN, "OOSNativePanels", "Stacked signal placement", e);
    }
  }
}
