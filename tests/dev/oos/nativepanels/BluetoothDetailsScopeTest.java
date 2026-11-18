package dev.oos.nativepanels;

import static dev.oos.nativepanels.BluetoothDetailsScope.State.*;
import static org.junit.Assert.*;

import android.content.Context;
import android.view.View;
import android.widget.TextView;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
public class BluetoothDetailsScopeTest {
  private static final String SETTINGS = BluetoothDetailsSettings.SETTINGS_PACKAGE;
  private static final String WIRELESS = BluetoothDetailsSettings.WIRELESS_PACKAGE;
  private Context context;
  private BluetoothDetailsScope scope;
  private FakeBackend service;
  private FakeRestarter restarter;

  @Before public void setUp() {
    context = RuntimeEnvironment.getApplication();
    setEnabled(false);
    restarter = new FakeRestarter();
    scope = new BluetoothDetailsScope(context, restarter);
    service = new FakeBackend();
  }

  @Test public void savedPreferenceAndPassiveRefreshNeverRequestScopeOrRoot() {
    scope.bind(service);
    scope.refresh();
    assertEquals(OFF, scope.state());
    setEnabled(true);
    scope.refresh();
    scope.refresh();
    assertEquals(MISSING, scope.state());
    service.packages = List.of(SETTINGS, WIRELESS);
    scope.refresh();
    assertEquals(ADDED, scope.state());
    assertTrue(service.requests.isEmpty());
    assertTrue(restarter.jobs.isEmpty());
  }

  @Test public void requestsOnlyMissingSettingsPackageAndAcceptsItsApproval() {
    service.packages = List.of("com.android.systemui", SETTINGS);
    beginRequest();
    assertEquals(List.of(WIRELESS), service.requests.get(0).packages);
    scope.request();
    scope.refresh();
    assertEquals(1, service.requests.size());
    service.approve(0, List.of(WIRELESS));
    ShadowLooper.idleMainLooper();
    assertEquals(ROOT_PENDING, scope.state());
    assertEquals(1, restarter.jobs.size());
    restarter.finish(0, RootRestart.Result.REQUESTED);
    ShadowLooper.idleMainLooper();
    assertEquals(READY, scope.state());
  }

  @Test public void partialApprovalNeedsTheOtherHostBeforeAnyRestart() {
    beginRequest();
    assertEquals(List.of(SETTINGS, WIRELESS), service.requests.get(0).packages);
    service.approve(0, List.of(SETTINGS));
    ShadowLooper.idleMainLooper();
    assertEquals(FAILED, scope.state());
    scope.refresh();
    assertTrue(restarter.jobs.isEmpty());
    assertEquals(1, service.requests.size());
    scope.request();
    assertEquals(List.of(WIRELESS), service.requests.get(1).packages);
    service.approve(1, List.of(WIRELESS));
    ShadowLooper.idleMainLooper();
    assertEquals(ROOT_PENDING, scope.state());
    assertEquals(1, restarter.jobs.size());
  }

  @Test public void aPreviouslyGrantedHostRemovedDuringApprovalNeedsAnotherRequest() {
    service.packages = List.of(SETTINGS);
    beginRequest();
    assertEquals(List.of(WIRELESS), service.requests.get(0).packages);
    service.packages = List.of();
    service.approve(0, List.of(WIRELESS));
    ShadowLooper.idleMainLooper();
    assertEquals(MISSING, scope.state());
    assertTrue(restarter.jobs.isEmpty());
    scope.refresh();
    assertEquals(1, service.requests.size());
    scope.request();
    assertEquals(List.of(SETTINGS), service.requests.get(1).packages);
    service.approve(1, List.of(SETTINGS));
    ShadowLooper.idleMainLooper();
    assertEquals(ROOT_PENDING, scope.state());
    assertEquals(1, restarter.jobs.size());
  }

  @Test public void unreadableScopeAfterApprovalDoesNotRestartUntilExplicitRetry() {
    beginRequest();
    service.scopeFailure = true;
    service.approve(0, List.of(SETTINGS, WIRELESS));
    ShadowLooper.idleMainLooper();
    assertEquals(UNAVAILABLE, scope.state());
    assertTrue(restarter.jobs.isEmpty());
    service.scopeFailure = false;
    scope.refresh();
    assertEquals(ADDED, scope.state());
    assertTrue(restarter.jobs.isEmpty());
    scope.request();
    assertEquals(ROOT_PENDING, scope.state());
    assertEquals(1, service.requests.size());
    assertEquals(1, restarter.jobs.size());
  }

