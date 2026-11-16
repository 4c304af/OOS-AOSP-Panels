package dev.oos.nativepanels;

import static org.junit.Assert.*;
import org.junit.Test;

public class ShadeDismissTest {
  @Test public void onlyASettledExpandedSceneIsEligible() {
    assertTrue(CombinedSettingsLaunch.fullyExpanded(1f));
    assertFalse(CombinedSettingsLaunch.fullyExpanded(.8f));
    assertFalse(CombinedSettingsLaunch.fullyExpanded(0f));
    assertFalse(CombinedSettingsLaunch.fullyExpanded(Float.NaN));
    assertFalse(CombinedSettingsLaunch.fullyExpanded(Float.POSITIVE_INFINITY));
  }

  @Test public void frameAndCanvasTogetherFollowPanelHeight() {
    int startY = 80;
    for (int inherited : new int[]{0, -40, -100}) {
      float added = CombinedSettingsLaunch.remainingTravel(1200, 800, startY, startY + inherited);
      assertEquals(-400f, inherited + added, 0f);
    }
    assertEquals(0f, CombinedSettingsLaunch.remainingTravel(1200, 800, 80, -320), 0f);
    assertEquals(0f, CombinedSettingsLaunch.remainingTravel(1200, 1200, 80, 80), 0f);
  }

  @Test public void ordinaryGesturesUseTheirOwnSceneAndTranslation() {
    CombinedSettingsLaunch launch = new CombinedSettingsLaunch(null);
    assertEquals(.37f, launch.sceneDuringExit(.37f, .8f), 0f);
    assertEquals(-63f, launch.translationDuringExit(-63f), 0f);
    assertEquals(.37f, CombinedSettingsLaunch.closingExpansion(1f, .37f), 0f);
  }
}
