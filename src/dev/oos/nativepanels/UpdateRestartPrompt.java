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

/** A once-per-version reminder; restarting still requires an explicit button press. */
public final class UpdateRestartPrompt extends DialogFragment {
  static final String TAG = "module-update-restart";
  static final String REQUEST_RESTART = "module-update-confirm-restart";
  static final String PREFERENCES = "update_restart_prompt";
  static final String SEEN_VERSION = "seen_version";

  static void showIfNeeded(FragmentActivity activity) {
    FragmentManager fragments = activity.getSupportFragmentManager();
    if (activity.isFinishing() || activity.isDestroyed() || fragments.isStateSaved()) return;
    SharedPreferences saved = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    long seen = saved.getLong(SEEN_VERSION, -1);
    long current = BuildConfig.VERSION_CODE;
    if (fragments.findFragmentByTag(TAG) != null) {
      // Android can restore an unanswered dialog from before an APK update.
      // Its recreated UI already describes this version; do not prompt again
      // after the user answers it merely because the stored marker was older.
      if (seen != current) saved.edit().putLong(SEEN_VERSION, current).apply();
      return;
    }
    if (seen == current) return;
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
    if (needsPrompt(seen, current, installed, updated)) {
      // showNow lets us record this version only after the dialog was displayed.
      // FragmentManager restores the same unanswered prompt after recreation.
      new UpdateRestartPrompt().showNow(fragments, TAG);
    }
    // Record display, not successful root access: declining or failing a restart
    // must not produce another prompt. The home screen always offers manual retry.
    saved.edit().putLong(SEEN_VERSION, current).apply();
  }

  static boolean needsPrompt(long seen, long current, long installed, long updated) {
    if (seen >= 0) return seen != current;
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
