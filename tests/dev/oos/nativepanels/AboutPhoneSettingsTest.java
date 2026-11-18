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
public class AboutPhoneSettingsTest {
  private static final int SETTINGS_UID = 10421;
  private static final int OTHER_UID = 10422;
  private Context context;
  private AboutPhoneSettingsProvider provider;

  @Before public void setUp() {
    context = RuntimeEnvironment.getApplication();
    ShadowBinder.setCallingUid(context.getApplicationInfo().uid);
    provider = Robolectric.setupContentProvider(AboutPhoneSettingsProvider.class);
  }

  @After public void resetCallingIdentity() { ShadowBinder.reset(); }

  @Test public void defaultOffStandaloneSwitchWorksOfflineAndDoesNotChangeBluetooth() {
    assertFalse(CombinedQsSettings.enabled(context, CombinedQsSettings.ABOUT_PHONE));
    assertFalse(AboutPhoneSettings.readEnabled(context));
    assertTrue(CombinedQsSettings.setEnabled(context, CombinedQsSettings.BLUETOOTH_DETAILS, true));
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup().visible()) {
      MainActivity activity = controller.get();
      ShadowLooper.idleMainLooper();
      MaterialSwitch about = toggle(activity, CombinedQsSettings.ABOUT_PHONE);
      assertFalse(about.isChecked());
      assertTrue(about.isEnabled());
      about.performClick();
      assertTrue(AboutPhoneSettings.readEnabled(context));
      toggle(activity, "combined_qs").performClick();
      ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS);
      assertTrue(about.isShown());
      controller.recreate();
      ShadowLooper.idleMainLooper();
      about = toggle(controller.get(), CombinedQsSettings.ABOUT_PHONE);
      assertTrue(about.isChecked());
      about.performClick();
      assertFalse(AboutPhoneSettings.readEnabled(context));
      assertTrue(CombinedQsSettings.enabled(context, CombinedQsSettings.BLUETOOTH_DETAILS));
      for (Intent intent : Shadows.shadowOf(activity.getApplication()).getBroadcastIntents())
        assertNotEquals(CombinedQsSettings.ABOUT_PHONE, intent.getStringExtra("feature"));
    }
  }

  @Test public void onlyModuleAndSystemSettingsMayReadAboutPhonePreference() {
    assertTrue(AboutPhoneSettingsProvider.allowedCaller(context, context.getApplicationInfo().uid));
    assertFalse(AboutPhoneSettingsProvider.allowedCaller(context, OTHER_UID));
    installPackage(AboutPhoneSettings.SETTINGS_PACKAGE, SETTINGS_UID, ApplicationInfo.FLAG_SYSTEM);
    assertTrue(AboutPhoneSettingsProvider.allowedCaller(context, SETTINGS_UID));
    installPackage(AboutPhoneSettings.SETTINGS_PACKAGE, SETTINGS_UID, ApplicationInfo.FLAG_UPDATED_SYSTEM_APP);
    assertTrue(AboutPhoneSettingsProvider.allowedCaller(context, SETTINGS_UID));
    installPackage(AboutPhoneSettings.SETTINGS_PACKAGE, SETTINGS_UID, 0);
    assertFalse(AboutPhoneSettingsProvider.allowedCaller(context, SETTINGS_UID));
    for (String name : new String[]{BluetoothDetailsSettings.WIRELESS_PACKAGE,
        Protocol.TARGET, "com.example.settings"}) {
      installPackage(name, OTHER_UID, ApplicationInfo.FLAG_SYSTEM);
      assertFalse(name, AboutPhoneSettingsProvider.allowedCaller(context, OTHER_UID));
    }
  }

  @Test public void aCallerCannotBorrowSettingsSystemIdentity() {
    installPackage(AboutPhoneSettings.SETTINGS_PACKAGE, SETTINGS_UID, ApplicationInfo.FLAG_SYSTEM);
    Shadows.shadowOf(context.getPackageManager()).setPackagesForUid(OTHER_UID,
        AboutPhoneSettings.SETTINGS_PACKAGE);
    assertFalse(AboutPhoneSettingsProvider.allowedCaller(context, OTHER_UID));
  }

  @Test public void settingsCanReadButCannotChangeThePreference() {
    installPackage(AboutPhoneSettings.SETTINGS_PACKAGE, SETTINGS_UID, ApplicationInfo.FLAG_SYSTEM);
    ShadowBinder.setCallingUid(SETTINGS_UID);
    Bundle attemptedWrite = new Bundle();
    attemptedWrite.putBoolean(AboutPhoneSettings.QUERY, true);
    attemptedWrite.putBoolean("enabled", true);
    Bundle answer = provider.call(AboutPhoneSettings.QUERY, null, attemptedWrite);
    assertFalse(answer.getBoolean(AboutPhoneSettings.QUERY));
    assertFalse(CombinedQsSettings.enabled(context, CombinedQsSettings.ABOUT_PHONE));
    assertThrows(IllegalArgumentException.class, () -> provider.call("set", null, attemptedWrite));
    assertThrows(IllegalArgumentException.class, () -> provider.call("report", null, attemptedWrite));
  }

  @Test public void failedProviderReadKeepsNativeBehaviorAndTheSavedSwitch() {
    assertTrue(CombinedQsSettings.setEnabled(context, CombinedQsSettings.ABOUT_PHONE, true));
    ShadowBinder.setCallingUid(OTHER_UID);
    assertThrows(SecurityException.class, () -> provider.call(AboutPhoneSettings.QUERY, null, null));
    assertFalse(AboutPhoneSettings.readEnabled(context));
    assertTrue(CombinedQsSettings.enabled(context, CombinedQsSettings.ABOUT_PHONE));
  }

  @Test public void providerHasNoGenericWriteSurface() {
    assertThrows(UnsupportedOperationException.class,
        () -> provider.insert(AboutPhoneSettings.URI, new android.content.ContentValues()));
    assertThrows(UnsupportedOperationException.class,
        () -> provider.update(AboutPhoneSettings.URI, new android.content.ContentValues(), null, null));
    assertThrows(UnsupportedOperationException.class,
        () -> provider.delete(AboutPhoneSettings.URI, null, null));
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
