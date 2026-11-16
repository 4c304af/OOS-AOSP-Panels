package dev.oos.nativepanels;

import java.lang.reflect.Method;

/** Keep the same lazy-grid item alive when its row changes during a drag. */
final class CombinedEditor {
  static String itemKey(String spec) {
    return spec == null || spec.isEmpty() ? null : "qs-edit:" + spec;
  }

  static void install(PanelRuntime r) throws Exception {
    String pkg = "com.android.systemui.qs.panels.ui.";
    Class<?> cell = r.type(pkg + "model.TileGridCell");
    Method tile = cell.getMethod("getTile");
    Method spec = r.type(pkg + "viewmodel.EditTileViewModel").getMethod("getTileSpec");
    Method name = r.type("com.android.systemui.qs.pipeline.shared.TileSpec").getMethod("getSpec");
    r.module.hook(cell.getMethod("getKey")).setId("native-panels:edit-placement").intercept(c -> {
      if (r.combinedQs.layout.hasCombinedGrid()) {
        Object model = tile.invoke(c.getThisObject());
        String key = itemKey((String)name.invoke(spec.invoke(model)));
        if (key != null) return key;
      }
      return c.proceed();
    });
    // The lazy grid's key function and the selection observer must agree. The
    // getter is small enough for ART to inline in either caller.
    String kt = pkg + "compose.infinitegrid.EditTileKt";
    for (String cls : new String[]{kt + "$$ExternalSyntheticLambda12",
        kt + "$TileGridCell$3$1$invokeSuspend$$inlined$map$1$2"}) {
      try {
        for (Method m : r.type(cls).getDeclaredMethods()) {
          if (m.getName().equals("invoke") || m.getName().equals("emit")) r.module.deoptimize(m);
        }
      } catch (ClassNotFoundException ignored) { }
    }
  }
}
