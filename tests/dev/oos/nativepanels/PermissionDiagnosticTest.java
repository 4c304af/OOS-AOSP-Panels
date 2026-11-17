package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PermissionDiagnosticTest {
  private Context context;
  private CountingProvider provider;

  @Before public void setUp() {
    context = RuntimeEnvironment.getApplication();
    provider = new CountingProvider();
    ProviderInfo info = new ProviderInfo();
    info.authority = PermissionDialogSettings.AUTHORITY;
    provider.attachInfo(context, info);
    ShadowContentResolver.registerProviderInternal(PermissionDialogSettings.AUTHORITY, provider);
  }

  @Test public void repeatedDialogsDoNotRepeatProviderIpcButChangedOutcomesDo() {
    PermissionDialogSettings.Reporter reporter = new PermissionDialogSettings.Reporter();
    reporter.report(context, "Native permission surface");
    for (int i = 0; i < 20; i++) reporter.report(context, "Native permission surface");
    assertEquals(1, provider.calls);

    reporter.report(context, "Original surface: unavailable native resources");
    assertEquals(2, provider.calls);
    reporter.report(context, "Native permission surface");
    assertEquals(3, provider.calls);
    assertEquals("Native permission surface", provider.report);
  }

  @Test public void failedDeliveryCanRetryInsteadOfCachingAnUnsentReport() {
    PermissionDialogSettings.Reporter reporter = new PermissionDialogSettings.Reporter();
    provider.fail = true;
    reporter.report(context, "Native permission surface");
    assertEquals(1, provider.calls);
    provider.fail = false;
    reporter.report(context, "Native permission surface");
    reporter.report(context, "Native permission surface");
    assertEquals(2, provider.calls);
    assertEquals("Native permission surface", provider.report);
  }

  @Test public void contentOutsideTheReportLimitDoesNotCauseMoreIpc() {
    PermissionDialogSettings.Reporter reporter = new PermissionDialogSettings.Reporter();
    String common = "x".repeat(4096);
    reporter.report(context, common + "first suffix");
    reporter.report(context, common + "second suffix");
    assertEquals(1, provider.calls);
    assertEquals(common, provider.report);
  }

  @Test public void providerDoesNotRewriteAnIdenticalStoredOutcomeAfterProcessRestart() {
    PermissionDialogSettings.remember(context, "Native permission surface");
    SharedPreferences saved = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("permission_dialog_diagnostics", Context.MODE_PRIVATE);
    saved.edit().putLong("time", 1234).commit();
    PermissionDialogSettings.remember(context, "Native permission surface");
    assertEquals(1234, saved.getLong("time", 0));

    PermissionDialogSettings.remember(context, "Original surface: inflater unavailable");
    assertNotEquals(1234, saved.getLong("time", 0));
    assertEquals("Original surface: inflater unavailable", saved.getString("text", null));
  }

  private static final class CountingProvider extends ContentProvider {
    int calls;
    boolean fail;
    String report;

    @Override public Bundle call(String method, String arg, Bundle extras) {
      assertEquals(PermissionDialogSettings.REPORT, method);
      calls++;
      if (fail) throw new IllegalStateException("Provider temporarily unavailable");
      report = extras.getString("report");
      return Bundle.EMPTY;
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
        String[] selectionArgs, String sortOrder) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection,
        String[] selectionArgs) { throw new UnsupportedOperationException(); }
  }
}
