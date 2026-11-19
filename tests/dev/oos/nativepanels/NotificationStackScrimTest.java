package dev.oos.nativepanels;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class NotificationStackScrimTest {
  @Test public void splitNotificationsFollowOnlyTheShadeAndPortraitFadesWithQs() {
    assertEquals(128, NotificationStackScrim.surfaceAlpha(1f, 1f, true));
    assertEquals(128, NotificationStackScrim.surfaceAlpha(1f, 0f, true));
    assertEquals(52, NotificationStackScrim.surfaceAlpha(.5f, 1f, true));
    assertEquals(0, NotificationStackScrim.surfaceAlpha(0f, 1f, true));
    assertEquals(0, NotificationStackScrim.surfaceAlpha(Float.NaN, 1f, true));
    assertEquals(128, NotificationStackScrim.surfaceAlpha(1f, 0f, false));
    assertEquals(0, NotificationStackScrim.surfaceAlpha(1f, 1f, false));
  }
  @Test public void wholeWindowTintKeepsTheThemeAlphaAtRestAndRetracesOnClose() {
    int surface = 0xa04f5261;
    assertEquals(surface, ShadeBackdrop.tintAtExpansion(surface, 1f));
    assertEquals(surface, ShadeBackdrop.tintAtExpansion(surface, 1.1f));
    assertEquals(0x504f5261, ShadeBackdrop.tintAtExpansion(surface, .5f));
    assertEquals(0x004f5261, ShadeBackdrop.tintAtExpansion(surface, 0f));
    assertEquals(0x004f5261, ShadeBackdrop.tintAtExpansion(surface, Float.NaN));
    assertEquals(0xff242123, ShadeBackdrop.tintAtExpansion(0xff242123, 1f));
  }

  @Test public void lastNotificationRemainsReachableWithoutExtraScrollForShortLists() {
    assertEquals(0, NotificationStackScrim.scrollRange(0, 400, 900, 850));
    assertEquals(20, NotificationStackScrim.scrollRange(0, 870, 900, 850));
    assertEquals(150, NotificationStackScrim.scrollRange(100, 1000, 900, 850));
    assertEquals(200, NotificationStackScrim.scrollRange(200, 1000, 900, 850));
    assertEquals(100, NotificationStackScrim.scrollRange(100, 1000, 900, 920));
  }
  @Test public void followsTheDisplayCornerOnlyNearTheTopEdge() {
    assertEquals(74f, NotificationStackScrim.cornerAtTop(74f, 32f, 0f), 0f);
    assertEquals(53f, NotificationStackScrim.cornerAtTop(74f, 32f, 16f), 0f);
    assertEquals(32f, NotificationStackScrim.cornerAtTop(74f, 32f, 32f), 0f);
    assertEquals(32f, NotificationStackScrim.cornerAtTop(74f, 32f, 600f), 0f);
    assertEquals(0f, NotificationStackScrim.cornerAtTop(0f, 32f, 0f), 0f);
  }
  @Test public void fadeFollowsTheShadeAndReversesDuringQuickSettingsExpansion() {
    assertEquals(0f, NotificationStackScrim.fraction(0f, 0f), 0f);
    assertEquals(.5f, NotificationStackScrim.fraction(.15f, 0f), .0001f);
    assertEquals(1f, NotificationStackScrim.fraction(.3f, 0f), 0f);
    assertEquals(.5f, NotificationStackScrim.fraction(1f, .5f), 0f);
    assertEquals(0f, NotificationStackScrim.fraction(1f, 1f), 0f);
    assertEquals(1f, NotificationStackScrim.fraction(1.2f, -.1f), 0f);
    assertEquals(0f, NotificationStackScrim.fraction(Float.NaN, 0f), 0f);
  }
  @Test public void quickTilesRevealFromTheTopOnTheFirstSampleAndRetraceTheSameEdge() {
    for (float progress : new float[]{.13f, .25f, .68f, 1f, .68f, .25f, 0f})
      assertEquals(900f * progress, ShadePullMotion.revealEdge(900, 900, progress), .001f);
    assertEquals(80f, ShadePullMotion.revealEdge(80, 900, .5f), 0f);
    assertEquals(990f, ShadePullMotion.revealEdge(990, 900, 1f), 0f);
    assertEquals(900f, ShadePullMotion.revealEdge(900, 0, .5f), 0f);
    assertEquals(900f, ShadePullMotion.revealEdge(900, 900, Float.NaN), 0f);
  }
}
