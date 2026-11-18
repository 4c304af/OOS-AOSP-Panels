package dev.oos.nativepanels;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/** Presents the recorder's own detail adapter in the same animated window used by the QS dialogs. */
final class RecorderTileDialog {
  private final PanelRuntime r;
  private final ThreadLocal<Object> clickSource = new ThreadLocal<>();
  private record Display(Object detail, AlertDialog dialog) {}
  private volatile Display showing;

  RecorderTileDialog(PanelRuntime r) { this.r = r; }

  void install() throws ReflectiveOperationException {
    Class<?> expandable = r.type("com.android.systemui.animation.Expandable");
    Class<?> detailType = r.type("com.oplus.systemui.qs.detail.interactor.OplusQSDetailInteractor");
    Method show = detailType.getDeclaredMethod("show", View.class);
    XposedInterface.Invoker<?, Method> original = r.module.getInvoker(show).setType(XposedInterface.Invoker.Type.ORIGIN);
    // OOS queues secondary clicks. Capture the Expandable from the delivered click, on the tile
    // thread, and intercept show only if the backend actually accepts that click.
    r.module.hook(show).setId("native-panels:recorder-dialog").intercept(chain -> {
      Object source = source();
      Object detail = chain.getThisObject();
      if (source == null || !CombinedTileClicks.recorderSpec((String)Reflect.call(detail, "getViewTag"))
          || !r.combinedQs.layout.hasCombinedGrid() || r.locked()
          || (boolean)Reflect.call(detail, "isShowing")) return chain.proceed();
      View view = (View)chain.getArg(0);
      return r.main.post(() -> {
        if (!r.combinedQs.layout.active() || r.locked() || showing != null) return;
        try {
          if (present(detail, source)) return;
        } catch (Throwable failure) {
          r.combinedQs.layout.warn(failure);
        }
        try { original.invoke(detail, view); }
        catch (Throwable failure) { r.combinedQs.layout.warn(failure); }
      });
    });
    // A service-hosted recorder can request dismissal through the shared dialog interactor.
    Class<?> host = r.type("com.oplusos.systemui.viewdialog.domain.SysUIViewDialogInteractor");
    r.module.hook(host.getMethod("dismissDialog", String.class)).setId("native-panels:recorder-dialog-dismiss")
        .intercept(chain -> {
          Display current = showing;
          if (current != null && Reflect.get(current.detail, "viewDialogInteractor") == chain.getThisObject())
            close(current);
          return chain.proceed();
        });

    Class<?> platform = r.type("com.oplus.systemui.qs.tiles.ScreenRecorderTile");
    Method secondary = platform.getDeclaredMethod("handleSecondaryClick", expandable);
    r.module.hook(secondary).setId("native-panels:recorder-dialog-source").intercept(chain ->
        withinClick(chain.getArg(0), chain::proceed));
    r.module.deoptimize(secondary);
    // Both the consent-activity and foreground-service paths finish the menu selection.
    r.module.hook(platform.getDeclaredMethod("startRecording", boolean.class))
        .setId("native-panels:recorder-dialog-selection").intercept(chain -> {
          Display current = showing;
          if (current != null && current.detail == Reflect.get(chain.getThisObject(), "detailInteractor"))
            close(current);
          return chain.proceed();
        });
    Class<?> extension = r.type("com.oplus.systemui.qs.tileex.CustomTileEx");
    Method customClick = extension.getDeclaredMethod("handleSecondaryClick",
        r.type("com.android.systemui.plugins.qs.QSTile"), Message.class, Handler.class);
    r.module.hook(customClick).setId("native-panels:recorder-service-dialog-source").intercept(chain -> {
      Object ref = ((Message)chain.getArg(1)).obj;
      if (!(ref instanceof WeakReference<?> weak)
          || !CombinedTileClicks.recorderSpec((String)Reflect.call(chain.getArg(0), "getTileSpec")))
        return chain.proceed();
      return withinClick(weak.get(), chain::proceed);
    });
    r.module.deoptimize(customClick);
    r.module.deoptimize(r.type("com.oplus.systemui.qs.tileex.QSTileExImpl")
        .getDeclaredMethod("onInterceptTileMessage", customClick.getParameterTypes()));
    r.module.deoptimize(r.type("com.android.systemui.qs.tileimpl.QSTileImpl$H")
        .getDeclaredMethod("handleMessage", Message.class));
  }

