package dev.oos.nativepanels;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

public final class CombinedQsSettingsProvider extends ContentProvider {
  @Override public boolean onCreate() { return true; }

  @Override
  public Bundle call(String method, String arg, Bundle extras) {
    if (DiagnosticSnapshot.SAVE.equals(method)) {
      if (extras != null) DiagnosticSnapshot.remember(getContext(), extras.getString("report"));
      return Bundle.EMPTY;
    }
    if (!CombinedQsSettings.QUERY.equals(method) && !CombinedQsSettings.standalone(method)) return null;
    Bundle result = new Bundle();
    result.putBoolean(method, CombinedQsSettings.enabled(getContext(), method));
    return result;
  }

  @Override public String getType(Uri uri) { return null; }
  @Override public Cursor query(Uri uri, String[] projection, String selection,
      String[] args, String sortOrder) { return null; }
  @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
  @Override public int update(Uri uri, ContentValues values, String where, String[] args) {
    throw new UnsupportedOperationException();
  }
  @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}
