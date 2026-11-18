package dev.oos.nativepanels;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;

/** Redirect the OEM Bluetooth list's device gear while retaining its normal activity launch. */
final class WirelessBluetoothDetailsHook {
  private final ModuleEntry module;
  private final ClassLoader loader;

  WirelessBluetoothDetailsHook(ModuleEntry module, ClassLoader loader) {
    this.module = module;
    this.loader = loader;
  }

  void install() throws ReflectiveOperationException {
    // Raw DEX names in Wireless Settings 17.5.18. The gear constructs the exact
    // SubSettingsExt + device-profile fragment intent before calling this helper.
    Class<?> utility = Class.forName("com.oplus.wirelesssettings.utils.f", false, loader);
    Method launch = utility.getDeclaredMethod("Q", Context.class, Intent.class);
    module.hook(launch).setId("native-panels:wireless-bluetooth-details")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          Intent original = (Intent)chain.getArg(1);
          if (!BluetoothDetailsIntents.isWirelessDetails(original)) return chain.proceed();
          Context context = (Context)chain.getArg(0);
          if (context == null) return chain.proceed();
          Intent target = BluetoothDetailsIntents.fromWireless(context, original,
              BluetoothDetailsSettings.readEnabled(context));
          return target == original ? chain.proceed() : chain.proceed(new Object[]{context, target});
        });
    module.deoptimize(launch);
    // R8 shares this listener with other buttons. Only its device-profile branch
    // reaches the guarded helper above; the other cases retain their own behavior.
    Class<?> gearListener = Class.forName("androidx.appcompat.app.e", false, loader);
    module.deoptimize(gearListener.getDeclaredMethod("onClick", View.class));
  }
}