  @Test public void bothExistingScopesApplyOnlyOnExplicitRequest() {
    service.packages = List.of(SETTINGS, WIRELESS);
    scope.bind(service);
    setEnabled(true);
    scope.refresh();
    assertTrue(restarter.jobs.isEmpty());
    scope.request();
    scope.request();
    scope.refresh();
    assertTrue(service.requests.isEmpty());
    assertEquals(1, restarter.jobs.size());
    restarter.finish(0, RootRestart.Result.NOT_RUNNING);
    ShadowLooper.idleMainLooper();
    assertEquals(READY, scope.state());
  }

  @Test public void lateFrameworkConnectionCompletesOnlyTheExplicitEnable() {
    setEnabled(true);
    scope.request();
    assertEquals(CONNECTING, scope.state());
    ShadowLooper.idleMainLooper(3, TimeUnit.SECONDS);
    assertEquals(UNAVAILABLE, scope.state());
    scope.bind(service);
    assertEquals(REQUESTING, scope.state());
    assertEquals(1, service.requests.size());
  }

  @Test public void disablingWhileConnectingCancelsTheQueuedRequest() {
    setEnabled(true);
    scope.request();
    setEnabled(false);
    scope.request();
    scope.bind(service);
    ShadowLooper.idleMainLooper(4, TimeUnit.SECONDS);
    assertEquals(OFF, scope.state());
    assertTrue(service.requests.isEmpty());
    assertTrue(restarter.jobs.isEmpty());
  }

  @Test public void disabledOrSupersededScopeCallbacksCannotApplyTheFeature() {
    beginRequest();
    BluetoothDetailsScope.Result previous = service.requests.get(0).result;
    setEnabled(false);
    scope.request();
    previous.approved(List.of(SETTINGS, WIRELESS));
    ShadowLooper.idleMainLooper();
    assertEquals(OFF, scope.state());
    assertTrue(restarter.jobs.isEmpty());
    setEnabled(true);
    scope.request();
    previous.approved(List.of(SETTINGS, WIRELESS));
    ShadowLooper.idleMainLooper();
    assertEquals(REQUESTING, scope.state());
    assertTrue(restarter.jobs.isEmpty());
    service.requests.get(1).result.failed();
    ShadowLooper.idleMainLooper();
    assertEquals(FAILED, scope.state());
  }

  @Test public void oldFrameworkCallbacksCannotCompleteANewConnection() {
    beginRequest();
    scope.bind(null);
    FakeBackend replacement = new FakeBackend();
    scope.bind(replacement);
    service.approve(0, List.of(SETTINGS, WIRELESS));
    ShadowLooper.idleMainLooper();
    assertEquals(MISSING, scope.state());
    assertTrue(replacement.requests.isEmpty());
    assertTrue(restarter.jobs.isEmpty());
    scope.request();
    replacement.approve(0, List.of(SETTINGS, WIRELESS));
    ShadowLooper.idleMainLooper();
    assertEquals(ROOT_PENDING, scope.state());
  }

  @Test public void resumingBeforeScopeCallbackKeepsOnePendingApply() {
    beginRequest();
    service.packages = List.of(SETTINGS, WIRELESS);
    scope.refresh();
    assertEquals(REQUESTING, scope.state());
    assertTrue(restarter.jobs.isEmpty());
    service.approve(0, List.of(SETTINGS, WIRELESS));
    ShadowLooper.idleMainLooper();
    scope.request();
    scope.refresh();
    assertEquals(1, restarter.jobs.size());
  }

