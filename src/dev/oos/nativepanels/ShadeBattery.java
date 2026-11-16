package dev.oos.nativepanels;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;

/** The Compose battery used by ShadeHeaderKt, embedded in our header's AndroidView. */
final class ShadeBattery {
  private static final String PIPELINE = "com.android.systemui.statusbar.pipeline.battery.";
  private final PanelRuntime r;
  final View view;
  private final Context context;
  private final Object tintState, percentState, modifier, localDensity, unit;
  private final Method get, set, remember, consume, toDp, height, batteryHeight, layout, description;
  private final Method level, full, glyphs, attribution, profile, contentDescription, dark, light;
  private final Method toArgb, pack;
  private final Constructor<?> modelConstructor;
  private final Object interactor, companion, create, levelSource, fullSource, glyphSource, colorSource;
  private final Object normalDark, normalLight;
  private Object model;
  private final java.util.Map<Class<?>, Object> themedPalettes = new java.util.HashMap<>();

  private interface Block { Object invoke(Object[] args) throws Throwable; }

  private Object function(Class<?> type, Block block) {
    return Proxy.newProxyInstance(r.loader, new Class<?>[]{type}, (proxy, method, args) -> switch (method.getName()) {
      case "invoke" -> block.invoke(args);
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == args[0];
      case "toString" -> "QS battery " + type.getSimpleName();
      default -> throw new UnsupportedOperationException(method.getName());
    });
  }

  private final float sizeFactor;
  ShadeBattery(PanelRuntime r, Context context, int tint) throws Throwable {
    this(r, context, tint, .75f);
  }
  ShadeBattery(PanelRuntime r, Context context, int tint, float sizeFactor) throws Throwable {
    this.sizeFactor = sizeFactor;
    this.r = r;
    this.context = context;
    Class<?> zero = r.type("kotlin.jvm.functions.Function0"), two = r.type("kotlin.jvm.functions.Function2");
    Class<?> composer = r.type("androidx.compose.runtime.Composer");
    Class<?> base = r.type("androidx.compose.ui.Modifier");
    modifier = base.getField("Companion").get(null);
    unit = r.type("kotlin.Unit").getField("INSTANCE").get(null);
    Class<?> viewType = r.type("androidx.compose.ui.platform.ComposeView");
    view = (View)viewType.getConstructor(Context.class).newInstance(context);
    Method state = r.type("androidx.compose.runtime.SnapshotStateKt").getMethod("mutableStateOf$default", Object.class);
    get = r.type("androidx.compose.runtime.State").getMethod("getValue");
    set = r.type("androidx.compose.runtime.MutableState").getMethod("setValue", Object.class);
    tintState = state.invoke(null, tint);
    percentState = state.invoke(null, true);

    Class<?> vm = r.type(PIPELINE + "ui.viewmodel.BatteryViewModel");
    Class<?> domain = r.type(PIPELINE + "domain.interactor.BatteryInteractor");
    interactor = r.provider("batteryInteractorProvider");
    modelConstructor = r.type(vm.getName() + "$AlwaysShowPercent").getConstructor(domain, Context.class);
    remember = r.type("com.android.systemui.lifecycle.SysUiViewModelKt").getMethod("rememberViewModel", String.class,
        Object.class, r.type("kotlin.coroutines.CoroutineContext"), zero, composer, int.class, int.class);
    create = function(zero, args -> modelConstructor.newInstance(interactor, context));
    level = vm.getMethod("getLevel");
    full = vm.getMethod("isFull");
    glyphs = vm.getMethod("getGlyphList");
    attribution = vm.getMethod("getAttribution");
    profile = vm.getMethod("getColorProfile");
    contentDescription = vm.getMethod("getContentDescription");
    Class<?> profiles = profile.getReturnType();
    dark = profiles.getMethod("getDark");
    light = profiles.getMethod("getLight");
    String colors = PIPELINE + "shared.ui.BatteryColors";
    normalDark = r.type(colors + "$DarkTheme$Default").getField("INSTANCE").get(null);
    normalLight = r.type(colors + "$LightTheme$Default").getField("INSTANCE").get(null);
    Class<?> composeColor = r.type("androidx.compose.ui.graphics.ColorKt");
    pack = composeColor.getMethod("Color", int.class);
    toArgb = composeColor.getMethod("toArgb-8_81llA", long.class);
    // Each palette delegates glyph contrast and obtains layer opacity from the ROM.
    Class<?> palette = r.type(colors);
    makePalette(palette, normalDark);
    makePalette(palette, normalLight);

    levelSource = function(zero, args -> level.invoke(model));
    fullSource = function(zero, args -> full.invoke(model));
    glyphSource = function(zero, args -> (boolean)get.invoke(percentState) ? glyphs.invoke(model) : Collections.emptyList());
    colorSource = function(zero, args -> {
      int foreground = (int)get.invoke(tintState);
      Object nativeColors = (CombinedStatusIcons.darkIntensity(foreground) == 0f ? dark : light).invoke(profile.invoke(model));
      return themedPalettes.getOrDefault(nativeColors.getClass(), nativeColors);
    });

    companion = vm.getField("Companion").get(null);
    batteryHeight = companion.getClass().getMethod("getStatusBarBatteryHeight-kPz2Gy4", Context.class);
    localDensity = r.type("androidx.compose.ui.platform.CompositionLocalsKt").getMethod("getLocalDensity").invoke(null);
    consume = composer.getMethod("consume", r.type("androidx.compose.runtime.CompositionLocal"));
    toDp = r.type("androidx.compose.ui.unit.FontScaling").getMethod("toDp-GaN1DYA", long.class);
    height = r.type("androidx.compose.foundation.layout.SizeKt").getMethod("height-3ABfNKs", base, float.class);
    description = r.type("com.android.systemui.common.ui.compose.ContentDescriptionKt").getMethod("load",
        contentDescription.getReturnType(), composer, int.class);
    layout = r.type(PIPELINE + "ui.composable.UnifiedBatteryKt").getMethod("BatteryLayout", attribution.getReturnType(),
        zero, zero, zero, zero, base, String.class, composer, int.class, int.class);
    viewType.getMethod("setContent", two).invoke(view, function(two, args -> {
      render(args[0]);
      return unit;
    }));
  }

