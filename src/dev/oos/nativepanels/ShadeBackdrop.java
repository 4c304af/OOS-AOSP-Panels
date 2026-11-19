package dev.oos.nativepanels;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/** Use the Android window-depth controller and Material scrim together in the unlocked shade. */
final class ShadeBackdrop implements View.OnAttachStateChangeListener {
  private final PanelRuntime runtime;
  private final WeakHashMap<View, WeakReference<Object>> controllers = new WeakHashMap<>();
  private Object depth, scrim, viewExtension;
  private View behind;
  private Field root, windowBlur, windowSupported, repository, state, layer, vendor, viewExt,
      expansion, transparent, blank, affectsAlpha, occluding, unlocking, bouncerTransition,
      behindTint, behindAlpha, vendorAmount;
  private Method color, blurSupported, setAmount, schedule, updateSpring, clearBlur, blurRequest,
      scaleRequest, setValue, onBlurApplied;
  private int paintedColor, panelColor;
  private Configuration paletteConfig;
  private boolean paletteHasBlur;
  private float lastVendorAmount;
  private boolean ready, releasing;

  ShadeBackdrop(PanelRuntime runtime) { this.runtime = runtime; }

  void install() throws ReflectiveOperationException {
    Class<?> controller = runtime.type("com.android.systemui.statusbar.NotificationShadeDepthController");
    Class<?> ext = runtime.type("com.oplus.systemui.statusbar.NotificationShadeDepthControllerExImp");
    Class<?> sc = runtime.type("com.android.systemui.statusbar.phone.ScrimController");
    Class<?> vendorController = runtime.type("com.oplus.systemui.statusbar.phone.ScrimControllerExImp");
    Class<?> view = runtime.type("com.android.systemui.scrim.ScrimView");
    Class<?> vx = runtime.type("com.oplus.systemui.scrim.ScrimViewExImp");
    root = Reflect.field(controller, "root");
    windowBlur = Reflect.field(controller, "windowRootViewBlurInteractor");
    Class<?> interactor = windowBlur.getType();
    windowSupported = Reflect.field(interactor, "isBlurredWallpaperOnShadeSupported");
    repository = Reflect.field(interactor, "repository");
    clearBlur = Reflect.method(interactor, "requestBlurForShade", float.class, int.class);
    blurRequest = Reflect.method(repository.getType(), "getBlurRequestedByShade");
    scaleRequest = Reflect.method(repository.getType(), "getScaleRequestedByShade");
    setValue = Reflect.method(blurRequest.getReturnType(), "setValue", Object.class);
    Field extOwner = Reflect.field(ext, "controller");
    state = Reflect.field(sc, "mState");
    layer = Reflect.field(sc, "mScrimBehind");
    vendor = Reflect.field(sc, "mExt");
    viewExt = Reflect.field(view, "mExt");
    expansion = Reflect.field(sc, "mPanelExpansionFraction");
    transparent = Reflect.field(sc, "mTransparentScrimBackground");
    blank = Reflect.field(sc, "mBlankScreen");
    affectsAlpha = Reflect.field(sc, "mExpansionAffectsAlpha");
    occluding = Reflect.field(sc, "mOccludeAnimationPlaying");
    unlocking = Reflect.field(sc, "mAnimatingPanelExpansionOnUnlock");
    bouncerTransition = Reflect.field(sc, "mIsBouncerToGoneTransitionRunning");
    behindTint = Reflect.field(sc, "mBehindTint");
    behindAlpha = Reflect.field(sc, "mBehindAlpha");
    vendorAmount = Reflect.field(vx, "blurAmount");
    color = Reflect.method(runtime.type("com.android.systemui.shade.ui.ShadeColors"), "shadePanel",
        Context.class, boolean.class, boolean.class);
    blurSupported = Reflect.method(sc, "isBlurCurrentlySupported");
    setAmount = Reflect.method(vx, "setBlurAmount", float.class, String.class);
    schedule = Reflect.method(controller, "scheduleUpdate");
    updateSpring = Reflect.method(controller, "updateShadeAnimationBlur", int.class, float.class, float.class, boolean.class);
    onBlurApplied = Reflect.method(controller, "access$onBlurApplied", controller, int.class, float.class);
    Method apply = Reflect.method(sc, "applyState$1");
    Method extra = Reflect.method(vendorController, "setExtraScrimState");
    Method logic = Reflect.method(ext, "isOplusLogic");
    Method draw = Reflect.method(view, "onDraw", Canvas.class);
    Method bind = Reflect.method(controller, "setRoot", View.class);
    Method tintChanged = Reflect.method(sc, "applyAndDispatchState");
    runtime.module.hook(bind).setId("native-panels:shade-depth-window").intercept(chain -> {
      Object result = chain.proceed();
      remember(chain.getThisObject(), (View)chain.getArg(0));
      return result;
    });
    runtime.module.hook(logic).setId("native-panels:shade-native-depth").intercept(chain -> {
      Object owner = extOwner.get(chain.getThisObject());
      if (owner != null && owner != depth) remember(owner, (View)root.get(owner));
      return owner != null && owner == depth ? false : chain.proceed();
    });
    runtime.module.hook(apply).setId("native-panels:shade-backdrop-state").intercept(chain -> {
      Object target = chain.getThisObject();
      Object result = chain.proceed();
      if (ready && !releasing) update(target);
      return result;
    });
    runtime.module.hook(extra).setId("native-panels:shade-backdrop-alpha").intercept(chain -> {
      Object result = chain.proceed();
      if (scrim != null && vendor.get(scrim) == chain.getThisObject()) applyTint();
      return result;
    });
    runtime.module.hook(setAmount).setId("native-panels:shade-single-blur").intercept(chain -> {
      if (viewExtension != chain.getThisObject()) return chain.proceed();
      lastVendorAmount = (Float)chain.getArg(0);
      return null; // The OOS proxy was disabled when the window acquired native blur.
    });
    runtime.module.hook(draw).setId("native-panels:shade-material-tint").intercept(chain -> {
      if (behind != chain.getThisObject()) return chain.proceed();
      if ((paintedColor >>> 24) != 0) ((Canvas)chain.getArg(0)).drawColor(paintedColor);
      return null;
    });
    for (Method caller : new Method[]{schedule, updateSpring, onBlurApplied, tintChanged,
        Reflect.method(vx, "setBlurAndScaleAmount", float.class, String.class)})
      runtime.module.deoptimize(caller);
    ready = true;
  }

