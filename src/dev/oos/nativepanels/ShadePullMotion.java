package dev.oos.nativepanels;

import android.content.res.Configuration;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Select the classic SystemUI pull for the module's combined shade on this display. */
final class ShadePullMotion {
  enum Pane { STOCK, PORTRAIT, SPLIT }

  private final PanelRuntime runtime;
  private Field fragment, bar, dozing, transition, policy, split, listController, panel, panelQs, fromHeadsUp, bouncer, pinned, headsUpAway;
  private Method listView, headsUp;
  private Field listExtension;
  private Method shouldAnimateStackAlpha;
  private Boolean lastStackAlpha;
  private Pane rowPass = Pane.STOCK;
  private boolean rowsReady;
  private int openingScrollBefore = -1, openingScrollAfter;
  private WeakReference<View> normalized = new WeakReference<>(null);

  ShadePullMotion(PanelRuntime runtime) { this.runtime = runtime; }

  void install(Class<?> qs) throws ReflectiveOperationException {
    Class<?> list = runtime.type("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout");
    // The lock screen can collapse this stack before its empty-notification view is bound.
    try {
      Method end = Reflect.method(list, "getAppearEndPosition");
      Method height = Reflect.method(list, "setExpandedHeight", float.class);
      if (end.getReturnType() == float.class) {
        XposedInterface.Hooker guard = appearEndHook(list);
        runtime.module.hook(end).setId("native-panels:unbound-stack-appear-end").intercept(guard);
        runtime.module.deoptimize(height);
      }
    } catch (ReflectiveOperationException missing) {
      runtime.combinedQs.layout.warn(missing);
    }
    Class<?> panelType = runtime.type("com.android.systemui.shade.NotificationPanelViewController");
    fragment = Reflect.field(qs, "mQs");
    bar = Reflect.field(qs, "mBarState");
    dozing = Reflect.field(qs, "mDozing");
    transition = Reflect.field(qs, "mTransitioningToFullShadeProgress");
    policy = Reflect.field(qs, "mExpansionEnabledPolicy");
    split = Reflect.field(qs, "mSplitShadeEnabled");
    listController = Reflect.field(qs, "mNotificationStackScrollLayoutController");
    listView = listController.getType().getMethod("getView");
    panel = Reflect.field(list, "mNotificationPanelController");
    panelQs = Reflect.field(panelType, "mQsController");
    fromHeadsUp = Reflect.field(panelType, "mExpandingFromHeadsUp");
    bouncer = Reflect.field(panelType, "mBouncerShowing");
    pinned = Reflect.field(list, "mInHeadsUpPinnedMode");
    headsUpAway = Reflect.field(list, "mHeadsUpAnimatingAway");
    headsUp = list.getDeclaredMethod("isHeadsUpTransition");

    Method translation = qs.getDeclaredMethod("getHeaderTranslation");
    Method edge = qs.getDeclaredMethod("getEdgePosition");
    Field fraction = Reflect.field(qs, "mShadeExpandedFraction");
    Field compactHeight = Reflect.field(qs, "mMinExpansionHeight");
    Method qsFraction = Reflect.method(qs, "computeExpansionFraction");
    runtime.module.hook(translation).setId("native-panels:pull-header-position").intercept(chain -> {
      float original = (Float)chain.proceed();
      return original < 0f && fraction.getFloat(chain.getThisObject()) > 0f
          && pane(chain.getThisObject()) != Pane.STOCK ? 0f : original;
    });
    runtime.module.hook(edge).setId("native-panels:compact-pull-edge").intercept(chain -> {
      float original = (Float)chain.proceed();
      Object controller = chain.getThisObject();
      if (pane(controller) != Pane.PORTRAIT || (Float)qsFraction.invoke(controller) > 0f) return original;
      return revealEdge(original, compactHeight.getInt(controller), fraction.getFloat(controller));
    });
    for (String caller : new String[]{"updateExpansion", "setClippingBounds"})
      runtime.module.deoptimize(qs.getDeclaredMethod(caller));
    runtime.module.deoptimize(qs.getDeclaredMethod("calculateBottomPosition", float.class));
    try {
      Method expanded = Reflect.method(list, "setIsExpanded", boolean.class);
      runtime.module.hook(expanded).setId("native-panels:compact-shade-opening-scroll")
          .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(openingScrollHook(list));
      runtime.module.deoptimize(Reflect.method(list, "setExpandedHeight", float.class));
    } catch (ReflectiveOperationException missing) { runtime.combinedQs.layout.warn(missing); }
    installRows(list);
  }

  Pane pane(Object controller) throws ReflectiveOperationException {
    return pane(controller, null);
  }

