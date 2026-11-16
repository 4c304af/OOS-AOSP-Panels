package dev.oos.nativepanels;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Looper;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import android.os.UserHandle;

/** Uses the ROM's Compose modes dialog, view model and Zen services as a single native unit. */
final class ModesPanel {
  static final String DELEGATE =
      "com.android.systemui.statusbar.policy.ui.dialog.ModesDialogDelegate";
  private final PanelRuntime r;
  private Object delegate;
  private Dialog ownedDialog;

  ModesPanel(PanelRuntime runtime) {
    r = runtime;
  }

  void install() throws Throwable {
    Class<?> type = r.type(DELEGATE);
    r.module
        .hook(
            Reflect.method(
                type,
                "openSettings",
                r.type("com.android.systemui.statusbar.phone.SystemUIDialog")))
        .setId("native-panels:modes-settings")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
        .intercept(
            c -> {
              if (!owns(c.getThisObject())) return c.proceed();
              return r.redirect(
                  c,
                  "modes",
                  null,
                  () -> {
                    Reflect.call(Reflect.get(delegate, "dialogEventLogger"), "logDialogSettings");
                    openSettings();
                    return null;
                  });
            });
    r.module
        .hook(Reflect.method(type, "launchFromDialog", Intent.class))
        .setId("native-panels:modes-rule-settings")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
        .intercept(
            c -> {
              if (!owns(c.getThisObject()) || !OosModesSettings.handles((Intent) c.getArg(0)))
                return c.proceed();
              return r.redirect(
                  c,
                  "modes",
                  null,
                  () -> {
                    // OOS's per-rule extra contract is not known. Open its main DND page, without
                    // passing an AOSP rule ID as if it were an OOS detail-page identifier.
                    openSettings();
                    return null;
                  });
            });
    r.module
        .hook(
            Reflect.method(
                r.type("com.oplus.systemui.qs.tiles.OplusDndTile"),
                "handleSecondaryClick",
                r.type("com.android.systemui.animation.Expandable")))
        .setId("native-panels:modes-tile")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
        .intercept(
            c -> {
              if (!r.enabled("modes") || r.locked()) return c.proceed();
              Object source = c.getArg(0);
              return r.redirect(
                  c,
                  "modes",
                  null,
                  () -> {
                    show(source);
                    return null;
                  });
            });
    r.modesHook = true;
  }

  // Run the same two suspending calls as ModesTile.handleSecondaryClick in SystemUI.
  void toggleFromGrid() throws Throwable {
    UserHandle user = r.currentUser();
    Object data = r.provider("modesTileDataInteractorProvider");
    Object actions = r.provider("modesTileUserActionInteractorProvider");
    Class<?> continuation = r.type("kotlin.coroutines.Continuation");
    Class<?> model = r.type("com.android.systemui.qs.tiles.impl.modes.domain.model.ModesTileModel");
    Method current = Reflect.method(data.getClass(), "getCurrentTileModel", continuation);
    Method toggle = Reflect.method(actions.getClass(), "handleToggleClick", model, continuation);
    Object suspended = r.type("kotlin.coroutines.intrinsics.CoroutineSingletons").getField("COROUTINE_SUSPENDED").get(null);
    Object dispatcher = Reflect.invoke(Reflect.method(r.type("kotlinx.coroutines.Dispatchers"), "getMain"), null);
    Result apply = value -> {
      if (!r.locked() && user.equals(r.currentUser()))
        Reflect.invoke(toggle, actions, value, completion(continuation, dispatcher, ignored -> {}));
    };
    Object value = Reflect.invoke(current, data, completion(continuation, dispatcher, apply));
    if (value != suspended) apply.accept(value);
  }

  private interface Result { void accept(Object value) throws Throwable; }

  private Object completion(Class<?> type, Object dispatcher, Result result) throws Exception {
    Method check = Reflect.method(r.type("kotlin.ResultKt"), "throwOnFailure", Object.class);
    return Proxy.newProxyInstance(r.loader, new Class<?>[]{type}, (proxy, method, args) -> switch (method.getName()) {
      case "getContext" -> dispatcher;
      case "resumeWith" -> {
        Runnable finish = () -> {
          try { Reflect.invoke(check, null, args[0]); result.accept(args[0]); }
          catch (Throwable error) { r.failure("modes", error); }
        };
        if (Looper.myLooper() == r.main.getLooper()) finish.run(); else r.main.post(finish);
        yield null;
      }
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == args[0];
      case "toString" -> "ModesTile continuation";
      default -> throw new UnsupportedOperationException(method.getName());
    });
  }

  void show(Object source) throws Throwable {
    if (Looper.myLooper() != Looper.getMainLooper())
      throw new IllegalStateException(r.text(R.string.modes_thread));
    if (delegate == null) delegate = r.provider("modesDialogDelegateProvider");
    Dialog current = (Dialog) Reflect.call(delegate, "getCurrentDialog");
    if (current != null && current.isShowing()) return;
    // createDialog owns the Compose content, lifecycle observer, view model and duration dialog.
    // This is the synchronous main-thread body of the ROM's suspend showDialog implementation.
    Dialog dialog = current != null ? current : (Dialog) Reflect.call(delegate, "createDialog");
    ownedDialog = dialog;
    r.motion.showFromTile(dialog, source, delegate, "configure_priority_modes");
    r.note(R.string.modes_open);
  }

  private boolean owns(Object candidate) throws Throwable {
    return candidate == delegate
        && ownedDialog != null
        && Reflect.call(candidate, "getCurrentDialog") == ownedDialog;
  }

  private void openSettings() throws Throwable {
    Context context = (Context) Reflect.call(delegate, "getContext");
    PackageManager pm = context.getPackageManager();
    Intent target = OosModesSettings.main();
    if (pm.resolveActivity(target, PackageManager.MATCH_DEFAULT_ONLY) == null) {
      target = OosModesSettings.explicitMain();
      if (pm.resolveActivity(target, 0) == null) throw new IllegalStateException(r.text(R.string.modes_error));
    }
    // The original helper retains dialog-to-activity animation and ActivityStarter/keyguard logic.
    Method launch =
        Reflect.method(
            r.type(DELEGATE), "access$actualLaunchFromDialog", r.type(DELEGATE), Intent.class);
    Reflect.invoke(launch, null, delegate, target);
    r.note(R.string.modes_settings);
  }

  void dismiss() {
    if (ownedDialog != null) ownedDialog.dismiss();
    ownedDialog = null;
  }
}
