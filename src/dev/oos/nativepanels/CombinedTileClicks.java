package dev.oos.nativepanels;

import android.content.res.Resources;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/** Use the grid's native icon and body targets with the actions supplied by the tile backend. */
final class CombinedTileClicks {
  private static String oplusTogglePanel(String tileClass) {
    return switch (tileClass) {
      case "com.oplus.systemui.qs.tiles.OplusWifiTile", "com.oplus.systemui.qs.tiles.OplusCellularTile" -> "internet";
      case "com.oplus.systemui.qs.tiles.OplusBluetoothTile" -> "bluetooth";
      case "com.oplus.systemui.qs.tiles.OplusDndTile" -> "modes";
      case "com.oplus.systemui.qs.tiles.OplusFlashlightTile" -> "flashlight";
      default -> "";
    };
  }

  static String panelFor(String spec, String tileClass) {
    return switch (spec == null ? "" : spec.toLowerCase(Locale.ROOT)) {
      case "wifi", "internet", "cell", "cellular" -> "internet";
      case "bt", "bluetooth" -> "bluetooth";
      case "dnd", "modes", "flashlight" -> tileClass.isEmpty()
          ? ("flashlight".equals(spec) ? "flashlight" : "modes") : oplusTogglePanel(tileClass);
      default -> oplusTogglePanel(tileClass);
    };
  }

  private static boolean available(PanelRuntime r, String feature) {
    return !feature.isEmpty() && r.enabled(feature)
        && (!feature.equals("flashlight") || r.flashlight.supportsGridPanel());
  }

  enum Action { SYSTEM, TOGGLE_OPLUS, DETAIL_OPLUS, PANEL }

  static boolean recorderSpec(String spec) {
    if (spec == null) return false;
    return switch (spec) {
      case "screenrecord",
          "custom(com.oplus.screenrecorder/.floatwindow.services.QuickPanelService)",
          "custom(com.oplus.screenrecorder/com.oplus.screenrecorder.floatwindow.services.QuickPanelService)",
          "custom(com.coloros.screenrecorder/.services.QuickPanelService)",
          "custom(com.coloros.screenrecorder/com.coloros.screenrecorder.services.QuickPanelService)" -> true;
      default -> false;
    };
  }

  static boolean recorderBackend(String spec, String tileClass) {
    return recorderSpec(spec) && ("screenrecord".equals(spec)
        ? "com.oplus.systemui.qs.tiles.ScreenRecorderTile".equals(tileClass)
        : "com.android.systemui.qs.external.CustomTile".equals(tileClass));
  }

  static Action recorderAction(boolean icon, boolean dualTarget, int state, boolean disabledByPolicy) {
    if (!dualTarget || state == 0 || disabledByPolicy) return Action.SYSTEM;
    return icon ? Action.TOGGLE_OPLUS : Action.DETAIL_OPLUS;
  }

  static Action action(boolean icon, String tileClass, int state, boolean disabledByPolicy, boolean keyguard) {
    if (state == 0 || disabledByPolicy) return Action.SYSTEM;
    if (!keyguard && !icon) return Action.PANEL;
    if (oplusTogglePanel(tileClass).isEmpty()) return Action.SYSTEM;
    return icon ? Action.TOGGLE_OPLUS : Action.DETAIL_OPLUS;
  }

  static String rotationDrawable(int state) {
    return state == 2 ? "qs_auto_rotate_icon_on" : "qs_auto_rotate_icon_off";
  }

  static void installRotation(PanelRuntime r) throws Exception {
    Class<?> state = r.type("com.android.systemui.plugins.qs.QSTile$State");
    Field spec = state.getField("spec"), status = state.getField("state"), icon = state.getField("icon");
    Method copy = state.getMethod("copy");
    Method load = r.type("com.android.systemui.qs.tileimpl.QSTileImpl").getDeclaredMethod(
        "maybeLoadResourceIcon", int.class, android.content.Context.class);
    Method mapper = r.type("com.android.systemui.qs.panels.ui.viewmodel.TileUiStateKt").getMethod("toIconProvider", state);
    r.module.hook(mapper).setId("native-panels:rotation-icon").intercept(c -> {
      Object original = c.getArg(0);
      if (!"rotation".equals(spec.get(original))) return c.proceed();
      ShadeDecor owner = r.combinedQs.layout.hapticOwner();
      if (owner == null) return c.proceed();
      android.content.Context ctx = owner.frame.getContext();
      int id = ctx.getResources().getIdentifier(rotationDrawable(status.getInt(original)), "drawable", Protocol.TARGET);
      if (id == 0) return c.proceed();
      Object replacement = load.invoke(null, id, ctx);
      if (replacement == null) return c.proceed();
      Object forGrid = copy.invoke(original);
      icon.set(forGrid, replacement);
      return c.proceed(new Object[]{forGrid});
    });
    Class<?> grid = r.type("com.android.systemui.qs.panels.ui.compose.infinitegrid.TileKt");
    for (Method caller : grid.getDeclaredMethods()) if (caller.getName().equals("Tile")) r.module.deoptimize(caller);
    try {
      r.module.deoptimize(r.type(grid.getName() + "$Tile$3$icon$2$1$1").getDeclaredMethod("emit",
          Object.class, r.type("kotlin.coroutines.Continuation")));
    } catch (ReflectiveOperationException ignored) { }
  }

