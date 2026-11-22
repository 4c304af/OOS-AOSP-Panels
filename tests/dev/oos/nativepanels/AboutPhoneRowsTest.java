package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
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

  @Test public void plainListKeepsTheNightPaletteAndSeparatesLightRowsFromThePage() {
    for (boolean dark : new boolean[]{false, true}) {
      Context app = RuntimeEnvironment.getApplication();
      Configuration config = new Configuration(app.getResources().getConfiguration());
      config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
          | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
      Context themed = app.createConfigurationContext(config);
      // The v34+ Settings color aliases on the target ROM point to these framework roles.
      int card = dark ? android.R.color.system_surface_bright_dark : android.R.color.system_surface_bright_light;
      int container = dark ? android.R.color.system_surface_container_dark : android.R.color.system_surface_container_light;
      int surface = dark ? android.R.color.system_surface_dark : android.R.color.system_surface_light;
      Resources resources = new Resources(themed.getAssets(), themed.getResources().getDisplayMetrics(), config) {
        @Override public int getIdentifier(String name, String type, String pkg) {
          if (!"com.android.settings".equals(pkg)) return 0;
          if ("dimen".equals(type)) return android.R.dimen.app_icon_size;
          return switch (name) {
            case "settingslib_materialColorSurfaceBright" -> card;
            case "settingslib_materialColorSurfaceContainer" -> container;
            case "settingslib_materialColorSurface" -> surface;
            default -> 0;
          };
        }
      };
      AboutPhoneHook.Metrics colors = AboutPhoneHook.Metrics.read(new ContextWrapper(themed) {
        @Override public Resources getResources() { return resources; }
      });
      assertNotNull(colors);
      assertEquals(themed.getColor(dark ? surface : container), colors.surface());
      assertEquals(themed.getColor(card), colors.card());
      assertNotEquals(colors.card(), colors.surface());
    }
  }

  @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
  public void systemArtworkRemainsVisibleThroughTheListOnOpenAndThemeChange() {
    Context context = RuntimeEnvironment.getApplication();
    FrameLayout root = new FrameLayout(context);
    ImageView backdrop = new ImageView(context);
    View list = new View(context);
    root.addView(backdrop);
    root.addView(list);
    int exact = View.MeasureSpec.makeMeasureSpec(32, View.MeasureSpec.EXACTLY);
    root.measure(exact, exact);
    root.layout(0, 0, 32, 32);
    Bitmap result = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(result);
    for (int color : new int[]{Color.MAGENTA, Color.BLUE}) {
      // OOS reuses the ImageView and reinstalls a transparent list background on configuration changes.
      ColorDrawable transparent = new ColorDrawable(Color.TRANSPARENT);
      list.setBackground(transparent);
      backdrop.setImageDrawable(new ColorDrawable(color));
      backdrop.setVisibility(View.VISIBLE);
      AboutPhoneHook.applyListFallback(list, backdrop, Color.GRAY);
      assertSame(transparent, list.getBackground());
      assertEquals(View.VISIBLE, backdrop.getVisibility());
      root.draw(canvas);
      assertEquals(color, result.getPixel(16, 16));
      backdrop.setVisibility(View.GONE);
      AboutPhoneHook.applyListFallback(list, backdrop, Color.BLACK);
      root.draw(canvas);
      assertEquals(Color.BLACK, result.getPixel(16, 16));
    }
    AboutPhoneHook.applyListFallback(list, null, Color.WHITE);
    root.draw(canvas);
    assertEquals(Color.WHITE, result.getPixel(16, 16));
    result.recycle();
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

  @Test public void anonymousCameraCategoryIsTransparentToActualAdapterNeighbors() throws Exception {
    Fixture f = new Fixture();
    Pref model = new Pref("device_market_name_header", f.screen);
    Pref cpu = new Pref("cpu_info", f.screen);
    Pref ram = new Pref("ram_info", f.screen);
    Category cameraContainer = new Category(null, f.screen);
    Pref camera = new Pref("camera_info", cameraContainer);
    Pref processor = new Pref("processor_detail", cameraContainer);
    cameraContainer.children.addAll(List.of(camera, processor));
    Pref battery = new Pref("battery_and_charge_info", f.screen);
    Pref display = new Pref("screen_physics_size", f.screen);
    // processor_detail is unavailable on this device: it remains in the real
    // hierarchy, while only camera_info appears in the adapter's visible list.
    f.adapter.visible.addAll(List.of(model, cpu, ram, cameraContainer, camera, battery, display));
    assertTrue(f.item(3).hardwareContainer());
    assertSame(f.screen, f.item(4).parent());
    assertEquals(AboutPhoneRows.FIRST, f.position(0));
    for (int position : new int[]{1, 2, 4, 5}) assertEquals(AboutPhoneRows.MIDDLE, f.position(position));
    assertEquals(AboutPhoneRows.LAST, f.position(6));
    assertFalse(AboutPhoneRows.sectionBreak(f.item(2), f.neighbor(2, 1)));
    // Removing another unsupported row cannot recreate the old camera-only pill.
    f.adapter.visible.remove(cpu);
    assertEquals(AboutPhoneRows.MIDDLE, f.position(1));
    assertEquals(AboutPhoneRows.MIDDLE, f.position(3));
    // An OOS variant that displays processor_detail joins it to the same group.
    f.adapter.visible.add(4, processor);
    assertEquals(AboutPhoneRows.MIDDLE, f.position(3));
    assertEquals(AboutPhoneRows.MIDDLE, f.position(4));
  }

  @Test public void namedTitledOrUnrelatedCategoriesKeepTheirBoundaries() throws Exception {
    Fixture f = new Fixture();
    Pref ram = new Pref("ram_info", f.screen);
    Category cameraContainer = new Category(null, f.screen);
    Pref camera = new Pref("camera_info", cameraContainer);
    cameraContainer.children.add(camera);
    Pref battery = new Pref("battery_and_charge_info", f.screen);
    f.adapter.visible.addAll(List.of(ram, cameraContainer, camera, battery));
    cameraContainer.title = "Camera information";
    assertFalse(f.item(1).hardwareContainer());
    assertSame(cameraContainer, f.item(2).parent());
    assertEquals(AboutPhoneRows.SINGLE, f.position(0));
    assertEquals(AboutPhoneRows.SINGLE, f.position(2));
    cameraContainer.title = null;
    cameraContainer.key = "named_hardware_group";
    assertFalse(f.item(1).hardwareContainer());
    cameraContainer.key = null;
    cameraContainer.children.add(new Pref("version_info", cameraContainer));
    assertFalse(f.item(1).hardwareContainer());
    cameraContainer.children.remove(1);
    f.screen.key = "other_settings_screen";
    assertFalse(f.item(1).hardwareContainer());
    assertSame(cameraContainer, f.item(2).parent());
  }

  @Test public void storageToHardwareUsesASectionGapWithoutSplittingNameAndStorage() throws Exception {
    Fixture f = new Fixture();
    Category nameStorage = new Category("about_device_name_storage_category", f.screen);
    Pref name = new Pref("device_name", nameStorage), storage = new Pref("device_storage", nameStorage);
    nameStorage.children.addAll(List.of(name, storage));
    Pref model = new Pref("device_market_name_header", f.screen);
    Pref cpu = new Pref("cpu_info", f.screen);
    f.adapter.visible.addAll(List.of(nameStorage, name, storage, model, cpu));
    assertFalse(AboutPhoneRows.sectionBreak(f.item(1), f.neighbor(1, 1)));
    assertTrue(AboutPhoneRows.sectionBreak(f.item(2), f.neighbor(2, 1)));
    assertEquals(AboutPhoneRows.LAST, f.position(2));
    assertEquals(AboutPhoneRows.FIRST, f.position(3));
    assertFalse(AboutPhoneRows.sectionBreak(f.item(3), f.neighbor(3, 1)));
    assertFalse(AboutPhoneRows.sectionBreak(f.item(4), new AboutPhoneRows.Item("legal", f.screen, true)));
    View view = new View(RuntimeEnvironment.getApplication());
    ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(300, 140);
    view.setLayoutParams(params);
    AboutPhoneRows.spacing(view, 12);
    assertEquals(12, params.bottomMargin);
    // The same holder subsequently binds an ordinary inner row, without a stale gap.
    AboutPhoneRows.spacing(view, 2);
    assertEquals(2, params.bottomMargin);
  }

  @Test public void zeroHeightCameraContainerRestoresItsLayoutBeforeReuse() {
    View view = new View(RuntimeEnvironment.getApplication());
    ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(300, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.setMargins(5, 16, 7, 4);
    view.setLayoutParams(params);
    view.setPadding(9, 12, 11, 3);
    view.setMinimumHeight(48);
    AboutPhoneRows.CollapsedCategory original = AboutPhoneRows.CollapsedCategory.collapse(view);
    assertNotNull(original);
    assertEquals(0, params.height);
    assertEquals(0, params.topMargin);
    assertEquals(0, params.bottomMargin);
    assertEquals(12, view.getPaddingTop());
    assertEquals(48, view.getMinimumHeight());
    original.restore(view);
    assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, params.height);
    assertEquals(16, params.topMargin);
    assertEquals(4, params.bottomMargin);
    assertEquals(5, params.leftMargin);
    assertEquals(7, params.rightMargin);
    // Native bind may now change dimensions for a titled category. A later
    // collapse/restore captures those values instead of restoring the first holder's.
    params.height = 60;
    params.topMargin = 24;
    AboutPhoneRows.CollapsedCategory next = AboutPhoneRows.CollapsedCategory.collapse(view);
    next.restore(view);
    assertEquals(60, params.height);
    assertEquals(24, params.topMargin);
  }

  public static class Pref {
    String key;
    CharSequence title;
    final Group parent;
    Pref(String key, Group parent) { this.key = key; this.parent = parent; }
    public String getKey() { return key; }
    public CharSequence getTitle() { return title; }
    public Group getParent() { return parent; }
  }

  public static class Group extends Pref {
    final List<Pref> children = new ArrayList<>();
    Group(String key, Group parent) { super(key, parent); }
    public int getPreferenceCount() { return children.size(); }
    public Pref getPreference(int position) { return children.get(position); }
  }

  public static class Category extends Group {
    Category(String key, Group parent) { super(key, parent); }
  }

  public static class Adapter {
    final List<Pref> visible = new ArrayList<>();
    public int getItemCount() { return visible.size(); }
    public Pref getItem(int position) { return visible.get(position); }
  }

  private static final class Fixture {
    final AboutPhoneHook hook = new AboutPhoneHook(null, AboutPhoneRowsTest.class.getClassLoader());
    final Group screen = new Group(AboutPhoneHook.SCREEN, null);
    final Adapter adapter = new Adapter();
    final Method item, neighbor;
    Fixture() throws Exception {
      set("categoryClass", Category.class);
      set("getKey", Pref.class.getMethod("getKey"));
      set("getTitle", Pref.class.getMethod("getTitle"));
      set("getParent", Pref.class.getMethod("getParent"));
      set("getPreference", Group.class.getMethod("getPreference", int.class));
      set("getPreferenceCount", Group.class.getMethod("getPreferenceCount"));
      set("getItem", Adapter.class.getMethod("getItem", int.class));
      set("getItemCount", Adapter.class.getMethod("getItemCount"));
      item = AboutPhoneHook.class.getDeclaredMethod("item", Object.class, int.class);
      neighbor = AboutPhoneHook.class.getDeclaredMethod("neighbor", Object.class, int.class, int.class);
      item.setAccessible(true);
      neighbor.setAccessible(true);
    }
    void set(String name, Object value) throws Exception {
      Field field = AboutPhoneHook.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(hook, value);
    }
    AboutPhoneRows.Item item(int position) throws Exception {
      return (AboutPhoneRows.Item)item.invoke(hook, adapter, position);
    }
    AboutPhoneRows.Item neighbor(int position, int direction) throws Exception {
      return (AboutPhoneRows.Item)neighbor.invoke(hook, adapter, position, direction);
    }
    int position(int position) throws Exception {
      return AboutPhoneRows.position(neighbor(position, -1), item(position), neighbor(position, 1));
    }
  }
}
