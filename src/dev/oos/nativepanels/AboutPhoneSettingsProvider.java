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

/** Exposes the About phone switch read-only to the system Settings app. */
public final class AboutPhoneSettingsProvider extends ContentProvider {
  @Override public boolean onCreate() { return true; }

  static boolean allowedCaller(Context context, int uid) {
    if (uid == context.getApplicationInfo().uid) return true;
    PackageManager packages = context.getPackageManager();
    String[] names = packages.getPackagesForUid(uid);
    if (names == null) return false;
    for (String name : names) {
      if (!AboutPhoneSettings.SETTINGS_PACKAGE.equals(name)) continue;
      try {
        ApplicationInfo application = packages.getApplicationInfo(name, 0);
        if (application.uid == uid && (application.flags
            & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) return true;
      } catch (PackageManager.NameNotFoundException missing) {
        // A package update or user switch may race the UID lookup.
      }
    }
    return false;
  }

  @Override public Bundle call(String method, String arg, Bundle extras) {
    if (!allowedCaller(getContext(), Binder.getCallingUid()))
      throw new SecurityException("Only system Settings may read this provider");
    if (!AboutPhoneSettings.QUERY.equals(method))
      throw new IllegalArgumentException("Unknown About phone settings method");
    Bundle result = new Bundle();
    result.putBoolean(AboutPhoneSettings.QUERY,
        CombinedQsSettings.enabled(getContext(), CombinedQsSettings.ABOUT_PHONE));
    return result;
  }

  @Override public String getType(Uri uri) { return null; }
  @Override public Cursor query(Uri uri, String[] projection, String selection,
      String[] args, String sortOrder) { throw new UnsupportedOperationException(); }
  @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
  @Override public int update(Uri uri, ContentValues values, String where, String[] args) {
    throw new UnsupportedOperationException();
  }
  @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}
