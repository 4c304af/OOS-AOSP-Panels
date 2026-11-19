package dev.oos.nativepanels;

import android.app.Fragment;
import android.view.View;
import android.view.ViewGroup;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Connect the OOS shade fraction to the placement condition in QSFragmentCompose. */
final class CombinedQsExpansion {
  private WeakReference<Fragment> current = new WeakReference<>(null);
  static final int SHADE = 0, KEYGUARD = 1, SHADE_LOCKED = 2;
  // updateExpansion and the fragment setter run synchronously on the UI thread.
  private boolean closingUpdate;
  private Object scrollingPanel;
  private ShadePullMotion motion;
  private ShadeBackdrop backdrop;
  private WeakReference<Object> controllerRef = new WeakReference<>(null);

  ShadePullMotion.Pane pane() throws ReflectiveOperationException {
    return motion == null ? ShadePullMotion.Pane.STOCK : motion.pane(controllerRef.get());
  }

  float gridFraction(float qs, float shade) {
    return closingUpdate ? CombinedSettingsLaunch.closingExpansion(qs, shade) : qs;
  }
  private String bridgeError = "";

  void reset() {
    current.clear();
    controllerRef.clear();
    if (backdrop != null) backdrop.release();
    bridgeError = "";
  }

  void install(PanelRuntime runtime, Class<?> compose) throws Exception {
    Class<?> controller = runtime.type("com.android.systemui.shade.QuickSettingsControllerImpl");
    // Some SystemUI branches expose the header through a different controller.
    // That must not prevent the fragment from receiving expansion updates.
    Field header = Reflect.optionalField(controller, "mShadeHeaderController");
    Field qs = Reflect.field(controller, "mQs");
    Field shadeFraction = Reflect.field(controller, "mShadeExpandedFraction");
    backdrop = new ShadeBackdrop(runtime);
    try { backdrop.install(); }
    catch (ReflectiveOperationException missing) { runtime.combinedQs.layout.warn(missing); }
    motion = new ShadePullMotion(runtime);
    try { motion.install(controller); }
    catch (ReflectiveOperationException missing) { runtime.combinedQs.layout.warn(missing); }
    Field split = Reflect.optionalField(controller, "mSplitShadeEnabled");
    Field bar = Reflect.field(controller, "mBarState");
    Field dozing = Reflect.field(controller, "mDozing");
    Method lockProgress = optionalMethod(controller, "getLockscreenShadeDragProgress");
    Method nativeVisible = optionalMethod(controller, "isQsVisible");
    Method expansionEnabled = optionalMethod(controller, "isExpansionEnabled");
    Field panelProvider = Reflect.optionalField(controller, "mPanelViewControllerLazy");
    Method getPanel = runtime.type("dagger.Lazy").getMethod("get");
    Method closing = optionalMethod(runtime.type("com.android.systemui.shade.NotificationPanelViewController"), "isClosing");
    Method setPanelExpanded = compose.getDeclaredMethod("setPanelExpanded", boolean.class);
    runtime.module.hook(controller.getDeclaredMethod("updateExpansion"))
        .setId("native-panels:combined-qs-expansion")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
        .intercept(chain -> {
          boolean previously = closingUpdate;
          Object controllerInstance = chain.getThisObject();
          boolean ordinaryShade = runtime.combinedQs.layout.isSelected(qs.get(controllerInstance))
              && bar.getInt(controllerInstance) == SHADE;
          if (ordinaryShade && controllerRef.get() != controllerInstance) controllerRef = new WeakReference<>(controllerInstance);
          closingUpdate = false;
          if (panelProvider != null && closing != null && ordinaryShade) {
            try { closingUpdate = (Boolean)closing.invoke(getPanel.invoke(panelProvider.get(chain.getThisObject()))); }
            catch (Throwable e) { runtime.combinedQs.layout.warn(e); }
          }
          Object result;
          try { result = chain.proceed(); }
          finally { closingUpdate = previously; }
          if (runtime.combinedQs.status != R.string.combined_selected) return result;
          try {
            Object host = chain.getThisObject();
            Object target = qs.get(host);
            if (compose.isInstance(target) && runtime.combinedQs.layout.isSelected(target)) {
              Fragment fragment = (Fragment) target;
              if (current.get() != fragment) {
                bridgeError = "";
                current = new WeakReference<>(fragment);
              }
              runtime.combinedQs.layout.notificationScrim.bind(host, split != null && split.getBoolean(host));
              float fraction = shadeFraction.getFloat(host);
              runtime.combinedQs.layout.shadeExpansion = fraction;
              int state = bar.getInt(host);
              boolean lockedShade = state == KEYGUARD || state == SHADE_LOCKED;
              boolean asleep = dozing.getBoolean(host);
              // A resting keyguard can have a fully expanded notification window.
              // SystemUI uses a separate drag value for the transition into locked QS.
              float progress = fraction;
              boolean visible = false, enabled = false;
              if (lockedShade && !asleep) {
                if (state == KEYGUARD) progress = lockProgress == null ? 0f : (Float) lockProgress.invoke(host);
                visible = nativeVisible != null && (Boolean) nativeVisible.invoke(host);
                enabled = expansionEnabled != null && (Boolean) expansionEnabled.invoke(host);
              }
              boolean expanded = isOpen(progress, state, asleep, visible, enabled);
              // OOS also writes this flag outside updateExpansion. Its native setter uses
              // structurally equal snapshot state, so writing the current value is a no-op.
              setPanelExpanded.invoke(fragment, expanded);
              if (expanded && header != null) runtime.combinedQs.layout.bindHeader(fragment, header.get(host));
              runtime.combinedQs.layout.setOpen(fragment, expanded, lockedShade || asleep);
            }
          } catch (Throwable e) {
            if (bridgeError.isEmpty()) bridgeError = android.util.Log.getStackTraceString(e);
          }
          return result;
        });

    if (split == null || expansionEnabled == null) return;
    try { installScrollCallback(runtime, controller, qs, split, bar, dozing, getPanel); }
    catch (ReflectiveOperationException missing) { runtime.combinedQs.layout.warn(missing); }
  }

