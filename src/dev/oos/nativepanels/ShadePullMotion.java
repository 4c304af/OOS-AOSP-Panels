package dev.oos.nativepanels;

import android.view.View;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Select the classic SystemUI pull for the module's combined shade on this display. */
final class ShadePullMotion {
  enum Pane { STOCK, PORTRAIT, SPLIT }

  private final PanelRuntime runtime;
  private Field fragment, bar, dozing, transition, policy, split, listController, panel, panelQs, fromHeadsUp, bouncer, pinned, headsUpAway;
  private Method listView, headsUp;
  private Pane rowPass = Pane.STOCK;
  private boolean rowsReady;
  private WeakReference<View> normalized = new WeakReference<>(null);
  private final Set<View> clearedRows = Collections.newSetFromMap(new WeakHashMap<>());

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

  private Pane listPane(Object list) throws ReflectiveOperationException {
    Object owner = panel.get(list);
    return owner == null ? Pane.STOCK : pane(panelQs.get(owner), list);
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
    Field extension = Reflect.field(list, "mExt");
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
          if (normalized.get() == target) {
            normalized.clear();
            clearedRows.clear();
          }
        } else if (normalized.get() != target && controller.get(target) != null) {
          // A previously hidden OOS list can still have an element-show animator or
          // its final scale/alpha. Return alpha to the native controller's visibility limits.
          Object animator = manager.get(extension.get(target));
          if ((Boolean)running.invoke(animator, target)) cancel.invoke(animator, target, "Combined shade pull");
          target.setScaleX(1f);
          target.setScaleY(1f);
          stockAlpha.invoke(controller.get(target));
          normalized = new WeakReference<>(target);
          clearedRows.clear();
        }
        return chain.proceed();
      } finally { rowPass = previous; }
    });
    runtime.module.deoptimize(update);
    runtime.module.hook(level).setId("native-panels:classic-row-positions").intercept(chain ->
        rowPass != Pane.STOCK ? false : chain.proceed());
    runtime.module.hook(alpha).setId("native-panels:classic-row-alpha").intercept(chain -> switch (rowPass) {
      case PORTRAIT -> chain.getArg(0); // SystemUI already evaluated ShadeInterpolation.getContentAlpha.
      case SPLIT -> splitContentAlpha((Float)chain.getArg(1));
      default -> chain.proceed();
    });
    Class<?> row = runtime.type("com.android.systemui.statusbar.notification.row.ExpandableView");
    Method reset = rows.getDeclaredMethod("resetViewState", row, boolean.class);
    Method apply = Reflect.method(rows, "updateViewAlphaAndScale", row,
        runtime.type("com.android.systemui.statusbar.notification.stack.ViewState"),
        runtime.type("com.android.systemui.statusbar.notification.stack.AmbientState"),
        runtime.type("com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm$StackScrollAlgorithmState"),
        android.view.ViewGroup.class);
    runtime.module.hook(apply).setId("native-panels:classic-row-show").intercept(chain -> {
      View child = (View)chain.getArg(0);
      if (rowPass == Pane.STOCK) {
        clearedRows.remove(child);
        return chain.proceed();
      }
      // Drop an inherited OOS show/hide request once when the row changes owner.
      if (clearedRows.add(child)) reset.invoke(chain.getThisObject(), child, false);
      return null;
    });
    // OOS separately scales or hides the whole list until its element-show threshold.
    // Keep those writes out of the selected stack; row appearance is now driven by expansion.
    for (Method effect : new Method[]{ext.getDeclaredMethod("ensureNsslHiddenWhenElementNotShowing"),
        ext.getDeclaredMethod("access$updateViewScale", ext)}) {
      boolean staticEffect = java.lang.reflect.Modifier.isStatic(effect.getModifiers());
      runtime.module.hook(effect).setId("native-panels:classic-stack-" + effect.getName()).intercept(chain -> {
        if (!rowsReady) return chain.proceed();
        Object target = extView.invoke(staticEffect ? chain.getArg(0) : chain.getThisObject());
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
