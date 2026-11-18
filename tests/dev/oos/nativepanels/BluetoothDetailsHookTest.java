package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.view.ContextThemeWrapper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BluetoothDetailsHookTest {
  private static Intent selected() {
    Bundle args = new Bundle();
    args.putString("device_address", "01:23:45:67:89:AB");
    return new Intent("com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS")
        .putExtra(OosSettingsIntents.FRAGMENT_ARGS, args);
  }

  @Test public void nativeExportedDevicePageAcceptsTheSelectedAddressAfterParceling() {
    Intent intent = selected();
    Parcel parcel = Parcel.obtain();
    try {
      intent.writeToParcel(parcel, 0);
      parcel.setDataPosition(0);
      Intent restored = Intent.CREATOR.createFromParcel(parcel);
      assertTrue(BluetoothDetailsHook.eligible(BluetoothDetailsHook.ACTIVITY, restored));
      assertEquals("01:23:45:67:89:AB", restored.getBundleExtra(OosSettingsIntents.FRAGMENT_ARGS)
          .getString("device_address"));
    } finally { parcel.recycle(); }
  }

  @Test public void internalSubSettingsRequiresExactDeviceFragment() {
    Intent intent = selected();
    assertFalse(BluetoothDetailsHook.eligible(BluetoothDetailsHook.SUB_SETTINGS, intent));
    intent.putExtra(BluetoothDetailsHook.SHOW_FRAGMENT, BluetoothDetailsHook.FRAGMENT);
    assertTrue(BluetoothDetailsHook.eligible(BluetoothDetailsHook.SUB_SETTINGS, intent));
    intent.putExtra(BluetoothDetailsHook.SHOW_FRAGMENT,
        "com.android.settings.bluetooth.BluetoothDeviceMoreSettingsFragment");
    assertFalse(BluetoothDetailsHook.eligible(BluetoothDetailsHook.SUB_SETTINGS, intent));
  }

  @Test public void explicitDeviceActivityCannotUseAConflictingOrMalformedFragment() {
    for (String fragment : new String[]{"", "com.android.settings.wifi.WifiSettings", null}) {
      Intent intent = selected().putExtra(BluetoothDetailsHook.SHOW_FRAGMENT, fragment);
      assertFalse(BluetoothDetailsHook.eligible(BluetoothDetailsHook.ACTIVITY, intent));
    }
    assertFalse(BluetoothDetailsHook.eligible(BluetoothDetailsHook.ACTIVITY,
        selected().putExtra(BluetoothDetailsHook.SHOW_FRAGMENT, 42)));
  }

  @Test public void aBluetoothIntentDoesNotChangeOtherSettingsActivities() {
    Intent intent = selected().putExtra(BluetoothDetailsHook.SHOW_FRAGMENT, BluetoothDetailsHook.FRAGMENT);
    for (String activity : new String[]{"com.android.settings.SettingsActivity",
        "com.android.settings.Settings$BluetoothSettingsActivity",
        "com.android.settings.Settings$PreviouslyConnectedDeviceActivity",
        "com.example.SubSettings", null}) {
      assertFalse(BluetoothDetailsHook.eligible(activity, intent));
    }
  }

  @Test public void missingOrInvalidSelectionKeepsTheOriginalPage() {
    assertFalse(BluetoothDetailsHook.eligible(BluetoothDetailsHook.ACTIVITY, null));
    assertFalse(BluetoothDetailsHook.eligible(BluetoothDetailsHook.ACTIVITY, new Intent()));
    assertFalse(BluetoothDetailsHook.eligible(BluetoothDetailsHook.ACTIVITY,
        new Intent().putExtra("device_address", "01:23:45:67:89:AB")));
    for (String address : new String[]{null, "", "01:23:45:67:89", "01:23:45:67:89:XY"}) {
      Intent intent = selected();
      intent.getBundleExtra(OosSettingsIntents.FRAGMENT_ARGS).putString("device_address", address);
      assertFalse(BluetoothDetailsHook.eligible(BluetoothDetailsHook.ACTIVITY, intent));
    }
    assertFalse(BluetoothDetailsHook.eligible(BluetoothDetailsHook.ACTIVITY,
        new Intent().putExtra(OosSettingsIntents.FRAGMENT_ARGS, "not a bundle")));
  }

  @Test public void themedPreferenceContextsResolveOnlyTheirOwnActivity() {
    try (var first = Robolectric.buildActivity(Activity.class).setup();
         var second = Robolectric.buildActivity(Activity.class).setup()) {
      Context wrapped = new ContextWrapper(new ContextThemeWrapper(first.get(), android.R.style.Theme_Material));
      assertSame(first.get(), BluetoothDetailsHook.activity(wrapped));
      assertSame(second.get(), BluetoothDetailsHook.activity(new ContextWrapper(second.get())));
      assertNull(BluetoothDetailsHook.activity(RuntimeEnvironment.getApplication()));
      assertNull(BluetoothDetailsHook.activity(null));
    }
  }

  @Test public void brokenContextWrappersCannotLoopTheThemeLookup() {
    class Loop extends ContextWrapper {
      Context next;
      Loop() { super(null); }
      @Override public Context getBaseContext() { return next; }
    }
    Loop one = new Loop(), two = new Loop();
    one.next = one;
    assertNull(BluetoothDetailsHook.activity(one));
    one.next = two; two.next = one;
    assertNull(BluetoothDetailsHook.activity(one));
  }
}
