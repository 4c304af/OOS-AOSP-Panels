package dev.oos.nativepanels;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Keep the selected QS frame on the same physical travel as the closing shade. */
final class CombinedSettingsLaunch {
  private final PanelRuntime r;
  private final int[] position = new int[2];
  private volatile Request pending;
  private final ThreadLocal<Boolean> activityCollapse = new ThreadLocal<>();
  private final ThreadLocal<Request> dispatch = new ThreadLocal<>();
  private WeakReference<View> frame = new WeakReference<>(null);
  private WeakReference<View> hidden = new WeakReference<>(null);
  private Object panel, exitController;
  private Method updateExpansion;
  private float exitScene = Float.NaN, exitTranslation;
  private boolean enteringCollapse;
  private int programmaticCloses;
  private float startHeight, lastHeight;
  private int startY, presses, launches, frames;
  private long watchUntil;
  private Method height;

  private record Request(WeakReference<Object> source, WeakReference<Object> tile, long expires) {
    boolean fresh() { return SystemClock.uptimeMillis() <= expires; }
  }

  CombinedSettingsLaunch(PanelRuntime r) { this.r = r; }

  static float closingExpansion(float qs, float shade) {
    if (!Float.isFinite(shade) || shade < 0f || shade > 1f || !Float.isFinite(qs)) return qs;
    return Math.min(qs, shade);
  }

  static float remainingTravel(float initial, float current, int initialTop, int actualTop) {
    if (!Float.isFinite(initial) || !Float.isFinite(current) || initial <= 0 || current >= initial) return 0;
    return Math.min(0, Math.max(0, current) - initial - (actualTop - initialTop));
  }

  void reset() {
    pending = null;
    revealFrame();
    stopWatching();
  }

  private void revealFrame() {
    View previous = hidden.get();
    if (previous != null) {
      hidden.clear();
      previous.invalidate();
    }
  }

  void interruptForTouch() {
    Object controller = exitController;
    reset();
    refreshScene(controller);
  }

  private void refreshScene(Object controller) {
    if (controller == null) return;
    try { updateExpansion.invoke(controller); }
    catch (ReflectiveOperationException e) { r.combinedQs.layout.warn(e); }
  }

  private void stopWatching() {
    View previous = frame.get();
    frame.clear();
    panel = null;
    exitController = null;
    exitScene = Float.NaN;
    if (previous != null) previous.invalidate();
  }

  boolean isTracking(View view) { return panel != null && frame.get() == view; }

  static boolean fullyExpanded(float fraction) {
    return fraction >= .999f && fraction <= 1f;
  }

  float sceneDuringExit(float proposed, float shade) {
    return !Float.isNaN(exitScene) && shade > 0f ? exitScene : proposed;
  }

  float translationDuringExit(float proposed) {
    return Float.isNaN(exitScene) ? proposed : exitTranslation;
  }

  void panelState(boolean wasOpen, boolean open, boolean restricted) {
    if (open || restricted) revealFrame();
    if (!wasOpen || (open && !restricted)) return;
    // Compose can still draw its previous scene after the panel reports height zero.
    if (!open && !restricted && !Float.isNaN(exitScene)) hidden = new WeakReference<>(frame.get());
    pending = null;
    stopWatching();
  }

  String report() { return "QS launch: longPress=" + presses + " immediate=" + launches + " expandedDismiss=" + programmaticCloses + " movingFrames=" + frames; }

  private void watch() throws Throwable {
    ShadeDecor owner = r.combinedQs.layout.touchOwner();
    if (owner == null) return;
    Object npvc = Reflect.call(r.provider("shadeControllerImplProvider"), "getNpvc");
    watch(owner, npvc);
  }

