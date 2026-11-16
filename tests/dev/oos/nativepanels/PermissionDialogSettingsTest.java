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
public class PermissionDialogSettingsTest {
  private static final int CONTROLLER_UID = 10421;
  private static final int OTHER_UID = 10422;
  private Context context;
  private PermissionDialogSettingsProvider provider;

  @Before public void setUp() {
    context = RuntimeEnvironment.getApplication();
    ShadowBinder.setCallingUid(context.getApplicationInfo().uid);
    provider = Robolectric.setupContentProvider(PermissionDialogSettingsProvider.class);
  }

  @After public void resetCallingIdentity() {
    ShadowBinder.reset();
  }

  @Test public void startsDisabledAndCanBeChangedWhileSystemUiIsOffline() {
    assertFalse(CombinedQsSettings.enabled(context, CombinedQsSettings.PERMISSION_DIALOG));
    assertFalse(PermissionDialogSettings.readEnabled(context));
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup().visible()) {
      MainActivity activity = controller.get();
      ShadowLooper.idleMainLooper();
      MaterialSwitch permission = toggle(activity, CombinedQsSettings.PERMISSION_DIALOG);
      assertFalse(permission.isChecked());
      assertTrue(permission.isEnabled());
      permission.performClick();
      assertTrue(PermissionDialogSettings.readEnabled(context));

      toggle(activity, "combined_qs").performClick();
      ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS);
      assertTrue(permission.isShown());
      assertFalse(activity.getWindow().getDecorView().findViewWithTag("internet").isShown());
      controller.recreate();
      ShadowLooper.idleMainLooper();
      permission = toggle(controller.get(), CombinedQsSettings.PERMISSION_DIALOG);
      assertTrue(permission.isChecked());
      assertTrue(permission.isShown());
      permission.performClick();
      assertFalse(PermissionDialogSettings.readEnabled(context));

      for (Intent intent : Shadows.shadowOf(activity.getApplication()).getBroadcastIntents())
        assertNotEquals(CombinedQsSettings.PERMISSION_DIALOG, intent.getStringExtra("feature"));
    }
  }

  @Test public void allowsOnlyTheModuleAndSystemPermissionControllers() {
    assertTrue(PermissionDialogSettingsProvider.allowedCaller(context, context.getApplicationInfo().uid));
    assertFalse(PermissionDialogSettingsProvider.allowedCaller(context, OTHER_UID));
    installPackage("com.google.android.permissioncontroller", CONTROLLER_UID, ApplicationInfo.FLAG_SYSTEM);
    assertTrue(PermissionDialogSettingsProvider.allowedCaller(context, CONTROLLER_UID));
    installPackage("com.android.permissioncontroller", OTHER_UID, ApplicationInfo.FLAG_UPDATED_SYSTEM_APP);
    assertTrue(PermissionDialogSettingsProvider.allowedCaller(context, OTHER_UID));

    installPackage("com.google.android.permissioncontroller", CONTROLLER_UID, 0);
    assertFalse(PermissionDialogSettingsProvider.allowedCaller(context, CONTROLLER_UID));
    installPackage("com.example.permissioncontroller", OTHER_UID, ApplicationInfo.FLAG_SYSTEM);
    assertFalse(PermissionDialogSettingsProvider.allowedCaller(context, OTHER_UID));
  }

  @Test public void callerCannotBorrowAnotherPackagesSystemIdentity() {
    installPackage("com.google.android.permissioncontroller", CONTROLLER_UID, ApplicationInfo.FLAG_SYSTEM);
    Shadows.shadowOf(context.getPackageManager()).setPackagesForUid(OTHER_UID,
        "com.google.android.permissioncontroller");
    assertFalse(PermissionDialogSettingsProvider.allowedCaller(context, OTHER_UID));
  }

  @Test public void controllerCanReadAndReportWithoutChangingTheSwitch() {
    installPackage("com.google.android.permissioncontroller", CONTROLLER_UID, ApplicationInfo.FLAG_SYSTEM);
    ShadowBinder.setCallingUid(CONTROLLER_UID);
    Bundle attemptedWrite = new Bundle();
    attemptedWrite.putBoolean(PermissionDialogSettings.QUERY, true);
    attemptedWrite.putBoolean("enabled", true);
    Bundle answer = call(PermissionDialogSettings.QUERY, attemptedWrite);
    assertNotNull(answer);
    assertFalse(answer.getBoolean(PermissionDialogSettings.QUERY));
    assertFalse(CombinedQsSettings.enabled(context, CombinedQsSettings.PERMISSION_DIALOG));

    Bundle report = new Bundle();
    report.putString("report", "Permission UI: native handler selected");
    report.putBoolean("enabled", true);
    call(PermissionDialogSettings.REPORT, report);
    assertTrue(PermissionDialogSettings.diagnostic(context).contains("native handler selected"));
    assertFalse(CombinedQsSettings.enabled(context, CombinedQsSettings.PERMISSION_DIALOG));
    assertThrows(IllegalArgumentException.class, () -> call("set", attemptedWrite));
    assertFalse(CombinedQsSettings.enabled(context, CombinedQsSettings.PERMISSION_DIALOG));
  }

  @Test public void untrustedCallersCannotReadOrReplaceDiagnostics() {
    PermissionDialogSettings.report(context, "Verified controller report");
    String before = PermissionDialogSettings.diagnostic(context);
    ShadowBinder.setCallingUid(OTHER_UID);
    Bundle forged = new Bundle();
    forged.putString("report", "Forged report");
    assertThrows(SecurityException.class, () -> call(PermissionDialogSettings.QUERY, null));
    assertThrows(SecurityException.class, () -> call(PermissionDialogSettings.REPORT, forged));
    assertEquals(before, PermissionDialogSettings.diagnostic(context));
  }

  @Test public void ipcFailureLeavesNativeBehaviorAvailable() {
    assertTrue(CombinedQsSettings.setEnabled(context, CombinedQsSettings.PERMISSION_DIALOG, true));
    PermissionDialogSettings.report(context, "Last known report");
    String before = PermissionDialogSettings.diagnostic(context);
    ShadowBinder.setCallingUid(OTHER_UID);
    assertFalse(PermissionDialogSettings.readEnabled(context));
    PermissionDialogSettings.report(context, "This call must not replace the stored report");
    assertEquals(before, PermissionDialogSettings.diagnostic(context));
    assertTrue(CombinedQsSettings.enabled(context, CombinedQsSettings.PERMISSION_DIALOG));
  }

  @Test public void permissionReportsDoNotOverwriteSystemUiDiagnostics() {
    DiagnosticSnapshot.remember(context, "SystemUI: original report");
    PermissionDialogSettings.report(context, "Permission UI: native theme active");
    assertEquals("SystemUI: original report", DiagnosticSnapshot.read(context).text());
    assertTrue(PermissionDialogSettings.diagnostic(context).contains("native theme active"));
    assertFalse(PermissionDialogSettings.diagnostic(context).contains("SystemUI: original report"));
  }

  @Test public void providerHasNoGenericWriteSurface() {
    assertThrows(UnsupportedOperationException.class,
        () -> provider.insert(PermissionDialogSettings.URI, new android.content.ContentValues()));
    assertThrows(UnsupportedOperationException.class,
        () -> provider.update(PermissionDialogSettings.URI, new android.content.ContentValues(), null, null));
    assertThrows(UnsupportedOperationException.class,
        () -> provider.delete(PermissionDialogSettings.URI, null, null));
  }

  private Bundle call(String method, Bundle extras) {
    return provider.call(method, null, extras);
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
