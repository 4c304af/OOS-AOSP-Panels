package dev.oos.nativepanels;

import android.graphics.Paint;
import android.graphics.Path;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Local, heavier artwork for the percentage; the ROM's singleton glyphs stay untouched. */
final class BatteryDigitGlyphs {
  private static final String UI = "com.android.systemui.statusbar.pipeline.battery.shared.ui.";
  private static final String[] DIGITS = {
      "Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine"
  };
  // In the ROM's 19.5 x 12 viewport this adds .2 units to each edge of a digit stroke.
  private static final float STROKE_WIDTH = .4f;
  private final Map<Object, Object> replacements = new IdentityHashMap<>();

  BatteryDigitGlyphs(PanelRuntime runtime) throws ReflectiveOperationException {
    Class<?> glyph = runtime.type(UI + "Glyph");
    Class<?> batteryGlyph = runtime.type(UI + "BatteryGlyph");
    Class<?> androidPath = runtime.type("androidx.compose.ui.graphics.AndroidPath");
    Constructor<?> wrap = androidPath.getConstructor(Path.class);
    Method getPath = glyph.getMethod("getPath");
    Method internalPath = androidPath.getMethod("getInternalPath");
    Method draw = glyph.getMethod("draw", runtime.type("androidx.compose.ui.graphics.drawscope.DrawScope"),
        runtime.type(UI + "BatteryColors"));
    XposedInterface.Invoker<?, Method> drawOriginal =
        runtime.module.getInvoker(draw).setType(XposedInterface.Invoker.Type.ORIGIN);
    for (String digit : DIGITS) {
      Object original = runtime.type(UI + "BatteryGlyph$" + digit).getField("INSTANCE").get(null);
      Path source = (Path)internalPath.invoke(getPath.invoke(original));
      Object path = wrap.newInstance(thicken(source));
      Object replacement = Proxy.newProxyInstance(runtime.loader, new Class<?>[]{batteryGlyph},
          (proxy, method, args) -> switch (method.getName()) {
            case "getPath" -> path;
            case "draw" -> drawOriginal.invokeSpecial(proxy, args);
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "Bold battery " + digit;
            // The host's exact width/height keep its spacing, scaling and attribution layout.
            default -> method.invoke(original, args);
          });
      replacements.put(original, replacement);
    }
  }

  List<?> apply(List<?> original) {
    List<Object> result = null;
    for (int i = 0; i < original.size(); i++) {
      Object glyph = original.get(i);
      Object replacement = replacements.get(glyph);
      if (replacement == null) continue;
      if (result == null) result = new ArrayList<>(original);
      result.set(i, replacement);
    }
    // Charging, power save and other attributions retain their actual native objects.
    return result == null ? original : result;
  }

  static Path thicken(Path original) {
    Path copy = new Path(original);
    Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    stroke.setStyle(Paint.Style.STROKE);
    stroke.setStrokeWidth(STROKE_WIDTH);
    stroke.setStrokeJoin(Paint.Join.ROUND);
    stroke.setStrokeCap(Paint.Cap.ROUND);
    Path outline = new Path();
    if (!stroke.getFillPath(copy, outline) || !copy.op(outline, Path.Op.UNION))
      throw new IllegalStateException("Cannot thicken battery digit");
    return copy;
  }
}
