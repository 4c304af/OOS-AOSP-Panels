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
  private Object shadeController;
  private Field height, ambient;
  private Method appear;

  static float parallax(float proposed, float height, float appear) {
    if (!Float.isFinite(appear) || !Float.isFinite(height) || height <= 0f || !(proposed < 0f)) return proposed;
    float fullTravel = -height * (1f - Math.min(1f, appear));
    // Only correct the OOS full-height interpolation. Keep other ROM paths,
    // including a host that already supplies AOSP's shorter translation.
    return Math.abs(proposed - fullTravel) <= .5f ? .175f * fullTravel : proposed;
  }

  float gridTranslation(float proposed) {
    if (shadeController == null || proposed >= 0f || height == null || appear == null) return proposed;
    try {
      return parallax(proposed, height.getFloat(shadeController), (Float) appear.invoke(ambient.get(shadeController)));
    } catch (ReflectiveOperationException ignored) { return proposed; }
  }
  float gridFraction(float qs, float shade) {
    return closingUpdate ? CombinedSettingsLaunch.closingExpansion(qs, shade) : qs;
  }
  private String bridgeError = "";

  void reset() {
    current.clear();
    bridgeError = "";
  }

  void install(PanelRuntime runtime, Class<?> compose) throws Exception {
    Class<?> controller = runtime.type("com.android.systemui.shade.QuickSettingsControllerImpl");
    // Some SystemUI branches expose the header through a different controller.
    // That must not prevent the fragment from receiving expansion updates.
    Field header = Reflect.optionalField(controller, "mShadeHeaderController");
    Field qs = Reflect.field(controller, "mQs");
    Field shadeFraction = Reflect.field(controller, "mShadeExpandedFraction");
    height = Reflect.optionalField(controller, "mExpansionHeight");
    ambient = Reflect.optionalField(controller, "mAmbientState");
    appear = ambient == null ? null : optionalMethod(ambient.getType(), "getExpansionFraction");
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
          Object previousController = shadeController;
          Object controllerInstance = chain.getThisObject();
          boolean ordinaryShade = runtime.combinedQs.layout.isSelected(qs.get(controllerInstance))
              && bar.getInt(controllerInstance) == SHADE;
          shadeController = ordinaryShade && split != null && !split.getBoolean(controllerInstance) ? controllerInstance : null;
          closingUpdate = false;
          if (panelProvider != null && closing != null && ordinaryShade) {
            try { closingUpdate = (Boolean)closing.invoke(getPanel.invoke(panelProvider.get(chain.getThisObject()))); }
            catch (Throwable e) { runtime.combinedQs.layout.warn(e); }
          }
          Object result;
          try { result = chain.proceed(); }
          finally { closingUpdate = previously; shadeController = previousController; }
          if (runtime.combinedQs.status != R.string.combined_selected) return result;
          try {
            Object host = chain.getThisObject();
            Object target = qs.get(host);
            if (compose.isInstance(target) && runtime.combinedQs.layout.isSelected(target)) {
              Fragment fragment = (Fragment) target;
              if (current.get() != fragment) {
                reset();
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