  private boolean watch(ShadeDecor owner, Object npvc) throws ReflectiveOperationException {
    float h = (Float)height.invoke(npvc);
    if (!Float.isFinite(h) || h <= 0f) return false;
    stopWatching();
    owner.frame.getLocationInWindow(position);
    frame = new WeakReference<>(owner.frame);
    startY = position[1];
    startHeight = lastHeight = h;
    frames = 0;
    panel = npvc;
    watchUntil = SystemClock.uptimeMillis() + 2500;
    return true;
  }

  void install() throws Exception {
    Class<?> source = r.type("com.android.systemui.animation.Expandable");
    Class<?> vm = r.type("com.android.systemui.qs.panels.ui.viewmodel.TileViewModel");
    Field backend = Reflect.field(vm, "tile");
    r.module.hook(vm.getMethod("settingsClick", source)).setId("native-panels:qs-settings-source").intercept(c -> {
      if (r.combinedQs.layout.active() && !r.locked()) {
        presses++;
        pending = new Request(new WeakReference<>(c.getArg(0)), new WeakReference<>(backend.get(c.getThisObject())),
            SystemClock.uptimeMillis() + 2500);
        try { watch(); } catch (Throwable unavailable) { r.combinedQs.layout.warn(unavailable); }
      }
      return c.proceed();
    });
    r.module.hook(vm.getMethod("mainClick", source)).setId("native-panels:qs-settings-clear").intercept(c -> {
      if (pending != null || panel != null) reset();
      return c.proceed();
    });

    Method legacy = r.type("com.android.systemui.qs.tileimpl.QSTileImpl").getMethod("handleLongClick", source);
    r.module.hook(legacy).setId("native-panels:qs-settings-dispatch").intercept(c -> {
      Request request = pending;
      if (request == null || !request.fresh() || request.tile.get() != c.getThisObject()) return c.proceed();
      dispatch.set(request);
      try { return c.proceed(); } finally { dispatch.remove(); }
    });

    Class<?> oplus = r.type("com.oplus.systemui.qs.anim.OplusQSActivityStarter");
    Field starter = Reflect.field(oplus, "activityStarter");
    Method start = r.type("com.android.systemui.plugins.ActivityStarter").getMethod("startActivity", Intent.class, boolean.class);
    Method ordinaryStart = r.type("com.android.systemui.plugins.ActivityStarter").getMethod(
        "postStartActivityDismissingKeyguard", Intent.class, int.class);
    Method collapse = r.type("com.android.systemui.shade.ShadeController").getMethod(
        "animateCollapseShade", float.class, int.class, boolean.class, boolean.class);
    r.module.hook(oplus.getMethod("postStartActivityDismissingKeyguard", source, Intent.class, boolean.class))
        .setId("native-panels:qs-settings-launch").intercept(c -> {
          Request request = pending;
          if (request == null || !request.fresh() || c.getArg(1) == null || r.locked()) return c.proceed();
          if (dispatch.get() != request && (c.getArg(0) == null || c.getArg(0) != request.source.get())) return c.proceed();
          pending = null;
          Object activity = starter.get(c.getThisObject());
          Intent intent = (Intent)c.getArg(1);
          return r.redirect(c, "combined", null, () -> {
            if (r.locked() || !r.combinedQs.layout.active()) {
              Reflect.invoke(ordinaryStart, activity, intent, 0);
              return null;
            }
            // Same flags and speed as collapseShadeForActivityStart, without its delayed start.
            Reflect.invoke(collapse, r.provider("shadeControllerImplProvider"), 1f, 2, true, false);
            Reflect.invoke(start, activity, intent, false);
            launches++;
            return null;
          });
        });
    r.module.deoptimize(legacy);
    r.module.deoptimize(r.type("com.oplus.systemui.qs.tileex.QSTileExImpl").getMethod(
        "postStartActivityDismissingKeyguard", source, Intent.class, boolean.class));

    // Some SystemUI tiles launch through ActivityStarter directly and bypass OplusQSActivityStarter.
    Class<?> shade = r.type("com.android.systemui.shade.ShadeControllerImpl");
    Method forActivity = shade.getMethod("collapseShadeForActivityStart");
    r.module.hook(forActivity).setId("native-panels:qs-settings-activity").intercept(c -> {
      Request request = pending;
      if (request == null || !request.fresh() || panel == null || r.locked()) return c.proceed();
      Boolean previous = activityCollapse.get();
      activityCollapse.set(true);
      try { return c.proceed(); } finally {
        if (previous == null) activityCollapse.remove(); else activityCollapse.set(previous);
      }
    });
    r.module.hook(shade.getMethod("animateCollapseShade", float.class, int.class, boolean.class, boolean.class))
        .setId("native-panels:qs-settings-delay").intercept(c -> {
          if (!Boolean.TRUE.equals(activityCollapse.get()) || !Boolean.TRUE.equals(c.getArg(3))) return c.proceed();
          pending = null;
          launches++;
          return c.proceed(new Object[]{c.getArg(0), c.getArg(1), c.getArg(2), false});
        });
    r.module.deoptimize(forActivity);
    r.module.deoptimize(r.type("com.android.systemui.shade.ShadeController").getMethod("animateCollapseShadeForcedDelayed"));

  }

