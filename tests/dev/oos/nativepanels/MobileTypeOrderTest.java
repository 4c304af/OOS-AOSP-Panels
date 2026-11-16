package dev.oos.nativepanels;

import static org.junit.Assert.assertArrayEquals;
import org.junit.Test;

public class MobileTypeOrderTest {
  @Test public void artworkCompensatesOnlyTheInternalGap() {
    org.junit.Assert.assertEquals(-5, MobileTypeOrder.compactEndMargin(0, 2, 6, 3));
    org.junit.Assert.assertEquals(0, MobileTypeOrder.compactEndMargin(0, 2, 0, 3));
    org.junit.Assert.assertEquals(-2, MobileTypeOrder.compactEndMargin(0, 2, 6, 6));
  }
  @Test public void unevenWidthsAndGap() {
    int[] positions = {7, 23};
    MobileTypeOrder.swapPositions(new int[]{14, 22}, positions, 0, false);
    assertArrayEquals(new int[]{31, 7}, positions);
  }
  @Test public void roamingAndRtl() {
    int[] positions = {54, 38, 15};
    MobileTypeOrder.swapPositions(new int[]{8, 14, 21}, positions, 1, true);
    assertArrayEquals(new int[]{54, 15, 31}, positions);
  }
  @Test public void noLabelOrUnknownOrder() {
    int[] positions = {2, 10};
    MobileTypeOrder.swapPositions(new int[]{8, 20}, positions, 1, false);
    MobileTypeOrder.swapPositions(new int[]{8, 20}, positions, -1, false);
    assertArrayEquals(new int[]{2, 10}, positions);
  }
}
