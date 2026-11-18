package dev.oos.nativepanels;

import static dev.oos.nativepanels.OptionalSettingsScope.State.*;
import static org.junit.Assert.*;

import android.content.Context;
import android.view.View;
import android.widget.TextView;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class AboutPhoneScopeTest {
  private static final String SETTINGS = AboutPhoneSettings.SETTINGS_PACKAGE;
  private Context context;
  private AboutPhoneScope scope;
  private FakeBackend service;
  private FakeRestarter restarter;

  @Before public void setUp() {
    context = RuntimeEnvironment.getApplication();
    setEnabled(false);
    restarter = new FakeRestarter();
    scope = new AboutPhoneScope(context, restarter);
    service = new FakeBackend();
  }

  @Test public void disabledAndSavedEnabledStatesDoNotRequestScopeOrRestart() {
    scope.bind(service);
    scope.refresh();
    assertEquals(OFF, scope.state());
    setEnabled(true);
    scope.refresh();
    assertEquals(MISSING, scope.state());
    service.packages = List.of(SETTINGS);
    scope.refresh();
    assertEquals(ADDED, scope.state());
    assertTrue(service.requests.isEmpty());
    assertTrue(restarter.jobs.isEmpty());
  }

  @Test public void explicitSetupRequestsOnlySettingsAndRequiresItsApproval() {
    beginRequest();
    assertEquals(List.of(SETTINGS), service.requests.get(0).packages);
    service.approve(0, List.of(BluetoothDetailsSettings.WIRELESS_PACKAGE));
    ShadowLooper.idleMainLooper();
    assertEquals(FAILED, scope.state());
    assertTrue(restarter.jobs.isEmpty());
    scope.request();
    assertEquals(List.of(SETTINGS), service.requests.get(1).packages);
    service.approve(1, List.of(SETTINGS));
    ShadowLooper.idleMainLooper();
    assertEquals(ROOT_PENDING, scope.state());
    assertEquals(1, restarter.jobs.size());
    restarter.finish(0, RootRestart.Result.REQUESTED);
    ShadowLooper.idleMainLooper();
    assertEquals(READY, scope.state());
  }

  @Test public void settingsGrantedForBluetoothNeedsNoSecondScopeRequest() {
    assertTrue(CombinedQsSettings.setEnabled(context, CombinedQsSettings.BLUETOOTH_DETAILS, true));
    service.packages = List.of("com.android.systemui", SETTINGS,
        BluetoothDetailsSettings.WIRELESS_PACKAGE);
    List<String> originalScope = service.packages;
    scope.bind(service);
    setEnabled(true);
    scope.refresh();
    assertTrue(restarter.jobs.isEmpty());
    scope.request();
    assertTrue(service.requests.isEmpty());
    assertEquals(1, restarter.jobs.size());
    restarter.finish(0, RootRestart.Result.NOT_RUNNING);
    ShadowLooper.idleMainLooper();
    assertEquals(READY, scope.state());
    setEnabled(false);
    scope.request();
    assertEquals(OFF, scope.state());
    assertTrue(CombinedQsSettings.enabled(context, CombinedQsSettings.BLUETOOTH_DETAILS));
    assertEquals(originalScope, service.packages);
    assertTrue(service.requests.isEmpty());
  }

  @Test public void disabledAndOldRequestCallbacksCannotApplyAboutPhone() {
    beginRequest();
    OptionalSettingsScope.Result previous = service.requests.get(0).result;
    setEnabled(false);
    scope.request();
    previous.approved(List.of(SETTINGS));
    ShadowLooper.idleMainLooper();
    assertEquals(OFF, scope.state());
    assertTrue(restarter.jobs.isEmpty());
    setEnabled(true);
    scope.request();
    previous.approved(List.of(SETTINGS));
    ShadowLooper.idleMainLooper();
    assertEquals(REQUESTING, scope.state());
    assertTrue(restarter.jobs.isEmpty());
    service.requests.get(1).result.failed();
    ShadowLooper.idleMainLooper();
    assertEquals(FAILED, scope.state());
  }

  @Test public void disablingCancelsRootAuthorizationAndIgnoresItsLateResult() {
    service.packages = List.of(SETTINGS);
    scope.bind(service);
    setEnabled(true);
    scope.request();
    assertTrue(restarter.jobs.get(0).allowed.getAsBoolean());
    setEnabled(false);
    scope.request();
    assertFalse(restarter.jobs.get(0).allowed.getAsBoolean());
    restarter.finish(0, RootRestart.Result.REQUESTED);
    ShadowLooper.idleMainLooper();
    assertEquals(OFF, scope.state());
    setEnabled(true);
    scope.request();
    restarter.finish(0, RootRestart.Result.DENIED);
    ShadowLooper.idleMainLooper();
    assertEquals(ROOT_PENDING, scope.state());
    restarter.finish(1, RootRestart.Result.REQUESTED);
    ShadowLooper.idleMainLooper();
    assertEquals(READY, scope.state());
  }

  @Test public void savedEnabledScreenHidesSetupUntilAnExplicitApplyFails() {
    setEnabled(true);
    service.packages = List.of(SETTINGS);
    useApplicationScope();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup().visible()) {
      MainActivity activity = controller.get();
      ShadowLooper.idleMainLooper();
      assertTrue(service.requests.isEmpty());
      assertTrue(restarter.jobs.isEmpty());
      assertEquals(activity.getString(R.string.about_scope_added), status(activity).getText().toString());
      assertEquals(View.GONE, retry(activity).getVisibility());
      toggle(activity).performClick();
      toggle(activity).performClick();
      assertEquals(1, restarter.jobs.size());
      restarter.finish(0, RootRestart.Result.DENIED);
      ShadowLooper.idleMainLooper();
      assertEquals(activity.getString(R.string.about_scope_root_denied), status(activity).getText().toString());
      assertEquals(View.VISIBLE, retry(activity).getVisibility());
      retry(activity).performClick();
      assertEquals(2, restarter.jobs.size());
      assertTrue(service.requests.isEmpty());
      toggle(activity).performClick();
      assertFalse(restarter.jobs.get(1).allowed.getAsBoolean());
      assertEquals(View.GONE, status(activity).getVisibility());
      assertEquals(View.GONE, retry(activity).getVisibility());
    }
  }

  @Test public void recreationKeepsOneApplyAndStopsUpdatingTheOldScreen() {
    useApplicationScope();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup().visible()) {
      MainActivity old = controller.get();
      ShadowLooper.idleMainLooper();
      assertEquals(View.GONE, status(old).getVisibility());
      toggle(old).performClick();
      TextView oldStatus = status(old);
      assertEquals(old.getString(R.string.about_scope_requesting), oldStatus.getText().toString());
      controller.recreate();
      ShadowLooper.idleMainLooper();
      MainActivity recreated = controller.get();
      assertTrue(toggle(recreated).isChecked());
      assertEquals(1, service.requests.size());
      service.approve(0, List.of(SETTINGS));
      ShadowLooper.idleMainLooper();
      assertEquals(recreated.getString(R.string.about_scope_root_pending), status(recreated).getText().toString());
      assertEquals(old.getString(R.string.about_scope_requesting), oldStatus.getText().toString());
      controller.recreate();
      ShadowLooper.idleMainLooper();
      assertEquals(1, restarter.jobs.size());
      restarter.finish(0, RootRestart.Result.REQUESTED);
      ShadowLooper.idleMainLooper();
      assertEquals(controller.get().getString(R.string.about_scope_ready), status(controller.get()).getText().toString());
    }
  }

  @Test public void aFreshControllerAfterSuccessfulApplyDoesNotAskToContinueSetup() {
    service.packages = List.of(SETTINGS);
    scope.bind(service);
    setEnabled(true);
    scope.request();
    restarter.finish(0, RootRestart.Result.REQUESTED);
    ShadowLooper.idleMainLooper();
    assertEquals(READY, scope.state());

    // The saved switch and framework scope survive app death; in-memory READY does not.
    restarter = new FakeRestarter();
    scope = new AboutPhoneScope(context, restarter);
    useApplicationScope();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup().visible()) {
      MainActivity activity = controller.get();
      ShadowLooper.idleMainLooper();
      assertEquals(ADDED, scope.state());
      assertTrue(toggle(activity).isChecked());
      assertEquals(activity.getString(R.string.about_scope_added), status(activity).getText().toString());
      assertEquals(View.GONE, retry(activity).getVisibility());
      assertTrue(service.requests.isEmpty());
      assertTrue(restarter.jobs.isEmpty());
      controller.recreate();
      ShadowLooper.idleMainLooper();
      assertEquals(View.GONE, retry(controller.get()).getVisibility());
      assertTrue(service.requests.isEmpty());
      assertTrue(restarter.jobs.isEmpty());
    }
  }

  private void useApplicationScope() {
    ReflectionHelpers.setField(RuntimeEnvironment.getApplication(), "aboutPhoneScope", scope);
    scope.bind(service);
  }

  private void beginRequest() {
    scope.bind(service);
    setEnabled(true);
    scope.request();
    assertEquals(REQUESTING, scope.state());
  }

  private void setEnabled(boolean enabled) {
    assertTrue(CombinedQsSettings.setEnabled(context, CombinedQsSettings.ABOUT_PHONE, enabled));
  }

  private MaterialSwitch toggle(MainActivity activity) {
    return activity.getWindow().getDecorView().findViewWithTag(CombinedQsSettings.ABOUT_PHONE);
  }

  private TextView status(MainActivity activity) {
    return activity.getWindow().getDecorView().findViewWithTag("about_scope_status");
  }

  private View retry(MainActivity activity) {
    return activity.getWindow().getDecorView().findViewWithTag("about_scope_retry");
  }

  private static final class FakeBackend implements OptionalSettingsScope.Backend {
    record Request(List<String> packages, OptionalSettingsScope.Result result) {}
    List<String> packages = List.of();
    final List<Request> requests = new ArrayList<>();
    @Override public List<String> scope() { return packages; }
    @Override public void request(List<String> missing, OptionalSettingsScope.Result result) {
      requests.add(new Request(List.copyOf(missing), result));
    }
    void approve(int request, List<String> added) {
      LinkedHashSet<String> combined = new LinkedHashSet<>(packages);
      combined.addAll(added);
      packages = List.copyOf(combined);
      requests.get(request).result.approved(added);
    }
  }

  /** Records setup work without running su or restarting Settings. */
  private static final class FakeRestarter implements OptionalSettingsScope.Restarter {
    record Job(BooleanSupplier allowed, OptionalSettingsScope.RestartResult result) {}
    final List<Job> jobs = new ArrayList<>();
    @Override public void restart(BooleanSupplier allowed, OptionalSettingsScope.RestartResult result) {
      jobs.add(new Job(allowed, result));
    }
    void finish(int job, RootRestart.Result result) { jobs.get(job).result.completed(result); }
  }
}