  private Pane pane(Object controller, Object expectedList) throws ReflectiveOperationException {
    if (headsUp == null || controller == null || bar.getInt(controller) != CombinedQsExpansion.SHADE
        || dozing.getBoolean(controller) || !policy.getBoolean(controller) || transition.getFloat(controller) > 0f
        || !runtime.combinedQs.layout.acceptsPullMotion(fragment.get(controller))) return Pane.STOCK;
    Object stackController = listController.get(controller);
    if (stackController == null) return Pane.STOCK;
    Object list = listView.invoke(stackController);
    if (expectedList != null && expectedList != list) return Pane.STOCK;
    Object owner = list == null ? null : panel.get(list);
    if (owner == null || bouncer.getBoolean(owner) || fromHeadsUp.getBoolean(owner) || pinned.getBoolean(list)
        || headsUpAway.getBoolean(list) || (Boolean)headsUp.invoke(list)) return Pane.STOCK;
    return split.getBoolean(controller) ? Pane.SPLIT : Pane.PORTRAIT;
  }

  XposedInterface.Hooker openingScrollHook(Class<?> list) throws ReflectiveOperationException {
    Field expanded = Reflect.field(list, "mIsExpanded");
    Field dragging = Reflect.field(list, "mIsBeingDragged");
    Field forcedScroll = Reflect.field(list, "mForcedScroll");
    Field launching = Reflect.field(list, "mLaunchingNotification");
    Field scroller = Reflect.field(list, "mScroller");
    Method finished = Reflect.method(scroller.getType(), "isFinished");
    Method scroll = Reflect.method(list, "getOwnScrollY");
    Method reset = Reflect.method(list, "resetScrollPosition");
    return chain -> {
      View stack = (View)chain.getThisObject();
      boolean opening = (Boolean)chain.getArg(0) && !expanded.getBoolean(stack);
      Object result = chain.proceed();
      if (!opening || !expanded.getBoolean(stack)
          || stack.getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT
          || dragging.getBoolean(stack) || launching.getBoolean(stack) || forcedScroll.get(stack) != null) return result;
      Object owner = panel.get(stack);
      Object listScroller = scroller.get(stack);
      if (owner == null || listScroller == null || pane(panelQs.get(owner), stack) != Pane.PORTRAIT) return result;
      // getEdgePosition subtracts the list's own scroll. A reset during closing can
      // be ignored by setOwnScrollY, leaving a long list scrolled over the compact QS.
      openingScrollBefore = (Integer)scroll.invoke(stack);
      if (openingScrollBefore > 0 || !(Boolean)finished.invoke(listScroller)) reset.invoke(stack);
      openingScrollAfter = (Integer)scroll.invoke(stack);
      return result;
    };
  }

  String notificationPullReport() {
    String alpha = lastStackAlpha == null ? "unknown" : lastStackAlpha ? "stack" : "rows";
    String scroll = openingScrollBefore < 0 ? "" : " openingListScroll(before/after)=" + openingScrollBefore + "/" + openingScrollAfter;
    return " portraitNotifAlpha=" + alpha + scroll;
  }

  void bindRowAnimation(Class<?> list, Class<?> ext) throws NoSuchFieldException {
    listExtension = Reflect.field(list, "mExt");
    try {
      Method query = Reflect.method(ext, "getShouldNsslAlphaAnimation");
      shouldAnimateStackAlpha = query.getReturnType() == boolean.class ? query : null;
    } catch (NoSuchMethodException | SecurityException | LinkageError unavailable) {
      shouldAnimateStackAlpha = null;
    }
  }

  private Boolean systemAnimatesStackAlpha(View list) {
    if (shouldAnimateStackAlpha == null) return null;
    try {
      Object extension = listExtension.get(list);
      if (shouldAnimateStackAlpha.getDeclaringClass().isInstance(extension))
        return (Boolean)shouldAnimateStackAlpha.invoke(extension);
    } catch (ReflectiveOperationException | RuntimeException unavailable) {
      // Leave ownership with SystemUI if this stack cannot report its animation mode.
    }
    return null;
  }

  Pane listPane(View list) throws ReflectiveOperationException {
    Object owner = panel.get(list);
    Pane mode = owner == null ? Pane.STOCK : pane(panelQs.get(owner), list);
    if (mode != Pane.PORTRAIT || list.getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT)
      return mode;
    // This is the same predicate SystemUI uses to attach its whole-list alpha callback.
    // Override row opacity only when the active stack explicitly uses row animations.
    lastStackAlpha = systemAnimatesStackAlpha(list);
    return Boolean.FALSE.equals(lastStackAlpha) ? Pane.PORTRAIT : Pane.STOCK;
  }

  static float revealEdge(float nativeEdge, int compactHeight, float progress) {
    if (!Float.isFinite(progress) || compactHeight <= 0 || progress >= 1f) return nativeEdge;
    return Math.min(nativeEdge, Math.max(nativeEdge, compactHeight) * Math.max(0f, progress));
  }

  // SplitShadeInterpolator uses the same interval for tiles, notification rows and their surface.
  static float splitContentAlpha(float progress) {
    return Float.isFinite(progress) ? Math.max(0f, Math.min(1f, (progress - .39f) / (.66f - .39f))) : 0f;
  }

