package dev.oos.nativepanels;

import android.app.Fragment;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;


/** Bridges the stock QS composition and its host view in combined mode. */
final class ShadeLayout {
  private final PanelRuntime runtime;
  final NotificationCards cards = new NotificationCards();
  final NotificationStackScrim notificationScrim = new NotificationStackScrim();
  final CombinedTouch touch;
  final CombinedMedia media;
  private WeakReference<Object> headerController = new WeakReference<>(null);
  private WeakReference<Fragment> selected = new WeakReference<>(null);
  private WeakReference<ShadeDecor> decor = new WeakReference<>(null);
  private WeakReference<Object> model = new WeakReference<>(null);
  private Method consume, editing, viewMotion, viewAlpha;
  private final ThreadLocal<ShadeDecor> brightnessBox = new ThreadLocal<>();
  private Object localView;
  private final ThreadLocal<ShadeDecor> headerBox = new ThreadLocal<>();
  private boolean headerSlotInstalled;
  private Method readHeaderSize, writeHeaderSize;
  private Object headerSize;
  private boolean open, lockedShade;
  private boolean measuringExpanded;
  private final java.util.Map<Object, WeakReference<ShadeDecor>> compactScrollBodies = new java.util.WeakHashMap<>();
  private final java.util.Set<Class<?>> compactScrollClasses = new java.util.HashSet<>();
  private final ThreadLocal<CompactScroller> compactScroller = new ThreadLocal<>();
  private int compactWeights, compactScrollers;
  private static final class CompactScroller {
    final ShadeDecor owner;
    boolean sized;
    CompactScroller(ShadeDecor owner) { this.owner = owner; }
  }
  private float qsExpansion, headerTranslation;
  float shadeExpansion, scrimTranslation;
  private String warning = "";

  ShadeLayout(PanelRuntime runtime) { this.runtime = runtime; touch = new CombinedTouch(runtime); media = new CombinedMedia(runtime, this); }
  void select(Fragment fragment) { selected = new WeakReference<>(fragment); }

  void clear() {
    runtime.settingsLaunch.reset();
    media.bind(null);
    touch.reset();
    headerController.clear();
    ShadeDecor old = decor.get();
    if (old != null) old.dispose();
    selected.clear();
    decor.clear();
    model.clear();
    open = false;
    lockedShade = false;
    cards.clear();
    notificationScrim.release();
    shadeExpansion = 0;
    qsExpansion = 0;
    headerTranslation = scrimTranslation = 0;
    warning = "";
    compactScrollBodies.clear();
  }

  void warn(Throwable failure) {
    String message = failure.toString();
    if (message.equals(warning)) return;
    warning = message;
    runtime.module.log(Log.WARN, "OOSNativePanels", "Combined shade layout", failure);
  }

