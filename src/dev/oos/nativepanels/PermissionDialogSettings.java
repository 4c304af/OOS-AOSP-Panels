package dev.oos.nativepanels;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

/** A separate control channel for PermissionController, independent of SystemUI. */
final class PermissionDialogSettings {
  static final String AUTHORITY = "dev.oos.nativepanels.permissiondialog";
  static final Uri URI = Uri.parse("content://" + AUTHORITY);
  static final String QUERY = CombinedQsSettings.PERMISSION_DIALOG;
  static final String REPORT = "save_permission_dialog_diagnostics";
  private static final int MAX_REPORT_LENGTH = 4096;

  static boolean readEnabled(Context caller) {
    try {
      Bundle result = caller.getContentResolver().call(URI, QUERY, null, null);
      return result != null && result.getBoolean(QUERY, false);
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  static boolean report(Context caller, String text) {
    if (text == null || text.isEmpty()) return false;
    Bundle data = new Bundle();
    data.putString("report", text.substring(0, Math.min(text.length(), MAX_REPORT_LENGTH)));
    try {
      return caller.getContentResolver().call(URI, REPORT, null, data) != null;
    } catch (RuntimeException unavailable) {
      // Reporting must never interrupt an app's permission request.
      return false;
    }
  }

  /** Keep one changed outcome per controller process, rather than reporting every dialog. */
  static final class Reporter {
    private String lastReport;

    synchronized void report(Context caller, String text) {
      if (text == null || text.isEmpty()) return;
      String bounded = text.substring(0, Math.min(text.length(), MAX_REPORT_LENGTH));
      if (!bounded.equals(lastReport) && PermissionDialogSettings.report(caller, bounded))
        lastReport = bounded;
    }
  }

  private static SharedPreferences diagnostics(Context context) {
    return context.createDeviceProtectedStorageContext()
        .getSharedPreferences("permission_dialog_diagnostics", Context.MODE_PRIVATE);
  }

  static void remember(Context context, String text) {
    if (text == null || text.isEmpty()) return;
    String bounded = text.substring(0, Math.min(text.length(), MAX_REPORT_LENGTH));
    SharedPreferences saved = diagnostics(context);
    if (!bounded.equals(saved.getString("text", null)))
      saved.edit().putString("text", bounded).putLong("time", System.currentTimeMillis()).apply();
  }

  static String diagnostic(Context module) {
    String state = module.getString(CombinedQsSettings.enabled(module, QUERY)
        ? R.string.enabled_label : R.string.disabled_label);
    SharedPreferences saved = diagnostics(module);
    String text = saved.getString("text", null);
    String detail = module.getString(R.string.permission_dialogs_not_seen);
    if (text != null) {
      String when = java.text.DateFormat.getDateTimeInstance().format(
          new java.util.Date(saved.getLong("time", 0)));
      detail = module.getString(R.string.saved_diagnostics, when) + "\n" + text;
    }
    return module.getString(R.string.permission_dialogs) + ": " + state + "\n" + detail;
  }

  private PermissionDialogSettings() {}
}
