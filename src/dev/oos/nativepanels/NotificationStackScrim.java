package dev.oos.nativepanels;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.view.RoundedCorner;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Paint the notification surface under the panel children in the native scrim's coordinates. */
final class NotificationStackScrim {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path shape = new Path();
  private final Rect bounds = new Rect();
  private final int[] hostPos = new int[2], scrimPos = new int[2];
  private final float[] radii = new float[8];
  private Class<?> panelType, windowType;
  private View layer, drawHost;
  private final PullOrigin motion = new PullOrigin();
  private Field lastClip, screenCorner, minHeight;
  private Method surfaceColor;
  private Object controller;
  private View panel, nativeScrim, stack;
  private int stackId;
  private boolean splitShade;
  private boolean effectiveSplit;
  private SplitShadeChrome.Surface splitLayer;
  private String lastColumn = "none";
  private int scrimId, firstTop, minTop, maxTop, draws;
  private int rawTop, paintedTop, rawFirst, rawMin, rawMax, lastDrawAlpha;
  private float shadeFraction, firstShade;
  private int firstAlpha;
  private String firstHost = "none";
  private boolean installed, visible, tracking;
  private String problem = "";

  void install(PanelRuntime runtime) throws ReflectiveOperationException {
    Class<?> qs = runtime.type("com.android.systemui.shade.QuickSettingsControllerImpl");
    // applyClippingImmediately writes this rectangle before converting to stack-local coordinates.
    lastClip = Reflect.field(qs, "mLastClipBounds");
    screenCorner = Reflect.optionalField(qs, "mScreenCornerRadius");
    minHeight = Reflect.optionalField(qs, "mMinExpansionHeight");
    panelType = runtime.type("com.android.systemui.shade.NotificationPanelView");
    try { windowType = runtime.type("com.android.systemui.shade.NotificationShadeWindowView"); }
    catch (ClassNotFoundException absent) { /* Use the panel renderer on other shade branches. */ }
    try {
      surfaceColor = runtime.type("com.android.systemui.shade.ui.ShadeColors").getMethod(
          "classicShadeNotificationScrimBg", Context.class, boolean.class, boolean.class);
    } catch (ClassNotFoundException | NoSuchMethodException unavailable) {
      // Framework palette fallback for a SystemUI branch without ShadeColors.
    }
    runtime.module.hook(panelType.getDeclaredMethod("dispatchDraw", Canvas.class))
        .setId("native-panels:notification-panel-scrim")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          if (drawHost == chain.getThisObject()) drawSurface((Canvas)chain.getArg(0));
          return chain.proceed();
        });
    Class<?> listType = runtime.type("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout");
    runtime.module.hook(listType.getDeclaredMethod("dispatchDraw", Canvas.class))
        .setId("native-panels:split-notification-clip")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          View view = (View)chain.getThisObject();
          if (splitLayer == null || !splitLayer.forList(view)) return chain.proceed();
          Canvas canvas = (Canvas)chain.getArg(0);
          int save = canvas.save();
          try {
            splitLayer.clipRows(canvas);
            return chain.proceed();
          } finally { canvas.restoreToCount(save); }
        });
    runtime.module.hook(listType.getMethod("dispatchTouchEvent", android.view.MotionEvent.class))
        .setId("native-panels:split-list-hit").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          android.view.MotionEvent event = (android.view.MotionEvent)chain.getArg(0);
          if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && splitLayer != null
              && splitLayer.forList((View)chain.getThisObject()) && !splitLayer.containsTouch(event.getX(), event.getY())) return false;
          return chain.proceed();
        });
    runtime.module.hook(listType.getMethod("updateTopPadding", float.class, boolean.class))
        .setId("native-panels:split-row-padding")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          if (splitLayer == null || !splitLayer.ownsList((View)chain.getThisObject())) return chain.proceed();
          float value = (float)chain.getArg(0);
          float effective = splitLayer.onSystemPadding(value);
          return value == effective ? chain.proceed() : chain.proceed(new Object[]{effective, chain.getArg(1)});
        });
    Field bottomPadding = Reflect.optionalField(listType, "mBottomPadding");
    Field content = Reflect.optionalField(listType, "mContentHeight");
    Field layoutHeight = Reflect.optionalField(listType, "mMaxLayoutHeight");
    if (content != null && layoutHeight != null) {
      runtime.module.hook(listType.getMethod("getScrollRange")).setId("native-panels:split-scroll-bottom")
          .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            int original = (int)chain.proceed();
            View view = (View)chain.getThisObject();
            if (splitLayer == null || !splitLayer.forList(view)) return original;
            return scrollRange(original, content.getInt(view), layoutHeight.getInt(view), splitLayer.scrollEnd(bottomPadding == null ? 0 : bottomPadding.getInt(view)));
          });
    }
    installed = true;
  }

  static int scrollRange(int original, int content, int nativeHeight, int visibleBottom) {
    if (visibleBottom <= 0 || visibleBottom >= nativeHeight) return original;
    return Math.max(original, Math.min(original + nativeHeight - visibleBottom, content - visibleBottom));
  }

  void attachLayer(View frame) {
    if (windowType == null || !frame.isAttachedToWindow() || layer != null) return;
    View branch = frame;
    while (branch.getParent() instanceof ViewGroup parent) {
      if (windowType.isInstance(parent)) {
        int index = parent.indexOfChild(branch);
        if (index < 0) return;
        // Use the shade window's own default full-size LayoutParams, as its inset handler expects.
        View surface = new View(parent.getContext()) {
          @Override protected void onDraw(Canvas canvas) {
            if (this == drawHost) drawSurface(canvas);
          }
        };
        surface.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        surface.setVisibility(View.INVISIBLE);
        parent.addView(surface, index);
        layer = surface;
        return;
      }
      branch = parent;
    }
  }

  private void drawSurface(Canvas canvas) {
    if (!visible) return;
    canvas.drawPath(shape, paint);
    motion.presented(shadeFraction);
    draws++;
    if (!tracking) {
      rawFirst = rawMin = rawMax = rawTop;
      firstTop = minTop = maxTop = paintedTop;
      firstShade = shadeFraction;
      firstAlpha = paint.getAlpha();
      firstHost = drawHost == layer ? "window" : "panel";
      tracking = true;
    } else {
      rawMin = Math.min(rawMin, rawTop); rawMax = Math.max(rawMax, rawTop);
      minTop = Math.min(minTop, paintedTop); maxTop = Math.max(maxTop, paintedTop);
    }
    lastDrawAlpha = paint.getAlpha();
  }

  // Several expansion samples may arrive before a display list is actually recorded.
  // Commit the origin only after a positive-alpha surface draw, then map the remaining travel.
  static final class PullOrigin {
    private boolean pending;
    private float origin;
    void begin() { origin = 0f; pending = true; }
    void reset() { origin = 0f; pending = false; }
    float progress(float shade) {
      if (!Float.isFinite(shade) || shade <= 0f) return 0f;
      if (shade >= 1f) return 1f;
      return pending ? 0f : Math.max(0f, (shade - origin) / (1f - origin));
    }
    void presented(float shade) {
      if (pending || shade >= 1f) origin = shade > 0f && shade < 1f ? shade : 0f;
      pending = false;
    }
  }

  void beginPull() { motion.begin(); }

  void bind(Object qs, boolean split) {
    if (qs != controller || split != splitShade) {
      hide();
      stack = null;
      motion.reset();
    }
    controller = qs;
    splitShade = split;
  }

  boolean isSplit() { return effectiveSplit; }
  boolean splitFor(View frame) { return SplitShadeChrome.split(frame, splitShade); }

  private void releaseColumn() {
    if (splitLayer == null) return;
    View old = splitLayer;
    splitLayer.hideColumn();
    if (old.getParent() instanceof ViewGroup parent) {
      if (!parent.isAttachedToWindow() || parent.isInLayout())
        parent.post(() -> { if (old.getParent() == parent) parent.removeView(old); });
      else parent.removeView(old);
    }
    splitLayer = null;
  }

  private void updateColumn(View frame, int alpha) throws ReflectiveOperationException {
    if (stack == null || !stack.isAttachedToWindow() || stack.getRootView() != frame.getRootView()) {
      stack = null;
      try {
        Object owner = Reflect.field(controller.getClass(), "mNotificationStackScrollLayoutController").get(controller);
        Object candidate = owner == null ? null : Reflect.method(owner.getClass(), "getView").invoke(owner);
        if (candidate instanceof View v && v.getRootView() == frame.getRootView()) stack = v;
      } catch (ReflectiveOperationException unavailable) { /* Look up the live view in this shade window below. */ }
      if (stack == null) {
        if (stackId == 0) stackId = frame.getResources().getIdentifier("notification_stack_scroller", "id", Protocol.TARGET);
        stack = stackId == 0 ? null : frame.getRootView().findViewById(stackId);
      }
    }
    if (stack == null || !stack.isShown() || !(stack.getParent() instanceof ViewGroup parent)) {
      hide(); problem = "waiting for live notification stack"; return;
    }
    if (splitLayer == null || splitLayer.getParent() != parent || !splitLayer.ownsList(stack)) {
      releaseColumn();
      splitLayer = new SplitShadeChrome.Surface(parent, stack);
    }
    if (splitLayer.place(stack, frame, resolveColor(frame.getContext()), alpha)) {
      problem = "";
    } else problem = "waiting for column layout";
  }

  void update(View frame, float shade, float qs, float headerTranslation) {
    if (!installed || controller == null) return;
    effectiveSplit = splitFor(frame);
    int alpha = surfaceAlpha(shade, qs, effectiveSplit);
    if (alpha == 0) { hide(); return; }
    try {
      if (effectiveSplit) {
        if (visible) hide();
        updateColumn(frame, alpha);
        return;
      }
      if (splitLayer != null) releaseColumn();
      if (panel == null || !panel.isAttachedToWindow() || panel.getRootView() != frame.getRootView()) {
        hide();
        View parent = frame;
        while (parent != null && !panelType.isInstance(parent))
          parent = parent.getParent() instanceof View p ? p : null;
        panel = parent;
        if (panel == null) { problem = "no shade panel"; return; }
        nativeScrim = stack = null;
      }
      View host = layer != null && layer.isAttachedToWindow() && layer.getRootView() == frame.getRootView() &&
          layer.getWidth() > 0 && layer.getHeight() > 0 ? layer : panel;
      if (host == panel && !panel.isShown()) { hide(); return; }
      if (drawHost != host) { hide(); drawHost = host; }
      if (nativeScrim == null || !nativeScrim.isAttachedToWindow()) {
        if (scrimId == 0) scrimId = panel.getResources().getIdentifier("scrim_notifications", "id", panel.getContext().getPackageName());
        nativeScrim = scrimId == 0 ? null : panel.getRootView().findViewById(scrimId);
      }
      host.getLocationInWindow(hostPos);
      View root = panel.getRootView();
      Rect src = (Rect)lastClip.get(controller);
      int headerHeight = minHeight == null ? 0 : minHeight.getInt(controller);
      int l, r, b, destination;
      if (src != null && !src.isEmpty() && nativeScrim != null) {
        nativeScrim.getLocationInWindow(scrimPos);
        int dx = scrimPos[0] - hostPos[0];
        l = src.left + dx; r = src.right + dx;
        b = src.bottom + scrimPos[1] - hostPos[1];
        destination = Math.max(0, src.top + scrimPos[1]);
      } else {
        // The first expansion update can precede applyClippingImmediately.
        if (headerHeight <= 0 || host.getWidth() <= 0 || root.getHeight() <= 0) {
          hide(); problem = "waiting for shade geometry"; return;
        }
        l = 0; r = host.getWidth(); b = root.getHeight() - hostPos[1]; destination = headerHeight;
      }
      rawTop = destination;
      shadeFraction = shade;
      paintedTop = revealTop(destination, headerHeight, motion.progress(shade), headerTranslation);
      int t = paintedTop - hostPos[1];
      if (t >= b || l >= r) { hide(); return; }
      Context context = panel.getContext();
      float openRadius = 32f * context.getResources().getDisplayMetrics().density;
      float screenRadius = screenCorner != null ? screenCorner.getInt(controller) : openRadius;
      WindowInsets insets = panel.getRootWindowInsets();
      float leftCorner = cornerAtTop(topRadius(insets, true, screenRadius), openRadius, paintedTop);
      float rightCorner = cornerAtTop(topRadius(insets, false, screenRadius), openRadius, paintedTop);
      int color = (resolveColor(context) & 0x00ffffff) | (alpha << 24);
      if (!visible || l != bounds.left || t != bounds.top || r != bounds.right || b != bounds.bottom ||
          leftCorner != radii[0] || rightCorner != radii[2] || color != paint.getColor()) {
        bounds.set(l, t, r, b);
        radii[0] = radii[1] = leftCorner;
        radii[2] = radii[3] = rightCorner;
        shape.rewind();
        shape.addRoundRect(l, t, r, b, radii, Path.Direction.CW);
        paint.setColor(color);
        visible = true;
        if (host == layer && layer.getVisibility() != View.VISIBLE) layer.setVisibility(View.VISIBLE);
        host.invalidate();
      }
      problem = "";
    } catch (ReflectiveOperationException | RuntimeException e) { hide(); problem = e.toString(); }
  }

  // All positions here are relative to the shade window, before converting to panel-local Y.
  // A ROM may pin the clip to QQS height, or already move it. Never pass an earlier native edge.
  static int revealTop(int nativeTop, int headerHeight, float shade, float translation) {
    if (!Float.isFinite(shade) || shade <= 0f || nativeTop <= 0) return 0;
    float travel = Math.min(1f, shade);
    if (headerHeight > 0 && Float.isFinite(translation))
      travel = Math.min(travel, Math.max(0f, 1f + translation / headerHeight));
    int extent = Math.max(nativeTop, headerHeight);
    return Math.min(nativeTop, Math.round(extent * travel));
  }

  private static float topRadius(WindowInsets insets, boolean left, float fallback) {
    if (Build.VERSION.SDK_INT >= 31 && insets != null) {
      RoundedCorner c = insets.getRoundedCorner(left ? RoundedCorner.POSITION_TOP_LEFT : RoundedCorner.POSITION_TOP_RIGHT);
      if (c != null) return c.getRadius();
    }
    return fallback;
  }

  static float cornerAtTop(float screen, float open, float y) {
    if (open <= 0) return 0;
    float progress = Math.max(0f, Math.min(1f, y / open));
    return screen + (open - screen) * progress;
  }

  private int resolveColor(Context context) {
    boolean dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
        == Configuration.UI_MODE_NIGHT_YES;
    if (surfaceColor != null) {
      try { return (Integer)surfaceColor.invoke(null, context, true, false); }
      catch (ReflectiveOperationException unavailable) { surfaceColor = null; }
    }
    return context.getColor(dark ? android.R.color.system_accent1_800 : android.R.color.system_accent1_100);
  }

  static int surfaceAlpha(float shade, float qs, boolean split) {
    // In split QS stays expanded alongside the list. Use the visible portrait
    // notification surface at the same shade pull, including its opacity.
    return Math.round(128f * fraction(shade, split ? 0f : qs));
  }

  static float fraction(float shade, float qs) {
    if (!Float.isFinite(shade) || !Float.isFinite(qs)) return 0f;
    return Math.max(0f, Math.min(1f, shade / .3f)) * (1f - Math.max(0f, Math.min(1f, qs)));
  }

  void hide() {
    if (splitLayer != null) {
      if (splitLayer.getVisibility() == View.VISIBLE) lastColumn = splitLayer.status();
      splitLayer.hideColumn();
    }
    if (!visible) return;
    visible = false;
    if (drawHost == layer && layer != null) layer.setVisibility(View.INVISIBLE);
    if (drawHost != null) drawHost.invalidate();
  }

  void endPull() {
    hide();
    motion.reset();
    tracking = false;
  }

  void release() {
    endPull();
    View old = layer;
    layer = drawHost = panel = nativeScrim = stack = null;
    releaseColumn();
    effectiveSplit = splitShade = false;
    controller = null;
    if (old != null && old.getParent() instanceof ViewGroup parent)
      parent.post(() -> { if (old.getParent() == parent) parent.removeView(old); });
  }

  String report() {
    return !installed ? "unavailable" : "draws=" + draws + " raw(first/min/max)=" + rawFirst + "/" + rawMin + "/" + rawMax +
        " painted(first/min/max)=" + firstTop + "/" + minTop + "/" + maxTop + " firstShade=" + firstShade +
        " splitColumn=" + (splitLayer == null ? lastColumn : splitLayer.status()) + " firstHost=" + firstHost + " firstAlpha=" + firstAlpha + " radius=" + Math.round(radii[0]) + "/" + Math.round(radii[2]) + " lastDrawAlpha=" + lastDrawAlpha +
        (problem.isEmpty() ? "" : " " + problem);
  }
}
