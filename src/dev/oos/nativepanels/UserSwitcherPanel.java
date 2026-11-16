package dev.oos.nativepanels;

import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;

/** The same user list, adapter, availability policies and dialog for both QS frontends. */
final class UserSwitcherPanel {
  private final PanelRuntime r;
  private volatile WeakReference<Dialog> showing = new WeakReference<>(null);
  private Constructor<?> adapterConstructor, delegateConstructor;
  private Method create, shadeContext, enabled;
  boolean ready;

  UserSwitcherPanel(PanelRuntime r) { this.r = r; }

  void install() throws Exception {
    Class<?> api = r.type("com.android.systemui.user.ui.dialog.UserSwitchDialogDelegate");
    Class<?> interactor = r.type("com.android.systemui.user.domain.interactor.UserSwitcherInteractor");
    Class<?> adapter = r.type("com.android.systemui.qs.tiles.UserDetailView$Adapter");
    Class<?> logger = r.type("com.android.internal.logging.UiEventLogger");
    Class<?> falsing = r.type("com.android.systemui.plugins.FalsingManager");
    adapterConstructor = adapter.getConstructor(Context.class,
        r.type("com.android.systemui.statusbar.policy.UserSwitcherController"), logger, falsing);
    delegateConstructor = api.getConstructor(adapter, logger, falsing,
        r.type("com.android.systemui.plugins.ActivityStarter"),
        r.type("com.android.systemui.animation.DialogTransitionAnimator"),
        r.type("com.android.systemui.statusbar.phone.SystemUIDialog$Factory"), Context.class);
    create = api.getMethod("createDialog");
    enabled = interactor.getMethod("isUserSwitcherEnabled");
    shadeContext = r.type("com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor").getMethod("getContext");
    Handler main = new Handler(Looper.getMainLooper());
    r.module.hook(interactor.getMethod("dismissDialog")).setId("native-panels:footer-users-dismiss").intercept(c -> {
      Dialog dialog = showing.get();
      if (dialog != null) main.post(() -> { if (dialog.isShowing()) dialog.dismiss(); });
      return c.proceed();
    });
    ready = true;
    Class<?> footer = r.type("com.android.systemui.qs.footer.domain.interactor.FooterActionsInteractorImpl");
    r.module.hook(footer.getMethod("showUserSwitcher", r.type("com.android.systemui.animation.Expandable")))
        .setId("native-panels:footer-users").intercept(c -> {
          if (!r.combinedQs.layout.active() || !r.enabled("users") || r.locked()) return c.proceed();
          Object source = c.getArg(0);
          return r.redirect(c, "users", null, () -> { show(source); return null; });
        });
  }

  void preview() throws Throwable {
    if (!(Boolean)enabled.invoke(r.provider("userSwitcherInteractorProvider"))) {
      r.message(R.string.users_disabled);
      return;
    }
    show(null);
  }

  void show(Object source) throws Throwable {
    if (r.locked()) return;
    Dialog previous = showing.get();
    if (previous != null && previous.isShowing()) return;
    Context context = (Context)shadeContext.invoke(r.provider("provideShadeDialogContextInteractorProvider"));
    Object logger = r.provider("qsEventLoggerImplProvider");
    Object falsing = r.provider("falsingManagerProxyProvider");
    Object adapter = adapterConstructor.newInstance(context, r.provider("userSwitcherControllerProvider"), logger, falsing);
    Object dialogFactory = Reflect.get(r.provider("internetDialogManagerProvider"), "systemUIDialogFactory");
    Object delegate = delegateConstructor.newInstance(adapter, logger, falsing,
        r.provider("activityStarterImplProvider"), r.provider("provideDialogTransitionAnimatorProvider"), dialogFactory, context);
    Dialog dialog = (Dialog)create.invoke(delegate);
    try {
      r.motion.showFromTile(dialog, source, delegate, "user_switcher");
    } catch (Throwable e) {
      if (!dialog.isShowing()) throw e;
    }
    showing = new WeakReference<>(dialog);
    r.note(R.string.users_opened);
  }
}