  void install(Class<?> fragment) {
    try { cards.install(runtime); } catch (ReflectiveOperationException e) { warn(e); }
    try { notificationScrim.install(runtime); } catch (ReflectiveOperationException e) { warn(e); }
    try {
      editing = fragment.getDeclaredMethod("isCustomizing");
      Method create = fragment.getDeclaredMethod("onCreateView", LayoutInflater.class, ViewGroup.class, Bundle.class);
      runtime.module.hook(create).setId("native-panels:shade-header")
          .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            if (runtime.combinedQs.status == R.string.combined_selected && result instanceof FrameLayout frame) {
              try {
                ShadeDecor old = decor.get();
                boolean rotated = old != null && old.orientationChangedTo(frame);
                if (old != null) old.dispose();
                Fragment qs = (Fragment) chain.getThisObject();
                select(qs);
                model = new WeakReference<>(Reflect.get(qs, "viewModel"));
                ShadeDecor current = new ShadeDecor(frame, this, runtime);
                decor = new WeakReference<>(current);
                try {
                  Object containerVm = Reflect.call(model.get(), "getContainerViewModel");
                  current.slider = Reflect.call(containerVm, "getBrightnessSliderViewModel");
                } catch (Throwable ignored) { /* Resolve from the owned composition on other branches. */ }
                media.bind(model.get());
                if (rotated) resetGeometry(current, true);
                current.install();
                if (!headerSlotInstalled) frame.addView(current.createHeaderHost(frame.getContext()));
                current.setOpen(open, lockedShade);
                current.expansion(qsExpansion);
                current.headerTranslation(open ? headerTranslation : 0f);
                if (headerController.get() != null) current.bindHeader(headerController.get());
              } catch (Exception e) { warn(e); }
            }
            return result;
          });
      Method expandedElement = fragment.getDeclaredMethod("QuickSettingsElement",
          runtime.type("com.android.compose.animation.scene.ContentScope"), runtime.type("androidx.compose.ui.Modifier"),
          runtime.type("androidx.compose.runtime.Composer"), int.class);
      runtime.module.hook(expandedElement).setId("native-panels:expanded-header-space").intercept(chain -> {
        boolean previous = measuringExpanded;
        measuringExpanded = chain.getThisObject() == selected.get();
        try { return chain.proceed(); } finally { measuringExpanded = previous; }
      });
      runtime.module.deoptimize(expandedElement);
      Class<?> fragmentModel = runtime.type("com.android.systemui.qs.composefragment.viewmodel.QSFragmentComposeViewModel");
      try {
        viewMotion = fragmentModel.getMethod("getViewTranslationY");
        viewAlpha = fragmentModel.getMethod("getViewAlpha");
      } catch (NoSuchMethodException missing) { warn(missing); }
      try {
        headerSize = runtime.type("androidx.compose.runtime.SnapshotStateKt").getMethod("mutableStateOf$default", Object.class).invoke(null, 0L);
        readHeaderSize = runtime.type("androidx.compose.runtime.State").getMethod("getValue");
        writeHeaderSize = runtime.type("androidx.compose.runtime.MutableState").getMethod("setValue", Object.class);
      } catch (ReflectiveOperationException missing) { warn(missing); }
      Method height = fragmentModel.getDeclaredMethod("getQqsHeaderHeight");
      runtime.module.hook(height).setId("native-panels:shade-header-space").intercept(chain -> {
        int original = (Integer) chain.proceed();
        ShadeDecor owner = decor.get();
        if (owner != null && chain.getThisObject() == model.get()) {
          // Subscribe both QQS and QS padding to measured inset changes after rotation.
          if (headerSize != null && readHeaderSize != null) readHeaderSize.invoke(headerSize);
          if (owner.splitLayout()) return owner.gridHeaderHeight();
        }
        if (owner == null || chain.getThisObject() != model.get()) return original;
        int measured = measuringExpanded ? owner.expandedHeight() : owner.headerHeight();
        return owner.compactExpandedLayout() && measuringExpanded ? measured : Math.max(original, measured);
      });
      Method expansion = fragment.getDeclaredMethod("setQsExpansion", float.class, float.class, float.class, float.class);
      runtime.module.hook(expansion).setId("native-panels:slider-expansion").intercept(chain -> {
        if (chain.getThisObject() == selected.get()) {
          float original = (Float) chain.getArg(0);
          float shade = (Float)chain.getArg(1);
          float scene = runtime.combinedQs.expansion.gridFraction(original, shade);
          qsExpansion = runtime.settingsLaunch.sceneDuringExit(scene, shade);
          ShadeDecor current = decor.get();
          float hostTranslation = (Float) chain.getArg(2);
          scrimTranslation = Float.isFinite(hostTranslation) ? hostTranslation : 0f;
          headerTranslation = runtime.settingsLaunch.translationDuringExit(
              runtime.combinedQs.expansion.gridTranslation(hostTranslation));
          if (current != null) {
            current.expansion(qsExpansion);
            current.headerTranslation(headerTranslation);
          }
          if (original != qsExpansion || headerTranslation != hostTranslation)
            return chain.proceed(new Object[]{qsExpansion, chain.getArg(1), headerTranslation, chain.getArg(3)});
        }
        return chain.proceed();
      });
    } catch (Exception e) { warn(e); }
    try {
      Class<?> composer = runtime.type("androidx.compose.runtime.Composer");
      consume = composer.getMethod("consume", runtime.type("androidx.compose.runtime.CompositionLocal"));
      localView = runtime.type("androidx.compose.ui.platform.AndroidCompositionLocals_androidKt")
          .getMethod("getLocalView").invoke(null);
      Method dim = runtime.type("androidx.compose.ui.res.PrimitiveResources_androidKt")
          .getMethod("dimensionResource", int.class, composer, int.class);
      runtime.module.hook(dim).setId("native-panels:shade-tile-dimensions").intercept(chain -> {
        ShadeDecor owner = decor.get();
        if (owner != null) {
          int id = (Integer) chain.getArg(0);
          if (measuringExpanded && id != 0 && id == owner.panelPaddingTop && ownsComposition(owner, chain.getArg(1))) {
            float original = (Float) chain.proceed();
            return owner.compactExpandedLayout() ? Math.min(original, 4f) : expandedPadding(original);
          }
          if (owner.isTileGap(id) && ownsComposition(owner, chain.getArg(1))) return 8f;
        }
        return chain.proceed();
      });
      try { installHeaderSlot(fragment, composer); } catch (Exception e) { warn(e); }
      try { installCompactFooter(fragment, composer); } catch (Exception e) { warn(e); }
      try { media.install(composer); } catch (Exception e) { warn(e); }
      installSlider(composer);
    } catch (Exception e) { warn(e); }
  }

  private void installCompactFooter(Class<?> fragment, Class<?> composer) throws Exception {
    Class<?> modifier = runtime.type("androidx.compose.ui.Modifier");
    Class<?> two = runtime.type("kotlin.jvm.functions.Function2");
    Class<?> three = runtime.type("kotlin.jvm.functions.Function3");
    Object base = modifier.getField("Companion").get(null);
    Class<?> sizes = runtime.type("androidx.compose.foundation.layout.SizeKt");
    Method fillWidth = sizes.getMethod("fillMaxWidth", modifier, float.class);
    Method fillSize = sizes.getMethod("fillMaxSize", modifier, float.class);
    runtime.module.hook(fillSize).setId("native-panels:compact-qs-scroll-height").intercept(chain -> {
      CompactScroller scope = compactScroller.get();
      if (scope != null && !scope.sized && scope.owner == decor.get()
          && scope.owner.compactExpandedLayout() && chain.getArg(0) == base && (Float) chain.getArg(1) == 1f) {
        // The first modifier of the owned scroller is fillMaxSize. Keep its width
        // and the original onPlaced/onSizeChanged/verticalScroll chain, so both
        // the native scroll bounds and pointer coordinates follow its real size.
        scope.sized = true;
        compactScrollers++;
        return fillWidth.invoke(null, base, 1f);
      }
      return chain.proceed();
    });
    // The ROM's scroller calls the Kotlin default bridge. Restore its call to
    // fillMaxSize when ART has already inlined that call into the bridge.
    runtime.module.deoptimize(sizes.getMethod("fillMaxSize$default", modifier,
        float.class, int.class, Object.class));
    Class<?> weightType = runtime.type("androidx.compose.foundation.layout.LayoutWeightElement");
    Method getWeight = weightType.getMethod("getWeight");
    Method then = modifier.getMethod("then", modifier);
    Method fold = modifier.getMethod("foldIn", Object.class, two);
    Class<?> column = runtime.type("androidx.compose.foundation.layout.ColumnScopeInstance");
    Object columnScope = column.getField("INSTANCE").get(null);
    Method weight = column.getMethod("weight", modifier, float.class, boolean.class);
    Class<?> elements = runtime.type("com.android.systemui.qs.shared.ui.QuickSettings$Elements");
    Object contentKey = elements.getMethod("getQuickSettingsContent").invoke(elements.getField("INSTANCE").get(null));
    Method element = runtime.type("com.android.compose.animation.scene.content.ContentScopeImpl")
        .getMethod("Element", runtime.type("com.android.compose.animation.scene.ElementKey"), modifier, three, composer, int.class);
    runtime.module.hook(element).setId("native-panels:compact-qs-footer").intercept(chain -> {
      ShadeDecor owner = decor.get();
      if (!measuringExpanded || owner == null || !owner.compactExpandedLayout()
          || !contentKey.equals(chain.getArg(0)) || !ownsComposition(owner, chain.getArg(3))) return chain.proceed();
      bindCompactScroller(chain.getArg(2), owner);
      Object rebuild = Proxy.newProxyInstance(two.getClassLoader(), new Class<?>[]{two}, (proxy, method, args) ->
          switch (method.getName()) {
            case "invoke" -> weightType.isInstance(args[1])
                ? weight.invoke(columnScope, args[0], getWeight.invoke(args[1]), false)
                : then.invoke(args[0], args[1]);
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "QS content-sized weight";
            default -> throw new UnsupportedOperationException(method.getName());
          });
      Object[] args = chain.getArgs().toArray();
      // fill=false preserves the maximum available scroll height while allowing
      // short content to end next to the native footer. The outer scene still
      // fills the shade and keeps its original gestures and footer callbacks.
      args[1] = fold.invoke(args[1], base, rebuild);
      compactWeights++;
      return chain.proceed(args);
    });
    // R8 can put the scroller body in a static bridge on the fragment.
    for (Method method : fragment.getDeclaredMethods())
      if (method.getName().contains("$r8$lambda$")) runtime.module.deoptimize(method);
  }

  private void bindCompactScroller(Object composable, ShadeDecor owner) throws Exception {
    java.lang.reflect.Field block = Reflect.optionalField(composable.getClass(), "_block");
    Object body = block == null ? composable : block.get(composable);
    if (body == null) return;
    Class<?> type = body.getClass();
    if (!compactScrollClasses.contains(type)) {
      Method invoke = Reflect.method(type, "invoke", Object.class, Object.class, Object.class);
      runtime.module.hook(invoke).setId("native-panels:compact-scroller:" + type.getName()).intercept(chain -> {
        WeakReference<ShadeDecor> reference = compactScrollBodies.get(chain.getThisObject());
        ShadeDecor current = reference == null ? null : reference.get();
        if (current == null || current != decor.get() || !current.compactExpandedLayout()) return chain.proceed();
        CompactScroller previous = compactScroller.get();
        compactScroller.set(new CompactScroller(current));
        try { return chain.proceed(); }
        finally { if (previous == null) compactScroller.remove(); else compactScroller.set(previous); }
      });
      runtime.module.deoptimize(invoke);
      compactScrollClasses.add(type);
    }
    // Bind the actual block instead of wrapping ComposableLambdaImpl: its own
    // restart can run independently of the parent Element composition.
    compactScrollBodies.put(body, new WeakReference<>(owner));
  }

  private void installHeaderSlot(Class<?> fragment, Class<?> composer) throws Exception {
    String scene = "com.android.compose.animation.scene.";
    Class<?> modifier = runtime.type("androidx.compose.ui.Modifier");
    Class<?> one = runtime.type("kotlin.jvm.functions.Function1");
    Method androidView = runtime.type("androidx.compose.ui.viewinterop.AndroidView_androidKt")
        .getMethod("AndroidView", one, modifier, one, composer, int.class, int.class);
    Object base = modifier.getField("Companion").get(null);
    Object width = runtime.type("androidx.compose.foundation.layout.SizeKt")
        .getMethod("fillMaxWidth", modifier, float.class).invoke(null, base, 1f);
    Method sceneLayout = runtime.type(scene + "SceneTransitionLayoutKt").getDeclaredMethod("SceneTransitionLayout",
        runtime.type(scene + "SceneTransitionLayoutState"), modifier, runtime.type(scene + "SceneTransitions"),
        runtime.type(scene + "SwipeSourceDetector"), runtime.type(scene + "SwipeDetector"), float.class, boolean.class,
        String.class, one, composer, int.class, int.class);
    Method content = fragment.getDeclaredMethod("CollapsableQuickSettingsSTL", composer, int.class);
    runtime.module.hook(content).setId("native-panels:qs-header-container").intercept(chain -> {
      ShadeDecor previous = headerBox.get();
      headerBox.set(isSelected(chain.getThisObject()) ? decor.get() : null);
      try { return chain.proceed(); }
      finally { if (previous == null) headerBox.remove(); else headerBox.set(previous); }
    });
    runtime.module.hook(sceneLayout).setId("native-panels:qs-content-header").intercept(chain -> {
      if (!"QuickSettings".equals(chain.getArg(7))) return chain.proceed();
      Object composition = chain.getArg(9);
      ShadeDecor owner = headerBox.get();
      if (owner == null) return chain.proceed();
      // Internal restarts of SceneTransitionLayout only redraw its own subtree.
      // Emit the sibling only at the original call site in the outer Box.
      headerBox.remove();
      try {
        Object result = chain.proceed();
        // The original call emits the scene inside Content's Box. Emit a single
        // wrap-content-height AndroidView beside it, still INSIDE the same placed,
        // alpha-modified Box. Inner scene restarts never emit a second header.
        if (owner.headerFactory == null) {
          owner.headerFactory = Proxy.newProxyInstance(one.getClassLoader(), new Class<?>[]{one}, (proxy, m, args) ->
              switch (m.getName()) {
                case "invoke" -> owner.createHeaderHost((android.content.Context) args[0]);
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "QS header";
                default -> throw new UnsupportedOperationException(m.getName());
              });
        }
        androidView.invoke(null, owner.headerFactory, width, null, composition, 0, 4);
        return result;
      } finally { headerBox.set(owner); }
    });
    runtime.module.deoptimize(content);
    headerSlotInstalled = true;
  }

  private boolean ownsComposition(ShadeDecor owner, Object composer) {
    try {
      View view = (View) consume.invoke(composer, localView);
      for (; view != null; view = view.getParent() instanceof View parent ? parent : null)
        if (view == owner.frame) return true;
    } catch (Exception e) { warn(e); }
    return false;
  }

  private void installSlider(Class<?> composer) throws Exception {
    Class<?> modifier = runtime.type("androidx.compose.ui.Modifier");
    String prefix = "com.android.systemui.brightness.ui.compose.";
    Class<?> sliderModel = runtime.type("com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel");
    Method container = runtime.type(prefix + "BrightnessSliderKt").getDeclaredMethod("BrightnessSliderContainer",
        sliderModel, modifier,
        runtime.type(prefix + "ContainerColors"), runtime.type(prefix + "BrightnessSliderDimensions"), composer, int.class, int.class);
    Method makeState = runtime.type("androidx.compose.runtime.SnapshotStateKt").getMethod("mutableStateOf$default", Object.class);
    Method readState = runtime.type("androidx.compose.runtime.State").getMethod("getValue");
    Method writeState = runtime.type("androidx.compose.runtime.MutableState").getMethod("setValue", Object.class);
    runtime.module.hook(container).setId("native-panels:shade-brightness").intercept(chain -> {
      ShadeDecor previous = brightnessBox.get();
      ShadeDecor owner = compositionOwner(chain.getArg(4));
      if (owner != null) {
        owner.slider = chain.getArg(0);
        owner.preview.attachState(makeState, readState, writeState);
      }
      brightnessBox.set(owner);
      try { return chain.proceed(); }
      finally {
        if (previous == null) brightnessBox.remove();
        else brightnessBox.set(previous);
      }
    });
    try { installInlineButton(runtime.type(prefix + "BrightnessSliderKt"), modifier, composer); }
    catch (ReflectiveOperationException e) { warn(e); }

    // In this implementation setIsDragging only publishes the mirror flag. Brightness
    // preview and commit use onDrag, independently of the mirror visibility interactor.
    Method drag = sliderModel.getDeclaredMethod("setIsDragging", boolean.class);
    runtime.module.hook(drag).setId("native-panels:qs-brightness-inline").intercept(chain -> {
      ShadeDecor owner = decor.get();
      if (owner == null || owner.slider != chain.getThisObject()) return chain.proceed();
      boolean start = (Boolean) chain.getArg(0);
      if (start && !owner.dragging) {
        owner.dragStarts++;
        owner.preview.start();
      }
      if (!start) {
        if (owner.dragging) owner.preview.reset();
        owner.mirrorClears++;
        owner.brightnessBand = -1;
      }
      owner.dragging = start;
      return start ? chain.proceed(new Object[]{false}) : chain.proceed();
    });
    // Only the slider composable should see this snapshot state. The fragment's
    // getShowingMirror() also consults this getter, but installs gesturesDisabled
    // on the entire QS when true, cancelling the finger already on the slider.
    runtime.module.hook(sliderModel.getDeclaredMethod("getShowMirror"))
        .setId("native-panels:systemui-brightness-overlay").intercept(chain -> {
          ShadeDecor owner = brightnessBox.get();
          if (owner != null && owner.slider == chain.getThisObject()) return owner.preview.requested();
          return chain.proceed();
        });
    runtime.module.deoptimize(container);
    try {
      runtime.module.deoptimize(runtime.type(prefix + "BrightnessSliderKt$$ExternalSyntheticLambda4")
          .getDeclaredMethod("invoke", Object.class));
    } catch (ReflectiveOperationException ignored) { /* Synthetic name varies between ROMs. */ }
  }

  private interface TileColorReader {
    ShadeDecor.ButtonColors read(Object composition) throws ReflectiveOperationException;
  }

  private TileColorReader tileColorReader(Class<?> composer) {
    try {
      String pkg = "com.android.systemui.qs.panels.ui.compose.infinitegrid.";
      Class<?> defaults = runtime.type(pkg + "TileDefaults");
      Object instance = defaults.getField("INSTANCE").get(null);
      Method active = defaults.getMethod("activeTileColors", composer, int.class);
      Method inactive = defaults.getMethod("inactiveTileColors", composer, int.class);
      Class<?> colors = active.getReturnType();
      Method background = colors.getMethod("getBackground-0d7_KjU");
      Method icon = colors.getMethod("getIcon-0d7_KjU");
      Method toArgb = runtime.type("androidx.compose.ui.graphics.ColorKt").getMethod("toArgb-8_81llA", long.class);
      return composition -> {
        Object on = active.invoke(instance, composition, 0);
        Object off = inactive.invoke(instance, composition, 0);
        return new ShadeDecor.ButtonColors(
            (Integer)toArgb.invoke(null, background.invoke(on)), (Integer)toArgb.invoke(null, icon.invoke(on)),
            (Integer)toArgb.invoke(null, background.invoke(off)), (Integer)toArgb.invoke(null, icon.invoke(off)));
      };
    } catch (ReflectiveOperationException unavailable) {
      warn(unavailable);
      return null;
    }
  }

  // BrightnessSliderContainer emits its track directly inside a padded Box. Emit
  // our AndroidView in that same Box, after the track, so the scene lays out,
  // clips and fades both together. The child's own restart only redraws the track.
  private void installInlineButton(Class<?> sliders, Class<?> modifier, Class<?> composer)
      throws ReflectiveOperationException {
    Class<?> one = runtime.type("kotlin.jvm.functions.Function1");
    Class<?> alignment = runtime.type("androidx.compose.ui.Alignment");
    Object base = modifier.getField("Companion").get(null);
    Object centerEnd = alignment.getField("Companion").get(null);
    centerEnd = centerEnd.getClass().getMethod("getCenterEnd").invoke(centerEnd);
    Class<?> scope = runtime.type("androidx.compose.foundation.layout.BoxScopeInstance");
    Object edge = scope.getMethod("align", modifier, alignment).invoke(scope.getField("INSTANCE").get(null), base, centerEnd);
    Object buttonModifier = runtime.type("androidx.compose.foundation.layout.SizeKt")
        .getMethod("size-3ABfNKs", modifier, float.class).invoke(null, edge, 48f);
    Object trackPadding = runtime.type("androidx.compose.foundation.layout.PaddingKt")
        .getMethod("padding-qDBjuR0", modifier, float.class, float.class, float.class, float.class)
        .invoke(null, base, 0f, 0f, 56f, 0f);
    Method then = modifier.getMethod("then", modifier);
    Method androidView = runtime.type("androidx.compose.ui.viewinterop.AndroidView_androidKt")
        .getMethod("AndroidView", one, modifier, one, composer, int.class, int.class);
    TileColorReader tileColors = tileColorReader(composer);
    Object unit = runtime.type("kotlin.Unit").getField("INSTANCE").get(null);
    Method track = sliders.getDeclaredMethod("BrightnessSlider", int.class, runtime.type("kotlin.ranges.IntRange"),
        one, runtime.type("kotlin.jvm.functions.Function3"), runtime.type("com.android.systemui.util.policy.PolicyRestriction"),
        one, one, one, boolean.class, modifier, boolean.class, runtime.type("kotlin.jvm.functions.Function0"),
        runtime.type("com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel$Factory"),
        runtime.type("com.android.systemui.brightness.ui.compose.BrightnessSliderDimensions"), composer,
        int.class, int.class, int.class);
    runtime.module.hook(track).setId("native-panels:brightness-box-controls").intercept(chain -> {
      ShadeDecor owner = brightnessBox.get();
      if (owner == null || !owner.hasAutomaticBrightness) return chain.proceed();
      Object[] args = chain.getArgs().toArray();
      if (owner.buttonFactory == null) {
        owner.buttonFactory = Proxy.newProxyInstance(one.getClassLoader(), new Class<?>[]{one}, (proxy, m, values) -> {
          return switch (m.getName()) {
            case "invoke" -> owner.createBrightnessButton((android.content.Context) values[0]);
            case "equals" -> proxy == values[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "QS automatic brightness";
            default -> throw new UnsupportedOperationException(m.getName());
          };
        });
      }
      int defaults = (Integer) args[17];
      args[9] = then.invoke(trackPadding, (defaults & (1 << 9)) != 0 ? base : args[9]);
      args[15] = (Integer) args[15] & ~0x70000000;
      args[17] = defaults & ~(1 << 9);
      // Scope the insertion to the immediate container-to-track call.
      brightnessBox.remove();
      try {
        Object result = chain.proceed(args);
        // Read the same composition locals as the QS grid. AndroidView's update
        // runs for both an existing button and one created by a restarted scene.
        Object update = null;
        if (tileColors != null) {
          try {
            ShadeDecor.ButtonColors colors = tileColors.read(args[14]);
            update = Proxy.newProxyInstance(one.getClassLoader(), new Class<?>[]{one}, (proxy, m, values) ->
                switch (m.getName()) {
                  case "invoke" -> {
                    owner.setButtonColors((android.widget.ImageButton) values[0], colors);
                    yield unit;
                  }
                  case "equals" -> proxy == values[0];
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "toString" -> "QS tile colors";
                  default -> throw new UnsupportedOperationException(m.getName());
                });
          } catch (ReflectiveOperationException unavailable) { warn(unavailable); }
        }
        androidView.invoke(null, owner.buttonFactory, buttonModifier, update, args[14], 0, update == null ? 4 : 0);
        return result;
      } finally { brightnessBox.set(owner); }
    });
  }

  void setOpen(Fragment fragment, boolean value, boolean restricted) {
    if (fragment != selected.get()) return;
    runtime.settingsLaunch.panelState(open, value, restricted);
    open = value;
    lockedShade = restricted;
    ShadeDecor owner = decor.get();
    if (owner != null) owner.setOpen(value, restricted);
  }

  void measuredHeader(int compact, int expanded) {
    if (headerSize != null && writeHeaderSize != null) try {
      writeHeaderSize.invoke(headerSize, ((long)compact << 32) | expanded);
    } catch (ReflectiveOperationException e) { warn(e); }
  }

  void resetGeometry(ShadeDecor owner, boolean rotated) {
    if (owner != decor.get()) return;
    headerTranslation = scrimTranslation = 0f;
    touch.cancelSplitGesture();
    if (rotated) {
      if (runtime.settingsLaunch.isTracking(owner.frame)) runtime.settingsLaunch.interruptForTouch();
      // ScrollState belongs to the fragment and can survive a configuration change.
      // A previous split offset must not shift the first portrait grid or vice versa.
      Fragment fragment = selected.get();
      if (fragment != null) try {
        Object scroll = Reflect.field(fragment.getClass(), "scrollState").get(fragment);
        int value = (Integer)Reflect.method(scroll.getClass(), "getValue").invoke(scroll);
        if (value != 0) Reflect.method(scroll.getClass(), "dispatchRawDelta", float.class).invoke(scroll, (float)-value);
      } catch (ReflectiveOperationException e) { warn(e); }
      notificationScrim.endPull();
    }
  }

  float contentTranslation() throws ReflectiveOperationException {
    Object vm = model.get();
    return vm == null || viewMotion == null ? Float.NaN : (Float) viewMotion.invoke(vm);
  }

  float qsAlpha() throws Exception {
    Object vm = model.get();
    return vm == null || viewAlpha == null ? 0f : (Float)viewAlpha.invoke(vm);
  }

  boolean isSelected(Object fragment) { return fragment != null && fragment == selected.get(); }

  ShadeDecor compositionOwner(Object composer) {
    ShadeDecor owner = decor.get();
    return owner != null && ownsComposition(owner, composer) ? owner : null;
  }

  ShadeDecor hapticOwner() {
    ShadeDecor owner = decor.get();
    return owner != null && owner.frame.isAttachedToWindow() ? owner : null;
  }

  boolean hasCombinedGrid() { return selected.get() != null && decor.get() != null; }

  boolean gridVisible() {
    ShadeDecor current = decor.get();
    return open && current != null && current.frame.isAttachedToWindow() && current.frame.isShown();
  }

  float sceneFraction() { return qsExpansion; }
  float contentOffset() { return headerTranslation; }

  boolean active() { return !lockedShade && gridVisible(); }

  ShadeDecor touchOwner() { return active() ? decor.get() : null; }

  void bindHeader(Fragment fragment, Object controller) {
    if (selected.get() != fragment || controller == null) return;
    if (headerController.get() != controller) headerController = new WeakReference<>(controller);
    ShadeDecor owner = decor.get();
    if (owner != null) owner.bindHeader(controller);
  }

  String report() {
    ShadeDecor current = decor.get();
    return current == null ? "QS layout: no frame" : current.report() + " qsExpansion=" + qsExpansion
        + " compactWeight/scroll=" + compactWeights + "/" + compactScrollers + "\n" + touch.report() + "\n" + media.report() + "\nQS notification scrim: " + notificationScrim.report() + "\nQS notification cards: " + cards.report() + "\n" + runtime.settingsLaunch.report() + (warning.isEmpty() ? "" : "\nQS layout warning: " + warning);
  }

  boolean isEditing() throws Exception {
    Fragment fragment = selected.get();
    return fragment == null || (Boolean) editing.invoke(fragment);
  }
  static float expandedPadding(float originalDp) { return Math.min(originalDp, 8f); }

}
