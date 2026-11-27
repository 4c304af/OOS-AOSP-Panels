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

  @Test public void nativeBatteryStaysCompactAndFitsSmallerMeasuredSlots() {
    assertEquals(.8f, BatteryFrameSize.fitScale(20, 12, 25, 12, true), .0001f);
    assertEquals(.8f, BatteryFrameSize.fitScale(20, 12, 40, 24, true), .0001f);
    assertEquals(.64f, BatteryFrameSize.fitScale(20, 12, 16, 12, true), .0001f);
    assertEquals(.6f, BatteryFrameSize.fitScale(20, 12, 20, 9, true), .0001f);
    assertEquals(0f, BatteryFrameSize.fitScale(20, 12, 0, 12, true), .0001f);
  }
}
