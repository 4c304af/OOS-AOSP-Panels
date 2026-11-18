package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AboutPhoneRowsTest {
  private static AboutPhoneRows.Item item(String key, Object parent) {
    return new AboutPhoneRows.Item(key, parent, false);
  }

  @Test public void hardwareUsesVisibleNeighborsInsteadOfStandaloneOemFlags() {
    Object screen = new Object();
    var model = item("device_market_name_header", screen);
    var cpu = item("cpu_info", screen);
    var ram = item("ram_info", screen);
    assertEquals(AboutPhoneRows.FIRST, AboutPhoneRows.position(null, model, cpu));
    assertEquals(AboutPhoneRows.MIDDLE, AboutPhoneRows.position(model, cpu, ram));
    assertEquals(AboutPhoneRows.LAST, AboutPhoneRows.position(cpu, ram, null));
    // A controller hides CPU: the adapter now presents model and RAM consecutively.
    assertEquals(AboutPhoneRows.FIRST, AboutPhoneRows.position(null, model, ram));
    assertEquals(AboutPhoneRows.LAST, AboutPhoneRows.position(model, ram, null));
    assertEquals(AboutPhoneRows.SINGLE, AboutPhoneRows.position(null, model, null));
  }

  @Test public void categoryAndDifferentParentsAlwaysEndASection() {
    Object root = new Object(), nameStorage = new Object(), cameraGroup = new Object();
    var category = new AboutPhoneRows.Item(null, root, true);
    var ram = item("ram_info", root);
    var camera = item("camera_info", cameraGroup);
    assertFalse(AboutPhoneRows.joined(ram, category));
    assertFalse(AboutPhoneRows.joined(category, camera));
    assertFalse(AboutPhoneRows.joined(ram, camera));
    assertEquals(AboutPhoneRows.SINGLE, AboutPhoneRows.position(category, camera, null));
    var name = item("device_name", nameStorage);
    var storage = item("device_storage", nameStorage);
    assertTrue(AboutPhoneRows.joined(name, storage));
    assertFalse(AboutPhoneRows.joined(storage, ram));
    assertFalse(AboutPhoneRows.joined(item("device_name", null), item("device_storage", null)));
  }

  @Test public void otaHeroAndCategoryAreNeverRestyledAsListCards() {
    Object root = new Object();
    assertFalse(AboutPhoneRows.card(null));
    assertFalse(AboutPhoneRows.card(item("key_ota_update", root)));
    assertFalse(AboutPhoneRows.card(new AboutPhoneRows.Item("category", root, true)));
    assertFalse(AboutPhoneRows.joined(item("key_ota_update", root), item("cpu_info", root)));
    assertFalse(AboutPhoneRows.joined(item("cpu_info", root), item("version_info", root)));
  }

  @Test public void rebindingOverwritesOtherModuleSpacingWithoutChangingContentsOrTouchTarget() {
    View view = new View(RuntimeEnvironment.getApplication());
    ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(300, 140);
    params.setMargins(12, 9, 14, 16);
    view.setLayoutParams(params);
    view.setPaddingRelative(32, 20, 24, 18);
    view.setMinimumHeight(140);
    boolean[] clicked = {false};
    view.setOnClickListener(v -> clicked[0] = true);
    for (int i = 0; i < 10; i++) AboutPhoneRows.spacing(view, 2);
    assertEquals(0, params.topMargin);
    assertEquals(2, params.bottomMargin);
    assertEquals(12, params.leftMargin);
    assertEquals(14, params.rightMargin);
    assertEquals(32, view.getPaddingStart());
    assertEquals(20, view.getPaddingTop());
    assertEquals(24, view.getPaddingEnd());
    assertEquals(18, view.getPaddingBottom());
    assertEquals(140, view.getMinimumHeight());
    view.performClick();
    assertTrue(clicked[0]);
  }

  @Test public void recycledFirstLastAndSingleRowsReplaceEveryCorner() {
    float[] radii = new float[8];
    AboutPhoneRows.radii(radii, AboutPhoneRows.FIRST, 20, 4);
    assertArrayEquals(new float[]{20,20,20,20,4,4,4,4}, radii, 0);
    AboutPhoneRows.radii(radii, AboutPhoneRows.LAST, 20, 4);
    assertArrayEquals(new float[]{4,4,4,4,20,20,20,20}, radii, 0);
    AboutPhoneRows.radii(radii, AboutPhoneRows.SINGLE, 20, 4);
    assertArrayEquals(new float[]{20,20,20,20,20,20,20,20}, radii, 0);
    AboutPhoneRows.radii(radii, AboutPhoneRows.MIDDLE, 20, 4);
    assertArrayEquals(new float[]{4,4,4,4,4,4,4,4}, radii, 0);
  }

  @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
  public void groupedClipRetainsInnerContentAndStorageBarWhileOnlyRoundingEdges() {
    Path path = new Path();
    RectF bounds = new RectF();
    float[] radii = new float[8];
    AboutPhoneRows.radii(radii, AboutPhoneRows.LAST, 20, 4);
    AboutPhoneRows.path(path, bounds, radii, 300, 140, 12);
    Bitmap bitmap = Bitmap.createBitmap(300, 140, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    canvas.clipPath(path);
    canvas.drawColor(Color.RED);
    Paint paint = new Paint();
    paint.setColor(Color.BLUE);
    canvas.drawRect(32, 105, 268, 113, paint);
    assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 70));
    assertEquals(Color.TRANSPARENT, bitmap.getPixel(12, 139));
    assertEquals(Color.RED, bitmap.getPixel(15, 5));
    assertEquals(Color.BLUE, bitmap.getPixel(32, 109));
    assertEquals(Color.BLUE, bitmap.getPixel(267, 109));
    assertEquals(new RectF(12, 0, 288, 140), bounds);
    bitmap.recycle();
  }

  @Test public void onlyTheActualOosAboutFragmentAndScreenCanOptIn() {
    assertTrue(AboutPhoneHook.matchesScreen(AboutPhoneHook.FRAGMENT, AboutPhoneHook.SCREEN));
    assertFalse(AboutPhoneHook.matchesScreen("com.android.settings.deviceinfo.aboutphone.MyDeviceInfoFragment",
        AboutPhoneHook.SCREEN));
    assertFalse(AboutPhoneHook.matchesScreen(AboutPhoneHook.FRAGMENT, "bluetooth_screen"));
    assertFalse(AboutPhoneHook.matchesScreen(null, AboutPhoneHook.SCREEN));
    assertFalse(AboutPhoneHook.matchesScreen(AboutPhoneHook.FRAGMENT, null));
  }
}
