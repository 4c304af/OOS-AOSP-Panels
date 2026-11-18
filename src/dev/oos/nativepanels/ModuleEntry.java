package dev.oos.nativepanels;

import android.app.Application;
import android.media.session.MediaSession;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.*;
import java.util.HashSet;
import java.util.Set;

public final class ModuleEntry extends XposedModule {
  private final Set<String> installed = new HashSet<>();
  private PanelRuntime runtime;

  // PackageReady supplies the final AppComponentFactory class loader before Application creation.
  @Override
  public void onPackageReady(XposedModuleInterface.PackageReadyParam p) {
    if (PermissionDialogHook.PACKAGE.equals(p.getPackageName())) {
      installPart("permission-dialogs", () -> new PermissionDialogHook(this, p.getClassLoader()).install());
      return;
    }
    if (BluetoothDetailsSettings.SETTINGS_PACKAGE.equals(p.getPackageName())) {
      installPart("bluetooth-details-theme", () -> new BluetoothDetailsHook(this, p.getClassLoader()).install());
      installPart("about-phone-m3e", () -> new AboutPhoneHook(this, p.getClassLoader()).install());
      return;
    }
    if (BluetoothDetailsSettings.WIRELESS_PACKAGE.equals(p.getPackageName())) {
      installPart("wireless-bluetooth-details", () -> new WirelessBluetoothDetailsHook(this, p.getClassLoader()).install());
      return;
    }
    if (Protocol.TARGET.equals(p.getPackageName())) install(p.getClassLoader());
  }