  static void install(PanelRuntime r) throws Exception {
    Class<?> vm = r.type("com.android.systemui.qs.panels.ui.viewmodel.TileViewModel");
    Class<?> source = r.type("com.android.systemui.animation.Expandable");
    Class<?> state = r.type("com.android.systemui.plugins.qs.QSTile$State");
    Method click = r.type("com.android.systemui.plugins.qs.QSTile").getMethod("click", source);
    Method secondaryClick = click.getDeclaringClass().getMethod("secondaryClick", source);
    Method current = vm.getMethod("getCurrentState");
    Field enabledState = state.getField("state");
    Field byPolicy = state.getField("disabledByPolicy");
    Field dualTarget = Reflect.optionalField(state, "dualTarget");
    String grid = "com.android.systemui.qs.panels.ui.compose.infinitegrid.TileKt";
    for (int suffix : new int[]{1, 18, 19}) {
      try { r.module.deoptimize(r.type(grid + "$$ExternalSyntheticLambda" + suffix).getDeclaredMethod("invoke")); }
      catch (ReflectiveOperationException ignored) { /* Synthetic names vary between ROMs. */ }
    }
    for (Method m : new Method[]{vm.getMethod("mainClick", source), vm.getMethod("toggleClick")}) {
      boolean icon = m.getParameterCount() == 0;
      r.module.hook(m).setId("native-panels:combined-" + m.getName()).intercept(chain -> {
        ShadeLayout layout = r.combinedQs.layout;
        if (!layout.gridVisible()) return chain.proceed();
        Object tileVm = chain.getThisObject();
        Object backend = Reflect.get(tileVm, "tile");
        String backendName = backend.getClass().getName();
        Object tileSpec = Reflect.call(tileVm, "getSpec");
        String name = (String) Reflect.call(tileSpec, "getSpec");
        boolean recorder = recorderBackend(name, backendName);
        String feature = panelFor(name, backendName);
        if (!recorder && !available(r, feature)) return chain.proceed();
        Object snapshot = current.invoke(tileVm);
        boolean keyguard = !layout.active() || r.locked();
        int status = enabledState.getInt(snapshot);
        boolean restricted = byPolicy.getBoolean(snapshot);
        Action route = recorder
            ? recorderAction(icon, dualTarget != null && dualTarget.getBoolean(snapshot), status, restricted)
            : action(icon, backendName, status, restricted, keyguard);
        if (route == Action.SYSTEM) return chain.proceed();
        Object from = icon ? null : chain.getArg(0);
        if (from == null) from = Reflect.call(tileVm, "getExpandable");
        try {
          // Keep keyguard toggles and detail requests on the public tile entry points.
          if (route == Action.DETAIL_OPLUS) Reflect.invoke(secondaryClick, backend, from);
          else if (route == Action.TOGGLE_OPLUS && !keyguard && feature.equals("modes")) r.modes.toggleFromGrid();
          else if (route == Action.TOGGLE_OPLUS) Reflect.invoke(click, backend, from);
          else if (feature.equals("modes")) r.modes.show(from);
          else if (feature.equals("flashlight")) r.flashlight.showFromGrid(from);
          else if (feature.equals("internet")) r.showInternet(from);
          else r.showBluetooth(from);
        } catch (Throwable e) {
          if (recorder) layout.warn(e);
          else r.failure(feature, e);
        }
        return null;
      });
    }

    Field spec = state.getField("spec");
    Field secondary = state.getField("handlesSecondaryClick");
    Method copy = state.getMethod("copy");
    Class<?> mapper = r.type("com.android.systemui.qs.panels.ui.viewmodel.TileUiStateKt");
    Method map = mapper.getDeclaredMethod("toUiState", state, Resources.class);
    r.module.hook(map).setId("native-panels:combined-tile-targets").intercept(chain -> {
      Object original = chain.getArg(0);
      if (!r.combinedQs.layout.hasCombinedGrid() || secondary.getBoolean(original)) return chain.proceed();
      String name = (String) spec.get(original);
      if (recorderSpec(name)) {
        // OOS reports the recorder menu through dualTarget. Compose reads handlesSecondaryClick.
        if (recorderAction(false, dualTarget != null && dualTarget.getBoolean(original),
            enabledState.getInt(original), byPolicy.getBoolean(original)) == Action.SYSTEM) return chain.proceed();
      } else if (!available(r, panelFor(name, ""))) return chain.proceed();
      // SystemUI reuses its State for other listeners. Change only the Compose mapper's input.
      Object forGrid = copy.invoke(original);
      secondary.setBoolean(forGrid, true);
      return chain.proceed(new Object[]{forGrid, chain.getArg(1)});
    });
    for (Method caller : r.type(grid).getDeclaredMethods()) {
      if (caller.getName().equals("Tile")) r.module.deoptimize(caller);
    }
    try {
      r.module.deoptimize(r.type(grid + "$Tile$3$uiState$2$1$1")
          .getDeclaredMethod("emit", Object.class, r.type("kotlin.coroutines.Continuation")));
    } catch (ReflectiveOperationException ignored) { /* State mapper still works without this optimization hint. */ }
  }
}