  private void render(Object composer) throws Throwable {
    // Default mask 6 selects Unit and LocalCoroutineContext, as in BatteryWithEstimate.
    // rememberViewModel starts collection on composition entry and cancels it on disposal.
    model = Reflect.invoke(remember, null, "BatteryWithEstimate", null, null, create, composer, 0, 6);
    float dp = (float)toDp.invoke(consume.invoke(composer, localDensity), batteryHeight.invoke(companion, context));
    Object sized = height.invoke(null, modifier, dp * sizeFactor);
    String label = (String)description.invoke(null, contentDescription.invoke(model), composer, 0);
    Reflect.invoke(layout, null, attribution.invoke(model), levelSource, fullSource, glyphSource, colorSource,
        sized, label == null ? "" : label, composer, 0, 0);
  }

  private void makePalette(Class<?> type, Object nativeColors) {
    Object colors = Proxy.newProxyInstance(r.loader, new Class<?>[]{type}, (proxy, method, args) -> switch (method.getName()) {
      case "getFill-0d7_KjU", "getBackgroundOnly-0d7_KjU", "getBackgroundWithGlyph-0d7_KjU", "getAttribution-0d7_KjU" -> {
        int nativeArgb = (int)toArgb.invoke(null, method.invoke(nativeColors));
        yield pack.invoke(null, tone((int)get.invoke(tintState), nativeArgb));
      }
      case "getGlyph-0d7_KjU" -> method.invoke(nativeColors);
      case "equals" -> proxy == args[0];
      case "hashCode" -> System.identityHashCode(proxy);
      case "toString" -> "QS battery colors";
      default -> method.invoke(nativeColors, args);
    });
    themedPalettes.put(nativeColors.getClass(), colors);
  }

  static int tone(int foreground, int layer) {
    int alpha = (Color.alpha(layer) * Color.alpha(foreground) + 127) / 255;
    return (foreground & 0x00ffffff) | (alpha << 24);
  }

  void tint(int color) throws ReflectiveOperationException { set.invoke(tintState, color); }
  void showDigits(boolean inside) throws ReflectiveOperationException { set.invoke(percentState, inside); }
}