  private synchronized void install(ClassLoader loader) {
    if (loader == null) return;
    String process = Application.getProcessName();
    if (process != null && !Protocol.TARGET.equals(process)) return;
    if (runtime == null) runtime = new PanelRuntime(this, loader);
    installPart("application", () -> {
      Method start = android.app.Instrumentation.class.getMethod("callApplicationOnCreate", Application.class);
      hook(start).setId("native-panels:application").intercept(c -> {
        Object result = c.proceed();
        attachSystemUi(c.getArg(0));
        return result;
      });
    });
    installPart("oplus-application", () -> {
      Method start = Reflect.method(runtime.type("com.android.systemui.application.impl.SystemUIApplicationImpl"), "onCreate");
      hook(start).setId("native-panels:oplus-application").intercept(c -> {
        Object result = c.proceed();
        attachSystemUi(c.getThisObject());
        return result;
      });
    });
    BluetoothSettingsNavigation bluetoothNavigation = new BluetoothSettingsNavigation(runtime);
    installPart("bluetooth-settings", bluetoothNavigation::install);
    installPart("bluetooth-tile-settings", bluetoothNavigation::installTile);
    installPart("home-battery", runtime.homeBattery::install);
    installPart("combined-qs", () -> runtime.combinedQs.install());
    installPart("combined-tile-clicks", () -> CombinedTileClicks.install(runtime));
    installPart("recorder-panel-motion", () -> new RecorderPanelMotion().install(runtime));
    installPart("recorder-tile-dialog", () -> new RecorderTileDialog(runtime).install());
    installPart("calculator-vendor", () -> CalculatorLaunch.install(runtime, true));
    installPart("calculator-service", () -> CalculatorLaunch.install(runtime, false));
    installPart("combined-disclosure", () -> CombinedFooter.installDisclosure(runtime));
    installPart("combined-users", () -> runtime.users.install());
    installPart("combined-rotation", () -> CombinedTileClicks.installRotation(runtime));
    installPart("combined-shade-travel", runtime.settingsLaunch::installTravel);
    installPart("combined-shade-dismiss", runtime.settingsLaunch::installDismiss);
    installPart("combined-media-dismiss", runtime.settingsLaunch::installMediaDismiss);
    installPart("combined-settings-launch", runtime.settingsLaunch::install);
    installPart("combined-edit", () -> CombinedEditor.install(runtime));
    installPart("combined-haptics", () -> CombinedHaptics.install(runtime));
    installPart("combined-touch", () -> {
      runtime.combinedQs.layout.touch.install();
      runtime.combinedQs.layout.touch.installParent();
    });
    installPart("internet-data", () -> runtime.wifi.install());
    installPart("carrier-data", () -> runtime.wifi.installCarrierRouting());
    installPart("dialog-motion", () -> runtime.motion.install());
    installPart("oos-settings", () -> new SettingsNavigation(runtime).install());
    installPart("privacy", () -> runtime.privacy.install());
    installPart("channels", () -> runtime.channels.install());
    installPart("flashlight", () -> runtime.flashlight.install());
    installPart("modes", () -> runtime.modes.install());
    for (String tile : new String[] {"OplusBluetoothTile", "OplusCellularTile"}) {
      installPart(
          "source-" + tile,
          () -> {
            Method m =
                Reflect.method(
                    runtime.type("com.oplus.systemui.qs.tiles." + tile),
                    "handleSecondaryClick",
                    runtime.type("com.android.systemui.animation.Expandable"));
            hook(m)
                .setId("native-panels:source")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(
                    c -> {
                      Object previous = runtime.motion.source.get();
                      runtime.motion.source.set(c.getArg(0));
                      try {
                        return c.proceed();
                      } finally {
                        runtime.motion.source.set(previous);
                      }
                    });
          });
    }
    installPart(
        "wifi",
        () -> {
          Class<?> type = Class.forName("com.oplus.systemui.qs.tiles.OplusWifiTile", false, loader);
          Method m =
              Reflect.method(
                  type,
                  "showOplusQSDetailDialogProvider",
                  runtime.type("com.android.systemui.animation.Expandable"));
          hook(m)
              .setId("native-panels:wifi")
              .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
              .intercept(
                  c -> {
                    boolean locked = runtime.locked();
                    if (!runtime.enabled("internet") || (locked && !runtime.combinedQs.layout.gridVisible()))
                      return c.proceed();
                    Object source = c.getArg(0);
                    return runtime.redirect(
                        c,
                        "internet",
                        null,
                        () -> {
                          runtime.showInternet(source);
                          return null;
                        });
                  });
          runtime.wifiHook = true;
        });
    installPart(
        "details",
        () -> {
          Class<?> type =
              Class.forName(
                  "com.oplus.systemui.qs.detail.interactor.OplusQSDetailInteractor", false, loader);
          Method m = Reflect.method(type, "show", View.class);
          hook(m)
              .setId("native-panels:details")
              .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
              .intercept(
                  c -> {
                    String adapter = Reflect.get(c.getThisObject(), "adapter").getClass().getName();
                    String feature;
                    if (adapter.equals(
                        "com.oplus.systemui.qs.tiles.OplusBluetoothTile$ColorBluetoothDetailAdapter"))
                      feature = "bluetooth";
                    else if (adapter.equals("com.oplus.systemui.qs.user.OplusUserDetailAdapter"))
                      feature = "users";
                    else if (adapter.startsWith("com.oplus.systemui.qs.tiles.OplusCellularTile$"))
                      feature = "internet";
                    else return c.proceed();
                    boolean locked = runtime.locked();
                    if (!runtime.enabled(feature) ||
                        (locked && (feature.equals("users") || !runtime.combinedQs.layout.gridVisible()))) return c.proceed();
                    // The Bluetooth backend reduces Compose's Expandable to a View here.
                    // Capture the original tile on this thread before redirect posts to main.
                    Object source = runtime.motion.fromSourceOrView((View) c.getArg(0));
                    return runtime.redirect(
                        c,
                        feature,
                        Boolean.TRUE,
                        () -> {
                          if (feature.equals("bluetooth")) runtime.showBluetooth(source);
                          else if (feature.equals("users")) runtime.users.show(source);
                          else runtime.showInternet(source);
                          return Boolean.TRUE;
                        });
                  });
          runtime.detailsHook = true;
        });
    installPart(
        "media",
        () -> {
          Class<?> type =
              runtime.type("com.android.systemui.media.dialog.MediaOutputDialogManager");
          Method m =
              Reflect.method(
                  type,
                  "create",
                  String.class,
                  boolean.class,
                  View.class,
                  boolean.class,
                  boolean.class,
                  boolean.class,
                  MediaSession.Token.class,
                  boolean.class);
          hook(m)
              .setId("native-panels:media")
              .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
              .intercept(
                  c -> {
                    // The OEM API returns an animation view. Native dialogs own their own window;
                    // callers in the supplied ROM explicitly accept null and skip OEM animation.
                    if (!runtime.enabled("media")
                        || runtime.locked()
                        || Looper.myLooper() != Looper.getMainLooper()) return c.proceed();
                    try {
                      runtime.showMedia(
                          c.getThisObject(),
                          (String) c.getArg(0),
                          (MediaSession.Token) c.getArg(6),
                          (Boolean) c.getArg(1),
                          (View) c.getArg(2));
                      return null;
                    } catch (Throwable e) {
                      runtime.failure("media", e);
                      return c.proceed();
                    }
                  });
          runtime.mediaHook = true;
        });
  }

  private void attachSystemUi(Object candidate) {
    if (candidate instanceof Application app && Protocol.TARGET.equals(app.getPackageName())) {
      runtime.attach(app);
    }
  }

  interface Install {
    void run() throws Throwable;
  }

  private void installPart(String key, Install action) {
    if (installed.contains(key)) return;
    try {
      action.run();
      installed.add(key);
    } catch (Throwable e) {
      if ("combined-qs".equals(key)) runtime.combinedQs.error = Log.getStackTraceString(e);
      log(Log.ERROR, "OOSNativePanels", "Failed to install hook: " + key, e);
    }
  }
}
