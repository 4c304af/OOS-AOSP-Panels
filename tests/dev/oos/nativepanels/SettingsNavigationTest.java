package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.provider.Settings;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SettingsNavigationTest {
  @Test
  public void detailsReachOosConsumerWithExactSelectedKeyAfterParceling() {
    String selected =
        "StandardWifiEntry:{\"SCAN_RESULT_KEY\":\"{\\\"SSID\\\":\\\"測試 Wi-Fi\\\","
            + "\\\"SECURITY_TYPES\\\":[2,4]}\",\"CONFIG_OWNER\":10}";
    Bundle args = new Bundle();
    args.putString(OosSettingsIntents.ENTRY_KEY, selected);
    Intent original =
        new Intent(OosSettingsIntents.DETAILS_ACTION)
            .putExtra(OosSettingsIntents.FRAGMENT_ARGS, args)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    Intent outgoing = parcel(OosSettingsIntents.redirect(original));

    assertEquals(OosSettingsIntents.WIFI_EDITOR, outgoing.getComponent().getClassName());
    assertEquals(OosSettingsIntents.PACKAGE, outgoing.getComponent().getPackageName());
    // These are the two direct Intent reads performed by the supplied WifiEditor.onCreate.
    assertEquals(selected, outgoing.getStringExtra("key_chosen_wifientry_key"));
    assertTrue(outgoing.getBooleanExtra("wifi.details.extra.config", false));
    assertFalse(outgoing.hasExtra(OosSettingsIntents.FRAGMENT_ARGS));
    assertEquals(
        Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK, outgoing.getFlags());
    assertEquals(
        selected,
        original
            .getBundleExtra(OosSettingsIntents.FRAGMENT_ARGS)
            .getString(OosSettingsIntents.ENTRY_KEY));
    assertFalse(original.hasExtra(OosSettingsIntents.SHOW_DETAILS));
  }

  @Test
  public void seeAllUsesOosHandlerWithoutAospFragmentArguments() {
    Intent input =
        new Intent(OosSettingsIntents.NETWORK_ACTION)
            .setClassName(
                "com.android.settings",
                "com.android.settings.Settings$NetworkProviderSettingsActivity")
            .putExtra(":settings:show_fragment", "stale.aosp.Fragment");
    Intent output = OosSettingsIntents.redirect(input);
    assertEquals(Settings.ACTION_WIFI_SETTINGS, output.getAction());
    assertEquals(OosSettingsIntents.PACKAGE, output.getPackage());
    assertNull(output.getComponent());
    assertFalse(output.hasExtra(":settings:show_fragment"));
  }

  @Test
  public void missingSelectionOpensListInsteadOfChoosingAnotherNetwork() {
    for (String key : new String[] {null, ""}) {
      Bundle args = new Bundle();
      args.putString(OosSettingsIntents.ENTRY_KEY, key);
      Intent output =
          OosSettingsIntents.redirect(
              new Intent(OosSettingsIntents.DETAILS_ACTION)
                  .putExtra(OosSettingsIntents.FRAGMENT_ARGS, args));
      assertEquals(Settings.ACTION_WIFI_SETTINGS, output.getAction());
      assertFalse(output.hasExtra(OosSettingsIntents.ENTRY_KEY));
    }
  }

  @Test
  public void simAndOtherSettingsKeepTheirOriginalRoutingAndExtras() {
    Intent sim =
        new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)
            .putExtra("android.provider.extra.SUB_ID", 2);
    assertSame(sim, OosSettingsIntents.redirect(sim));
    assertEquals(2, sim.getIntExtra("android.provider.extra.SUB_ID", -1));
    Intent bluetooth = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
    assertSame(bluetooth, OosSettingsIntents.redirect(bluetooth));
    assertNull(OosSettingsIntents.redirect(null));
  }

  private static Intent parcel(Intent intent) {
    Parcel parcel = Parcel.obtain();
    try {
      intent.writeToParcel(parcel, 0);
      parcel.setDataPosition(0);
      return Intent.CREATOR.createFromParcel(parcel);
    } finally {
      parcel.recycle();
    }
  }
}
