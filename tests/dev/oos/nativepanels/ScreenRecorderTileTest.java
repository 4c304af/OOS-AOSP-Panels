package dev.oos.nativepanels;

import static dev.oos.nativepanels.CombinedTileClicks.Action.*;
import static org.junit.Assert.*;
import org.junit.Test;

public class ScreenRecorderTileTest {
  private static final String PLATFORM = "com.oplus.systemui.qs.tiles.ScreenRecorderTile";
  private static final String SERVICE = "com.android.systemui.qs.external.CustomTile";

  @Test public void platformAndBothServiceNamesIdentifyTheOosRecorder() {
    assertTrue(CombinedTileClicks.recorderBackend("screenrecord", PLATFORM));
    for (String component : new String[]{"com.oplus.screenrecorder/.floatwindow.services.QuickPanelService",
        "com.oplus.screenrecorder/com.oplus.screenrecorder.floatwindow.services.QuickPanelService",
        "com.coloros.screenrecorder/.services.QuickPanelService",
        "com.coloros.screenrecorder/com.coloros.screenrecorder.services.QuickPanelService"}) {
      assertTrue(CombinedTileClicks.recorderBackend("custom(" + component + ")", SERVICE));
    }
    assertFalse(CombinedTileClicks.recorderBackend("screenrecord", "com.android.systemui.qs.tiles.ScreenRecordTile"));
    assertFalse(CombinedTileClicks.recorderBackend("screenrecord", SERVICE));
    assertFalse(CombinedTileClicks.recorderBackend("custom(com.other/.QuickPanelService)", SERVICE));
    assertFalse(CombinedTileClicks.recorderBackend(null, PLATFORM));
  }

  @Test public void iconTogglesAndLabelOpensTheOosMenuIncludingWhileRecording() {
    for (int state : new int[]{1, 2}) {
      assertEquals(TOGGLE_OPLUS, CombinedTileClicks.recorderAction(true, true, state, false));
      assertEquals(DETAIL_OPLUS, CombinedTileClicks.recorderAction(false, true, state, false));
    }
  }

  @Test public void absentMenuAndRestrictedTilesKeepSystemHandling() {
    for (boolean icon : new boolean[]{true, false}) {
      assertEquals(SYSTEM, CombinedTileClicks.recorderAction(icon, false, 1, false));
      assertEquals(SYSTEM, CombinedTileClicks.recorderAction(icon, true, 0, false));
      assertEquals(SYSTEM, CombinedTileClicks.recorderAction(icon, true, 2, true));
    }
  }
}
