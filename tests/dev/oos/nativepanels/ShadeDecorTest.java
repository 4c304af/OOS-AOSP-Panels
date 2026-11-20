package dev.oos.nativepanels;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.os.BatteryManager;
import android.view.View;
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
}
