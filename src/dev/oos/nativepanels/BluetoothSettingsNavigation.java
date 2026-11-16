package dev.oos.nativepanels;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class BluetoothSettingsNavigation {
  static final String LIST = "com.android.settings.PREVIOUSLY_CONNECTED_DEVICE";
  static final String DEVICE = "com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS";
  static final String PROFILES = "wireless.settings.DEVICE_PROFILES_SETTINGS";
  private final PanelRuntime runtime;

  BluetoothSettingsNavigation(PanelRuntime runtime) { this.runtime = runtime; }

  void install() throws Exception {
    Class<?> manager = runtime.type("com.android.systemui.bluetooth.qsdialog.BluetoothDetailsContentManager");
    Class<?> vm = runtime.type("com.android.systemui.bluetooth.ui.viewModel.BluetoothDetailsContentViewModel");
    Field active = Reflect.field(vm, "contentManager");
    Method launch = manager.getDeclaredMethod("startSettingsActivity", Intent.class, View.class);
    runtime.module.hook(launch).setId("native-panels:bluetooth-settings")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          boolean ownPanel = runtime.bluetoothModel != null && active.get(runtime.bluetoothModel) == chain.getThisObject();
          if (!ownPanel && !runtime.combinedQs.layout.gridVisible()) return chain.proceed();
          Intent original = (Intent)chain.getArg(0);
          View source = (View)chain.getArg(1);
          if (source == null) return chain.proceed();
          Intent target = redirect(source.getContext(), original);
          if (target == original) return chain.proceed();
          return chain.proceed(new Object[]{target, source});
        });
    runtime.module.deoptimize(manager.getDeclaredMethod("onSeeAllClicked", View.class));
    runtime.module.deoptimize(runtime.type(manager.getName() + "$bind$1").getDeclaredMethod("onClick", View.class));
    Class<?> callback = runtime.type(vm.getName() + "$updateDetailsUIState$2$8$$ExternalSyntheticLambda0");
    for (Method method : callback.getDeclaredMethods())
      if (method.getName().equals("invoke")) runtime.module.deoptimize(method);
  }

  void installTile() throws Exception {
    Class<?> tile = runtime.type("com.android.systemui.qs.tiles.BluetoothTile");
    Field context = tile.getField("mContext");
    Method intent = tile.getMethod("getLongClickIntent");
    runtime.module.hook(intent).setId("native-panels:bluetooth-tile-settings")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          Intent original = (Intent)chain.proceed();
          Object owner = chain.getThisObject();
          if (!tile.isInstance(owner) || !runtime.combinedQs.layout.gridVisible() || original == null
              || !Settings.ACTION_BLUETOOTH_SETTINGS.equals(original.getAction())) return original;
          return redirect((Context)context.get(owner), original);
        });
    Class<?> base = runtime.type("com.android.systemui.qs.tileimpl.QSTileImpl");
    runtime.module.deoptimize(base.getDeclaredMethod("handleLongClick",
        runtime.type("com.android.systemui.animation.Expandable")));
  }

  static Intent redirect(Context context, Intent original) {
    if (original == null) return null;
    String action = original.getAction();
    if (!LIST.equals(action) && !DEVICE.equals(action) && !Settings.ACTION_BLUETOOTH_SETTINGS.equals(action))
      return original;
    String pkg = OosSettingsIntents.PACKAGE;
    Intent list = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).setPackage(pkg).setFlags(original.getFlags());
    if (DEVICE.equals(action)) {
      Bundle args = original.getBundleExtra(OosSettingsIntents.FRAGMENT_ARGS);
      String address = args == null ? null : args.getString("device_address");
      if (BluetoothAdapter.checkBluetoothAddress(address)) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter != null) {
          Bundle selected = new Bundle();
          selected.putParcelable("device", adapter.getRemoteDevice(address));
          Intent details = new Intent(PROFILES).setPackage(pkg).setFlags(original.getFlags())
              .putExtra(OosSettingsIntents.FRAGMENT_ARGS, selected);
          if (details.resolveActivity(context.getPackageManager()) != null) return details;
        }
      }
    }
    return list.resolveActivity(context.getPackageManager()) != null ? list : original;
  }
}
