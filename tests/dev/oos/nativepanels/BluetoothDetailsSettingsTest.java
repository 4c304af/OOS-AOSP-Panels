package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.View;
import com.google.android.material.materialswitch.MaterialSwitch;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
public class BluetoothDetailsSettingsTest {
  private static final int HOST_UID = 10421;
  private static final int OTHER_UID = 10422;
  private Context context;
  private BluetoothDetailsSettingsProvider provider;

  @Before public void setUp() {
    context = RuntimeEnvironment.getApplication();
    ShadowBinder.setCallingUid(context.getApplicationInfo().uid);
    provider = Robolectric.setupContentProvider(BluetoothDetailsSettingsProvider.class);
  }

  @After public void resetCallingIdentity() { ShadowBinder.reset(); }

  @Test public void startsDisabledAndSwitchWorksWithoutSystemUi() {
    assertFalse(CombinedQsSettings.enabled(context, CombinedQsSettings.BLUETOOTH_DETAILS));
    assertFalse(BluetoothDetailsSettings.readEnabled(context));
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup().visible()) {
      MainActivity activity = controller.get();
      ShadowLooper.idleMainLooper();
      MaterialSwitch details = toggle(activity, CombinedQsSettings.BLUETOOTH_DETAILS);
      assertFalse(details.isChecked());
      assertTrue(details.isEnabled());
      details.performClick();
      assertTrue(BluetoothDetailsSettings.readEnabled(context));
      toggle(activity, "combined_qs").performClick();
      ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS);
      assertTrue(details.isShown());
      assertFalse(activity.getWindow().getDecorView().findViewWithTag("internet").isShown());
      controller.recreate();
      ShadowLooper.idleMainLooper();
      details = toggle(controller.get(), CombinedQsSettings.BLUETOOTH_DETAILS);
      assertTrue(details.isChecked());
      details.performClick();
      assertFalse(BluetoothDetailsSettings.readEnabled(context));
      for (Intent intent : Shadows.shadowOf(activity.getApplication()).getBroadcastIntents())
        assertNotEquals(CombinedQsSettings.BLUETOOTH_DETAILS, intent.getStringExtra("feature"));
    }
  }

  @Test public void onlyTheModuleAndParticipatingSystemHostsMayRead() {
    assertTrue(BluetoothDetailsSettingsProvider.allowedCaller(context, context.getApplicationInfo().uid));
    assertFalse(BluetoothDetailsSettingsProvider.allowedCaller(context, OTHER_UID));
    for (String name : new String[]{BluetoothDetailsSettings.SETTINGS_PACKAGE,
        BluetoothDetailsSettings.WIRELESS_PACKAGE, Protocol.TARGET}) {
      installPackage(name, HOST_UID, ApplicationInfo.FLAG_SYSTEM);
      assertTrue(name, BluetoothDetailsSettingsProvider.allowedCaller(context, HOST_UID));
      installPackage(name, HOST_UID, ApplicationInfo.FLAG_UPDATED_SYSTEM_APP);
      assertTrue(name, BluetoothDetailsSettingsProvider.allowedCaller(context, HOST_UID));
      installPackage(name, HOST_UID, 0);
      assertFalse(name, BluetoothDetailsSettingsProvider.allowedCaller(context, HOST_UID));
    }
    installPackage("com.example.settings", OTHER_UID, ApplicationInfo.FLAG_SYSTEM);
    assertFalse(BluetoothDetailsSettingsProvider.allowedCaller(context, OTHER_UID));
  }

  @Test public void callerCannotBorrowAnotherPackagesSystemIdentity() {
    installPackage(BluetoothDetailsSettings.SETTINGS_PACKAGE, HOST_UID, ApplicationInfo.FLAG_SYSTEM);
    Shadows.shadowOf(context.getPackageManager()).setPackagesForUid(OTHER_UID,
        BluetoothDetailsSettings.SETTINGS_PACKAGE);
    assertFalse(BluetoothDetailsSettingsProvider.allowedCaller(context, OTHER_UID));
  }

  @Test public void validHostCanReadButCannotChangeTheSwitchOrWriteDiagnostics() {
    installPackage(BluetoothDetailsSettings.WIRELESS_PACKAGE, HOST_UID, ApplicationInfo.FLAG_SYSTEM);
    ShadowBinder.setCallingUid(HOST_UID);
    Bundle attemptedWrite = new Bundle();
    attemptedWrite.putBoolean(BluetoothDetailsSettings.QUERY, true);
    attemptedWrite.putBoolean("enabled", true);
    Bundle answer = provider.call(BluetoothDetailsSettings.QUERY, null, attemptedWrite);
    assertNotNull(answer);
    assertFalse(answer.getBoolean(BluetoothDetailsSettings.QUERY));
    assertFalse(CombinedQsSettings.enabled(context, CombinedQsSettings.BLUETOOTH_DETAILS));
    assertThrows(IllegalArgumentException.class, () -> provider.call("set", null, attemptedWrite));
    assertThrows(IllegalArgumentException.class, () -> provider.call("report", null, attemptedWrite));
    assertFalse(CombinedQsSettings.enabled(context, CombinedQsSettings.BLUETOOTH_DETAILS));
  }

  @Test public void blockedProviderCallLeavesNativeBehaviorAndSavedPreferenceIntact() {
    assertTrue(CombinedQsSettings.setEnabled(context, CombinedQsSettings.BLUETOOTH_DETAILS, true));
    ShadowBinder.setCallingUid(OTHER_UID);
    assertThrows(SecurityException.class,
        () -> provider.call(BluetoothDetailsSettings.QUERY, null, null));
    assertFalse(BluetoothDetailsSettings.readEnabled(context));
    assertTrue(CombinedQsSettings.enabled(context, CombinedQsSettings.BLUETOOTH_DETAILS));
  }

  @Test public void providerHasNoGenericWriteSurface() {
    assertThrows(UnsupportedOperationException.class,
        () -> provider.insert(BluetoothDetailsSettings.URI, new android.content.ContentValues()));
    assertThrows(UnsupportedOperationException.class,
        () -> provider.update(BluetoothDetailsSettings.URI, new android.content.ContentValues(), null, null));
    assertThrows(UnsupportedOperationException.class,
        () -> provider.delete(BluetoothDetailsSettings.URI, null, null));
  }

  private MaterialSwitch toggle(MainActivity activity, String key) {
    View found = activity.getWindow().getDecorView().findViewWithTag(key);
    assertTrue(key, found instanceof MaterialSwitch);
    return (MaterialSwitch)found;
  }

  private void installPackage(String name, int uid, int flags) {
    PackageInfo info = new PackageInfo();
    info.packageName = name;
    info.applicationInfo = new ApplicationInfo();
    info.applicationInfo.packageName = name;
    info.applicationInfo.uid = uid;
    info.applicationInfo.flags = flags;
    var packages = Shadows.shadowOf(context.getPackageManager());
    packages.installPackage(info);
    packages.setPackagesForUid(uid, name);
  }
}
