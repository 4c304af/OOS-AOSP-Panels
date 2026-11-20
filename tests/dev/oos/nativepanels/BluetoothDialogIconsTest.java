package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.util.Pair;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BluetoothDialogIconsTest {
  private final Context app = RuntimeEnvironment.getApplication();
  private final BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:55");
  private final Pair<?, ?> headset = Pair.create(new ColorDrawable(), "Headset");
  private static String deviceType;

  public static String metadata(BluetoothDevice device, int key) {
    assertEquals(17, key);
    return deviceType;
  }

  private Pair<?, ?> resolve(int deviceClass, String declaredType, Pair<?, ?> original, String resource) throws Exception {
    deviceType = declaredType;
    Shadows.shadowOf(device).setBluetoothClass(deviceClass < 0 ? null : ReflectionHelpers.callConstructor(
        BluetoothClass.class, ClassParameter.from(int.class, deviceClass)));
    Resources res = new Resources(app.getAssets(), app.getResources().getDisplayMetrics(), app.getResources().getConfiguration()) {
      @Override public int getIdentifier(String name, String type, String pkg) {
        return name.equals(resource) && "drawable".equals(type) && Protocol.TARGET.equals(pkg)
            ? android.R.drawable.ic_menu_recent_history : 0;
      }
    };
    Context host = new ContextWrapper(app) {
      @Override public Resources getResources() { return res; }
    };
    Method metadata = BluetoothDialogIconsTest.class.getMethod("metadata", BluetoothDevice.class, int.class);
    return BluetoothDialogIcons.icon(host, device, original, metadata);
  }

  @Test public void savedWristwatchOrExplicitWatchMetadataOverridesHeadsetGlyph() throws Exception {
    String icon = "ic_media_smartwatch";
    Pair<?, ?> result = resolve(BluetoothClass.Device.WEARABLE_WRIST_WATCH, null, headset, icon);
    assertNotSame(headset.first, result.first);
    assertNotNull(result.first);
    assertNull(result.second);
    assertNotSame(headset, resolve(-1, "Watch", headset, icon));
    assertNotSame(headset, resolve(BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE, "OplusWatch", headset,
        "status_bar_qs_bt_watch_ic"));
  }

  @Test public void headphonesOtherWearablesAndDeviceSuppliedArtworkKeepTheirOwnIcons() throws Exception {
    String icon = "ic_media_smartwatch";
    assertSame(headset, resolve(BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES, "Untethered Headset", headset, icon));
    assertSame(headset, resolve(BluetoothClass.Device.WEARABLE_GLASSES, null, headset, icon));
    assertSame(headset, resolve(-1, null, headset, icon));
    assertSame(headset, resolve(BluetoothClass.Device.WEARABLE_WRIST_WATCH, null, headset, null));
    Pair<?, ?> artwork = Pair.create(new BitmapDrawable(app.getResources(),
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)), "Custom device icon");
    assertSame(artwork, resolve(BluetoothClass.Device.WEARABLE_WRIST_WATCH, null, artwork, icon));
  }
}
