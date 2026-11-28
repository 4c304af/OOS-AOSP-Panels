package dev.oos.nativepanels;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class StatusBatterySizeTest {
  @Test public void chargingAddsWidthWithoutReducingTheBodyAtDifferentDisplaySizes() {
    for (int height : new int[]{13, 26, 39, 52}) {
      int neutral = new BatteryFrameSize(24f, 13f, 1.5f).normalWidth(height);
      float scale = BatteryFrameSize.fitScale(neutral, height, height * 2, height * 1.25f);
      assertEquals(0, HomeStatusBattery.extraWidth(neutral, neutral, scale));
      assertEquals((int)Math.ceil(7 * scale), HomeStatusBattery.extraWidth(neutral + 7, neutral, scale));
      assertEquals(height * .80f * (height * 2 / (float)neutral), height * scale, .0001f);
    }
  }

  @Test public void batteryScalesUpOrDownToTheMeasuredGlyph() {
    assertEquals(.88f, BatteryFrameSize.fitScale(20, 10, 22, 12), .0001f);
    assertEquals(1.2f, BatteryFrameSize.fitScale(20, 10, 40, 15), .0001f);
    assertEquals(.64f, BatteryFrameSize.fitScale(20, 10, 16, 20), .0001f);
    assertEquals(.4f, BatteryFrameSize.fitScale(20, 10, 20, 5), .0001f);
    assertEquals(0f, BatteryFrameSize.fitScale(20, 10, 0, 5), .0001f);
  }

  @Test public void nativeBatteryOnlyShrinksForRealParentConstraintsAndNeverEnlarges() {
    assertEquals(1f, BatteryFrameSize.intrinsicScale(27, 13, 27, 13), .0001f);
    assertEquals(1f, BatteryFrameSize.intrinsicScale(27, 13, 100, 50), .0001f);
    assertEquals(.5f, BatteryFrameSize.intrinsicScale(27, 13, 13.5f, 13), .0001f);
    assertEquals(.5f, BatteryFrameSize.intrinsicScale(27, 13, 27, 6.5f), .0001f);
    assertEquals(0f, BatteryFrameSize.intrinsicScale(27, 13, 0, 13), .0001f);
  }

  @Test public void nativeHomeReservesActualCanvasInsteadOfTheOemSlotAndKeepsExternalPercent() {
    // The same 16px of external percentage/padding surrounds different OEM glyphs.
    assertEquals(43, HomeStatusBattery.nativeWidth(40, 24, 27, 1f));
    assertEquals(43, HomeStatusBattery.nativeWidth(56, 40, 27, 1f));
    assertEquals(43, HomeStatusBattery.nativeWidth(28, 12, 27, 1f));
    // Charging attribution receives its full width in the measurement pass.
    assertEquals(49, HomeStatusBattery.nativeWidth(40, 24, 33, 1f));
    assertEquals(33, HomeStatusBattery.nativeWidth(40, 24, 33, .5f));
    // LTR's original meter shifts by +3; RTL keeps the external text at x=0.
    assertEquals(0f, HomeStatusBattery.nativeArtworkLeft(3, 27, 27, 1f, false), 0f);
    assertEquals(16f, HomeStatusBattery.nativeArtworkLeft(16, 40, 27, 1f, true), 0f);
  }
}
