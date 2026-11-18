package dev.oos.nativepanels;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Parcel;
import android.provider.Settings;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BluetoothDetailsIntentsTest {
  private static final String SELECTED = "12:34:56:78:9A:BC";
  private static final ComponentName NATIVE = new ComponentName(
      BluetoothDetailsIntents.SETTINGS, BluetoothDetailsIntents.ACTIVITY);
  private Context context;

  @Before public void setUp() {
    context = RuntimeEnvironment.getApplication();
    ActivityInfo info = shadowOf(context.getPackageManager()).addActivityIfNotPresent(NATIVE);
    info.exported = true;
    info.enabled = true;
    info.applicationInfo.enabled = true;
    shadowOf(context.getPackageManager()).addOrUpdateActivity(info);
    IntentFilter filter = new IntentFilter(BluetoothDetailsIntents.ACTION);
    filter.addCategory(Intent.CATEGORY_DEFAULT);
    shadowOf(context.getPackageManager()).addIntentFilterForActivity(NATIVE, filter);
  }

  @Test public void wirelessGearUsesExactSelectedDeviceAfterParceling() {
    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    BluetoothDevice selected = adapter.getRemoteDevice(SELECTED);
    BluetoothDevice other = adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF");
    shadowOf(adapter).setBondedDevices(Set.of(other, selected));
    Intent original = wireless(selected);
    original.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
    original.setSourceBounds(new Rect(3, 5, 23, 45));

    Intent result = parcel(BluetoothDetailsIntents.fromWireless(context, original, true));

    assertEquals(NATIVE, result.getComponent());
    assertEquals(BluetoothDetailsIntents.ACTION, result.getAction());
    assertEquals(SELECTED, result.getBundleExtra(BluetoothDetailsIntents.ARGUMENTS)
        .getString(BluetoothDetailsIntents.ADDRESS));
    assertEquals(original.getFlags(), result.getFlags());
    assertEquals(original.getSourceBounds(), result.getSourceBounds());
    assertFalse(result.hasExtra(BluetoothDetailsIntents.FRAGMENT));
    assertFalse(result.getBundleExtra(BluetoothDetailsIntents.ARGUMENTS).containsKey("device"));
    assertEquals(BluetoothDetailsIntents.PROFILE_FRAGMENT,
        original.getStringExtra(BluetoothDetailsIntents.FRAGMENT));
    assertEquals(selected, original.getBundleExtra(BluetoothDetailsIntents.ARGUMENTS).get("device"));
  }

  @Test public void nativeGearKeepsSelectedAddressAndUsesVerifiedActivity() {
    Intent original = nativeGear(SELECTED).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .addCategory(Intent.CATEGORY_DEFAULT).putExtra(":settings:source_metrics", 42);
    Intent result = BluetoothDetailsIntents.fromNative(context, original, true);
    assertEquals(NATIVE, result.getComponent());
    assertEquals(SELECTED, BluetoothDetailsIntents.selectedNativeAddress(result));
    assertEquals(original.getFlags(), result.getFlags());
    assertNull(original.getComponent());
    assertEquals(original.getCategories(), result.getCategories());
    assertEquals(42, result.getIntExtra(":settings:source_metrics", -1));
  }

  @Test public void optOutKeepsBothOriginalIntents() {
    Intent nativeGear = nativeGear(SELECTED);
    Intent wireless = wireless(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(SELECTED));
    assertSame(nativeGear, BluetoothDetailsIntents.fromNative(context, nativeGear, false));
    assertSame(wireless, BluetoothDetailsIntents.fromWireless(context, wireless, false));
  }

  @Test public void unavailableNativePagePreservesOosSelectedDevice() {
    shadowOf(context.getPackageManager()).removeActivity(NATIVE);
    Intent wireless = wireless(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(SELECTED));
    Intent nativeGear = nativeGear(SELECTED);
    assertSame(wireless, BluetoothDetailsIntents.fromWireless(context, wireless, true));
    assertSame(nativeGear, BluetoothDetailsIntents.fromNative(context, nativeGear, true));
  }

  @Test public void disabledOrUnexportedNativePageKeepsOriginalOosDetails() {
    Intent original = wireless(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(SELECTED));
    ActivityInfo info = shadowOf(context.getPackageManager()).addActivityIfNotPresent(NATIVE);
    info.enabled = false;
    shadowOf(context.getPackageManager()).addOrUpdateActivity(info);
    assertSame(original, BluetoothDetailsIntents.fromWireless(context, original, true));
    info.enabled = true;
    info.exported = false;
    shadowOf(context.getPackageManager()).addOrUpdateActivity(info);
    assertSame(original, BluetoothDetailsIntents.fromWireless(context, original, true));
    info.exported = true;
    info.applicationInfo.enabled = false;
    shadowOf(context.getPackageManager()).addOrUpdateActivity(info);
    assertSame(original, BluetoothDetailsIntents.fromWireless(context, original, true));
  }

  @Test public void missingOrInvalidNativeAddressNeverSelectsAnotherDeviceOrList() {
    for (String address : new String[]{null, "", "device", "12:34:56:78:9A", "12:34:56:78:9a:bc"}) {
      Intent original = nativeGear(address);
      assertSame(original, BluetoothDetailsIntents.fromNative(context, original, true));
      assertSame(original, BluetoothSettingsNavigation.redirect(context, original, true));
    }
    Intent missing = new Intent(BluetoothDetailsIntents.ACTION);
    assertSame(missing, BluetoothDetailsIntents.fromNative(context, missing, true));
  }

  @Test public void wrongWirelessParcelableAndMissingArgumentsKeepOriginal() {
    Intent missing = wireless(null);
    assertSame(missing, BluetoothDetailsIntents.fromWireless(context, missing, true));
    Bundle wrong = new Bundle();
    wrong.putString("device", SELECTED);
    Intent malformed = wireless(null).putExtra(BluetoothDetailsIntents.ARGUMENTS, wrong);
    assertSame(malformed, BluetoothDetailsIntents.fromWireless(context, malformed, true));
    Intent noArgs = wireless(null);
    noArgs.removeExtra(BluetoothDetailsIntents.ARGUMENTS);
    assertSame(noArgs, BluetoothDetailsIntents.fromWireless(context, noArgs, true));
  }

  @Test public void foreignComponentAndDifferentFragmentAreNotRerouted() {
    Intent foreignNative = nativeGear(SELECTED).setPackage("earbuds.companion");
    assertSame(foreignNative, BluetoothDetailsIntents.fromNative(context, foreignNative, true));
    assertSame(foreignNative, BluetoothSettingsNavigation.redirect(context, foreignNative, true));
    assertSame(foreignNative, BluetoothSettingsNavigation.redirect(context, foreignNative, false));
    Intent componentNative = nativeGear(SELECTED).setClassName(
        "earbuds.companion", "earbuds.companion.DeviceActivity");
    assertSame(componentNative, BluetoothDetailsIntents.fromNative(context, componentNative, true));
    Intent foreignWireless = wireless(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(SELECTED))
        .setClassName("earbuds.companion", BluetoothDetailsIntents.SUB_SETTINGS);
    assertSame(foreignWireless, BluetoothDetailsIntents.fromWireless(context, foreignWireless, true));
    Intent differentFragment = wireless(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(SELECTED))
        .putExtra(BluetoothDetailsIntents.FRAGMENT, "com.oplus.wirelesssettings.bluetooth.OtherFragment");
    assertSame(differentFragment, BluetoothDetailsIntents.fromWireless(context, differentFragment, true));
  }

  @Test public void profileSpecificLinksKeepTheirOriginalDestination() {
    for (String key : new String[]{":settings:fragment_args_key", "highlight_args_key"}) {
      Intent highlighted = wireless(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(SELECTED))
          .putExtra(key, "bt_abs_volume");
      assertSame(highlighted, BluetoothDetailsIntents.fromWireless(context, highlighted, true));
    }
  }

  @Test public void listPairingAndCompanionActionsKeepOriginalRouting() {
    for (String action : new String[]{Settings.ACTION_BLUETOOTH_SETTINGS,
        BluetoothSettingsNavigation.LIST, "android.bluetooth.device.action.PAIRING_REQUEST",
        "earbuds.companion.DEVICE_SETTINGS"}) {
      Intent original = new Intent(action);
      assertSame(original, BluetoothDetailsIntents.fromNative(context, original, true));
      assertSame(original, BluetoothDetailsIntents.fromWireless(context, original, true));
    }
  }

  @Test public void qsUnavailableNativePageFallsBackToSelectedOosDetails() {
    shadowOf(context.getPackageManager()).removeActivity(NATIVE);
    registerOos(BluetoothSettingsNavigation.PROFILES);
    Intent original = nativeGear(SELECTED).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    Intent result = BluetoothSettingsNavigation.redirect(context, original, true);
    assertEquals(BluetoothSettingsNavigation.PROFILES, result.getAction());
    assertEquals(BluetoothDetailsIntents.WIRELESS, result.getPackage());
    assertEquals(SELECTED, ((BluetoothDevice)result.getBundleExtra(BluetoothDetailsIntents.ARGUMENTS)
        .get("device")).getAddress());
    assertEquals(original.getFlags(), result.getFlags());
    assertFalse(original.getBundleExtra(BluetoothDetailsIntents.ARGUMENTS).containsKey("device"));
  }

  @Test public void qsToggleOffKeepsOosDeviceDetailsAndLists() {
    registerOos(BluetoothSettingsNavigation.PROFILES);
    registerOos(Settings.ACTION_BLUETOOTH_SETTINGS);
    assertEquals(BluetoothSettingsNavigation.PROFILES,
        BluetoothSettingsNavigation.redirect(context, nativeGear(SELECTED), false).getAction());
    for (boolean enabled : new boolean[]{false, true}) {
      Intent list = BluetoothSettingsNavigation.redirect(context,
          new Intent(BluetoothSettingsNavigation.LIST), enabled);
      Intent longPress = BluetoothSettingsNavigation.redirect(context,
          new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), enabled);
      assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, list.getAction());
      assertEquals(BluetoothDetailsIntents.WIRELESS, list.getPackage());
      assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, longPress.getAction());
      assertEquals(BluetoothDetailsIntents.WIRELESS, longPress.getPackage());
    }
  }

  @Test public void qsEnabledNativeGearNoLongerRedirectsToOos() {
    registerOos(BluetoothSettingsNavigation.PROFILES);
    Intent result = BluetoothSettingsNavigation.redirect(context, nativeGear(SELECTED), true);
    assertEquals(NATIVE, result.getComponent());
    assertEquals(SELECTED, BluetoothDetailsIntents.selectedNativeAddress(result));
  }

  private void registerOos(String action) {
    ComponentName activity = new ComponentName(BluetoothDetailsIntents.WIRELESS,
        "com.android.settings.Settings$DeviceProfilesSettingsActivity");
    shadowOf(context.getPackageManager()).addActivityIfNotPresent(activity).exported = true;
    IntentFilter filter = new IntentFilter(action);
    filter.addCategory(Intent.CATEGORY_DEFAULT);
    shadowOf(context.getPackageManager()).addIntentFilterForActivity(activity, filter);
  }

  private static Intent nativeGear(String address) {
    Bundle arguments = new Bundle();
    arguments.putString(BluetoothDetailsIntents.ADDRESS, address);
    return new Intent(BluetoothDetailsIntents.ACTION).putExtra(BluetoothDetailsIntents.ARGUMENTS, arguments);
  }

  private static Intent wireless(BluetoothDevice selected) {
    Bundle arguments = new Bundle();
    arguments.putParcelable("device", selected);
    return new Intent().setClassName(BluetoothDetailsIntents.WIRELESS, BluetoothDetailsIntents.SUB_SETTINGS)
        .putExtra(BluetoothDetailsIntents.FRAGMENT, BluetoothDetailsIntents.PROFILE_FRAGMENT)
        .putExtra(BluetoothDetailsIntents.ARGUMENTS, arguments);
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
