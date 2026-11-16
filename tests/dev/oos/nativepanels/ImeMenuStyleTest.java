package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ImeMenuStyleTest {
  private ContentResolver resolver;
  private SharedPreferences prefs;
  private ImeMenuStyle style;
  private boolean failDelete;
  private int deletes;

  @Before public void setup() {
    Context context = RuntimeEnvironment.getApplication();
    resolver = context.getContentResolver();
    prefs = context.getSharedPreferences("ime-test", Context.MODE_PRIVATE);
    prefs.edit().clear().commit();
    style = new ImeMenuStyle(resolver, prefs, RuntimeEnvironment.getApplication()::getString);
    // ShadowSettings tracks Global values in memory. Supply the ROM's DELETE_global contract.
    ContentProvider provider = new ContentProvider() {
      @Override public boolean onCreate() { return true; }
      @Override public Bundle call(String method, String arg, Bundle extras) {
        assertEquals("DELETE_global", method);
        assertEquals(ImeMenuStyle.KEY, arg);
        deletes++;
        if (!failDelete) Settings.Global.putString(resolver, arg, null);
        return Bundle.EMPTY;
      }
      @Override public String getType(Uri uri) { throw new AssertionError(); }
      @Override public Cursor query(Uri uri, String[] cols, String q, String[] args, String sort) { throw new AssertionError(); }
      @Override public Uri insert(Uri uri, ContentValues v) { throw new AssertionError(); }
      @Override public int update(Uri uri, ContentValues v, String q, String[] args) { throw new AssertionError(); }
      @Override public int delete(Uri uri, String q, String[] args) { throw new AssertionError(); }
    };
    provider.attachInfo(context, null);
    ShadowContentResolver.registerProviderInternal("settings", provider);
  }

  @Test public void handlesManualOneFromBeforeInstall() {
    Settings.Global.putString(resolver, ImeMenuStyle.KEY, "1");
    assertEquals("1", style.read());
    style.setEnabled(false);
    assertNull(style.read());
    assertEquals(1, deletes);
  }

  @Test public void absentDefaultRoundTripsAcrossRestart() {
    assertNull(style.read());
    style.setEnabled(true);
    assertEquals("1", style.read());
    new ImeMenuStyle(resolver, prefs, RuntimeEnvironment.getApplication()::getString).setEnabled(false);
    assertNull(style.read());
    assertEquals(1, deletes);
  }

  @Test public void restoresExactPreviousValueAfterRepeatedEnable() {
    Settings.Global.putString(resolver, ImeMenuStyle.KEY, "0");
    style.setEnabled(true);
    new ImeMenuStyle(resolver, prefs, RuntimeEnvironment.getApplication()::getString).setEnabled(true);
    style.setEnabled(false);
    assertEquals("0", style.read());
    assertEquals(0, deletes);
  }

  @Test public void failedDeletionReflectsActualStateAndCanRetry() {
    style.setEnabled(true);
    failDelete = true;
    assertThrows(IllegalStateException.class, () -> style.setEnabled(false));
    assertEquals("1", style.read());
    failDelete = false;
    style.setEnabled(false);
    assertNull(style.read());
  }

  @Test public void preservesExternalEditAndUsesItAtNextEnable() {
    style.setEnabled(true);
    Settings.Global.putString(resolver, ImeMenuStyle.KEY, "3");
    style.setEnabled(false);
    assertEquals("3", style.read());
    style.setEnabled(true);
    style.setEnabled(false);
    assertEquals("3", style.read());
    assertEquals(0, deletes);
  }
}
