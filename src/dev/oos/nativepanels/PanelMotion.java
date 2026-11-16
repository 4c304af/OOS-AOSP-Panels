package dev.oos.nativepanels;

import android.app.Dialog;
import android.view.View;
import android.view.ViewParent;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;

/** Preserves QS Expandable objects; media searches its source hierarchy for a LaunchableView. */
final class PanelMotion {
  final ThreadLocal<Object> source = new ThreadLocal<>();
  private final PanelRuntime r;

  PanelMotion(PanelRuntime r) {
    this.r = r;
  }

  Object fromSourceOrView(View view) throws Throwable {
    Object current = source.get();
    if (current != null) return current;
    View candidate = launchable(view);
    if (candidate == null) return null;
    Class<?> expandable = r.type("com.android.systemui.animation.Expandable");
    return Reflect.invoke(Reflect.method(expandable, "fromView", View.class), null, candidate);
  }

  private View launchable(View view) throws ClassNotFoundException {
    Class<?> launchable = r.type("com.android.systemui.animation.LaunchableView");
    View current = view;
    for (int i = 0; current != null && i < 12; i++) {
      if (launchable.isInstance(current) && current.isAttachedToWindow()) return current;
      ViewParent parent = current.getParent();
      current = parent instanceof View ? (View) parent : null;
    }
    return null;
  }

  Object mediaController(View view) throws Throwable {
    Object expandable = fromSourceOrView(view);
    if (expandable == null) return null;
    Class<?> cuj = r.type("com.android.systemui.animation.DialogCuj");
    Object marker = cuj.getConstructor(int.class, String.class).newInstance(58, "media_output");
    return Reflect.invoke(
        Reflect.method(expandable.getClass(), "dialogTransitionController", cuj),
        expandable,
        marker);
  }

  void showFromTile(Dialog dialog, Object source, Object delegate, String tag) throws Throwable {
    if (dialog.getWindow() != null)
      dialog.getWindow().setWindowAnimations(android.R.style.Animation_Dialog);
    Object controller = null;
    if (source != null) {
      Class<?> cuj = r.type("com.android.systemui.animation.DialogCuj");
      Object marker =
          cuj.getConstructor(int.class, String.class).newInstance(58, tag);
      controller =
          Reflect.invoke(
              Reflect.method(
                  r.type("com.android.systemui.animation.Expandable"),
                  "dialogTransitionController",
                  cuj),
              source,
              marker);
    }
    if (controller == null) dialog.show();
    else {
      Object animator = Reflect.get(delegate, "dialogTransitionAnimator");
      boolean shown =
          (Boolean)
              Reflect.invoke(
                  Reflect.method(
                      animator.getClass(),
                      "show",
                      Dialog.class,
                      r.type("com.android.systemui.animation.DialogTransitionAnimator$Controller"),
                      boolean.class),
                  animator,
                  dialog,
                  controller,
                  false);
      if (!shown && !dialog.isShowing()) dialog.show();
    }
  }

  void install() throws Throwable {
    // Native transition animators override these attributes when a source is available.
    // For tests started by another process, keep a platform popup animation instead of a hard cut.
    String[] delegates = {
      "com.android.systemui.qs.tiles.dialog.InternetDialogDelegateLegacy",
      "com.android.systemui.bluetooth.qsdialog.BluetoothTileDialogDelegate",
      "com.android.systemui.media.dialog.MediaOutputDialogDelegate"
    };
    for (String name : delegates) {
      Method create = Reflect.method(r.type(name), "createDialog");
      r.module
          .hook(create)
          .setId("native-panels:window-motion")
          .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
          .intercept(
              c -> {
                Object result = c.proceed();
                if (result instanceof Dialog) {
                  Dialog dialog = (Dialog) result;
                  if (dialog.getWindow() != null)
                    dialog.getWindow().setWindowAnimations(android.R.style.Animation_Dialog);
                }
                return result;
              });
    }
  }
}
