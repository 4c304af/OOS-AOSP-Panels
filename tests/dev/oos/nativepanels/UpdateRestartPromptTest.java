package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class UpdateRestartPromptTest {
  private Context context;
  private SharedPreferences saved;

  @Before public void prepare() throws Exception {
    context = RuntimeEnvironment.getApplication();
    saved = context.getSharedPreferences(UpdateRestartPrompt.PREFERENCES, Context.MODE_PRIVATE);
    saved.edit().clear().commit();
    installDates(1000, 1000);
  }

  @Test public void firstInstallEstablishesBaselineWithoutPrompting() {
    try (ActivityController<MainActivity> controller = open()) {
      assertNull(prompt(controller.get()));
      assertEquals(BuildConfig.VERSION_CODE, saved.getLong(UpdateRestartPrompt.SEEN_VERSION, -1));
      assertFalse(restarting(controller.get()));
    }
  }

  @Test public void olderInstallWithoutAMarkerGetsOneMigrationPrompt() throws Exception {
    installDates(1000, 2000);
    try (ActivityController<MainActivity> controller = open()) {
      AlertDialog dialog = dialog(controller.get());
      assertTrue(dialog.isShowing());
      assertFalse(restarting(controller.get()));
      assertEquals(BuildConfig.VERSION_CODE, saved.getLong(UpdateRestartPrompt.SEEN_VERSION, -1));
    }
    try (ActivityController<MainActivity> controller = open()) {
      assertNull(prompt(controller.get()));
    }
  }

  @Test public void laterDoesNotRestartOrShowAgainOnResumeOrRelaunch() throws Exception {
    installDates(1000, 2000);
    try (ActivityController<MainActivity> controller = open()) {
      MainActivity activity = controller.get();
      AtomicInteger restarts = observeRestarts(activity);
      dialog(activity).getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
      ShadowLooper.idleMainLooper();
      controller.pause().resume();
      ShadowLooper.idleMainLooper();
      assertNull(prompt(activity));
      assertEquals(0, restarts.get());
      assertFalse(restarting(activity));
    }
    try (ActivityController<MainActivity> controller = open()) {
      assertNull(prompt(controller.get()));
    }
  }

  @Test public void backOrOutsideCancellationDoesNotRestartOrRepeat() throws Exception {
    installDates(1000, 2000);
    try (ActivityController<MainActivity> controller = open()) {
      MainActivity activity = controller.get();
      AtomicInteger restarts = observeRestarts(activity);
      dialog(activity).cancel();
      ShadowLooper.idleMainLooper();
      assertNull(prompt(activity));
      assertEquals(0, restarts.get());
      assertFalse(restarting(activity));
    }
    try (ActivityController<MainActivity> controller = open()) {
      assertNull(prompt(controller.get()));
    }
  }

  @Test public void confirmationDispatchesOnceAndManualRetryRemainsAvailable() throws Exception {
    installDates(1000, 2000);
    try (ActivityController<MainActivity> controller = open()) {
      MainActivity activity = controller.get();
      // Observe the real fragment-result event without starting a root process.
      // No result is returned, matching a denied/failed restart for prompt state.
      AtomicInteger restarts = observeRestarts(activity);
      dialog(activity).getButton(DialogInterface.BUTTON_POSITIVE).performClick();
      ShadowLooper.idleMainLooper();
      assertEquals(1, restarts.get());
      assertNull(prompt(activity));
    }
    try (ActivityController<MainActivity> controller = open()) {
      MainActivity activity = controller.get();
      assertNull(prompt(activity));
      View manual = findText(activity.getWindow().getDecorView(), activity.getString(R.string.restart_ui));
      assertNotNull(manual);
      assertTrue(manual.isEnabled());
      manual.performClick();
      ShadowLooper.idleMainLooper();
      AlertDialog confirmation = (AlertDialog)ShadowDialog.getLatestDialog();
      assertTrue(confirmation.isShowing());
      assertNotNull(findText(confirmation.getWindow().getDecorView(), activity.getString(R.string.restart_title)));
      confirmation.dismiss();
    }
  }

  @Test public void rotationRestoresOnlyTheExistingUnansweredPrompt() throws Exception {
    installDates(1000, 2000);
    try (ActivityController<MainActivity> controller = open()) {
      UpdateRestartPrompt before = prompt(controller.get());
      assertNotNull(before);
      controller.recreate();
      ShadowLooper.idleMainLooper();
      MainActivity recreated = controller.get();
      assertNotNull(prompt(recreated));
      assertNotSame(before, prompt(recreated));
      assertTrue(dialog(recreated).isShowing());
      assertEquals(1, recreated.getSupportFragmentManager().getFragments().stream()
          .filter(fragment -> fragment instanceof UpdateRestartPrompt).count());
      assertFalse(restarting(recreated));
      dialog(recreated).getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
      ShadowLooper.idleMainLooper();
      controller.recreate();
      ShadowLooper.idleMainLooper();
      assertNull(prompt(controller.get()));
    }
  }

  @Test public void incompatibleEarlierVersionPromptsEvenWithoutPackageDates() throws Exception {
    installDates(0, 0);
    saved.edit().putLong(UpdateRestartPrompt.SEEN_VERSION, 87).commit();
    try (ActivityController<MainActivity> controller = open()) {
      assertTrue(dialog(controller.get()).isShowing());
      assertFalse(restarting(controller.get()));
    }
  }

  @Test public void anUnansweredDialogRestoredAcrossAnUpdateCountsForTheCurrentVersion() throws Exception {
    installDates(1000, 2000);
    try (ActivityController<MainActivity> controller = open()) {
      assertNotNull(prompt(controller.get()));
      // Model saved activity state from the previous APK being restored by
      // Android while its preferences still record that previous version.
      saved.edit().putLong(UpdateRestartPrompt.SEEN_VERSION, 87).commit();
      controller.recreate();
      ShadowLooper.idleMainLooper();
      assertTrue(dialog(controller.get()).isShowing());
      assertEquals(BuildConfig.VERSION_CODE, saved.getLong(UpdateRestartPrompt.SEEN_VERSION, -1));
      dialog(controller.get()).getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
      ShadowLooper.idleMainLooper();
      controller.pause().resume();
      ShadowLooper.idleMainLooper();
      assertNull(prompt(controller.get()));
      assertFalse(restarting(controller.get()));
    }
    try (ActivityController<MainActivity> controller = open()) {
      assertNull(prompt(controller.get()));
    }
  }

  @Test public void unknownPackageDatesQuietlyEstablishBaseline() throws Exception {
    installDates(0, 0);
    try (ActivityController<MainActivity> controller = open()) {
      assertNull(prompt(controller.get()));
      assertEquals(BuildConfig.VERSION_CODE, saved.getLong(UpdateRestartPrompt.SEEN_VERSION, -1));
    }
  }

  @Test public void validMigrationDatesCanMoveBackwardsWithTheDeviceClock() {
    assertTrue(UpdateRestartPrompt.needsPrompt(null, "61", 2000, 1000));
    assertFalse(UpdateRestartPrompt.needsPrompt(null, "61", 1000, 1000));
    assertFalse(UpdateRestartPrompt.needsPrompt(null, "61", 0, 1000));
    assertFalse(UpdateRestartPrompt.needsPrompt(null, "61", 1000, 0));
  }

  @Test public void eachNewVersionPromptsOnceIncludingSkippedReleases() {
    assertFalse(UpdateRestartPrompt.needsPrompt("61", "61", 1000, 2000));
    assertTrue(UpdateRestartPrompt.needsPrompt("61", "62", 0, 0));
    assertFalse(UpdateRestartPrompt.needsPrompt("62", "62", 0, 0));
    assertTrue(UpdateRestartPrompt.needsPrompt("62", "65", 0, 0));
    assertFalse(UpdateRestartPrompt.needsPrompt("65", "65", 0, 0));
  }

  @Test public void upgradeFrom078WithLegacyReceiverPromptsForRestart() throws Exception {
    saved.edit().putLong(UpdateRestartPrompt.SEEN_VERSION, 90)
        .putString(UpdateRestartPrompt.SEEN_REVISION, "89").commit();
    installDates(1000, 2000);
    try (ActivityController<MainActivity> controller = open()) {
      assertNotNull(prompt(controller.get()));
      assertEquals(BuildConfig.SYSTEM_UI_REVISION, saved.getString(UpdateRestartPrompt.SEEN_REVISION, null));
    }
  }

  @Test public void appOnlyUpgradeUsesTheLiveReceiverToDecideWhetherToPrompt() throws Exception {
    installDates(1000, 2000);
    for (boolean running : new boolean[]{false, true}) {
      saved.edit().putLong(UpdateRestartPrompt.SEEN_VERSION, BuildConfig.VERSION_CODE - 1)
          .putString(UpdateRestartPrompt.SEEN_REVISION, BuildConfig.SYSTEM_UI_REVISION).commit();
      try (TestSystemUiReceiver host = running ? new TestSystemUiReceiver() : null;
          ActivityController<MainActivity> controller = open()) {
        assertEquals(!running, prompt(controller.get()) != null);
      }
    }
  }

  @Test public void compatibleLiveHostDismissesAnObsoleteRestoredPrompt() throws Exception {
    installDates(1000, 2000);
    try (ActivityController<MainActivity> controller = open()) {
      assertNotNull(prompt(controller.get()));
      UpdateRestartPrompt.showIfNeeded(controller.get(), true);
      ShadowLooper.idleMainLooper();
      assertNull(prompt(controller.get()));
      assertFalse(restarting(controller.get()));
    }
  }

  private void installDates(long first, long update) throws Exception {
    PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
    info.firstInstallTime = first;
    info.lastUpdateTime = update;
    shadowOf(context.getPackageManager()).installPackage(info);
  }

  private static ActivityController<MainActivity> open() {
    ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup().visible();
    ShadowLooper.idleMainLooper();
    return controller;
  }

  private static UpdateRestartPrompt prompt(MainActivity activity) {
    return (UpdateRestartPrompt)activity.getSupportFragmentManager().findFragmentByTag(UpdateRestartPrompt.TAG);
  }

  private static AlertDialog dialog(MainActivity activity) {
    UpdateRestartPrompt prompt = prompt(activity);
    assertNotNull(prompt);
    return (AlertDialog)prompt.requireDialog();
  }

  private static AtomicInteger observeRestarts(MainActivity activity) {
    AtomicInteger requests = new AtomicInteger();
    activity.getSupportFragmentManager().setFragmentResultListener(
        UpdateRestartPrompt.REQUEST_RESTART, activity, (key, data) -> requests.incrementAndGet());
    return requests;
  }

  private static boolean restarting(MainActivity activity) {
    try { return Reflect.field(MainActivity.class, "restarting").getBoolean(activity); }
    catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
  }

  private static View findText(View view, String text) {
    if (view instanceof TextView label && text.contentEquals(label.getText())) return view;
    if (view instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findText(group.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }
}
