package dev.oos.nativepanels;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** The system launcher's read-only switch is independent of SystemUI permissions. */
public final class LauncherIconSettingsProvider extends ContentProvider {
  @Override public boolean onCreate() { return true; }

  static boolean allowedCaller(Context context, int uid) {
    if (context.getApplicationInfo().uid == uid) return true;
    try {
      ApplicationInfo launcher = context.getPackageManager().getApplicationInfo(LauncherIconRefresh.PACKAGE, 0);
      return launcher.uid == uid && (launcher.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    } catch (PackageManager.NameNotFoundException unavailable) {
      return false;
    }
  }

  @Override public Bundle call(String method, String arg, Bundle extras) {
    if (!allowedCaller(getContext(), Binder.getCallingUid())) throw new SecurityException("System launcher required");
    if (!CombinedQsSettings.LAUNCHER_ICONS.equals(method)) throw new IllegalArgumentException("Unknown launcher setting");
    Bundle result = new Bundle();
    result.putBoolean(method, CombinedQsSettings.enabled(getContext(), method));
    return result;
  }

  @Override public String getType(Uri uri) { return null; }
  @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
    throw new UnsupportedOperationException();
  }
  @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
  @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
  @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