  private void installScrollCallback(PanelRuntime runtime, Class<?> controller, Field qs, Field split,
      Field bar, Field dozing, Method getPanel) throws ReflectiveOperationException {
    Class<?> listener = Reflect.field(controller, "mQsScrollListener").getType();
    Field owner = Reflect.field(listener, "f$0");
    Field panel = Reflect.field(controller, "mPanelViewControllerLazy");
    Field full = Reflect.field(controller, "mFullyExpanded");
    Method changed = Reflect.method(listener, "onQsPanelScrollChanged", int.class);
    Method expand = runtime.type("com.android.systemui.shade.NotificationPanelViewController").getDeclaredMethod("expandToQs");
    // Scrolling this fragment is only possible in expanded QS. Its last offset is
    // published again as the media/QQS height changes during collapse. Keep OOS's
    // header scroll update, without treating the offset as a new request to open QS.
    runtime.module.hook(changed).setId("native-panels:combined-scroll-callback").intercept(chain -> {
      Object previous = scrollingPanel;
      try {
        Object host = owner.get(chain.getThisObject());
        scrollingPanel = (Integer)chain.getArg(0) > 0 && !full.getBoolean(host)
            && !split.getBoolean(host) && bar.getInt(host) == SHADE && !dozing.getBoolean(host)
            && runtime.combinedQs.layout.isSelected(qs.get(host)) && !runtime.combinedQs.layout.isEditing()
            ? getPanel.invoke(panel.get(host)) : null;
        return chain.proceed();
      } finally { scrollingPanel = previous; }
    });
    runtime.module.hook(expand).setId("native-panels:combined-scroll-reopen").intercept(chain ->
        scrollingPanel != null && scrollingPanel == chain.getThisObject() ? null : chain.proceed());
    runtime.module.deoptimize(changed);
    // R8 specialised this snapshotFlow callback to the ROM listener.
    runtime.module.deoptimize(runtime.type("com.android.systemui.qs.composefragment.QSFragmentCompose$setListenerCollections$1$1$$ExternalSyntheticLambda0")
        .getDeclaredMethod("invoke"));
  }

  private static Method optionalMethod(Class<?> controller, String name) {
    try { return Reflect.method(controller, name); }
    catch (NoSuchMethodException missing) { return null; }
  }

  static boolean isOpen(float progress, int state, boolean dozing, boolean visible, boolean enabled) {
    if (dozing || !Float.isFinite(progress) || progress <= 0f) return false;
    return state == SHADE || ((state == KEYGUARD || state == SHADE_LOCKED) && visible && enabled);
  }

  String report() {
    Fragment fragment = current.get();
    String live = fragment == null ? "no controller update" : describe(fragment);
    // Reflective snapshots are collected only when a diagnostic report is requested.
    return "QS now: " + live + "\n" + (bridgeError.isEmpty() ? "" : bridgeError + "\n");
  }

  private static String describe(Fragment fragment) {
    View root = fragment.getView();
    if (!(root instanceof ViewGroup frame)) return "no root view";
    View child = frame.getChildCount() == 0 ? null : frame.getChildAt(0);
    String geometry = "root=" + frame.getWidth() + "x" + frame.getHeight()
        + " attached=" + frame.isAttachedToWindow() + " shown=" + frame.isShown();
    try {
      Object vm = Reflect.get(fragment, "viewModel");
      return geometry + " composition=" + (child == null ? "—" : Reflect.call(child, "getHasComposition"))
          + " frameVisible=" + Reflect.call(frame, "getQsVisible")
          + " placed=" + Reflect.call(vm, "isQsVisibleAndAnyShadeExpanded")
          + " qsEnabled=" + Reflect.call(vm, "isQsEnabled")
          + " shade=" + Reflect.call(vm, "getPanelExpansionFraction")
          + " alpha=" + Reflect.call(vm, "getViewAlpha")
          + " qqsHeight=" + Reflect.call(vm, "getQqsHeight");
    } catch (Throwable e) {
      return geometry + " (" + e + ")";
    }
  }
}
