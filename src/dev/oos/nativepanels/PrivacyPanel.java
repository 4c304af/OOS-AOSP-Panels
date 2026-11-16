package dev.oos.nativepanels;

import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.UserHandle;
import android.view.View;
import android.view.ViewParent;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;

/** Uses the ROM's permission queries, profile filtering, detail rows and permission intents. */
final class PrivacyPanel {
  private static final String CONTROLLER = "com.android.systemui.privacy.PrivacyDialogControllerV2";
  private static final String CHIP = "com.android.systemui.privacy.AbstractOngoingPrivacyChip";
  private static final String OOS_DIALOG = "com.oplus.systemui.privacy.dialog.OplusQSPrivacyDialogController";
  private final PanelRuntime r;
  private Class<?> chipType;
  private Method showNative;
  private Request pending;

  // A distinct wrapper follows the native request through both executor callbacks. Other
  // SystemUI callers keep their own context and their runnables always execute unmodified.
  private static final class Request extends ContextWrapper {
    final UserHandle user;
    final boolean preview;

    Request(Context base, UserHandle user, boolean preview) {
      super(base);
      this.user = user;
      this.preview = preview;
    }
  }

  PrivacyPanel(PanelRuntime runtime) {
    r = runtime;
  }

  void install() throws Throwable {
    chipType = r.type(CHIP);
    Class<?> controller = r.type(CONTROLLER);
    showNative = Reflect.method(controller, "showDialog", Context.class, chipType);
    Reflect.field(controller, "dialog");
    hookWorker(CONTROLLER + "$showDialog$1", false);
    hookWorker(CONTROLLER + "$showDialog$1$1", true);
    // The separate shade may route this particular tap straight to Safety Center.
    // Hook the indicator dispatcher as well as the OEM dialog used by the other shade layout.
    hookIndicator("com.oplus.systemui.separate.OplusSeparateQSPrivacyManager$QSPrivaryImpl");
    hookIndicator(OOS_DIALOG);
    r.privacyHook = true;
  }

  private void hookIndicator(String owner) throws Throwable {
    r.module.hook(Reflect.method(r.type(owner), "showDialog", View.class))
        .setId("native-panels:privacy-indicator")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
        .intercept(c -> {
          if (!r.enabled("privacy") || r.locked()) return c.proceed();
          View anchor = (View) c.getArg(0);
          return r.redirect(c, "privacy", null, () -> {
            show(anchor, false);
            return null;
          });
        });
  }

  private void hookWorker(String name, boolean ui) throws Throwable {
    Class<?> type = r.type(name);
    java.lang.reflect.Field context = Reflect.field(type, "$context");
    java.lang.reflect.Field owner = ui ? Reflect.field(type, "this$0") : null;
    r.module.hook(Reflect.method(type, "run"))
        .setId(ui ? "native-panels:privacy-ui" : "native-panels:privacy-query")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
        .intercept(c -> {
          Object task = c.getThisObject();
          if (!(context.get(task) instanceof Request request)) return c.proceed();
          try {
            if (ui && (pending != request || r.locked() || !request.user.equals(r.currentUser())
                || (!request.preview && !r.enabled("privacy")))) {
              if (pending == request) pending = null;
              return null;
            }
            Object result = c.proceed();
            if (ui) {
              Dialog dialog = (Dialog) Reflect.get(owner.get(task), "dialog");
              if (dialog != null && dialog.isShowing()) r.note(R.string.privacy_opened);
              else r.message(R.string.privacy_empty);
              pending = null;
            }
            return result;
          } catch (Throwable error) {
            r.main.post(() -> {
              if (pending == request) {
                pending = null;
                r.failure("privacy", error);
              }
            });
            return null;
          }
        });
  }

  void show(View anchor, boolean preview) throws Throwable {
    if (r.locked()) return;
    Object controller = r.provider("privacyDialogControllerV2Provider");
    Context base = (Context) Reflect.call(r.provider("shadeDialogContextInteractorImplProvider"), "getContext");
    Request request = new Request(base, r.currentUser(), preview);
    pending = request;
    Object chip = findChip(anchor);
    try {
      Reflect.invoke(showNative, controller, request, chip);
    } catch (Throwable error) {
      pending = null;
      throw error;
    }
  }

  private Object findChip(View anchor) {
    // The OOS path also accepts plain views. Only an actual attached AOSP chip can supply
    // the controller used for the native expand/collapse transition.
    for (View current = anchor; current != null && current.isAttachedToWindow();) {
      if (chipType.isInstance(current)) return current;
      ViewParent parent = current.getParent();
      current = parent instanceof View ? (View) parent : null;
    }
    return null;
  }
}
