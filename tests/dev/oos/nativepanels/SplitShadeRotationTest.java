package dev.oos.nativepanels;

import static org.junit.Assert.*;
import org.junit.Test;

public class SplitShadeRotationTest {
  @Test public void unlockedSplitContentTracksSlowPullReversalAndCollapse() {
    float[] progress = {0f, .17f, .39f, .525f, .66f, 1f, .525f, .39f, 0f};
    float[] alpha = {0f, 0f, 0f, .5f, 1f, 1f, .5f, 0f, 0f};
    for (int i = 0; i < progress.length; i++) {
      assertEquals(alpha[i], ShadePullMotion.splitContentAlpha(progress[i]), .0001f);
      assertEquals(Math.round(128f * alpha[i]), NotificationStackScrim.surfaceAlpha(progress[i], 1f, true));
    }
    assertEquals(0f, ShadePullMotion.splitContentAlpha(Float.NaN), 0f);
  }

  @Test public void fingerUpScrollsDownAndTheBottomYields() {
    assertTrue(CombinedTouch.canScroll(0, 100, -20, false));
    assertFalse(CombinedTouch.canScroll(0, 100, 20, false));
    assertTrue(CombinedTouch.canScroll(45, 100, 20, false));
    assertFalse(CombinedTouch.canScroll(100, 100, -20, false));
  }
  @Test public void reverseScrollingAndAnEmptyViewportRespectBoundaries() {
    assertTrue(CombinedTouch.canScroll(100, 100, -10, true));
    assertFalse(CombinedTouch.canScroll(0, 100, -10, true));
    assertFalse(CombinedTouch.canScroll(0, 0, -10, false));
    assertFalse(CombinedTouch.canScroll(Float.NaN, 100, -10, false));
  }
  @Test public void fullWindowDoesNotImplyTwoSeparatePanes() {
    assertTrue(SplitShadeChrome.narrow(950, 2048));
    assertFalse(SplitShadeChrome.narrow(2048, 2048));
    assertFalse(SplitShadeChrome.narrow(0, 2048));
  }
}
