package dev.oos.nativepanels;

import static org.junit.Assert.*;
import org.junit.Test;

public class SplitShadeRotationTest {
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
