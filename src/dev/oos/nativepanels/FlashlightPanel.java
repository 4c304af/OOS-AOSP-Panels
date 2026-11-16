package dev.oos.nativepanels;

import android.app.Dialog;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;

final class FlashlightPanel {
  private final PanelRuntime r;
  private volatile int cachedLevels = -1;
  private Object startedRepo, startedByModule;
  private WeakReference<Dialog> active = new WeakReference<>(null);

  FlashlightPanel(PanelRuntime r) {
    this.r = r;
  }

  void install() throws Throwable {
    Class<?> repo = r.type("com.android.systemui.flashlight.data.repository.FlashlightRepositoryImpl");
    r.module.hook(Reflect.method(repo, "start")).setId("native-panels:torch-start")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          synchronized (this) {
            if (startedByModule == c.getThisObject()) return null;
            Object result = c.proceed();
            startedRepo = c.getThisObject();
            return result;
          }
        });
    Method level = Reflect.method(
        r.type("com.android.systemui.flashlight.ui.viewmodel.FlashlightSliderViewModel"),
        "setFlashlightLevel$1", int.class, boolean.class);
    r.module.hook(level).setId("native-panels:flashlight-restrictions")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          Dialog dialog = active.get();
          if (dialog == null || !dialog.isShowing() || (Integer) c.getArg(0) == 0) return c.proceed();
          if (r.locked() || restricted()) {
            dialog.dismiss();
            r.message(R.string.torch_limited);
            return null;
          }
          return c.proceed();
        });
    Class<?> adapter = r.type("com.oplus.systemui.qs.detail.flashlight.OplusQSFlashlightDetailProvider");
    Method show = Reflect.method(
        r.type("com.oplusos.systemui.viewdialog.domain.SysUIViewDialogInteractor"), "showDialog",
        r.type("com.oplusos.systemui.common.viewdialog.model.ViewDialogAdapter"));
    r.module.hook(show).setId("native-panels:flashlight-detail")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          Object detail = c.getArg(0);
          if (!adapter.isInstance(detail) || !r.enabled("flashlight") || r.locked()) return c.proceed();
          if (restricted()) return c.proceed();
          View anchor = (View) Reflect.call(detail, "getExpandableView");
          return r.redirect(c, "flashlight", null, () -> {
            show(r.motion.fromSourceOrView(anchor), false);
            return null;
          });
        });
    r.flashlightHook = true;
  }

  void test() throws Throwable {
    if (restricted()) {
      r.message(R.string.torch_retry);
      return;
    }
    show(null, false);
  }

  private boolean restricted() throws Throwable {
    Object oem = r.provider("flashlightControllerImplProvider");
    Object power = r.provider("superPowerSaveRepositoryImplProvider");
    return (Boolean) Reflect.get(oem, "mTorchLimited")
        || (Boolean) Reflect.call(power, "getInSuperPowerSaveMode");
  }

  boolean supportsGridPanel() {
    if (cachedLevels >= 0) return cachedLevels > 1;
    try {
      Object repo = r.provider("flashlightRepositoryImplProvider");
      return strengthLevels((CameraManager)Reflect.get(repo, "cameraManager")) > 1;
    } catch (Throwable unavailable) { return false; }
  }

  void showFromGrid(Object source) throws Throwable {
    if (android.app.ActivityManager.isUserAMonkey()) return;
    if (restricted()) { r.message(R.string.torch_limited); return; }
    show(source, true);
  }

  private void show(Object source, boolean enableOnOpen) throws Throwable {
    Object repo = r.provider("flashlightRepositoryImplProvider");
    int levels = strengthLevels((CameraManager) Reflect.get(repo, "cameraManager"));
    if (levels <= 1)
      throw new IllegalStateException(r.text(R.string.torch_unsupported, levels));
    synchronized (this) {
      if (startedRepo != repo && !(Boolean) Reflect.call(repo, "getDeviceSupportsFlashlight")) {
        Reflect.call(repo, "start");
        startedRepo = repo;
        startedByModule = repo;
      }
    }
    Object delegate = r.provider("flashlightDialogDelegateProvider");
    Dialog current = (Dialog) Reflect.get(delegate, "currentDialog");
    if (current != null && current.isShowing()) return;
    Dialog dialog = (Dialog) Reflect.call(delegate, "createDialog");
    Reflect.field(delegate.getClass(), "currentDialog").set(delegate, dialog);
    active = new WeakReference<>(dialog);
    try {
      if (enableOnOpen) {
        Object flashlight = r.provider("flashlightInteractorProvider");
        Reflect.invoke(Reflect.method(flashlight.getClass(), "setEnabled", boolean.class), flashlight, true);
      }
      r.motion.showFromTile(dialog, source, delegate, "flashlight");
    } catch (Throwable error) {
      dialog.dismiss();
      Reflect.field(delegate.getClass(), "currentDialog").set(delegate, null);
      throw error;
    }
    r.note(R.string.torch_open, levels);
  }

  private int strengthLevels(CameraManager cameras) throws Exception {
    if (cachedLevels >= 0) return cachedLevels;
    // The ROM selects the first rear-facing camera reporting a flash.
    for (String id : cameras.getCameraIdList()) {
      CameraCharacteristics c = cameras.getCameraCharacteristics(id);
      if (Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))
          && Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(c.get(CameraCharacteristics.LENS_FACING))) {
        if (c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL) == null) return cachedLevels = 1;
        Integer levels = c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
        return cachedLevels = levels == null ? 1 : levels;
      }
    }
    return 0;
  }
}
