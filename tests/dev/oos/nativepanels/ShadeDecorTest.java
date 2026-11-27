package dev.oos.nativepanels;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.os.BatteryManager;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "ja-rJP")
public class ShadeDecorTest {
  @Test public void brightnessPreviewRestoresLayersOnUpAndCancelWithoutTakingInput() {
    Context context = RuntimeEnvironment.getApplication();
    FrameLayout frame = new FrameLayout(context);
    ShadeDecor decor = new ShadeDecor(frame, null, null);
    android.view.View layer = new android.view.View(context);
    for (int action : new int[]{android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL}) {
      android.view.MotionEvent start = android.view.MotionEvent.obtain(10, 10, android.view.MotionEvent.ACTION_DOWN, 24, 30, 0);
      android.view.MotionEvent end = android.view.MotionEvent.obtain(10, 20, action, 26, 30, 0);
      decor.preview.down(start);
      layer.setAlpha(.7f);
      layer.setTransitionAlpha(.85f);
      decor.preview.fade(layer);
      decor.preview.fade(layer);
      assertEquals(.7f, layer.getAlpha(), .001f);
      assertEquals(0f, layer.getTransitionAlpha(), .001f);
      assertEquals(android.view.View.VISIBLE, layer.getVisibility());
      decor.preview.finish(end);
      assertEquals(.85f, layer.getTransitionAlpha(), .001f);
      decor.preview.reset();
      assertEquals(.85f, layer.getTransitionAlpha(), .001f);
      start.recycle(); end.recycle();
    }
  }

  @Test public void nativeMediaBridgeOnlyReplacesItsOwnEntries() {
    Object first = new Object(), nativeUpdate = new Object();
    assertTrue(CombinedMedia.canSupply(null, null));
    assertTrue(CombinedMedia.canSupply(first, first));
    assertFalse(CombinedMedia.canSupply(nativeUpdate, null));
    assertFalse(CombinedMedia.canSupply(nativeUpdate, first));
  }

  @Test public void brightnessButtonIsBornInTheSliderAndSharesItsParentThroughoutExpansion() {
    Context context = RuntimeEnvironment.getApplication();
    FrameLayout frame = new FrameLayout(context);
    ShadeDecor decor = new ShadeDecor(frame, null, null);
    decor.install();
    FrameLayout slot = new FrameLayout(context);
    android.widget.ImageButton button = decor.createBrightnessButton(context);
    slot.addView(button);
    for (float progress : new float[]{0f, .1f, .5f, .98f, 1f, .5f, 0f}) {
      decor.expansion(progress);
      assertSame(slot, button.getParent());
      assertEquals(android.view.View.VISIBLE, button.getVisibility());
      assertEquals(0, frame.getChildCount()); // The QS frame has no header overlay.
    }
    android.widget.ImageButton replacement = decor.createBrightnessButton(context);
    assertNotSame(button, replacement);
    assertNull(replacement.getParent());
    decor.dispose();
  }

  @Test public void expandedSpacerAccountsForPanelPaddingWithoutCoveringTheHeader() {
    // Portrait keeps 8dp of the panel's 16dp padding under the date. Split only trims 2dp
    // of that padding, and honors a header already reserved by the surrounding pane.
    assertEquals(208, ShadeDecor.expandedSpacer(200, 16, 8) + 16);
    assertEquals(62, ShadeDecor.expandedSpacer(48, 16, 2) + 16);
    assertEquals(0, ShadeDecor.expandedSpacer(0, 16, 2));
    assertEquals(200, ShadeDecor.expandedSpacer(200, 4, 8) + 4);
    assertEquals(200, ShadeDecor.expandedSpacer(200, 0, 8));
  }