  Object withinClick(Object source, PanelRuntime.Work action) throws Throwable {
    Object previous = clickSource.get();
    clickSource.set(source);
    try { return action.run(); }
    finally {
      if (previous == null) clickSource.remove();
      else clickSource.set(previous);
    }
  }

  Object source() { return clickSource.get(); }

  private boolean present(Object detail, Object source) throws Throwable {
    Object controller = r.motion.dialogController(source, "screenrecord");
    if (controller == null) return false;
    Object factory = Reflect.get(r.provider("internetDialogManagerProvider"), "systemUIDialogFactory");
    Context shade = (Context)Reflect.call(r.provider("provideShadeDialogContextInteractorProvider"), "getContext");
    AlertDialog dialog = (AlertDialog)Reflect.invoke(Reflect.method(factory.getClass(), "create", Context.class),
        factory, shade);
    Context theme = (Context)Reflect.call(detail, "updateThemeContext");
    Object adapter = Reflect.get(detail, "adapter");
    FrameLayout body = new FrameLayout(theme);
    Method createView = r.type("com.android.systemui.plugins.qs.DetailAdapter")
        .getMethod("createDetailView", Context.class, ViewGroup.class, boolean.class);
    View content = (View)Reflect.invoke(createView, adapter, theme, body, false);
    if (content == null) return false;
    setContent(dialog, body, content, (CharSequence)Reflect.call(adapter, "getTitle"));
    Intent settings = (Intent)Reflect.call(adapter, "getSettingsIntent");
    if (settings != null) {
      int label = theme.getResources().getIdentifier("quick_more_settings", "string", Protocol.TARGET);
      Object starter = r.provider("activityStarterImplProvider");
      Method start = Reflect.method(starter.getClass(), "postStartActivityDismissingKeyguard", Intent.class, int.class);
      dialog.setButton(DialogInterface.BUTTON_POSITIVE, theme.getText(label), (d, button) -> {
        try { Reflect.invoke(start, starter, settings, 0); }
        catch (Throwable failure) { r.combinedQs.layout.warn(failure); }
      });
    }
    Object animator = r.provider("provideDialogTransitionAnimatorProvider");
    setWindowSurface(dialog);
    dialog.setOnDismissListener(ignored -> release(dialog, detail));
    showing = new Display(detail, dialog);
    try {
      Reflect.call(detail, "onShow");
      // The window supplies the sole outer shape. SystemUI reads it and the clicked tile's live
      // Compose outline for both halves of the transition, including the return after Back.
      if (r.motion.showWithController(dialog, animator, controller) || dialog.isShowing()) return true;
    } catch (Throwable failure) {
      if (dialog.isShowing()) {
        r.combinedQs.layout.warn(failure);
        return true;
      }
      throw failure;
    } finally {
      if (!dialog.isShowing()) release(dialog, detail);
    }
    return false;
  }

  static void setContent(AlertDialog dialog, FrameLayout body, View content, CharSequence title) {
    ViewGroup.LayoutParams lp = content.getLayoutParams();
    int height = lp != null && lp.height > 0 ? lp.height : ViewGroup.LayoutParams.WRAP_CONTENT;
    body.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
    dialog.setView(body);
    dialog.setTitle(title);
  }

  static void setWindowSurface(AlertDialog dialog) {
    // AnimatedDialog moves the window background onto its LaunchableFrameLayout and reads
    // the GradientDrawable corners. Put the resting shape on that exact drawable.
    TypedArray style = dialog.getContext().obtainStyledAttributes(
        new int[]{android.R.attr.colorBackground, android.R.attr.dialogCornerRadius});
    try {
      ColorStateList color = style.getColorStateList(0);
      if (color == null || !style.hasValue(1)) return;
      GradientDrawable surface = new GradientDrawable();
      surface.setColor(color);
      surface.setCornerRadius(style.getDimension(1, 0f));
      dialog.getWindow().setBackgroundDrawable(surface);
    } finally { style.recycle(); }
  }

  private void close(Display current) {
    r.main.post(() -> { if (showing == current) current.dialog.dismiss(); });
  }

  private void release(AlertDialog dialog, Object detail) {
    Display current = showing;
    if (current == null || current.dialog != dialog) return;
    showing = null;
    try { Reflect.call(detail, "onDismiss"); }
    catch (Throwable failure) { r.combinedQs.layout.warn(failure); }
  }
}
