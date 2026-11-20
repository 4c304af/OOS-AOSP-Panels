package dev.oos.nativepanels;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Pair;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;

final class BluetoothDialogIcons {
  private static final int METADATA_DEVICE_TYPE = 17;

  static void install(PanelRuntime runtime) throws ReflectiveOperationException {
    Class<?> refresh = runtime.type("com.android.systemui.bluetooth.qsdialog.DeviceItemInteractor$updateDeviceItems$2");
    Class<?> cached = runtime.type("com.android.settingslib.bluetooth.CachedBluetoothDevice");
    Class<?> utils = runtime.type("com.android.settingslib.bluetooth.BluetoothUtils");
    Method buildList = Reflect.method(refresh, "invokeSuspend", Object.class);
    Method enter = Reflect.method(refresh, "invoke", Object.class, Object.class);
    Method getDevice = Reflect.method(cached, "getDevice");
    Method getIcon = Reflect.method(cached, "getDrawableWithDescription");
    Method glyph = Reflect.method(utils, "getBtDrawableWithDescription", Context.class, cached);
    Method metadata = Reflect.method(utils, "getStringMetaData", BluetoothDevice.class, int.class);

    // Each synchronous list-building pass runs on SystemUI's background dispatcher.
    ThreadLocal<Boolean> buildingList = new ThreadLocal<>();
    runtime.module.hook(buildList).setId("native-panels:bluetooth-device-list-icons")
        .intercept(chain -> {
          if (buildingList.get() == Boolean.TRUE) return chain.proceed();
          buildingList.set(true);
          try { return chain.proceed(); }
          finally { buildingList.remove(); }
        });
    runtime.module.hook(glyph).setId("native-panels:bluetooth-watch-icon")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          Object original = chain.proceed();
          if (buildingList.get() != Boolean.TRUE || !(original instanceof Pair<?, ?> icon)) return original;
          try {
            BluetoothDevice device = (BluetoothDevice)getDevice.invoke(chain.getArg(1));
            return icon((Context)chain.getArg(0), device, icon, metadata);
          } catch (ReflectiveOperationException | SecurityException | Resources.NotFoundException unavailable) {
            return original;
          }
        });
    runtime.module.deoptimize(enter);
    runtime.module.deoptimize(getIcon);
  }

  static Pair<?, ?> icon(Context context, BluetoothDevice device, Pair<?, ?> original, Method metadata)
      throws ReflectiveOperationException {
    if (device == null || !(original.first instanceof Drawable) || original.first instanceof BitmapDrawable
        || !isWatch(device, metadata)) return original;
    Resources res = context.getResources();
    int id = res.getIdentifier("ic_media_smartwatch", "drawable", Protocol.TARGET);
    if (id == 0) id = res.getIdentifier("status_bar_qs_bt_watch_ic", "drawable", Protocol.TARGET);
    Drawable watch = id == 0 ? null : context.getDrawable(id);
    // SystemUI applies the device's colors next. The row announces its own name, so drop
    // the headset-specific description together with its glyph.
    return watch == null ? original : Pair.create(watch, null);
  }

  private static boolean isWatch(BluetoothDevice device, Method metadata) throws ReflectiveOperationException {
    BluetoothClass type = device.getBluetoothClass();
    if (type != null && type.getDeviceClass() == BluetoothClass.Device.WEARABLE_WRIST_WATCH) return true;
    Object declaredType = metadata.invoke(null, device, METADATA_DEVICE_TYPE);
    return "Watch".equals(declaredType) || "OplusWatch".equals(declaredType);
  }
}
