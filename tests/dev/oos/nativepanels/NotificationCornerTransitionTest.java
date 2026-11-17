package dev.oos.nativepanels;

import static org.junit.Assert.*;
import static dev.oos.nativepanels.NotificationCornerTransition.*;

import org.junit.Test;

public class NotificationCornerTransitionTest {
  @Test public void initialBindingImmediatelyUsesTheCorrectShapeWithoutAnOpeningFlash() {
    NotificationCornerTransition corners = new NotificationCornerTransition();
    assertFalse(corners.isRunning(100));
    corners.target(TOP | BOTTOM, 100, true);
    assertEquals(1f, corners.top(100), 0f);
    assertEquals(1f, corners.bottom(100), 0f);
    assertFalse(corners.isRunning(100));
    assertFalse(corners.isRunning(10000));
  }

  @Test public void removingANeighborReleasesOnlyThatEdgeAndEventuallyStopsRequestingFrames() {
    NotificationCornerTransition corners = new NotificationCornerTransition();
    corners.target(TOP | BOTTOM, 100, true);
    corners.target(BOTTOM, 200, true);
    assertEquals(1f, corners.top(200), 0f);
    assertTrue(corners.isRunning(200));
    assertTrue(corners.top(290) > 0f);
    assertTrue(corners.top(290) < 1f);
    assertEquals(1f, corners.bottom(290), 0f);
    assertEquals(0f, corners.top(380), 0f);
    assertFalse(corners.isRunning(380));
    assertFalse(corners.isRunning(10000));
  }

  @Test public void repeatedTargetsDoNotExtendTheAnimationDeadline() {
    NotificationCornerTransition corners = new NotificationCornerTransition();
    corners.target(0, 0, true);
    corners.target(TOP, 10, true);
    for (long now = 11; now < 190; now++) corners.target(TOP, now, true);
    assertTrue(corners.isRunning(189));
    assertEquals(1f, corners.top(190), 0f);
    assertEquals(0f, corners.bottom(190), 0f);
    assertFalse(corners.isRunning(190));
  }

  @Test public void reversingOneEdgePreservesContinuityAndTheOtherEdgesOriginalDeadline() {
    NotificationCornerTransition corners = new NotificationCornerTransition();
    corners.target(0, 0, true);
    corners.target(TOP | BOTTOM, 100, true);
    float topBefore = corners.top(160), bottomBefore = corners.bottom(160);
    corners.target(BOTTOM, 160, true);
    assertEquals(topBefore, corners.top(160), 0f);
    assertEquals(bottomBefore, corners.bottom(160), 0f);
    assertTrue(corners.top(200) < topBefore);
    assertTrue(corners.bottom(200) > bottomBefore);
    assertEquals(1f, corners.bottom(280), 0f);
    assertTrue(corners.isRunning(280));
    assertEquals(0f, corners.top(340), 0f);
    assertFalse(corners.isRunning(340));
  }

  @Test public void rapidChangesStartAtTheDisplayedAmountsWithoutOvershooting() {
    NotificationCornerTransition corners = new NotificationCornerTransition();
    corners.target(TOP, 0, true);
    int mask = BOTTOM;
    for (long now : new long[]{10, 30, 65, 99, 140}) {
      float topBefore = corners.top(now), bottomBefore = corners.bottom(now);
      corners.target(mask, now, true);
      assertEquals(topBefore, corners.top(now), 0f);
      assertEquals(bottomBefore, corners.bottom(now), 0f);
      assertUnitRange(corners.top(now + 1));
      assertUnitRange(corners.bottom(now + 1));
      mask = mask == TOP ? BOTTOM : TOP;
    }
    assertFalse(corners.isRunning(320));
  }

  @Test public void disabledAnimationsSnapEvenWhenTheTargetHasNotChanged() {
    NotificationCornerTransition corners = new NotificationCornerTransition();
    corners.target(0, 0, true);
    corners.target(TOP | BOTTOM, 100, true);
    assertTrue(corners.isRunning(150));
    corners.target(TOP | BOTTOM, 150, false);
    assertEquals(1f, corners.top(150), 0f);
    assertEquals(1f, corners.bottom(150), 0f);
    assertFalse(corners.isRunning(150));
    corners.target(0, 160, false);
    assertEquals(0f, corners.top(160), 0f);
    assertEquals(0f, corners.bottom(160), 0f);
    assertFalse(corners.isRunning(160));
  }

  @Test public void bothDirectionsRemainMonotonicAndWithinTheirEndpoints() {
    NotificationCornerTransition corners = new NotificationCornerTransition();
    corners.target(TOP, 0, true);
    corners.target(BOTTOM, 100, true);
    float previousTop = 1f, previousBottom = 0f;
    for (long now = 100; now <= 280; now++) {
      float top = corners.top(now), bottom = corners.bottom(now);
      assertUnitRange(top);
      assertUnitRange(bottom);
      assertTrue(top <= previousTop);
      assertTrue(bottom >= previousBottom);
      previousTop = top;
      previousBottom = bottom;
    }
    assertEquals(0f, previousTop, 0f);
    assertEquals(1f, previousBottom, 0f);
    assertFalse(corners.isRunning(280));
  }

  @Test public void aSampleBeforeTheStartDoesNotExtrapolatePastTheVisibleShape() {
    NotificationCornerTransition corners = new NotificationCornerTransition();
    corners.target(0, 100, true);
    corners.target(TOP, 200, true);
    assertEquals(0f, corners.top(199), 0f);
    assertEquals(0f, corners.bottom(199), 0f);
  }

  private static void assertUnitRange(float value) {
    assertTrue(value >= 0f);
    assertTrue(value <= 1f);
  }
}