  @Test public void automaticModeChangesBothButtonShapeAndSelection() throws Exception {
    Context c = RuntimeEnvironment.getApplication();
    ShadeDecor decor = new ShadeDecor(new FrameLayout(c), null, null);
    var automatic = ShadeDecor.class.getDeclaredField("automatic");
    var paint = ShadeDecor.class.getDeclaredMethod("paintButton", android.widget.ImageButton.class);
    automatic.setAccessible(true);
    paint.setAccessible(true);
    android.widget.ImageButton view = decor.createBrightnessButton(c);
    float inactive = 0;
    for (boolean selected : new boolean[]{false, true}) {
      automatic.setBoolean(decor, selected);
      paint.invoke(decor, view);
      var ripple = (android.graphics.drawable.RippleDrawable) view.getBackground();
      float radius = ((android.graphics.drawable.GradientDrawable) ripple.getDrawable(0)).getCornerRadius();
      assertEquals(selected, view.isSelected());
      if (selected) assertTrue(radius < inactive);
      else inactive = radius;
    }
  }

  @Test public void brightnessColorsFollowTheTilePaletteWhenThemeOrModeChanges() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    ShadeDecor decor = new ShadeDecor(new FrameLayout(context), null, null);
    decor.install();
    android.widget.ImageButton button = decor.createBrightnessButton(context);
    var state = ShadeDecor.class.getDeclaredField("automatic");
    var repaint = ShadeDecor.class.getDeclaredMethod("paintButton", android.widget.ImageButton.class);
    var style = ShadeDecor.class.getDeclaredMethod("style");
    state.setAccessible(true);
    repaint.setAccessible(true);
    style.setAccessible(true);
    state.setBoolean(decor, true);
    var light = new ShadeDecor.ButtonColors(0xff805060, 0xffffffff, 0xaaeee3e4, 0xff201a1b);
    var dark = new ShadeDecor.ButtonColors(0xffffaec9, 0xff491629, 0x99302528, 0xffeadfe1);
    for (var colors : new ShadeDecor.ButtonColors[]{light, dark, light}) {
      decor.setButtonColors(button, colors);
      style.invoke(decor); // Opening the shade must not overwrite the Compose palette.
      for (boolean active : new boolean[]{true, false, true}) {
        state.setBoolean(decor, active);
        repaint.invoke(decor, button);
        var ripple = (android.graphics.drawable.RippleDrawable) button.getBackground();
        var fill = (android.graphics.drawable.GradientDrawable) ripple.getDrawable(0);
        assertEquals(active ? colors.activeBackground() : colors.inactiveBackground(), fill.getColor().getDefaultColor());
        assertEquals(active ? colors.activeIcon() : colors.inactiveIcon(), button.getImageTintList().getDefaultColor());
      }
    }
  }

  @Test public void launcherUsesMaskedForegroundAndMonochrome() {
    Context c = RuntimeEnvironment.getApplication();
    AdaptiveIconDrawable icon = (AdaptiveIconDrawable) c.getDrawable(c.getApplicationInfo().icon);
    assertNotNull(icon.getBackground());
    assertNotNull(icon.getForeground());
    assertNotNull(icon.getMonochrome());
  }

  @Test public void headerFollowsClosingAndReversingWithoutWaitingForQsExpansion() {
    Context c = RuntimeEnvironment.getApplication();
    FrameLayout frame = new FrameLayout(c);
    ShadeDecor decor = new ShadeDecor(frame, null, null);
    decor.install();
    decor.setOpen(true, false);
    decor.headerTranslation(-20f);
    FrameLayout host = decor.createHeaderHost(c);
    assertEquals(-20f, host.getTranslationY(), .001f);
    for (float y : new float[]{0f, -16f, -43f, -21f, -61f, 0f}) {
      decor.headerTranslation(y);
      assertEquals(y, host.getTranslationY(), .001f);
      assertEquals(decor.headerHeight(), host.getLayoutParams().height);
    }
    decor.expansion(.5f);
    assertTrue(host.getLayoutParams().height > decor.headerHeight());
    assertTrue(host.getLayoutParams().height < decor.expandedHeight());
    android.view.View icons = host.getChildAt(0);
    FrameLayout recreated = decor.createHeaderHost(c);
    assertEquals(0, host.getChildCount());
    assertSame(icons, recreated.getChildAt(0));
    decor.dispose();
    assertEquals(0, recreated.getChildCount());
  }

  @Test public void nativeContainerOffsetIsOnlyAppliedOnce() {
    assertEquals(-45f, ShadeDecor.remainingTranslation(-60f, -15f), .001f);
    assertEquals(0f, ShadeDecor.remainingTranslation(-60f, -60f), .001f);
    assertEquals(0f, ShadeDecor.remainingTranslation(-60f, -82f), .001f);
    assertEquals(20f, ShadeDecor.remainingTranslation(35f, 15f), .001f);
    assertEquals(0f, ShadeDecor.remainingTranslation(0f, -10f), .001f);
  }

  @Test public void onlyOosContainerGlyphsAreReplacedInThePrimaryMediaButton() {
    assertTrue(CombinedMedia.isOosTransportContainer("qs_media_container_action_pause"));
    assertTrue(CombinedMedia.isOosTransportContainer("qs_media_container_action_play"));
    assertFalse(CombinedMedia.isOosTransportContainer("ic_media_play_button"));
    assertFalse(CombinedMedia.isOosTransportContainer("qs_media_container_action_next"));
    assertFalse(CombinedMedia.isOosTransportContainer(null));
  }

  @Test public void compressedMediaHeightTracksBothDirectionsAndKeepsItsEndpoints() {
    for (float t : new float[]{0f, .25f, .6f, 1f, .6f, .25f, 0f})
      assertEquals(100f + 80f * t, CombinedMedia.mediaHeight(100f,180f,t), .001f);
    assertEquals(100f, CombinedMedia.mediaHeight(100f,180f,Float.NaN), .001f);
  }

  @Test public void expandedClockHasItsOwnSpaceAndReturnsToCollapsed() throws Exception {
    Context c = RuntimeEnvironment.getApplication();
    FrameLayout frame = new FrameLayout(c);
    ShadeDecor decor = new ShadeDecor(frame, null, null);
    decor.install();
    assertTrue(decor.expandedHeight() > decor.headerHeight());
    int nativeHeader = decor.expandedHeight() + 100;
    assertEquals(nativeHeader, decor.sceneHeaderHeight(nativeHeader, false));
    assertEquals(decor.expandedGridHeaderHeight(false), decor.sceneHeaderHeight(nativeHeader, true));
    FrameLayout content = decor.createHeaderHost(c);
    TextClock big = (TextClock)content.getChildAt(1);
    TextClock small = (TextClock)((LinearLayout)content.getChildAt(0)).getChildAt(0);
    decor.expansion(1f);
    decor.positionHeader(Float.NaN, 0f);
    assertEquals(android.view.View.VISIBLE, big.getVisibility());
    assertTrue(big.getTextSize() > small.getTextSize() * 2);
    assertEquals(0f, small.getAlpha(), .001f);
    decor.expansion(0f);
    decor.positionHeader(Float.NaN, 0f);
    assertEquals(android.view.View.INVISIBLE, big.getVisibility());
    assertEquals(1f, small.getAlpha(), .001f);
    decor.dispose();
  }

  public static final class ClockGraph {
    int launches;
    final ClockGraph clockInteractorProvider = this;
    public ClockGraph get() { return this; }
    public void launchClockActivity() { launches++; }
  }

  @Test public void onlyTheVisibleClockOpensTheSystemClockAction() {
    var activity = org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();
    var runtime = new PanelRuntime(null, getClass().getClassLoader());
    var graph = new ClockGraph();
    runtime.graph = graph;
    ShadeDecor decor = new ShadeDecor(new FrameLayout(activity), null, runtime);
    decor.install();
    FrameLayout host = decor.createHeaderHost(activity);
    activity.setContentView(host);
    LinearLayout row = (LinearLayout)host.getChildAt(0);
    View small = row.getChildAt(0), date = row.getChildAt(1), large = host.getChildAt(1);
    decor.setOpen(true, false);
    for (float progress : new float[]{0f, .3f, .7f, 1f, 0f}) {
      decor.expansion(progress);
      decor.positionHeader(Float.NaN, 0f);
      host.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(decor.expandedHeight(), View.MeasureSpec.EXACTLY));
      host.layout(0, 0, 900, decor.expandedHeight());
      View active = progress < .5f ? small : large;
      View inactive = active == small ? large : small;
      assertTrue(active.isClickable() && active.isFocusable());
      assertEquals("android.widget.Button", active.getAccessibilityClassName());
      assertFalse(inactive.isClickable() || inactive.isFocusable());
      assertFalse(date.isClickable());
      int[] xy = new int[2];
      active.getLocationInWindow(xy);
      assertTrue(decor.hitClock(xy[0] + 1, xy[1] + 1));
      assertFalse(decor.hitClock(899, xy[1] + 1));
      int before = graph.launches;
      inactive.performClick();
      date.performClick();
      assertEquals(before, graph.launches);
      int[] origin = new int[2];
      host.getLocationInWindow(origin);
      float x = xy[0] - origin[0] + 1, y = xy[1] - origin[1] + 1;
      dispatchTap(host, x, y, MotionEvent.ACTION_CANCEL);
      assertEquals(before, graph.launches);
      dispatchTap(host, x, y, MotionEvent.ACTION_UP);
      assertEquals(before + 1, graph.launches);
    }
    runtime.graph = new ClockGraph();
    decor.setOpen(true, true);
    decor.positionHeader(1f, 0f);
    large.performClick();
    assertEquals(1, ((ClockGraph)runtime.graph).launches);
    decor.setOpen(false, false);
    large.performClick();
    assertEquals(1, ((ClockGraph)runtime.graph).launches);
    runtime.graph = null;
    decor.positionHeader(1f, 0f);
    assertFalse(large.hasOnClickListeners());
    decor.dispose();
  }

  private static void dispatchTap(View host, float x, float y, int endAction) {
    long t = android.os.SystemClock.uptimeMillis();
    for (int action : new int[]{MotionEvent.ACTION_DOWN, endAction}) {
      MotionEvent event = MotionEvent.obtain(t, action == MotionEvent.ACTION_DOWN ? t : t + 30, action, x, y, 0);
      try { host.dispatchTouchEvent(event); }
      finally { event.recycle(); }
    }
    Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
  }

  @Test public void directLockPullMovesAndFadesTheExpandedClockWithoutMorphingFromTheSmallClock() {
    Context context = RuntimeEnvironment.getApplication();
    ShadeDecor decor = new ShadeDecor(new FrameLayout(context), null, null);
    decor.install();
    FrameLayout host = decor.createHeaderHost(context);
    View clock = host.getChildAt(1), status = host.getChildAt(0);
    decor.positionHeader(1f, 0f);
    float restingClock = clock.getTranslationY(), restingStatus = status.getTranslationY();
    float density = context.getResources().getDisplayMetrics().density;
    for (float p : new float[]{.25f, .58f, .79f, .93f, .79f, .25f, 1f}) {
      float inherited = -12 * (1 - p) * density;
      decor.positionHeader(p, inherited);
      assertEquals(restingClock - (1 - p) * Math.round(48 * density), clock.getTranslationY() + inherited, .001f);
      assertEquals(restingStatus - (1 - p) * Math.round(72 * density), status.getTranslationY() + inherited, .001f);
      float alpha = Math.max(0f, (p - .58f) / .42f);
      assertEquals(alpha, clock.getAlpha() * p, .001f);
      assertEquals(alpha, status.getAlpha() * p, .001f);
      assertEquals(1f, clock.getScaleY(), .001f);
    }
    decor.positionHeader(Float.NaN, 0);
    assertEquals(View.INVISIBLE, clock.getVisibility());
    assertEquals(1f, status.getAlpha(), .001f);
    decor.dispose();
  }

  @Test public void japaneseDateAndBatteryAreBoundToTheFrameLifecycle() {
    Context c = RuntimeEnvironment.getApplication();
    FrameLayout frame = new FrameLayout(c);
    ShadeDecor decor = new ShadeDecor(frame, null, null);
    decor.install();
    FrameLayout content = decor.createHeaderHost(c);
    LinearLayout header = (LinearLayout) content.getChildAt(0);
    assertTrue(header.getLayoutParams().height >= decor.headerHeight());
    assertEquals(android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.JAPAN, "MMMEd"),
        ((TextClock)header.getChildAt(1)).getFormat24Hour());
    decor.setOpen(true, false);
    decor.onViewAttachedToWindow(frame);
    c.sendBroadcast(new Intent(Intent.ACTION_BATTERY_CHANGED)
        .putExtra(BatteryManager.EXTRA_LEVEL, 17).putExtra(BatteryManager.EXTRA_SCALE, 20));
    Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    assertEquals("85%", ((TextView) header.getChildAt(2)).getText().toString());
    decor.onViewDetachedFromWindow(frame);
    c.sendBroadcast(new Intent(Intent.ACTION_BATTERY_CHANGED)
        .putExtra(BatteryManager.EXTRA_LEVEL, 10).putExtra(BatteryManager.EXTRA_SCALE, 20));
    Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    assertEquals("85%", ((TextView) header.getChildAt(2)).getText().toString());
    decor.dispose();
    assertEquals(0, frame.getChildCount());
  }

  @Test public void android17ExpandedHeaderReservesStableTopSpaceAndReleasesCompactClockSpace() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    float density = context.getResources().getDisplayMetrics().density;
    int width = Math.round(360f * density);
    int inset = Math.round(44f * density);
    int breathing = Math.round(8f * density);
    int height = Math.round(600f * density);
    FrameLayout root = new FrameLayout(context);
    root.layout(0, 0, width, height);
    FrameLayout frame = new FrameLayout(context);
    root.addView(frame);
    frame.layout(0, 0, width, height);
    ShadeDecor decor = new ShadeDecor(frame, null, null);
    var style = ShadeDecor.class.getDeclaredField("aospStatusBar");
    style.setAccessible(true);
    style.setBoolean(decor, true);
    decor.install();
    FrameLayout host = decor.createHeaderHost(context);
    TextClock expandedClock = (TextClock) host.getChildAt(1);
    LinearLayout statusRow = (LinearLayout) host.getChildAt(0);
    TextClock compactClock = (TextClock) statusRow.getChildAt(0);
    TextClock date = (TextClock) statusRow.getChildAt(1);
    compactClock.setText("12:34");
    var measureInsets = ShadeDecor.class.getDeclaredMethod("measureHeaderInsets", WindowInsets.class);
    measureInsets.setAccessible(true);
    decor.expansion(1f);
    decor.positionHeader(Float.NaN, 0f);
    assertEquals(statusRow.getPaddingTop() + breathing, expandedClock.getTranslationY(), .001f);
    decor.expansion(0f);
    Rect center = new Rect(width / 2 - 8, 0, width / 2 + 8, inset);
    measureInsets.invoke(decor, new WindowInsets.Builder()
        .setInsetsIgnoringVisibility(WindowInsets.Type.statusBars(), Insets.of(0, inset, 0, 0))
        .setDisplayCutout(new DisplayCutout(Insets.of(0, inset, 0, 0), null, center, null, null))
        .build());
    assertEquals(inset, statusRow.getPaddingTop());
    // QQS and QS must both reserve the rows we own, even if the ROM keeps its
    // former large-clock height. Otherwise the scene interpolates a temporary void.
    int nativeHeight = decor.expandedHeight() + Math.round(120f * density);
    assertEquals(decor.headerHeight(), decor.sceneHeaderHeight(nativeHeight, false));
    assertEquals(decor.expandedGridHeaderHeight(false), decor.sceneHeaderHeight(nativeHeight, true));
    for (float progress : new float[]{0f, .25f, .49f, .5f, .8f, 1f, .8f, .49f, 0f}) {
      decor.expansion(progress);
      root.setTranslationY(-Math.round(60f * density) * progress);
      frame.setTranslationY(-Math.round(12f * density) * progress);
      measureInsets.invoke(decor, new WindowInsets.Builder()
          .setInsetsIgnoringVisibility(WindowInsets.Type.statusBars(), Insets.of(0, inset, 0, 0))
          .build());
      decor.positionHeader(Float.NaN, 0f);
      assertEquals(inset, statusRow.getPaddingTop());
      assertEquals(decor.headerHeight() + Math.round((decor.expandedHeight() - decor.headerHeight()) * progress),
          host.getLayoutParams().height);
      assertEquals(decor.headerHeight(), decor.sceneHeaderHeight(nativeHeight, false));
    }
    root.setTranslationY(0f);
    frame.setTranslationY(0f);
    decor.positionHeader(Float.NaN, 0f);
    host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(decor.headerHeight(), View.MeasureSpec.EXACTLY));
    host.layout(0, 0, width, decor.headerHeight());
    int compactDateWidth = date.getMeasuredWidth();

    decor.expansion(1f);
    decor.positionHeader(Float.NaN, 0f);
    int compactExpandedHeight = host.getLayoutParams().height;
    assertTrue("Expanded header should follow its rows, not a fixed 120dp scene minimum",
        compactExpandedHeight - inset - breathing < Math.round(120f * density));
    assertEquals(inset + breathing, expandedClock.getTranslationY(), .001f);
    assertTrue(expandedClock.getLayoutParams().height >= Math.round(48f * density));
    float rowTop = statusRow.getTranslationY() + statusRow.getPaddingTop();
    float clockBottom = expandedClock.getTranslationY() + expandedClock.getLayoutParams().height;
    assertEquals(Math.round(4f * density), rowTop - clockBottom, .001f);
    assertEquals(Math.round(4f * density),
        compactExpandedHeight - statusRow.getTranslationY() - statusRow.getLayoutParams().height, .001f);
    host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(decor.expandedHeight(), View.MeasureSpec.EXACTLY));
    host.layout(0, 0, width, decor.expandedHeight());
    assertEquals(View.GONE, compactClock.getVisibility());
    assertEquals(statusRow.getPaddingLeft(), date.getLeft());
    assertTrue(date.getMeasuredWidth() > compactDateWidth);

    Rect sideCutout = new Rect(0, 0, center.width(), inset);
    measureInsets.invoke(decor, new WindowInsets.Builder()
        .setInsetsIgnoringVisibility(WindowInsets.Type.statusBars(), Insets.of(0, inset, 0, 0))
        .setDisplayCutout(new DisplayCutout(Insets.of(0, inset, 0, 0), null, sideCutout, null, null))
        .build());
    decor.positionHeader(Float.NaN, 0f);
    assertEquals(compactExpandedHeight, host.getLayoutParams().height);
    assertEquals(inset + breathing, expandedClock.getTranslationY(), .001f);

    // A lockscreen pull uses the upstream direct motion without changing the
    // compact resting clock/status positions or their 4dp gap.
    float restingClock = expandedClock.getTranslationY(), restingStatus = statusRow.getTranslationY();
    for (float progress : new float[]{.25f, .58f, .79f, 1f, .79f, .25f}) {
      float inherited = -12f * density * (1f - progress);
      decor.positionHeader(progress, inherited);
      assertEquals(restingClock - (1f - progress) * Math.round(48f * density),
          expandedClock.getTranslationY() + inherited, .001f);
      assertEquals(restingStatus - (1f - progress) * Math.round(72f * density),
          statusRow.getTranslationY() + inherited, .001f);
      assertEquals(1f, expandedClock.getScaleY(), .001f);
    }
    decor.positionHeader(Float.NaN, 0f);

    WindowInsets statusOnly = new WindowInsets.Builder()
        .setInsetsIgnoringVisibility(WindowInsets.Type.statusBars(), Insets.of(0, inset, 0, 0))
        .build();
    measureInsets.invoke(decor, statusOnly);
    decor.positionHeader(Float.NaN, 0f);
    assertEquals(compactExpandedHeight, host.getLayoutParams().height);
    assertEquals(inset + breathing, expandedClock.getTranslationY(), .001f);

    frame.layout(0, inset, width, height);
    measureInsets.invoke(decor, statusOnly);
    decor.positionHeader(Float.NaN, 0f);
    assertEquals(0, statusRow.getPaddingTop());
    assertEquals(breathing, expandedClock.getTranslationY(), .001f);
    assertEquals(compactExpandedHeight - inset, host.getLayoutParams().height);

    decor.expansion(0f);
    decor.positionHeader(Float.NaN, 0f);
    assertEquals(View.INVISIBLE, expandedClock.getVisibility());
    assertEquals(View.VISIBLE, compactClock.getVisibility());
    assertEquals(decor.headerHeight(), host.getLayoutParams().height);
    decor.dispose();
  }
}