  // Both the normal activity-start path and the force-collapse command from tiles reach
  // this overload. A finger dragging QS between scenes instead goes through flingQs.
  void installDismiss() throws Exception {
    Class<?> npvc = r.type("com.android.systemui.shade.NotificationPanelViewController");
    Field controller = Reflect.field(npvc, "mQsController");
    Field fragment = Reflect.field(controller.getType(), "mQs");
    updateExpansion = controller.getType().getDeclaredMethod("updateExpansion");
    updateExpansion.setAccessible(true);
    Method canCollapse = npvc.getMethod("canBeCollapsed$1");
    Method closing = npvc.getMethod("isClosing");
    Method collapse = npvc.getMethod("collapse", boolean.class, float.class, String.class);
    r.module.hook(collapse).setId("native-panels:qs-expanded-dismiss").intercept(c -> {
      ShadeLayout layout = r.combinedQs.layout;
      Object target = c.getThisObject();
      float scene = layout.sceneFraction();
      if (height == null || !fullyExpanded(scene) || !Float.isNaN(exitScene)) return c.proceed();
      ShadeDecor owner = layout.touchOwner();
      Object qsController = controller.get(target);
      if (owner == null || qsController == null || !layout.isSelected(fragment.get(qsController))
          || r.locked() || !(Boolean)canCollapse.invoke(target)) return c.proceed();
      if (!watch(owner, target)) return c.proceed();
      exitController = qsController;
      exitScene = scene;
      exitTranslation = layout.contentOffset();
      programmaticCloses++;
      enteringCollapse = true;
      try { return c.proceed(); }
      finally {
        enteringCollapse = false;
        if (panel == target && !(Boolean)closing.invoke(target)) finishOrCancel(target);
      }
    });
    r.module.hook(npvc.getMethod("setClosing", boolean.class)).setId("native-panels:qs-dismiss-finish").intercept(c -> {
      Object result = c.proceed();
      if (!enteringCollapse && panel == c.getThisObject() && !Float.isNaN(exitScene) && !((Boolean)c.getArg(0)))
        finishOrCancel(panel);
      return result;
    });
    r.module.deoptimize(npvc.getMethod("collapse", float.class, boolean.class, boolean.class));
    r.module.deoptimize(r.type("com.android.systemui.shade.ShadeControllerImpl").getMethod("collapseWithDuration", int.class));
  }

  private void finishOrCancel(Object target) throws ReflectiveOperationException {
    boolean complete = (Float)height.invoke(target) <= 0f;
    Object controller = exitController;
    if (complete) hidden = new WeakReference<>(frame.get());
    stopWatching();
    if (!complete) refreshScene(controller);
  }

