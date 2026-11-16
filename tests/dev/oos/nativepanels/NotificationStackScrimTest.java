package dev.oos.nativepanels;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class NotificationStackScrimTest {
  @Test public void splitNotificationsFollowOnlyTheShadeAndPortraitFadesWithQs() {
    assertEquals(128, NotificationStackScrim.surfaceAlpha(1f, 1f, true));
    assertEquals(128, NotificationStackScrim.surfaceAlpha(1f, 0f, true));
    assertEquals(128, NotificationStackScrim.surfaceAlpha(.5f, 1f, true));
    assertEquals(0, NotificationStackScrim.surfaceAlpha(0f, 1f, true));
    assertEquals(0, NotificationStackScrim.surfaceAlpha(Float.NaN, 1f, true));
    assertEquals(128, NotificationStackScrim.surfaceAlpha(1f, 0f, false));
    assertEquals(0, NotificationStackScrim.surfaceAlpha(1f, 1f, false));
  }
  @Test public void bothOrientationsUseTheSameSurfaceOpacityThroughoutAPull() {
    for (float shade : new float[]{0f, .05f, .25f, .3f, .8f, 1f, Float.NaN}) {
      assertEquals(NotificationStackScrim.surfaceAlpha(shade, 0f, false), NotificationStackScrim.surfaceAlpha(shade, 1f, true));
    }
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
  @Test public void fixedClipStartsAtTheWindowEdgeAndRetracesDuringClose() {
    int[] tops = {0, 9, 226, 451, 677, 902, 677, 451, 226, 9, 0};
    float[] fractions = {0f, .01f, .25f, .5f, .75f, 1f, .75f, .5f, .25f, .01f, 0f};
    for (int i = 0; i < tops.length; i++)
      assertEquals(tops[i], NotificationStackScrim.revealTop(902, 902, fractions[i], -902f * (1f - fractions[i])));
    assertEquals(1011, NotificationStackScrim.revealTop(1011, 902, 1f, 0f));
  }

  @Test public void retainsAnAlreadyMovingNativeEdgeAndHandlesMissingHeader() {
    assertEquals(40, NotificationStackScrim.revealTop(40, 902, .5f, 0f));
    assertEquals(0, NotificationStackScrim.revealTop(902, 902, .03f, -902f));
    assertEquals(451, NotificationStackScrim.revealTop(902, 0, .5f, Float.NaN));
    assertEquals(0, NotificationStackScrim.revealTop(902, 902, Float.NaN, 0f));
  }
  @Test public void anchorsTheFirstRecordedFrameWithCoalescedExpansionCallbacks() {
    NotificationStackScrim.PullOrigin motion = new NotificationStackScrim.PullOrigin();
    motion.begin();
    float first = .1275335f;
    assertEquals(0f, motion.progress(.04f), 0f);
    assertEquals(0, NotificationStackScrim.revealTop(902, 902, motion.progress(first), 0));
    motion.presented(first);
    assertEquals(0f, motion.progress(first), 0f);
    assertEquals(.5f, motion.progress(first + .5f * (1f - first)), .00001f);
    assertEquals(1028, NotificationStackScrim.revealTop(1028, 902, motion.progress(1f), 0));
    motion.presented(1f);
    assertEquals(.25f, motion.progress(.25f), 0f);
  }

  @Test public void cancelledAndInstantOpensDoNotInheritAnOldOffset() {
    NotificationStackScrim.PullOrigin motion = new NotificationStackScrim.PullOrigin();
    motion.begin();
    motion.presented(.13f);
    assertEquals(0f, motion.progress(.07f), 0f);
    motion.reset();
    assertEquals(.07f, motion.progress(.07f), 0f);
    motion.begin();
    assertEquals(1f, motion.progress(1f), 0f);
    motion.presented(1f);
    assertEquals(.4f, motion.progress(.4f), 0f);
  }
}
