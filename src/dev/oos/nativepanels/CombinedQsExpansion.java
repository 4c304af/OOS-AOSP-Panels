package dev.oos.nativepanels;

import android.animation.ValueAnimator;
import android.app.Fragment;
import android.view.MotionEvent;
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
  private float keyguardDrag = Float.NaN;
  private Object scrollingPanel;
  private ShadePullMotion motion;
  private ShadeBackdrop backdrop;
  private Object releasingTouch;
  private LockscreenQsRelease releaseMotion;
  private boolean releaseMotionMissing;
  private long previousCloseMs = -1, appliedCloseMs;
  private WeakReference<Object> controllerRef = new WeakReference<>(null);

  ShadePullMotion.Pane pane() throws ReflectiveOperationException {
    return motion == null ? ShadePullMotion.Pane.STOCK : motion.pane(controllerRef.get());
  }

  void updateNotificationClip(View frame, PortraitNotificationClip clip) {
    if (motion != null) motion.updateNotificationClip(controllerRef.get(), frame, clip);
  }

  float gridFraction(float qs, float shade) {
    return closingUpdate ? CombinedSettingsLaunch.closingExpansion(qs, shade) : qs;
  }

  float panelFraction(float shade) {
    // The keyguard notification window can remain fully expanded as QS is dismissed.
    return Float.isFinite(keyguardDrag) ? Math.max(0f, Math.min(1f, keyguardDrag)) : shade;
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
          float previousDrag = keyguardDrag;
          Object controllerInstance = chain.getThisObject();
          boolean selected = runtime.combinedQs.layout.isSelected(qs.get(controllerInstance));
          if (selected && controllerRef.get() != controllerInstance) controllerRef = new WeakReference<>(controllerInstance);
          boolean ordinaryShade = selected && bar.getInt(controllerInstance) == SHADE;
          closingUpdate = false;
          keyguardDrag = Float.NaN;
          if (panelProvider != null && closing != null && ordinaryShade) {
            try { closingUpdate = (Boolean)closing.invoke(getPanel.invoke(panelProvider.get(chain.getThisObject()))); }
            catch (Throwable e) { runtime.combinedQs.layout.warn(e); }
          }
          Object result;
          try {
            if (selected && bar.getInt(controllerInstance) == KEYGUARD && !dozing.getBoolean(controllerInstance)
                && lockProgress != null) {
              try { keyguardDrag = (Float)lockProgress.invoke(controllerInstance); }
              catch (ReflectiveOperationException missing) { runtime.combinedQs.layout.warn(missing); }
            }
            result = chain.proceed();
          } finally {
            closingUpdate = previously;
            keyguardDrag = previousDrag;
          }
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
    try { installLockedRelease(runtime, controller, qs, split, bar, dozing, expansionEnabled); }
    catch (ReflectiveOperationException missing) { runtime.combinedQs.layout.warn(missing); }
    try { installLockedDrag(runtime, controller, qs, bar, dozing, expansionEnabled); }
    catch (ReflectiveOperationException missing) { runtime.combinedQs.layout.warn(missing); }
    try { installLockedClipping(runtime, compose, controller, qs, bar, dozing, expansionEnabled); }
    catch (ReflectiveOperationException missing) { runtime.combinedQs.layout.warn(missing); }
    try { installScrollCallback(runtime, controller, qs, split, bar, dozing, getPanel); }
    catch (ReflectiveOperationException missing) { runtime.combinedQs.layout.warn(missing); }
  }

  private void installLockedRelease(PanelRuntime runtime, Class<?> controller, Field qs, Field split,
      Field bar, Field dozing, Method enabled) throws ReflectiveOperationException {
    Method touch = Reflect.method(controller, "handleTouch", MotionEvent.class, boolean.class, boolean.class);
    Method fling = Reflect.method(controller, "flingQs", float.class, int.class, Runnable.class, boolean.class);
    Field animator = Reflect.field(controller, "mExpansionAnimator");
    Field overscrolling = Reflect.field(controller, "mStackScrollerOverscrolling");
    Field fullShade = Reflect.field(controller, "mTransitioningToFullShadeProgress");
    Method height = Reflect.method(controller, "getExpansionHeight");
    Method minimum = Reflect.method(controller, "getMinExpansionHeight");
    runtime.module.hook(touch).setId("native-panels:locked-qs-touch-release")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          if (((MotionEvent)chain.getArg(0)).getActionMasked() != MotionEvent.ACTION_UP) return chain.proceed();
          Object previous = releasingTouch;
          releasingTouch = chain.getThisObject();
          try { return chain.proceed(); } finally { releasingTouch = previous; }
        });
    runtime.module.hook(fling).setId("native-panels:locked-qs-close-motion")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          Object host = chain.getThisObject();
          if (host != releasingTouch || (Integer)chain.getArg(1) != 1 || (Boolean)chain.getArg(3)
              || releaseMotionMissing || bar.getInt(host) != KEYGUARD || split.getBoolean(host) || dozing.getBoolean(host)
              || overscrolling.getBoolean(host) || fullShade.getFloat(host) != 0f || !(Boolean)enabled.invoke(host)
              || !ValueAnimator.areAnimatorsEnabled()) return chain.proceed();
          Fragment fragment = (Fragment)qs.get(host);
          if (!runtime.combinedQs.layout.acceptsPullMotion(fragment) || fragment.getView() == null) return chain.proceed();
          float velocity = (Float)chain.getArg(0);
          float from = (Float)height.invoke(host), to = (Integer)minimum.invoke(host);
          if (!Float.isFinite(velocity) || velocity > 0 || !Float.isFinite(from) || from <= to) return chain.proceed();
          LockscreenQsRelease.Curve curve = null;
          try {
            if (releaseMotion == null) releaseMotion = new LockscreenQsRelease(runtime);
            curve = releaseMotion.closing(from, to, velocity, fragment.getResources().getDisplayMetrics().density);
          } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            if (releaseMotion == null) releaseMotionMissing = true;
            runtime.combinedQs.layout.warn(unavailable);
          }
          Object previous = animator.get(host);
          Object result = chain.proceed();
          // flingQs starts its own height animator synchronously. Its initial sample is
          // the current height. Change only its remaining trajectory before the next vsync.
          if (curve != null && animator.get(host) instanceof ValueAnimator next && next != previous && next.isStarted()) {
            previousCloseMs = next.getDuration();
            curve.apply(next);
            appliedCloseMs = curve.millis;
          }
          return result;
        });
    runtime.module.deoptimize(touch);
  }

  private void installLockedDrag(PanelRuntime runtime, Class<?> controller, Field qs, Field bar,
      Field dozing, Method expansionEnabled) throws ReflectiveOperationException {
    Class<?> effects = runtime.type("com.oplus.systemui.panelanimation.OplusPanelAnimationExImpl");
    Class<?> translation = runtime.type(effects.getName() + "$PanelTranslationYValue");
    Class<?> callback = runtime.type(controller.getName() + "$$ExternalSyntheticLambda10");
    Method move = Reflect.method(translation, "updateKeyguardQs", float.class);
    Method dragCaller = Reflect.method(effects, "onTouchMoveForKeyguardQs", float.class,
        runtime.type("kotlin.jvm.functions.Function2"));
    XposedInterface.Hooker hook = LockscreenQsMotion.dragHook(translation, callback, controller,
        host -> bar.getInt(host) == KEYGUARD && !dozing.getBoolean(host)
            && runtime.combinedQs.layout.isSelected(qs.get(host)) && (Boolean)expansionEnabled.invoke(host)
            && Float.isFinite(runtime.combinedQs.layout.directLockProgress()));
    runtime.module.hook(move).setId("native-panels:locked-qs-touch-height")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hook);
    runtime.module.deoptimize(dragCaller);
  }

  private void installLockedClipping(PanelRuntime runtime, Class<?> compose, Class<?> controller,
      Field qs, Field bar, Field dozing, Method expansionEnabled) throws ReflectiveOperationException {
    Field overscrolling = Reflect.field(controller, "mStackScrollerOverscrolling");
    Field viewModel = Reflect.field(compose, "viewModel");
    Method state = Reflect.method(viewModel.getType(), "getExpansionState");
    Method progress = Reflect.method(state.getReturnType(), "getProgress");
    Method panelFraction = Reflect.method(viewModel.getType(), "getPanelExpansionFraction");
    Method apply = controller.getDeclaredMethod("applyClippingImmediately",
        int.class, int.class, int.class, int.class, boolean.class);
    Method clip = compose.getDeclaredMethod("setFancyClipping",
        int.class, int.class, int.class, int.class, int.class, boolean.class, boolean.class);
    runtime.module.hook(clip).setId("native-panels:locked-qs-clipping")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          // OOS enables this exclusion only when QS is visible in single-column shade.
          Fragment fragment = (Fragment)chain.getThisObject();
          if (!(Boolean)chain.getArg(5) || !runtime.combinedQs.layout.isSelected(fragment)) return chain.proceed();
          Object host = controllerRef.get();
          if (host == null || qs.get(host) != fragment) return chain.proceed();
          int barState = bar.getInt(host);
          if ((barState != KEYGUARD && barState != SHADE_LOCKED) || dozing.getBoolean(host)
              || overscrolling.getBoolean(host)) return chain.proceed();
          View root = fragment.getView();
          Object model = viewModel.get(fragment);
          if (root == null || root.getHeight() <= 0 || root.getRootWindowInsets() == null || model == null
              || !(Boolean)expansionEnabled.invoke(host)) return chain.proceed();
          // The locked grid can already be fully expanded while the notification edge stays
          // near the top. Use the same expansion state that Compose uses to render this grid.
          float scene = (Float)progress.invoke(state.invoke(model));
          float panel = (Float)panelFraction.invoke(model);
          float fraction = lockedClipProgress(scene, panel, runtime.combinedQs.layout.directLockProgress());
          int top = (Integer)chain.getArg(1), bottom = (Integer)chain.getArg(3);
          int adjusted = lockedClipTop(top, bottom, fraction);
          if (adjusted == top) return chain.proceed();
          Object[] args = chain.getArgs().toArray();
          args[1] = adjusted;
          args[5] = adjusted < bottom;
          return chain.proceed(args);
        });
    runtime.module.deoptimize(apply);
  }

  static int lockedClipTop(int top, int bottom, float fraction) {
    if (top >= bottom || bottom <= 0 || !Float.isFinite(fraction) || fraction <= 0f || fraction > 1f) return top;
    return Math.max(top, Math.round(bottom * fraction));
  }

  static float lockedProgress(float original, float panel) {
    return Float.isFinite(panel) && panel >= 0f && panel <= 1f ? Math.min(original, panel) : original;
  }

  static float lockedClipProgress(float scene, float panel, float directLockProgress) {
    // The direct scene brings a full grid in from above. A second bottom-up mask
    // would slice through moving rows and the footer. Restore the native edge at rest.
    return directLockProgress > 0f && directLockProgress <= 1f ? scene : lockedProgress(scene, panel);
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
    String close = previousCloseMs < 0 ? "" : " lockedCloseMs(ROM/scene)=" + previousCloseMs + "/" + appliedCloseMs;
    return "QS now: " + live + close + "\n" + (bridgeError.isEmpty() ? "" : bridgeError + "\n");
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
