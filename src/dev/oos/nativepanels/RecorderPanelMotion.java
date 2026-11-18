package dev.oos.nativepanels;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

final class RecorderPanelMotion {
  // OOS creates one view model per displayed panel. Dismissal can run after the shade detaches.
  private final WeakHashMap<Object, Boolean> combinedAtOpen = new WeakHashMap<>();

  Object origin(Object panel, String tag, boolean combined, PanelRuntime.Work explicitOrigin,
      PanelRuntime.Work systemOrigin) throws Throwable {
    if (!CombinedTileClicks.recorderSpec(tag)
        || !combinedAtOpen.computeIfAbsent(panel, key -> combined)) return systemOrigin.run();
    // A Compose tile normally has no source View. The OEM tag search can then find the recorder
    // list inside this very dialog and ghost its text as if it belonged to the tile. A null origin
    // selects OOS's center transition for the entire detail_container on both show and dismiss.
    return explicitOrigin.run();
  }

  void install(PanelRuntime r) throws ReflectiveOperationException {
    String binder = "com.oplus.systemui.viewdialog.ui.binder.ViewDialogContentBinder";
    Class<?> model = r.type("com.oplus.systemui.viewdialog.ui.viewmodel.ViewDialogContentViewModel");
    Field tag = Reflect.field(model, "viewTag"), adapter = Reflect.field(model, "adapter");
    Method explicit = adapter.getType().getMethod("getExpandableView");
    r.module.hook(model.getDeclaredMethod("getOriginView")).setId("native-panels:recorder-motion")
        .intercept(chain -> {
          Object panel = chain.getThisObject();
          return origin(panel, (String)tag.get(panel), r.combinedQs.layout.hasCombinedGrid(),
              () -> explicit.invoke(adapter.get(panel)), chain::proceed);
        });
    // The getter is small enough for ART to inline independently in the two coroutine collectors.
    for (String suffix : new String[]{"$collectViewLifecycle$2", "$collectDismissDialogEvent$2"}) {
      try {
        String name = suffix.startsWith("$collectViewLifecycle") ? "invokeSuspend" : "emit";
        for (Method m : r.type(binder + suffix).getDeclaredMethods())
          if (m.getName().equals(name)) r.module.deoptimize(m);
      } catch (ClassNotFoundException absent) { /* Collector names can differ on other builds. */ }
    }
  }
}
