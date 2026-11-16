package dev.oos.nativepanels;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import java.util.IdentityHashMap;
import java.util.Map;

/** Only manages the shade background. SystemUI draws the brightness mirror itself. */
final class BrightnessPreview {
  private final ShadeDecor owner;
  private final ShadeLayout layout;
  private final PanelRuntime runtime;
  private final Map<View, Float> faded = new IdentityHashMap<>();
  private long down = -1;
  private Object mirrorState;
  private java.lang.reflect.Method readState, writeState;
  private boolean visible;
  private Object depth;
  int shown;

  BrightnessPreview(ShadeDecor owner, ShadeLayout layout, PanelRuntime runtime) {
    this.owner = owner;
    this.layout = layout;
    this.runtime = runtime;
  }

  void down(MotionEvent event) {
    reset();
    down = event.getDownTime();

  }

  void finish(MotionEvent event) {
    if (down == event.getDownTime()) reset();
  }

  void attachState(java.lang.reflect.Method make, java.lang.reflect.Method read, java.lang.reflect.Method write) throws ReflectiveOperationException {
    if (mirrorState != null) return;
    mirrorState = make.invoke(null, false);
    readState = read;
    writeState = write;
  }

  boolean requested() throws ReflectiveOperationException {
    return mirrorState != null && Boolean.TRUE.equals(readState.invoke(mirrorState));
  }

  void start() {
    if (down < 0 || visible || mirrorState == null || runtime.locked()) return;
    try {
      View root = owner.frame.getRootView();
      int id = root.getResources().getIdentifier("legacy_window_root", "id", root.getContext().getPackageName());
      if (id == 0 || !(root.findViewById(id) instanceof ViewGroup parent)) return;
      writeState.invoke(mirrorState, true);
      visible = true;
      for (String name : new String[]{"panel_view_pager", "scrim_behind", "scrim_notifications", "oplus_background_mask"}) {
        int childId = parent.getResources().getIdentifier(name, "id", parent.getContext().getPackageName());
        View child = parent.findViewById(childId);
        fade(child);
      }
      try {
        Object controller = runtime.provider("notificationShadeDepthControllerProvider");
        if (Boolean.FALSE.equals(Reflect.call(controller, "getBrightnessMirrorVisible"))) {
          depth = controller;
          Reflect.method(controller.getClass(), "setBrightnessMirrorVisible", boolean.class).invoke(controller, true);
        }
      } catch (Throwable e) { layout.warn(e); }
      shown++;
    } catch (Exception e) { reset(); layout.warn(e); }
  }

  void fade(View layer) {
    if (layer == null || faded.containsKey(layer)) return;
    faded.put(layer, layer.getTransitionAlpha());
    layer.setTransitionAlpha(0f);
  }

  void reset() {
    down = -1;
    if (visible) {
      visible = false;
      try { writeState.invoke(mirrorState, false); }
      catch (ReflectiveOperationException e) { layout.warn(e); }
    }
    for (Map.Entry<View,Float> item : faded.entrySet())
      if (item.getKey().getTransitionAlpha() == 0f) item.getKey().setTransitionAlpha(item.getValue());
    faded.clear();
    if (depth != null) {
      try { Reflect.method(depth.getClass(), "setBrightnessMirrorVisible", boolean.class).invoke(depth, false); }
      catch (Exception e) { layout.warn(e); }
      depth = null;
    }
  }

  boolean visible() { return visible; }
}