  private void remember(Object owner, View window) {
    if (window == null) return;
    View key = window.getRootView();
    WeakReference<Object> known = controllers.get(key);
    if (known == null || known.get() != owner) controllers.put(key, new WeakReference<>(owner));
  }

  private void update(Object target) throws ReflectiveOperationException {
    View view = (View)layer.get(target);
    WeakReference<Object> known = view == null ? null : controllers.get(view.getRootView());
    Object owner = known == null ? null : known.get();
    View window = owner == null ? null : (View)root.get(owner);
    ShadeDecor decor = runtime.combinedQs.layout.hapticOwner();
    if (window == null || decor == null || decor.preview.visible() || !view.isAttachedToWindow()
        || decor.frame.getRootView() != view.getRootView() || window.getRootView() != view.getRootView()
        || !"UNLOCKED".equals(((Enum<?>)state.get(target)).name()) || transparent.getBoolean(target)
        || blank.getBoolean(target) || !affectsAlpha.getBoolean(target) || occluding.getBoolean(target)
        || unlocking.getBoolean(target) || bouncerTransition.getBoolean(target)
        || !windowSupported.getBoolean(windowBlur.get(owner))) {
      if (target == scrim) release();
      return;
    }
    if (view != behind || owner != depth) {
      release();
      Object extension = viewExt.get(view);
      if (!setAmount.getDeclaringClass().isInstance(extension)) return;
      float amount = vendorAmount.getFloat(extension);
      setAmount.invoke(extension, 0f, "Android shade background");
      behind = view;
      viewExtension = extension;
      lastVendorAmount = amount;
      scrim = target;
      depth = owner;
      behind.addOnAttachStateChangeListener(this);
      updateSpring.invoke(depth, 0, expansion.getFloat(scrim), 0f, false);
      schedule.invoke(depth);
    }
    boolean supported = (Boolean)blurSupported.invoke(scrim);
    Configuration config = behind.getResources().getConfiguration();
    if (paletteHasBlur != supported || !config.equals(paletteConfig)) {
      panelColor = (Integer)color.invoke(null, behind.getContext(), supported, true);
      paletteConfig = new Configuration(config);
      paletteHasBlur = supported;
    }
    applyTint();
  }

  private void applyTint() throws ReflectiveOperationException {
    int next = tintAtExpansion(panelColor, expansion.getFloat(scrim));
    behindTint.setInt(scrim, panelColor);
    behindAlpha.setFloat(scrim, (next >>> 24) / 255f);
    if (next != paintedColor) {
      paintedColor = next;
      behind.invalidate();
    }
  }

  static int tintAtExpansion(int panelColor, float fraction) {
    float open = Float.isFinite(fraction) ? Math.max(0f, Math.min(1f, fraction)) : 0f;
    return (Math.round((panelColor >>> 24) * open) << 24) | (panelColor & 0xffffff);
  }

  void release() {
    if (behind == null || releasing) return;
    releasing = true;
    View old = behind;
    Object extension = viewExtension;
    try {
      updateSpring.invoke(depth, 0, 0f, 0f, false);
      Object interactor = windowBlur.get(depth);
      // During bouncer transitions the shared interactor rejects new shade requests. Clear
      // just the shade source so it cannot reappear when the bouncer releases the window.
      if (!(Boolean)clearBlur.invoke(interactor, 1f, 0)) {
        Object repo = repository.get(interactor);
        setValue.invoke(blurRequest.invoke(repo), 0f);
        setValue.invoke(scaleRequest.invoke(repo), 1f);
      }
      onBlurApplied.invoke(null, depth, 0, 0f);
    } catch (ReflectiveOperationException e) { runtime.combinedQs.layout.warn(e); }
    finally {
      behind = null;
      viewExtension = scrim = depth = null;
      paletteConfig = null;
      paintedColor = 0;
      old.removeOnAttachStateChangeListener(this);
      try { setAmount.invoke(extension, lastVendorAmount, "Restore shade background"); }
      catch (ReflectiveOperationException e) { runtime.combinedQs.layout.warn(e); }
      old.invalidate();
      releasing = false;
    }
  }

  @Override public void onViewDetachedFromWindow(View view) { if (view == behind) release(); }
  @Override public void onViewAttachedToWindow(View view) {}
}
