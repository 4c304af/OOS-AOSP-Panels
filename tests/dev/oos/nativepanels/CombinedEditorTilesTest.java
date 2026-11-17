package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CombinedEditorTilesTest {
  @Test public void availableLegacyTilesReceiveNativeNamesAndIconsWithoutAStaticConfig() throws Exception {
    CombinedEditorTiles editor = editor();
    Data input = data("inputmethod", "inputmethod", "star");
    Data shutdown = data("shutdown", "shutdown", "star");
    Object keyboard = new Object(), power = new Object();
    CombinedEditorTiles.Catalog catalog = new CombinedEditorTiles.Catalog(
        Set.of("inputmethod", "shutdown"), Set.of(), Map.of(
            "inputmethod", new CombinedEditorTiles.Presentation(keyboard, "入力方法"),
            "shutdown", new CombinedEditorTiles.Presentation(power, "電源メニュー")));
    List<?> result = editor.presentPlatform(List.of(input, shutdown), catalog);
    Data first = (Data)result.get(0), second = (Data)result.get(1);
    assertSame(input.spec, first.spec);
    assertSame(keyboard, first.icon);
    assertEquals("入力方法", first.label);
    assertSame(power, second.icon);
    assertEquals("電源メニュー", second.label);
    assertSame(input.category, first.category);
    assertEquals("star", input.icon);
    assertEquals("inputmethod", input.label);
  }

  @Test public void onlyUnavailableUnselectedPlatformCandidatesAreRemoved() throws Exception {
    Data adaptive = data("autoscreentemperature", "アダプティブトーン", "configured icon");
    Data recording = data("screenrecord", "画面録画", "Google icon");
    Data selected = data("darkmode", "darkmode", "star");
    Data supported = data("nfc", "nfc", "star");
    Data custom = new Data(new Spec("custom(com.oplus.screenrecorder/.QuickPanelService)"),
        new Object(), "画面録画", "Oplus", new Object(), "Utilities");
    CombinedEditorTiles.Catalog catalog = new CombinedEditorTiles.Catalog(
        Set.of("nfc"), Set.of("darkmode"), Map.of());
    List<?> result = editor().presentPlatform(
        List.of(adaptive, recording, selected, supported, custom), catalog);
    // Selected but policy-blocked tiles remain removable/reorderable. A custom
    // service keeps its own provider identity rather than becoming a platform tile.
    assertEquals(List.of(selected, supported, custom), result);
    assertSame(custom, result.get(2));
  }

  @Test public void supportedOemRecordingUsesItsBackendArtworkAndUnchangedSpec() throws Exception {
    Data recording = data("screenrecord", "画面録画", "Google icon");
    Object oplusIcon = new Object();
    CombinedEditorTiles.Catalog catalog = new CombinedEditorTiles.Catalog(
        Set.of("screenrecord"), Set.of(), Map.of("screenrecord",
            new CombinedEditorTiles.Presentation(oplusIcon, "画面録画")));
    Data result = (Data)editor().presentPlatform(List.of(recording), catalog).get(0);
    assertSame(recording.spec, result.spec);
    assertSame(oplusIcon, result.icon);
    assertSame(recording.category, result.category);
  }

  @Test public void missingArtworkKeepsExistingIconWhileRestoringTheNativeLabel() throws Exception {
    Data item = data("lockscreen", "lockscreen", new Object());
    CombinedEditorTiles.Catalog catalog = new CombinedEditorTiles.Catalog(
        Set.of("lockscreen"), Set.of(), Map.of("lockscreen",
            new CombinedEditorTiles.Presentation(null, "画面ロック")));
    Data result = (Data)editor().presentPlatform(List.of(item), catalog).get(0);
    assertSame(item.icon, result.icon);
    assertEquals("画面ロック", result.label);
  }

  @Test public void adaptiveToneServiceCannotDuplicateTheSelectedNativePlatformTile() throws Exception {
    String alias = "custom(com.oplus.eyeprotect/.services.AmbientColorAdaptationTileService)";
    Data adaptive = custom(alias, "アダプティブトーン");
    Data unrelated = custom("custom(example.app/.TileService)", "アダプティブトーン");
    Data platform = data("autoscreentemperature", "色温度の自動調整", new Object());
    CombinedEditorTiles.Catalog catalog = new CombinedEditorTiles.Catalog(
        Set.of("autoscreentemperature"), Set.of("autoscreentemperature"), Map.of(),
        Map.of(alias, List.of("autoscreentemperature")));
    List<?> result = editor().presentCustom(List.of(adaptive, unrelated), List.of(platform), catalog);
    assertEquals(List.of(unrelated), result);
    assertSame(unrelated, result.get(0));
    assertEquals("アダプティブトーン", adaptive.label);
  }

  @Test public void aliasRemainsHiddenWhenItsCanonicalTileIsAnUnselectedCandidate() throws Exception {
    String alias = "custom(com.oplus.eyeprotect/.services.AmbientColorAdaptationTileService)";
    Data adaptive = custom(alias, "アダプティブトーン");
    Data platform = data("autoscreentemperature", "色温度の自動調整", new Object());
    CombinedEditorTiles.Catalog catalog = new CombinedEditorTiles.Catalog(
        Set.of("autoscreentemperature"), Set.of(), Map.of(),
        Map.of(alias, List.of("autoscreentemperature")));
    assertTrue(editor().presentCustom(List.of(adaptive), List.of(platform), catalog).isEmpty());
  }

  @Test public void correctOemRecorderServiceSurvivesWhenThePlatformCandidateIsUnavailable() throws Exception {
    String alias = "custom(com.oplus.screenrecorder/.floatwindow.services.QuickPanelService)";
    Data recording = custom(alias, "画面録画");
    Data platform = data("screenrecord", "画面録画", "Google icon");
    CombinedEditorTiles editor = editor();
    // OOS mapper returns null for this service when the device uses its custom backend.
    CombinedEditorTiles.Catalog customBackend = new CombinedEditorTiles.Catalog(
        Set.of(alias), Set.of(), Map.of(), Map.of());
    List<?> platforms = editor.presentPlatform(List.of(platform), customBackend);
    assertTrue(platforms.isEmpty());
    assertEquals(List.of(recording), editor.presentCustom(List.of(recording), platforms, customBackend));
    // The other ROM branch maps the service to an available platform tile.
    CombinedEditorTiles.Catalog platformBackend = new CombinedEditorTiles.Catalog(
        Set.of("screenrecord"), Set.of(), Map.of(), Map.of(alias, List.of("screenrecord")));
    assertTrue(editor.presentCustom(List.of(recording), List.of(platform), platformBackend).isEmpty());
  }

  @Test public void selectedCustomAliasesStayRemovableEvenWhenTheirCanonicalTargetIsUnavailable() throws Exception {
    String alias = "custom(com.oplus.eyeprotect/.services.AmbientColorAdaptationTileService)";
    Data adaptive = custom(alias, "アダプティブトーン");
    CombinedEditorTiles.Catalog catalog = new CombinedEditorTiles.Catalog(
        Set.of(), Set.of(alias), Map.of(), Map.of(alias, List.of("autoscreentemperature")));
    assertEquals(List.of(adaptive), editor().presentCustom(List.of(adaptive), List.of(), catalog));
  }

  @Test public void unavailableMappedTargetsAreHiddenWithoutFilteringUnmappedServices() throws Exception {
    String alias = "custom(com.oplus.eyeprotect/.services.AmbientColorAdaptationTileService)";
    Data adaptive = custom(alias, "アダプティブトーン");
    Data unrelated = custom("custom(example.app/.TileService)", "Third-party tile");
    CombinedEditorTiles.Catalog catalog = new CombinedEditorTiles.Catalog(
        Set.of(), Set.of(), Map.of(), Map.of(alias, List.of("autoscreentemperature")));
    assertEquals(List.of(unrelated), editor().presentCustom(List.of(adaptive, unrelated), List.of(), catalog));
  }

  @Test public void aliasThatAddsAnUnrepresentedAvailableTargetIsStillUsable() throws Exception {
    String alias = "custom(example.oem/.CombinedService)";
    Data item = custom(alias, "Multiple tiles");
    Data present = data("present", "Present", new Object());
    CombinedEditorTiles.Catalog catalog = new CombinedEditorTiles.Catalog(
        Set.of("present", "additional"), Set.of(), Map.of(),
        Map.of(alias, List.of("present", "additional")));
    assertEquals(List.of(item), editor().presentCustom(List.of(item), List.of(present), catalog));
  }

  @Test public void editorDrawableCannotRetintResizeOrStealTheNormalTilesCallback() {
    GradientDrawable original = new GradientDrawable();
    original.setColor(Color.RED);
    original.setBounds(1, 2, 25, 26);
    original.setState(new int[]{android.R.attr.state_checked});
    View normalTile = new View(RuntimeEnvironment.getApplication());
    original.setCallback(normalTile);
    GradientDrawable copy = (GradientDrawable)CombinedEditorTiles.independentIcon(original,
        RuntimeEnvironment.getApplication().getResources());
    assertNotNull(copy);
    assertNotSame(original, copy);
    assertNull(copy.getCallback());
    copy.setCallback(new View(RuntimeEnvironment.getApplication()));
    copy.setBounds(0, 0, 80, 80);
    copy.setColor(Color.BLUE);
    copy.setTint(Color.GREEN);
    copy.setState(new int[0]);
    assertSame(normalTile, original.getCallback());
    assertEquals(new Rect(1, 2, 25, 26), original.getBounds());
    assertEquals(Color.RED, original.getColor().getDefaultColor());
    assertArrayEquals(new int[]{android.R.attr.state_checked}, original.getState());
    assertNull(original.getColorFilter());
  }

  @Test public void uncloneableDrawablesFallBackWithoutSharingTheOriginal() {
    Drawable original = new Drawable() {
      @Override public void draw(Canvas canvas) {}
      @Override public void setAlpha(int value) {}
      @Override public void setColorFilter(ColorFilter value) {}
      @Override public int getOpacity() { return PixelFormat.TRANSPARENT; }
    };
    View normalTile = new View(RuntimeEnvironment.getApplication());
    original.setCallback(normalTile);
    assertNull(CombinedEditorTiles.independentIcon(original,
        RuntimeEnvironment.getApplication().getResources()));
    assertSame(normalTile, original.getCallback());
    assertNull(CombinedEditorTiles.independentIcon(null,
        RuntimeEnvironment.getApplication().getResources()));
  }

  @Test public void failedNativeQueryDestroysOnlyItsUnfinishedOwnedPlatformTiles() throws Exception {
    CombinedEditorTiles editor = editor();
    Tile liveCurrent = new Tile(false), finished = new Tile(true), orphan = new Tile(false);
    CustomTile custom = new CustomTile();
    Query query = new Query(Map.of("finished", finished, "orphan", orphan, "custom", custom));
    editor.destroyOrphanedTiles(query);
    assertEquals(0, liveCurrent.calls);
    assertEquals(0, finished.calls);
    assertEquals(1, orphan.calls);
    assertTrue(orphan.destroyed);
    assertEquals("Custom tiles remain owned by native clearCacheTile", 0, custom.calls);
    editor.destroyOrphanedTiles(query);
    assertEquals(1, orphan.calls);
  }

  @Test public void oneBrokenTileCannotSkipOtherOrphanedTileCleanup() throws Exception {
    CombinedEditorTiles editor = editor();
    Tile broken = new Tile(false) {
      @Override public void destroy() { throw new IllegalStateException("backend failed"); }
    };
    Tile orphan = new Tile(false);
    Map<String, Tile> cache = new LinkedHashMap<>();
    cache.put("broken", broken);
    cache.put("orphan", orphan);
    assertThrows(ReflectiveOperationException.class, () -> editor.destroyOrphanedTiles(new Query(cache)));
    assertEquals(1, orphan.calls);
  }

  private static Data data(String spec, Object label, Object icon) {
    return new Data(new Spec(spec), icon, label, null, null, "Unknown");
  }

  private static Data custom(String spec, String label) {
    return new Data(new Spec(spec), new Object(), label, "Provider", new Object(), "Utilities");
  }

  private static CombinedEditorTiles editor() throws Exception {
    CombinedEditorTiles editor = new CombinedEditorTiles(null);
    Reflect.field(CombinedEditorTiles.class, "specName").set(editor, Spec.class.getMethod("getSpec"));
    String[][] accessors = {{"dataSpec", "getTileSpec"}, {"dataIcon", "getIcon"},
        {"dataLabel", "getLabel"}, {"dataAppName", "getAppName"},
        {"dataAppIcon", "getAppIcon"}, {"dataCategory", "getCategory"}};
    for (String[] accessor : accessors)
      Reflect.field(CombinedEditorTiles.class, accessor[0]).set(editor, Data.class.getMethod(accessor[1]));
    Reflect.field(CombinedEditorTiles.class, "dataConstructor").set(editor,
        Data.class.getConstructor(Spec.class, Object.class, Object.class,
            Object.class, Object.class, Object.class));
    Reflect.field(CombinedEditorTiles.class, "queryCache").set(editor, Query.class.getField("cache"));
    Reflect.field(CombinedEditorTiles.class, "tileDestroyed").set(editor, Tile.class.getMethod("isDestroyed"));
    Reflect.field(CombinedEditorTiles.class, "destroyTile").set(editor, Tile.class.getMethod("destroy"));
    Reflect.field(CombinedEditorTiles.class, "customTile").set(editor, CustomTile.class);
    return editor;
  }

  public static class Tile {
    boolean destroyed;
    int calls;
    Tile(boolean destroyed) { this.destroyed = destroyed; }
    public boolean isDestroyed() { return destroyed; }
    public void destroy() { destroyed = true; calls++; }
  }
  public static final class CustomTile extends Tile { CustomTile() { super(false); } }
  public static final class Query {
    public final Map<String, Tile> cache;
    Query(Map<String, Tile> cache) { this.cache = cache; }
  }

  public record Spec(String value) { public String getSpec() { return value; } }

  public static final class Data {
    final Spec spec;
    final Object icon, label, appName, appIcon, category;
    public Data(Spec spec, Object icon, Object label, Object appName, Object appIcon, Object category) {
      this.spec = spec; this.icon = icon; this.label = label;
      this.appName = appName; this.appIcon = appIcon; this.category = category;
    }
    public Spec getTileSpec() { return spec; }
    public Object getIcon() { return icon; }
    public Object getLabel() { return label; }
    public Object getAppName() { return appName; }
    public Object getAppIcon() { return appIcon; }
    public Object getCategory() { return category; }
  }
}
