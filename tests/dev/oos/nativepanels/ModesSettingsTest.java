package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.content.Intent;
import android.os.Parcel;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ModesSettingsTest {
  @Test
  public void matchesOosOwnDndPreferenceContractAfterParceling() {
    Parcel p = Parcel.obtain();
    try {
      OosModesSettings.main().writeToParcel(p, 0);
      p.setDataPosition(0);
      Intent outgoing = Intent.CREATOR.createFromParcel(p);
      // Exact consumer contract from the supplied OplusZenModePreferenceController.
      assertEquals("android.settings.ZEN_MODE_SETTINGS", outgoing.getAction());
      assertEquals("com.oplus.notificationmanager", outgoing.getPackage());
      assertNull(outgoing.getComponent());
      assertNull(outgoing.getExtras());
    } finally {
      p.recycle();
    }
  }

  @Test
  public void fallbackMatchesOosSettingsPluginWithoutInventingRuleExtras() {
    Intent outgoing = OosModesSettings.explicitMain();
    assertEquals(Intent.ACTION_MAIN, outgoing.getAction());
    assertEquals("com.oplus.notificationmanager", outgoing.getComponent().getPackageName());
    assertEquals(
        "com.oplus.zenmode.zenmodesettings.ZenModeSettingsActivity",
        outgoing.getComponent().getClassName());
    assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP, outgoing.getFlags());
    assertNull(outgoing.getExtras());
  }

  @Test
  public void unrelatedSettingsAndPolicyAccessKeepTheirNativeRouting() {
    assertTrue(OosModesSettings.handles(new Intent("android.settings.ZEN_MODE_SETTINGS")));
    assertTrue(
        OosModesSettings.handles(new Intent("android.settings.AUTOMATIC_ZEN_RULE_SETTINGS")));
    assertFalse(
        OosModesSettings.handles(
            new Intent("android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS")));
    assertFalse(OosModesSettings.handles(new Intent("android.settings.WIFI_SETTINGS")));
    assertFalse(OosModesSettings.handles(null));
  }
}
