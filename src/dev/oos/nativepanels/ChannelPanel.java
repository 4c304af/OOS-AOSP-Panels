package dev.oos.nativepanels;

import android.app.Dialog;
import android.app.KeyguardManager;
import android.app.NotificationChannel;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.view.HapticFeedbackConstants;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Runs on the posted, policy-checked OOS row action before its popup animation starts. */
final class ChannelPanel {
  private static final String ROW = "com.android.systemui.statusbar.notification.row.";
  private static final String MENU =
      "com.oplus.systemui.notification.row.menupanel.OplusNotificationPopupMenuImpl";
  private final PanelRuntime r;
  private WeakReference<Dialog> active = new WeakReference<>(null);

  ChannelPanel(PanelRuntime r) {
    this.r = r;
  }

  void install() throws Throwable {
    Method action = Reflect.method(r.type(MENU),
        "$r8$lambda$fNxQdPMIg70ELy1_UZZUEDoi9GI", r.type(ROW + "ExpandableNotificationRow"),
        r.type(MENU), boolean.class, r.type(MENU + "$$ExternalSyntheticLambda0"));
    r.module.hook(action).setId("native-panels:notification-channel")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          if (!(Boolean) c.getArg(2) || !r.enabled("channels") || r.locked()) return c.proceed();
          boolean shown;
          try {
            shown = show((View) c.getArg(0));
          } catch (Throwable error) {
            r.failure("channels", error);
            return c.proceed();
          }
          if (!shown) return c.proceed();
          // The original completion resets the exposed swipe menu and shade overscroll.
          return Reflect.call(c.getArg(3), "invoke");
        });
    r.channelsHook = true;
  }

  private boolean show(View row) throws Throwable {
    if (!row.isAttachedToWindow() || (Boolean) Reflect.get(row, "mShowingPublic")
        || (Boolean) Reflect.call(row, "isSummaryWithChildren")) return false;
    Object entry = Reflect.call(row, "getEntry");
    if (entry == null || !(Boolean) Reflect.get(entry, "mBlockable")) return false;
    StatusBarNotification sbn = (StatusBarNotification) Reflect.call(entry, "getSbn");
    NotificationChannel channel = (NotificationChannel) Reflect.call(entry, "getChannel");
    if (sbn == null || channel == null) return false;
    int uid = (Integer) Reflect.call(sbn, "getUid");
    String pkg = sbn.getPackageName();
    UserHandle user = sbn.getUser();
    if (uid < 0 || !UserHandle.getUserHandleForUid(uid).equals(user)) return false;
    Context context = (Context) Reflect.invoke(
        Reflect.method(Context.class, "createContextAsUser", UserHandle.class, int.class),
        row.getContext(), user, 0);
    if (context.getSystemService(KeyguardManager.class).isDeviceLocked()) return false;
    PackageManager pm = context.getPackageManager();
    ApplicationInfo info;
    try {
      info = pm.getApplicationInfo(pkg, 0);
    } catch (PackageManager.NameNotFoundException removed) {
      return false;
    }
    if (info.uid != uid) return false;
    Dialog previous = active.get();
    if (previous != null && previous.isShowing()) return true;
    // The ROM singleton retains proposed edits after Back. Use fresh edit state for every row.
    Object base = r.provider("channelEditorDialogControllerProvider");
    Class<?> builder = r.type(ROW + "ChannelEditorDialog$Builder");
    Object editor = base.getClass().getConstructor(
        r.type("com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor"),
        r.type("android.app.INotificationManager"), builder).newInstance(
            Reflect.get(base, "shadeDialogContextInteractor"), Reflect.get(base, "noMan"),
            builder.getConstructor().newInstance());
    Class<?> listener = r.type(ROW + "NotificationInfo$OnSettingsClickListener");
    Object guts = r.provider("notificationGutsManagerProvider");
    Method settings = Reflect.method(guts.getClass(), "startAppNotificationSettingsActivity",
        String.class, int.class, NotificationChannel.class, r.type(ROW + "ExpandableNotificationRow"));
    Object callback = Proxy.newProxyInstance(r.loader, new Class<?>[]{listener}, (proxy, method, args) -> {
      switch (method.getName()) {
        case "onClick":
          if (!r.locked() && !context.getSystemService(KeyguardManager.class).isDeviceLocked()
              && ((Integer) args[1]) == uid) {
            try {
              Reflect.invoke(settings, guts, pkg, uid, args[0], row);
            } catch (Throwable error) {
              r.failure("channels", error);
            }
          }
          return null;
        case "hashCode": return System.identityHashCode(proxy);
        case "equals": return proxy == args[0];
        case "toString": return "Notification channel settings";
        default: throw new UnsupportedOperationException(method.getName());
      }
    });
    Reflect.invoke(Reflect.method(editor.getClass(), "prepareDialogForApp", String.class,
        String.class, int.class, NotificationChannel.class, Drawable.class, listener), editor,
        info.loadLabel(pm).toString(), pkg, uid, channel, info.loadIcon(pm), callback);
    Dialog dialog = (Dialog) Reflect.get(editor, "dialog");
    dialog.show();
    active = new WeakReference<>(dialog);
    // The OEM popup performs (LONG_PRESS, FLAG_IGNORE_VIEW_SETTING) on this same row.
    try {
      row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS,
          HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
    } catch (RuntimeException error) {
      r.note(R.string.haptic_error, error.getClass().getSimpleName());
    }
    r.note(R.string.channels_open);
    return true;
  }
}
