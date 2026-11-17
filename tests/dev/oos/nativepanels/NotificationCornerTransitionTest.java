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

  @Test public void nativeRadiusStepsStayContinuousEvenWhenTheJoinedEdgesNeverChange() {
    NotificationCornerTransition nativeRadii = new NotificationCornerTransition();
    NotificationCornerTransition joins = new NotificationCornerTransition();
    nativeRadii.target(28f, 28f, 0, true);
    joins.target(BOTTOM, 0, true);
    assertEquals(28f, nativeRadii.top(0), 0f);
    assertFalse(nativeRadii.isRunning(0));

    nativeRadii.target(0f, 0f, 100, true);
    joins.target(BOTTOM, 100, true);
    assertEquals(28f, nativeRadii.top(100), 0f);
    assertTrue(nativeRadii.top(190) > 0f);
    assertTrue(nativeRadii.top(190) < 28f);
    assertEquals(0f, nativeRadii.top(280), 0f);
    nativeRadii.target(28f, 28f, 300, true);
    joins.target(BOTTOM, 300, true);
    assertEquals(0f, nativeRadii.top(300), 0f);
    for (long now = 300; now <= 480; now++) {
      float bottom = nativeRadii.bottom(now);
      assertEquals(4f, bottom + (4f - bottom) * joins.bottom(now), .00001f);
    }
    assertEquals(28f, nativeRadii.top(480), 0f);
    assertFalse(nativeRadii.isRunning(480));
    assertFalse(joins.isRunning(480));
  }

  @Test public void reversingAPixelRadiusKeepsTheDisplayedValueAndTheOtherEdgeDeadline() {
    NotificationCornerTransition source = new NotificationCornerTransition();
    source.target(56f, 0f, 0, true);
    source.target(0f, 56f, 100, true);
    float top = source.top(160), bottom = source.bottom(160);
    source.target(56f, 56f, 160, true);
    assertEquals(top, source.top(160), 0f);
    assertEquals(bottom, source.bottom(160), 0f);
    assertTrue(source.top(200) > top);
    assertEquals(56f, source.bottom(280), 0f);
    assertTrue(source.isRunning(280));
    assertEquals(56f, source.top(340), 0f);
    assertFalse(source.isRunning(340));
  }

  @Test public void continuouslyUpdatedNativeTargetsRemainBoundedAndSettleAfterTheLastChange() {
    NotificationCornerTransition source = new NotificationCornerTransition();
    source.target(0f, 28f, 0, true);
    for (long now = 16; now <= 192; now += 16) {
      float top = source.top(now), bottom = source.bottom(now);
      float target = 28f * now / 192;
      source.target(target, 28f - target, now, true);
      assertEquals(top, source.top(now), 0f);
      assertEquals(bottom, source.bottom(now), 0f);
      assertTrue(source.top(now + 1) >= top);
      assertTrue(source.top(now + 1) <= target);
      assertTrue(source.bottom(now + 1) <= bottom);
      assertTrue(source.bottom(now + 1) >= 28f - target);
    }
    // A native frame stream must remain responsive. Restarting the join curve
    // every 16ms left this sequence near 3px even though native had reached 28px.
    assertTrue(28f - source.top(192) <= 6f);
    assertTrue(source.bottom(192) <= 6f);
    long finish = 192 + DURATION_MILLIS;
    for (long now = 193; now <= finish; now++) source.target(28f, 0f, now, true);
    assertEquals(28f, source.top(finish), 0f);
    assertEquals(0f, source.bottom(finish), 0f);
    assertFalse(source.isRunning(finish));
    assertFalse(source.isRunning(finish + 10000));
  }

  @Test public void disablingAnimationsImmediatelyAppliesPixelTargetsAndStopsBothEdges() {
    NotificationCornerTransition source = new NotificationCornerTransition();
    source.target(0f, 56f, 0, true);
    source.target(56f, 0f, 100, true);
    assertTrue(source.isRunning(150));
    source.target(56f, 0f, 150, false);
    assertEquals(56f, source.top(150), 0f);
    assertEquals(0f, source.bottom(150), 0f);
    assertFalse(source.isRunning(150));
    source.target(0f, 56f, 160, false);
    assertEquals(0f, source.top(160), 0f);
    assertEquals(56f, source.bottom(160), 0f);
    assertFalse(source.isRunning(160));
  }

  private static void assertUnitRange(float value) {
    assertTrue(value >= 0f);
    assertTrue(value <= 1f);
  }
}
