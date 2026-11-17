package dev.oos.nativepanels;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Restores the Google controller's own grant UI, leaving all permission decisions untouched. */
final class PermissionDialogHook {
  static final String PACKAGE = "com.google.android.permissioncontroller";
  private static final String UI = "com.android.permissioncontroller.permission.ui.";
  private final ModuleEntry module;
  private final ClassLoader loader;
  private final PermissionDialogInflation inflation = new PermissionDialogInflation();
  private final PermissionDialogResources resources = new PermissionDialogResources();
  private final PermissionDialogSettings.Reporter diagnostics = new PermissionDialogSettings.Reporter();
  private final Set<String> warnings = ConcurrentHashMap.newKeySet();

  PermissionDialogHook(ModuleEntry module, ClassLoader loader) {
    this.module = module;
    this.loader = loader;
  }

  void install() throws ReflectiveOperationException {
    Class<?> activityType = Class.forName(UI + "GrantPermissionsActivity", false, loader);
    Class<?> handlerType = Class.forName(UI + "handheld.GrantPermissionsViewHandlerImpl", false, loader);
    Field activityField = Reflect.field(handlerType, "mActivity");
    Method createView = handlerType.getDeclaredMethod("createView");
    if (!Activity.class.isAssignableFrom(activityType)
        || !Activity.class.isAssignableFrom(activityField.getType())
        || !View.class.isAssignableFrom(createView.getReturnType()))
      throw new NoSuchMethodException("Unsupported permission grant view signature");

    Method from = LayoutInflater.class.getMethod("from", Context.class);
    module.hook(from).setId("native-panels:permission-inflater")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          LayoutInflater original = (LayoutInflater)c.proceed();
          try { return inflation.select((Context)c.getArg(0), original); }
          catch (RuntimeException failure) {
            warnOnce("inflater", "Permission inflater kept original context", failure);
            return original;
          }
        });
    // LayoutInflater.from is small enough to have been inlined into this caller.
    if (!module.deoptimize(createView))
      module.log(Log.WARN, "OOSNativePanels", "Permission view deoptimization unavailable", null);
    module.hook(createView).setId("native-panels:permission-view")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          Activity activity = (Activity)activityField.get(c.getThisObject());
          if (!activityType.isInstance(activity)) return c.proceed();
          if (!PermissionDialogSettings.readEnabled(activity)) return c.proceed();
          Context presentation;
          try { presentation = resources.create(activity); }
          catch (RuntimeException | java.io.IOException failure) {
            warnOnce("resources", "Permission UI kept original resources", failure);
            diagnostics.report(activity, "Grant UI: original\n" + failure);
            return c.proceed();
          }
          // The unmodified handler chooses its layout and binds the original result
          // listener, SecureButtons, location controls and grant/deny actions.
          Object view = inflation.run(activity, presentation, c::proceed);
          boolean applied = view instanceof View root && root.getResources() == presentation.getResources();
          String background = null;
          if (applied) {
            try { background = PermissionDialogColors.apply((View)view); }
            catch (RuntimeException failure) {
              // A cosmetic failure must not rerun the handler or interrupt its permission request.
              warnOnce("background", "Permission dialog kept original background", failure);
            }
          }
          diagnostics.report(activity, "Package: " + activity.getPackageName()
              + "\nGrant UI: " + (applied ? "native APK resources" : "original (inflater not reached)")
              + "\nDialog background: " + (background != null ? background : "unchanged"));
          return view;
        });
  }

  private void warnOnce(String part, String message, Throwable failure) {
    if (warnings.add(part)) module.log(Log.WARN, "OOSNativePanels", message, failure);
  }
}