  void installMediaDismiss() throws Exception {
    Class<?> media = r.type("com.android.systemui.media.remedia.domain.interactor.MediaInteractorImpl");
    Class<?> source = r.type("com.android.systemui.animation.Expandable");
    Class<?> transition = r.type("com.android.systemui.animation.ActivityTransitionAnimator$Controller");
    Method click = media.getMethod("startClickIntent", source, PendingIntent.class);
    ThreadLocal<PendingIntent> mediaClick = new ThreadLocal<>();
    r.module.hook(click).setId("native-panels:qs-media-click").intercept(c -> {
      PendingIntent intent = (PendingIntent)c.getArg(1);
      ShadeLayout layout = r.combinedQs.layout;
      if (intent == null || !intent.isActivity() || !fullyExpanded(layout.sceneFraction())
          || !layout.active() || r.locked()) return c.proceed();
      PendingIntent previous = mediaClick.get();
      mediaClick.set(intent);
      try { return c.proceed(); }
      finally { if (previous == null) mediaClick.remove(); else mediaClick.set(previous); }
    });
    // A full-screen card-to-app transition can skip shade height animation altogether.
    // Keep the PendingIntent, dispatch, and keyguard checks; request ordinary shade dismissal.
    Method post = r.type("com.android.systemui.statusbar.phone.ActivityStarterImpl")
        .getMethod("postStartActivityDismissingKeyguard", PendingIntent.class, transition);
    r.module.hook(post).setId("native-panels:qs-media-shade-exit").intercept(c -> {
      PendingIntent active = mediaClick.get();
      if (active != null && active == c.getArg(0) && c.getArg(1) != null) return c.proceed(new Object[]{active, null});
      return c.proceed();
    });
    r.module.deoptimize(click);
    r.module.deoptimize(r.type("com.android.systemui.media.remedia.domain.interactor.MediaInteractorImpl$toMediaSessionModel$1$$ExternalSyntheticLambda0")
        .getDeclaredMethod("invoke", Object.class));
  }

  void installTravel() throws Exception {
    Class<?> npvc = r.type("com.android.systemui.shade.NotificationPanelViewController");
    height = npvc.getMethod("getExpandedHeight");
    Method progress = Reflect.method(npvc, "setExpandedHeightInternal", float.class, Boolean.class);
    r.module.hook(progress).setId("native-panels:qs-settings-height").intercept(c -> {
      Object result = c.proceed();
      if (panel == c.getThisObject()) {
        View view = frame.get();
        if (view == null || !view.isAttachedToWindow() || (Float.isNaN(exitScene) && SystemClock.uptimeMillis() > watchUntil)) stopWatching();
        else view.invalidate();
      }
      return result;
    });
    Class<?> host = r.type("com.android.systemui.qs.composefragment.FrameLayoutTouchPassthrough");
    r.module.hook(host.getDeclaredMethod("dispatchDraw", Canvas.class)).setId("native-panels:qs-settings-travel").intercept(c -> {
      if (c.getThisObject() == hidden.get()) return null;
      View view = frame.get();
      if (panel == null || view != c.getThisObject()) return c.proceed();
      float now = (Float)height.invoke(panel);
      if (!Float.isNaN(exitScene) && now > lastHeight + 1f) {
        // A new pull took over. Refresh after this traversal so Compose can remeasure.
        Object controller = exitController;
        stopWatching();
        view.post(() -> { if (panel == null && view.isAttachedToWindow()) refreshScene(controller); });
        return c.proceed();
      }
      if (Float.isNaN(exitScene) && (SystemClock.uptimeMillis() > watchUntil || now > lastHeight + 1f)) {
        stopWatching();
        return c.proceed();
      }
      lastHeight = now;
      view.getLocationInWindow(position);
      float dy = remainingTravel(startHeight, now, startY, position[1]);
      if (dy == 0) return c.proceed();
      frames++;
      Canvas canvas = (Canvas)c.getArg(0);
      int save = canvas.save();
      try {
        canvas.translate(0, dy);
        return c.proceed();
      } finally { canvas.restoreToCount(save); }
    });
  }
}
