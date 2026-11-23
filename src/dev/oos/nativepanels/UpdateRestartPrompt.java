package dev.oos.nativepanels;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Shows one reminder after an upgrade if the matching SystemUI receiver is unavailable. */
public final class UpdateRestartPrompt extends DialogFragment {
  static final String TAG = "module-update-restart";
  static final String REQUEST_RESTART = "module-update-confirm-restart";
  static final String PREFERENCES = "update_restart_prompt";
  static final String SEEN_VERSION = "seen_version";
  static final String SEEN_REVISION = "seen_system_ui_revision";

  static void showIfNeeded(FragmentActivity activity, boolean compatibleHostRunning) {
    FragmentManager fragments = activity.getSupportFragmentManager();
    if (activity.isFinishing() || activity.isDestroyed() || fragments.isStateSaved()) return;
    SharedPreferences saved = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    long seen = saved.getLong(SEEN_VERSION, -1);
    long current = BuildConfig.VERSION_CODE;
    String revision = BuildConfig.SYSTEM_UI_REVISION;
    String previous = saved.getString(SEEN_REVISION, null);
    UpdateRestartPrompt restored = (UpdateRestartPrompt) fragments.findFragmentByTag(TAG);
    if (restored != null && compatibleHostRunning) restored.dismiss();
    if (seen == current && revision.equals(previous)) return;
    long installed = 0, updated = 0;
    if (seen < 0) {
      try {
        PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
        installed = info.firstInstallTime;
        updated = info.lastUpdateTime;
      } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
        // Without a previous marker or valid package dates, establish a quiet
        // baseline. A later version change can still be recognized reliably.
      }
    }
    if (restored == null && !compatibleHostRunning && (seen >= 0 || needsPrompt(previous, revision, installed, updated))) {
      // showNow lets us record this version only after the dialog was displayed.
      // FragmentManager restores the same unanswered prompt after recreation.
      new UpdateRestartPrompt().showNow(fragments, TAG);
    }
    // Record display, not successful root access: declining or failing a restart
    // must not produce another prompt. The home screen always offers manual retry.
    saved.edit().putLong(SEEN_VERSION, current).putString(SEEN_REVISION, revision).apply();
  }

  static boolean needsPrompt(String previous, String current, long installed, long updated) {
    if (previous != null) return !previous.equals(current);
    // Existing releases have no marker. Different valid dates identify an APK
    // update even if the device clock moved backwards between installations.
    return installed > 0 && updated > 0 && installed != updated;
  }

  @Override public Dialog onCreateDialog(Bundle state) {
    return new MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.update_restart_title)
        .setMessage(R.string.update_restart_explain)
        .setNegativeButton(R.string.later, null)
        .setPositiveButton(R.string.restart, (dialog, which) ->
            getParentFragmentManager().setFragmentResult(REQUEST_RESTART, Bundle.EMPTY))
        .create();
  }
}
