package dev.oos.nativepanels;

import static org.junit.Assert.*;
import org.junit.Test;

public class SplitShadeRotationTest {
  @Test public void unlockedSplitContentTracksSlowPullReversalAndCollapse() {
    for (float shade : new float[]{0f, .125f, .5f, 1f, .5f, .125f, 0f}) {
      assertEquals(shade > 0f, CombinedQsExpansion.showDuringSplitPull(shade, CombinedQsExpansion.SHADE, false));
      assertEquals(shade, ShadeLayout.splitAlpha(1f, shade), 0f);
      assertEquals(-140f * (1f - shade), CombinedQsExpansion.splitTranslation(-800f, shade, 800), .001f);
    }
    assertEquals(.1f, ShadeLayout.splitAlpha(.1f, .4f), 0f);
    assertEquals(-4f, CombinedQsExpansion.splitTranslation(-4f, .5f, 800), 0f);
    assertEquals(12f, CombinedQsExpansion.splitTranslation(12f, .75f, 800), 0f);
    assertFalse(CombinedQsExpansion.showDuringSplitPull(.5f, CombinedQsExpansion.KEYGUARD, false));
    assertFalse(CombinedQsExpansion.showDuringSplitPull(.5f, CombinedQsExpansion.SHADE_LOCKED, false));
    assertFalse(CombinedQsExpansion.showDuringSplitPull(.5f, CombinedQsExpansion.SHADE, true));
    assertFalse(CombinedQsExpansion.showDuringSplitPull(Float.NaN, CombinedQsExpansion.SHADE, false));
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