  static XposedInterface.Hooker appearEndHook(Class<?> list) throws ReflectiveOperationException {
    Field emptyView = Reflect.field(list, "mEmptyShadeView");
    Field shelf = Reflect.field(list, "mShelf");
    Method minimum = Reflect.method(list, "getMinExpansionHeight");
    if (minimum.getReturnType() != int.class) throw new NoSuchMethodException(minimum.toString());
    return chain -> {
      Object stack = chain.getThisObject();
      // OOS getOpeningHeight uses this same minimum when there is no empty view.
      // Both the minimum and the original end position can dereference the shelf.
      if (shelf.get(stack) == null) return 0f;
      if (emptyView.get(stack) == null) return ((Integer)minimum.invoke(stack)).floatValue();
      return chain.proceed();
    };
  }

  private void installRows(Class<?> list) throws ReflectiveOperationException {
    Class<?> rows = runtime.type("com.oplus.systemui.panelanimation.notification.OplusNotificationRowShowAnimationExImpl");
    Class<?> ext = runtime.type("com.oplus.systemui.statusbar.notification.stack.NotificationStackScrollLayoutExtImpl");
    bindRowAnimation(list, ext);
    Field manager = Reflect.field(ext, "appearAnimationManager");
    Method running = manager.getType().getMethod("isAnimationRunning", View.class);
    Method cancel = manager.getType().getMethod("cancelViewAnimation", View.class, String.class);
    Field controller = Reflect.field(list, "mController");
    Method stockAlpha = Reflect.method(controller.getType(), "updateAlpha$2");
    Method extView = ext.getDeclaredMethod("getView");
    Method update = list.getDeclaredMethod("updateChildren$1");
    Method alpha = rows.getDeclaredMethod("getNotificationAlpha", float.class, float.class);
    Method level = runtime.type("com.oplus.systemui.panelanimation.LevelPositionAlgorithmImpl").getDeclaredMethod("isInPanelAnimation");
    runtime.module.hook(update).setId("native-panels:classic-stack-rows").intercept(chain -> {
      if (!rowsReady) return chain.proceed();
      Pane previous = rowPass;
      View target = (View)chain.getThisObject();
      try {
        rowPass = listPane(target);
        if (rowPass == Pane.STOCK) {
          if (normalized.get() == target) normalized.clear();
        } else if (normalized.get() != target && controller.get(target) != null) {
          // A previously hidden OOS list can still have an element-show animator or
          // its final scale/alpha. Return alpha to the native controller's visibility limits.
          Object animator = manager.get(listExtension.get(target));
          if ((Boolean)running.invoke(animator, target)) cancel.invoke(animator, target, "Combined shade pull");
          target.setScaleX(1f);
          target.setScaleY(1f);
          stockAlpha.invoke(controller.get(target));
          normalized = new WeakReference<>(target);
        }
        return chain.proceed();
      } finally { rowPass = previous; }
    });
    runtime.module.deoptimize(update);
    runtime.module.hook(level).setId("native-panels:classic-row-positions").intercept(chain ->
        rowPass != Pane.STOCK ? false : chain.proceed());
    // Adjust only the base pull opacity. OOS still has to update each row and its
    // grouped children when a hide animation ends or a notification is rebound.
    runtime.module.hook(alpha).setId("native-panels:classic-row-alpha").intercept(chain -> switch (rowPass) {
      case PORTRAIT -> chain.getArg(0); // SystemUI already evaluated ShadeInterpolation.getContentAlpha.
      case SPLIT -> splitContentAlpha((Float)chain.getArg(1));
      default -> chain.proceed();
    });
    // OOS separately scales or hides the whole list until its element-show threshold.
    // Keep those writes out of the selected stack; row appearance is now driven by expansion.
    for (Method effect : new Method[]{ext.getDeclaredMethod("ensureNsslHiddenWhenElementNotShowing"),
        ext.getDeclaredMethod("access$updateViewScale", ext)}) {
      boolean staticEffect = java.lang.reflect.Modifier.isStatic(effect.getModifiers());
      runtime.module.hook(effect).setId("native-panels:classic-stack-" + effect.getName()).intercept(chain -> {
        if (!rowsReady) return chain.proceed();
        View target = (View)extView.invoke(staticEffect ? chain.getArg(0) : chain.getThisObject());
        return target != null && listPane(target) != Pane.STOCK ? null : chain.proceed();
      });
    }
    Class<?> listener = runtime.type(ext.getName() + "$elementShowStateChangeListener$1");
    runtime.module.deoptimize(listener.getDeclaredMethod("onElementShowingChanged"));
    runtime.module.deoptimize(listener.getDeclaredMethod("onShadeShowingChanged", int.class, boolean.class));
    runtime.module.deoptimize(rows.getDeclaredMethod("onResetViewStates",
        runtime.type("com.android.systemui.statusbar.notification.stack.AmbientState"), android.view.ViewGroup.class));
    rowsReady = true;
  }
}
