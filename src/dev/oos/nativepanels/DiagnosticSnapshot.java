package dev.oos.nativepanels;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;

/** One recent report, accessible in the module even if the broadcast receiver is offline. */
final class DiagnosticSnapshot {
  static final String SAVE = "save_diagnostics";
  record Record(long time, String text) {}

  private static SharedPreferences file(Context c) {
    return c.createDeviceProtectedStorageContext().getSharedPreferences("last_diagnostics", Context.MODE_PRIVATE);
  }
  static void remember(Context c, String text) {
    if (text == null || text.isEmpty()) return;
    SharedPreferences prefs = file(c);
    String saved = text.substring(0, Math.min(text.length(), 24000));
    if (!saved.equals(prefs.getString("text", null)))
      prefs.edit().putLong("time", System.currentTimeMillis()).putString("text", saved).apply();
  }
  static Record read(Context c) {
    SharedPreferences f = file(c);
    String text = f.getString("text", null);
    return text == null ? null : new Record(f.getLong("time", 0), text);
  }

  private final PanelRuntime runtime;
  private boolean pending;
  private long nextWrite, failureCount;

  DiagnosticSnapshot(PanelRuntime runtime) { this.runtime = runtime; }

  // Ordinary input keeps only in-memory counters. Preserve a disconnected report
  // for failures, coalescing bursts on the UI thread and doing provider IPC off it.
  void failure(Context app) {
    failureCount++;
    schedule(app);
  }

  private void schedule(Context app) {
    if (pending) return;
    pending = true;
    long when = Math.max(nextWrite, SystemClock.uptimeMillis() + 400);
    runtime.main.postAtTime(() -> flush(app), when);
  }

  private void flush(Context app) {
    long captured = failureCount;
    nextWrite = SystemClock.uptimeMillis() + 60_000;
    try {
      Bundle data = new Bundle();
      data.putString("report", runtime.status());
      new Thread(() -> {
        try { app.getContentResolver().call(CombinedQsSettings.URI, SAVE, null, data); }
        catch (RuntimeException e) { android.util.Log.w("OOSNativePanels", "Save failure report", e); }
        finally {
          runtime.main.post(() -> {
            pending = false;
            if (captured != failureCount) schedule(app);
          });
        }
      }, "QS failure report").start();
    } catch (RuntimeException e) {
      pending = false;
      android.util.Log.w("OOSNativePanels", "Capture failure report", e);
    }
  }
}
