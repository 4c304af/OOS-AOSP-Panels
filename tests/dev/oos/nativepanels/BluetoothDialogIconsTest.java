package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.util.Pair;
import android.widget.ImageView;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
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

  @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
  public void productPhotoFitsTheDeviceRowRegardlessOfBitmapAndDisplayDensity() {
    for (int densityScale : new int[]{1, 2, 3}) {
      Bitmap reference = null;
      for (int sourceDensity : new int[]{Bitmap.DENSITY_NONE, 160, 480}) {
        Bitmap photo = Bitmap.createBitmap(72, 36, Bitmap.Config.ARGB_8888);
        photo.setDensity(sourceDensity);
        photo.eraseColor(Color.GREEN);
        Canvas source = new Canvas(photo);
        Paint markers = new Paint();
        markers.setColor(Color.RED);
        source.drawRect(0, 0, 12, 36, markers);
        markers.setColor(Color.BLUE);
        source.drawRect(60, 0, 72, 36, markers);

        int side = 36 * densityScale;
        ImageView rowIcon = new ImageView(app);
        rowIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        rowIcon.setImageDrawable(productIcon(photo, densityScale));
        rowIcon.layout(0, 0, side, side);
        Bitmap output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        output.setDensity(160 * densityScale);
        rowIcon.draw(new Canvas(output));
        int mid = side / 2;
        assertEquals(Color.RED, output.getPixel(7 * densityScale, mid));
        assertEquals(Color.BLUE, output.getPixel(28 * densityScale, mid));
        assertEquals(Color.WHITE, output.getPixel(mid, 10 * densityScale));
        assertEquals(Color.GREEN, output.getPixel(mid, 13 * densityScale));
        assertEquals(Color.WHITE, output.getPixel(mid, 25 * densityScale));
        if (reference != null) assertTrue(reference.sameAs(output));
        reference = output;
      }
    }
  }

  @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
  public void theEntireBadgeStrokeAndPhotoFollowOffsetAndResizedBounds() {
    Bitmap photo = Bitmap.createBitmap(36, 72, Bitmap.Config.ARGB_8888);
    photo.eraseColor(Color.RED);
    BluetoothProductIcon icon = productIcon(photo, 1);
    icon.setBounds(0, 0, 72, 72);
    icon.setBounds(2, 5, 46, 41); // 36px badge centered in a 44px slot.
    Bitmap output = Bitmap.createBitmap(48, 46, Bitmap.Config.ARGB_8888);
    icon.draw(new Canvas(output));
    assertEquals(Color.TRANSPARENT, output.getPixel(5, 23));
    assertEquals(Color.TRANSPARENT, output.getPixel(42, 23));
    assertEquals(Color.TRANSPARENT, output.getPixel(24, 4));
    assertEquals(Color.TRANSPARENT, output.getPixel(24, 41));
    for (int x : new int[]{7, 40}) assertEquals(Color.BLACK, output.getPixel(x, 23));
    for (int y : new int[]{6, 39}) assertEquals(Color.BLACK, output.getPixel(24, y));
    assertEquals(Color.WHITE, output.getPixel(15, 23));
    assertEquals(Color.RED, output.getPixel(24, 12));
    assertEquals(Color.RED, output.getPixel(24, 33));
  }

  private static BluetoothProductIcon productIcon(Bitmap photo, int densityScale) {
    Path mask = new Path();
    mask.addCircle(50, 50, 50, Path.Direction.CW);
    Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    outline.setColor(Color.BLACK);
    outline.setStyle(Paint.Style.STROKE);
    outline.setStrokeWidth(2 * densityScale);
    return new BluetoothProductIcon(photo, mask, outline, 6 * densityScale);
  }
}
