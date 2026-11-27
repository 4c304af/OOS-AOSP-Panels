package dev.oos.nativepanels;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ReleaseDialogsTest {
  @Test public void homeOpensSummaryAndBundledGplOffline() {
    try (var ui = Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = ui.get();
      button(activity, R.string.license_title).performClick();
      AlertDialog summary = dialog(activity, "license");
      assertEquals(activity.getString(R.string.license_summary), message(summary));
      summary.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      ShadowLooper.idleMainLooper();
      AlertDialog full = dialog(activity, "license-full");
      assertTrue(message(full).contains("GNU GENERAL PUBLIC LICENSE"));
      assertTrue(message(full).contains("END OF TERMS AND CONDITIONS"));
    }
  }

  @Test public void checkResultSurvivesRotationAndOpensTheMatchingRelease() {
    try (var ui = Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity initial = ui.get();
      var release = new GitHubUpdates.Release(GitHubUpdates.Version.parse("1.0.0"), "v1.0.0");
      var check = new ViewModelProvider(initial).get(UpdateCheckDialog.Check.class);
      check.result.setValue(new GitHubUpdates.Result(GitHubUpdates.Status.CHECKING, null));
      button(initial, R.string.check_updates).performClick();
      check.result.setValue(new GitHubUpdates.Result(GitHubUpdates.Status.AVAILABLE, release));
      assertTrue(message(dialog(initial, "updates")).contains("1.0.0"));
      ui.recreate();
      ShadowLooper.idleMainLooper();
      AlertDialog updates = dialog(ui.get(), "updates");
      assertTrue(message(updates).contains("1.0.0"));
      updates.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      assertEquals(release.page(), shadowOf(ui.get()).getNextStartedActivity().getDataString());
    }
  }

  @Test public void onlyAnAvailableStartupResultPromptsAndItIsConsumedAcrossRecreation() {
    try (var ui = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
      MainActivity activity = ui.get();
      var check = new ViewModelProvider(activity).get(UpdateCheckDialog.Check.class);
      check.result.setValue(new GitHubUpdates.Result(GitHubUpdates.Status.CHECKING, null));
      ui.windowFocusChanged(true);
      for (var status : new GitHubUpdates.Status[] {GitHubUpdates.Status.CURRENT, GitHubUpdates.Status.EMPTY,
          GitHubUpdates.Status.RATE_LIMIT, GitHubUpdates.Status.FAILED}) {
        check.result.setValue(new GitHubUpdates.Result(status, null));
        ShadowLooper.idleMainLooper();
        assertNull(activity.getSupportFragmentManager().findFragmentByTag(UpdateCheckDialog.TAG));
      }
      var release = new GitHubUpdates.Release(GitHubUpdates.Version.parse("1.0.0"), "v1.0.0");
      check.result.setValue(new GitHubUpdates.Result(GitHubUpdates.Status.AVAILABLE, release));
      ShadowLooper.idleMainLooper();
      dialog(activity, UpdateCheckDialog.TAG).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
      ShadowLooper.idleMainLooper();
      ui.recreate().visible().windowFocusChanged(true);
      ShadowLooper.idleMainLooper();
      assertTrue(check.promptHandled);
      assertNull(ui.get().getSupportFragmentManager().findFragmentByTag(UpdateCheckDialog.TAG));
    }
  }

  private static View button(MainActivity activity, int text) {
    java.util.ArrayList<View> matches = new java.util.ArrayList<>();
    activity.getWindow().getDecorView().findViewsWithText(matches, activity.getString(text), View.FIND_VIEWS_WITH_TEXT);
    return matches.get(0);
  }

  private static AlertDialog dialog(MainActivity activity, String tag) {
    DialogFragment fragment = (DialogFragment) activity.getSupportFragmentManager().findFragmentByTag(tag);
    return (AlertDialog) fragment.requireDialog();
  }

  private static String message(AlertDialog dialog) {
    return ((TextView) dialog.findViewById(android.R.id.message)).getText().toString();
  }
}