  @Test public void disablingCancelsRootAuthorizationAndIgnoresLateResults() {
    service.packages = List.of(SETTINGS, WIRELESS);
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

  @Test public void rootFailuresRequireAnExplicitRetryWithoutAnotherScopePrompt() {
    RootRestart.Result[] results = {RootRestart.Result.DENIED, RootRestart.Result.TIMEOUT,
        RootRestart.Result.FAILED};
    BluetoothDetailsScope.State[] states = {ROOT_DENIED, ROOT_TIMEOUT, ROOT_FAILED};
    service.packages = List.of(SETTINGS, WIRELESS);
    scope.bind(service);
    setEnabled(true);
    for (int i = 0; i < results.length; i++) {
      scope.request();
      restarter.finish(i, results[i]);
      ShadowLooper.idleMainLooper();
      scope.refresh();
      assertEquals(states[i], scope.state());
      assertEquals(i + 1, restarter.jobs.size());
      assertTrue(service.requests.isEmpty());
      assertTrue(CombinedQsSettings.enabled(context, CombinedQsSettings.BLUETOOTH_DETAILS));
    }
  }

  @Test public void recreationKeepsTheOperationAndDetachesTheOldScreen() {
    useApplicationScope();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup().visible()) {
      MainActivity old = controller.get();
      ShadowLooper.idleMainLooper();
      assertEquals(View.GONE, status(old).getVisibility());
      toggle(old).performClick();
      TextView oldStatus = status(old);
      assertEquals(old.getString(R.string.bluetooth_scope_requesting), oldStatus.getText().toString());
      controller.recreate();
      ShadowLooper.idleMainLooper();
      MainActivity recreated = controller.get();
      assertTrue(toggle(recreated).isChecked());
      assertEquals(1, service.requests.size());
      service.approve(0, List.of(SETTINGS, WIRELESS));
      ShadowLooper.idleMainLooper();
      assertEquals(recreated.getString(R.string.bluetooth_scope_root_pending), status(recreated).getText().toString());
      assertEquals(old.getString(R.string.bluetooth_scope_requesting), oldStatus.getText().toString());
      controller.recreate();
      ShadowLooper.idleMainLooper();
      assertEquals(1, restarter.jobs.size());
      restarter.finish(0, RootRestart.Result.REQUESTED);
      ShadowLooper.idleMainLooper();
      assertEquals(controller.get().getString(R.string.bluetooth_scope_ready), status(controller.get()).getText().toString());
    }
  }

  @Test public void savedEnabledScreenDoesNotPromptUntilContinueSetup() {
    setEnabled(true);
    service.packages = List.of(SETTINGS, WIRELESS);
    useApplicationScope();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup().visible()) {
      MainActivity activity = controller.get();
      ShadowLooper.idleMainLooper();
      assertTrue(service.requests.isEmpty());
      assertTrue(restarter.jobs.isEmpty());
      assertEquals(View.VISIBLE, retry(activity).getVisibility());
      retry(activity).performClick();
      assertEquals(1, restarter.jobs.size());
      restarter.finish(0, RootRestart.Result.DENIED);
      ShadowLooper.idleMainLooper();
      assertEquals(activity.getString(R.string.bluetooth_scope_root_denied), status(activity).getText().toString());
      assertEquals(View.VISIBLE, retry(activity).getVisibility());
      toggle(activity).performClick();
      assertEquals(View.GONE, status(activity).getVisibility());
      assertEquals(View.GONE, retry(activity).getVisibility());
    }
  }

  private void useApplicationScope() {
    ReflectionHelpers.setField(RuntimeEnvironment.getApplication(), "bluetoothDetailsScope", scope);
    scope.bind(service);
  }

  private void beginRequest() {
    scope.bind(service);
    setEnabled(true);
    scope.request();
    assertEquals(REQUESTING, scope.state());
  }

  private void setEnabled(boolean enabled) {
    assertTrue(CombinedQsSettings.setEnabled(context, CombinedQsSettings.BLUETOOTH_DETAILS, enabled));
  }

  private MaterialSwitch toggle(MainActivity activity) {
    return activity.getWindow().getDecorView().findViewWithTag(CombinedQsSettings.BLUETOOTH_DETAILS);
  }

  private TextView status(MainActivity activity) {
    return activity.getWindow().getDecorView().findViewWithTag("bluetooth_scope_status");
  }

  private View retry(MainActivity activity) {
    return activity.getWindow().getDecorView().findViewWithTag("bluetooth_scope_retry");
  }

  private static final class FakeBackend implements BluetoothDetailsScope.Backend {
    record Request(List<String> packages, BluetoothDetailsScope.Result result) {}
    List<String> packages = List.of();
    final List<Request> requests = new ArrayList<>();
    boolean scopeFailure;

    @Override public List<String> scope() {
      if (scopeFailure) throw new IllegalStateException("Framework unavailable");
      return packages;
    }
    @Override public void request(List<String> missing, BluetoothDetailsScope.Result result) {
      requests.add(new Request(List.copyOf(missing), result));
    }

    void approve(int request, List<String> added) {
      LinkedHashSet<String> combined = new LinkedHashSet<>(packages);
      combined.addAll(added);
      packages = List.copyOf(combined);
      requests.get(request).result.approved(added);
    }
  }

  /** Records apply requests without launching su or touching a host process. */
  private static final class FakeRestarter implements BluetoothDetailsScope.Restarter {
    record Job(BooleanSupplier allowed, BluetoothDetailsScope.RestartResult result) {}
    final List<Job> jobs = new ArrayList<>();
    @Override public void restart(BooleanSupplier allowed, BluetoothDetailsScope.RestartResult result) {
      jobs.add(new Job(allowed, result));
    }
    void finish(int job, RootRestart.Result result) { jobs.get(job).result.completed(result); }
  }
}
