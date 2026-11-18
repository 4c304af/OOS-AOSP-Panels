package dev.oos.nativepanels;

import static org.junit.Assert.*;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CombinedInputTest {
  public static final class SubscriptionController {
    int subscription = 1, registrations, removals;
    public void addIconGroup(LinearLayout group) {
      View signal = new View(group.getContext());
      signal.setTag(subscription);
      group.addView(signal);
      registrations++;
    }
    public void removeIconGroup(LinearLayout group) {
      group.removeAllViews();
      removals++;
    }
  }

  @Test public void movingHeaderBetweenHostsRebindsCurrentSubscriptionWhileShadeStaysAttached() throws Exception {
    var activity = org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().visible().get();
    LinearLayout shade = new LinearLayout(activity), first = new LinearLayout(activity),
        second = new LinearLayout(activity), group = new LinearLayout(activity);
    activity.setContentView(shade);
    shade.addView(first);
    shade.addView(second);
    first.addView(group);
    var runtime = new PanelRuntime(null, getClass().getClassLoader());
    var icons = new CombinedStatusIcons(runtime, group, new View(activity));
    Reflect.field(CombinedStatusIcons.class, "icons").set(icons, group);
    Reflect.field(CombinedStatusIcons.class, "manager").set(icons, group);
    var controller = new SubscriptionController();
    icons.observeGroup(controller, LinearLayout.class);
    assertEquals(1, group.getChildAt(0).getTag());
    first.removeView(group);
    assertTrue(shade.isAttachedToWindow());
    assertEquals(0, group.getChildCount());
    controller.subscription = 2;
    second.addView(group);
    assertEquals(1, group.getChildCount());
    assertEquals(2, group.getChildAt(0).getTag());
    icons.dispose();
    second.removeView(group);
    first.addView(group);
    assertEquals(2, controller.registrations);
    assertEquals(2, controller.removals);
    assertEquals(0, group.getChildCount());
  }

  @Test public void cellularInsetUsesLogicalEndAndRestoresOemPadding() {
    for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
      LinearLayout icons = new LinearLayout(org.robolectric.RuntimeEnvironment.getApplication());
      icons.setLayoutDirection(direction);
      icons.setPaddingRelative(3, 4, 2, 5);
      HomeStatusBattery.EndPadding padding = new HomeStatusBattery.EndPadding(icons);
      padding.set(6);
      padding.set(6);
      assertEquals(8, icons.getPaddingEnd());
      assertEquals(8, direction == View.LAYOUT_DIRECTION_RTL ? icons.getPaddingLeft() : icons.getPaddingRight());
      padding.set(0);
      assertEquals(2, icons.getPaddingEnd());
      icons.setPaddingRelative(7, 10, 11, 12);
      padding.set(6);
      assertEquals(17, icons.getPaddingEnd());
      padding.set(0);
      assertEquals(11, icons.getPaddingEnd());
      assertEquals(7, icons.getPaddingStart());
      assertEquals(10, icons.getPaddingTop());
      assertEquals(12, icons.getPaddingBottom());
    }
  }

  @Test public void chargeIndicatorStaysCollapsedWhenOosChangesTheCharger() {
    var context = org.robolectric.RuntimeEnvironment.getApplication();
    for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
      LinearLayout row = new LinearLayout(context);
      row.setLayoutDirection(direction);
      View battery = new View(context), percent = new View(context);
      ImageView charge = new ImageView(context);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(16, 20);
      params.setMarginStart(3);
      params.setMarginEnd(2);
      row.addView(battery, 30, 20);
      row.addView(charge, params);
      row.addView(percent, 12, 20);
      HomeStatusBattery.ChargingIndicator indicator = new HomeStatusBattery.ChargingIndicator(charge);
      assertEquals(63, wrapWidth(row));
      assertTrue(indicator.setHidden(true));
      charge.setVisibility(View.GONE);
      assertEquals(42, wrapWidth(row));
      charge.setImageResource(android.R.drawable.ic_lock_idle_charging);
      charge.setVisibility(View.VISIBLE);
      assertFalse(indicator.setHidden(true));
      assertEquals(42, wrapWidth(row));
      assertEquals(0, charge.getMeasuredWidth());
      assertEquals(new Rect(), charge.getClipBounds());
      assertTrue(indicator.setHidden(false));
      assertSame(params, charge.getLayoutParams());
      assertNull(charge.getClipBounds());
      assertEquals(63, wrapWidth(row));
    }
  }

  @Test public void chargeIndicatorRestoresLatestUnpluggedStateAndOriginalClip() {
    ImageView charge = new ImageView(org.robolectric.RuntimeEnvironment.getApplication());
    charge.setLayoutParams(new LinearLayout.LayoutParams(20, 20));
    Rect initial = new Rect(1, 2, 18, 19);
    charge.setClipBounds(initial);
    var indicator = new HomeStatusBattery.ChargingIndicator(charge);
    assertTrue(indicator.setHidden(true));
    charge.setVisibility(View.GONE);
    assertTrue(indicator.setHidden(false));
    assertEquals(View.GONE, charge.getVisibility());
    assertEquals(initial, charge.getClipBounds());
    assertFalse(indicator.setHidden(false));
    assertFalse(new HomeStatusBattery.ChargingIndicator(null).setHidden(true));
  }

  private int wrapWidth(LinearLayout row) {
    row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
    return row.getMeasuredWidth();
  }

  @Test public void batteryWrapperKeepsItsCompactWidthAtEachDensity() {
    assertEquals(98, HomeStatusBattery.slotWidth(100, 1f));
    assertEquals(93, HomeStatusBattery.slotWidth(100, 3.5f));
    assertEquals(0, HomeStatusBattery.slotWidth(2, 3.5f));
  }

  @Test public void homeBatteryAppliesDispatcherOpacityOnceDuringTransitions() {
    assertEquals(.8f, HomeStatusBattery.artworkAlpha(0xccffffff, 1f, 1f), .001f);
    assertEquals(.2f, HomeStatusBattery.artworkAlpha(0xccffffff, .5f, .5f), .001f);
    assertEquals(0f, HomeStatusBattery.artworkAlpha(0xe6000000, 0f, 1f), 0f);
    assertEquals(1f, HomeStatusBattery.artworkAlpha(0xff112233, 1f, 1f), 0f);
  }

  @Test public void collapsedClipDoesNotRejectEveryPointBelowItsTop() {
    assertFalse(CombinedTouch.excludes(new Path(), 32, 140));
    Path band = new Path();
    band.addRect(0, 0, 500, 100, Path.Direction.CW);
    assertTrue(CombinedTouch.excludes(band, 32, 20));
    assertFalse(CombinedTouch.excludes(band, 32, 140));
  }

  @Test public void roundedClipRespectsVisibleCorner() {
    Path band = new Path();
    band.addRoundRect(0, 0, 200, 200, 80, 80, Path.Direction.CW);
    assertFalse(CombinedTouch.excludes(band, 2, 2));
    assertTrue(CombinedTouch.excludes(band, 99, 99));
  }

  @Test public void verticalCollapseRemainsAvailableAfterTileDown() {
    assertFalse(CombinedTouch.verticalDrag(1, 3, 8));
    assertFalse(CombinedTouch.verticalDrag(-60, 22, 8));
    assertTrue(CombinedTouch.verticalDrag(14, -64, 8));
  }

  @Test public void offlineDiagnosticSurvivesAnEmptyResponse() {
    var c = org.robolectric.RuntimeEnvironment.getApplication();
    DiagnosticSnapshot.remember(c, "QS touch: parentPass=1");
    DiagnosticSnapshot.remember(c, null);
    var saved = DiagnosticSnapshot.read(c);
    assertTrue(saved.time() > 0);
    assertEquals("QS touch: parentPass=1", saved.text());
  }

  @Test public void brightnessFeedbackUsesDistinctEndpointsAndThrottlesDenseMoves() {
    int start = CombinedHaptics.band(0f), middle = CombinedHaptics.band(.5f), end = CombinedHaptics.band(1f);
    assertNotEquals(start, CombinedHaptics.band(.001f));
    assertNotEquals(end, CombinedHaptics.band(.999f));
    assertTrue(CombinedHaptics.sliderEffect(-1, middle, 0) >= 0);
    assertEquals(-1, CombinedHaptics.sliderEffect(middle, middle, 100));
    assertEquals(-1, CombinedHaptics.sliderEffect(middle, middle + 1, 15));
    assertTrue(CombinedHaptics.sliderEffect(middle, middle + 1, 50) >= 0);
    assertEquals(android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE, CombinedHaptics.sliderEffect(middle, end, 5));
    assertEquals(-1, CombinedHaptics.sliderEffect(end, end, 100));
    assertEquals(-1, CombinedHaptics.sliderEffect(start, CombinedHaptics.band(Float.NaN), 100));
  }

  @Test public void wideConnectivityBodyOpensDetailsAndOnlyVendorIconUsesPrimaryToggle() {
    String vendor = "com.oplus.systemui.qs.tiles.OplusBluetoothTile";
    assertEquals(CombinedTileClicks.Action.PANEL, CombinedTileClicks.action(false, vendor, 1, false, false));
    assertEquals(CombinedTileClicks.Action.TOGGLE_OPLUS, CombinedTileClicks.action(true, vendor, 2, false, false));
    assertEquals(CombinedTileClicks.Action.SYSTEM, CombinedTileClicks.action(true, "com.android.systemui.qs.tiles.BluetoothTile", 2, false, false));
    assertEquals(CombinedTileClicks.Action.SYSTEM, CombinedTileClicks.action(false, vendor, 0, false, false));
    assertEquals(CombinedTileClicks.Action.SYSTEM, CombinedTileClicks.action(true, vendor, 2, true, false));
    assertEquals("", CombinedTileClicks.panelFor(null, ""));
  }

  @Test public void bothNetworkTilesAndBluetoothOnly() {
    for (String s : new String[]{"wifi", "internet", "cell", "cellular"}) assertEquals("internet", CombinedTileClicks.panelFor(s, ""));
    assertEquals("bluetooth", CombinedTileClicks.panelFor("bt", ""));
    assertEquals("bluetooth", CombinedTileClicks.panelFor("vendor", "com.oplus.systemui.qs.tiles.OplusBluetoothTile"));
    assertEquals("", CombinedTileClicks.panelFor("airplane", ""));
    assertEquals("", CombinedTileClicks.panelFor("custom(com.example/.WifiTile)", ""));
  }

  @Test public void wideModesAndTorchUseNativeGridTargets() {
    for (String[] pair : new String[][]{{"dnd","OplusDndTile","modes"}, {"flashlight","OplusFlashlightTile","flashlight"}}) {
      String tile = "com.oplus.systemui.qs.tiles." + pair[1];
      assertEquals(pair[2], CombinedTileClicks.panelFor(pair[0], tile));
      assertEquals(pair[2], CombinedTileClicks.panelFor(pair[0], ""));
      assertEquals(CombinedTileClicks.Action.TOGGLE_OPLUS, CombinedTileClicks.action(true, tile, 2, false, false));
      assertEquals(CombinedTileClicks.Action.PANEL, CombinedTileClicks.action(false, tile, 1, false, false));
      assertEquals(CombinedTileClicks.Action.SYSTEM, CombinedTileClicks.action(false, tile, 0, false, false));
      assertEquals(CombinedTileClicks.Action.SYSTEM, CombinedTileClicks.action(true, tile, 2, true, false));
    }
  }

  @Test public void otherModesAndNativeBackendsKeepTheirOwnClick() {
    assertEquals("", CombinedTileClicks.panelFor("nodisturb", "com.oplus.systemui.qs.tiles.NoDisturbTile"));
    assertEquals("", CombinedTileClicks.panelFor("dnd", "com.android.systemui.qs.tiles.ModesTile"));
    assertEquals("", CombinedTileClicks.panelFor("flashlight", "com.android.systemui.qs.tiles.FlashlightTileWithLevel"));
  }

  @Test public void settingsTravelOnlyAddsMissingShadeDisplacement() {
    assertEquals(-200f, CombinedSettingsLaunch.remainingTravel(1000, 800, 0, 0), 0);
    assertEquals(-120f, CombinedSettingsLaunch.remainingTravel(1000, 800, 50, -30), 0);
    assertEquals(0f, CombinedSettingsLaunch.remainingTravel(1000, 800, 50, -150), 0);
    assertEquals(0f, CombinedSettingsLaunch.remainingTravel(1000, 800, 50, -160), 0);
    assertEquals(-1000f, CombinedSettingsLaunch.remainingTravel(1000, 0, 0, 0), 0);
    assertEquals(0f, CombinedSettingsLaunch.remainingTravel(1000, 1200, 0, 0), 0);
    assertEquals(0f, CombinedSettingsLaunch.remainingTravel(1000, Float.NaN, 0, 0), 0);
  }

  @Test public void qsHeaderUsesLightGlyphsOnDarkSurfacesAndDarkGlyphsOnLight() {
    assertEquals(0f, CombinedStatusIcons.darkIntensity(0xffeee1e5), 0f);
    assertEquals(1f, CombinedStatusIcons.darkIntensity(0xff241d1f), 0f);
  }

  @Test public void oxygenPercentageSettingUsesIndependentInsideAndOutsideRendering() {
    assertFalse(CombinedStatusIcons.insidePercent(0));
    assertTrue(CombinedStatusIcons.insidePercent(1));
    assertFalse(CombinedStatusIcons.insidePercent(2));
    assertTrue(CombinedStatusIcons.insidePercent(-1));
  }

  @Test public void batteryFillUsesExactHeaderForegroundAndRomLayerOpacity() {
    for (int color : new int[]{0xffeee1e5, 0xff241d1f}) {
      assertEquals(color, ShadeBattery.tone(color, 0xffffffff));
      assertEquals((color & 0xffffff) | 0x73000000, ShadeBattery.tone(color, 0x73ffffff));
      assertEquals((color & 0xffffff) | 0x33000000, ShadeBattery.tone(color, 0x33000000));
    }
    assertEquals(0x40776655, ShadeBattery.tone(0x80776655, 0x80000000));
  }
}
