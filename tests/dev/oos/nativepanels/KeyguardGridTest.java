package dev.oos.nativepanels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import org.junit.Test;

public class KeyguardGridTest {
  @Test public void lockedOosIconTogglesAndBodyInvokesGatedDetailAction() {
    for (String type : new String[]{"Wifi", "Bluetooth", "Cellular", "Dnd", "Flashlight"}) {
      String tile = "com.oplus.systemui.qs.tiles.Oplus" + type + "Tile";
      for (int state : new int[]{1, 2}) {
        assertEquals(CombinedTileClicks.Action.TOGGLE_OPLUS, CombinedTileClicks.action(true, tile, state, false, true));
        assertEquals(CombinedTileClicks.Action.DETAIL_OPLUS, CombinedTileClicks.action(false, tile, state, false, true));
        assertEquals(CombinedTileClicks.Action.PANEL, CombinedTileClicks.action(false, tile, state, false, false));
      }
    }
  }

  @Test public void nativeRestrictedAndUnavailableTilesUseTheirOwnEntryPoints() {
    String nativeTile = "com.android.systemui.qs.tiles.BluetoothTile";
    String oos = "com.oplus.systemui.qs.tiles.OplusWifiTile";
    for (boolean icon : new boolean[]{true, false}) {
      assertEquals(CombinedTileClicks.Action.SYSTEM, CombinedTileClicks.action(icon, nativeTile, 2, false, true));
      assertEquals(CombinedTileClicks.Action.SYSTEM, CombinedTileClicks.action(icon, oos, 2, true, true));
      assertEquals(CombinedTileClicks.Action.SYSTEM, CombinedTileClicks.action(icon, oos, 0, false, true));
    }
  }

  @Test public void cellularOnlyAddsSpaceOnTheReplacementMeter() {
    for (String slot : new String[]{"mobile", "stacked_mobile"}) {
      assertTrue(HomeBatteryHook.mobileSlot(slot));
      assertEquals(6, HomeStatusBattery.cellInset(true, true, 3f));
      assertEquals(0, HomeStatusBattery.cellInset(false, true, 3f));
      assertEquals(94, HomeStatusBattery.slotWidth(100, 3f));
    }
  }

  @Test public void wifiAndOtherNeighboursKeepTheSameGapsInBothDirections() {
    for (String slot : new String[]{"wifi", "airplane", "vpn", "vowifi", null}) {
      assertFalse(HomeBatteryHook.mobileSlot(slot));
      assertEquals(0, HomeStatusBattery.cellInset(true, false, 3f));
      assertEquals(44f, HomeStatusBattery.artworkLeft(50f, true, false, 3f), 0f);
      assertEquals(56f, HomeStatusBattery.artworkLeft(50f, true, true, 3f), 0f);
      assertEquals(50f, HomeStatusBattery.artworkLeft(50f, false, true, 3f), 0f);
    }
  }

  @Test public void aTranslatedHostOnlyReceivesMissingTravelOnBothSidesOfZero() {
    for (float target : new float[]{-100f, -42f, 0f, 28f, 0f, -10f}) {
      assertEquals(0f, ShadeDecor.remainingTranslation(target, target), .001f);
      assertEquals(target / 2f, ShadeDecor.remainingTranslation(target, target / 2f), .001f);
    }
  }
}
