package dev.oos.nativepanels;

import static dev.oos.nativepanels.OptionalSettingsScope.State.*;
import static org.junit.Assert.*;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.util.ArrayList;
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
public class PermissionDialogScopeTest {
  private Context context;
  private PermissionDialogScope scope;
  private FakeBackend service;
  private FakeRestarter restarter;

  @Before public void setUp() {
    context = RuntimeEnvironment.getApplication();
    setEnabled(false);
    restarter = new FakeRestarter();
    scope = new PermissionDialogScope(context, restarter);
    service = new FakeBackend();
  }

  @Test public void reconnectAndRefreshDoNotPromptWithoutAnExplicitEnableOrRetry() {
    scope.bind(service);
    scope.refresh();
    assertEquals(OFF, scope.state());
    assertTrue(service.requests.isEmpty());

    // A saved switch from an earlier app launch is not a new user request.
    setEnabled(true);
    scope.refresh();
    scope.refresh();
    assertEquals(MISSING, scope.state());
    assertTrue(service.requests.isEmpty());
    assertTrue(restarter.jobs.isEmpty());

    scope.request();
    scope.request();
    scope.refresh();
    assertEquals(REQUESTING, scope.state());
    assertEquals(1, service.requests.size());
  }

  @Test public void existingScopeSkipsTheFrameworkRequest() {
    service.packages = List.of("com.android.systemui", PermissionDialogHook.PACKAGE);
    scope.bind(service);
    setEnabled(true);
    scope.request();
    assertEquals(ROOT_PENDING, scope.state());
    assertTrue(service.requests.isEmpty());
    assertEquals(1, restarter.jobs.size());
    restarter.finish(0, RootRestart.Result.REQUESTED);
    ShadowLooper.idleMainLooper();
    assertEquals(READY, scope.state());
  }

  @Test public void enableWaitsForLateServiceEvenAfterShowingConnectionHelp() {
    setEnabled(true);
    scope.request();
    assertEquals(CONNECTING, scope.state());
    scope.refresh();
    assertEquals(CONNECTING, scope.state());
    ShadowLooper.idleMainLooper(3, TimeUnit.SECONDS);
    assertEquals(UNAVAILABLE, scope.state());

    scope.bind(service);
    assertEquals(REQUESTING, scope.state());
    assertEquals(1, service.requests.size());
    scope.refresh();
    assertEquals(1, service.requests.size());
  }

  @Test public void disablingWhileConnectingCancelsTheQueuedRequestAndTimeout() {
    setEnabled(true);
    scope.request();
    setEnabled(false);
    scope.request();
    scope.bind(service);
    ShadowLooper.idleMainLooper(4, TimeUnit.SECONDS);
    assertEquals(OFF, scope.state());
    assertTrue(service.requests.isEmpty());
  }

  @Test public void denialWaitsForExplicitRetryAndDoesNotChangeTheSavedPreference() {
    startRequest();
    service.requests.get(0).failed();
    ShadowLooper.idleMainLooper();
    assertEquals(FAILED, scope.state());
    scope.refresh();
    scope.refresh();
    assertEquals(FAILED, scope.state());
    assertEquals(1, service.requests.size());
    assertTrue(CombinedQsSettings.enabled(context, CombinedQsSettings.PERMISSION_DIALOG));

    scope.request();
    assertEquals(2, service.requests.size());
    service.approve(1);
    ShadowLooper.idleMainLooper();
    assertEquals(ROOT_PENDING, scope.state());
    assertEquals(1, restarter.jobs.size());
    restarter.finish(0, RootRestart.Result.REQUESTED);
    ShadowLooper.idleMainLooper();
    assertEquals(READY, scope.state());
  }

  @Test public void anUnrelatedApprovalDoesNotReportPermissionControllerAsAdded() {
    startRequest();
    service.requests.get(0).approved(List.of("com.android.systemui"));
    ShadowLooper.idleMainLooper();
    assertEquals(FAILED, scope.state());
    assertTrue(restarter.jobs.isEmpty());
  }

  @Test public void disablingDuringARequestIgnoresItsApprovalAndDoesNotReenableTheFeature() {
    startRequest();
    setEnabled(false);
    scope.request();
    service.approve(0);
    ShadowLooper.idleMainLooper();
    assertEquals(OFF, scope.state());
    assertFalse(CombinedQsSettings.enabled(context, CombinedQsSettings.PERMISSION_DIALOG));
    assertTrue(restarter.jobs.isEmpty());
    scope.refresh();
    assertEquals(OFF, scope.state());
  }

  @Test public void aPreviousRequestCannotCompleteANewerRequest() {
    startRequest();
    PermissionDialogScope.Result previous = service.requests.get(0);
    setEnabled(false);
    scope.request();
    setEnabled(true);
    scope.request();
    previous.approved(List.of(PermissionDialogHook.PACKAGE));
    ShadowLooper.idleMainLooper();
    assertEquals(REQUESTING, scope.state());
    service.requests.get(1).failed();
    ShadowLooper.idleMainLooper();
    assertEquals(FAILED, scope.state());
  }

  @Test public void disconnectIgnoresOldCallbacksAndDoesNotRepeatThePromptAfterRebind() {
    startRequest();
    scope.bind(null);
    assertEquals(UNAVAILABLE, scope.state());
    FakeBackend replacement = new FakeBackend();
    scope.bind(replacement);
    service.approve(0);
    ShadowLooper.idleMainLooper();
    assertEquals(MISSING, scope.state());
    assertTrue(replacement.requests.isEmpty());

    scope.request();
    assertEquals(1, replacement.requests.size());
    replacement.approve(0);
    ShadowLooper.idleMainLooper();
    assertEquals(ROOT_PENDING, scope.state());
    assertEquals(1, restarter.jobs.size());
  }

  @Test public void binderFailuresLeaveAUsableRetryPath() {
    service.scopeFailure = true;
    scope.bind(service);
    setEnabled(true);
    scope.refresh();
    assertEquals(UNAVAILABLE, scope.state());
    scope.request();
    assertEquals(FAILED, scope.state());

    service.scopeFailure = false;
    service.requestFailure = true;
    scope.request();
    assertEquals(FAILED, scope.state());
    service.requestFailure = false;
    scope.request();
    assertEquals(REQUESTING, scope.state());
    assertEquals(1, service.requests.size());
  }

  @Test public void backgroundCallbackNotifiesOnlyAttachedObserversOnTheMainThread() throws Exception {
    startRequest();
    List<OptionalSettingsScope.State> detached = new ArrayList<>();
    PermissionDialogScope.Observer oldObserver = detached::add;
    scope.observe(oldObserver);
    scope.removeObserver(oldObserver);
    List<Looper> observedLoopers = new ArrayList<>();
    scope.observe(state -> {
      if (state == ROOT_PENDING || state == READY) observedLoopers.add(Looper.myLooper());
    });

    Thread binderThread = new Thread(() -> service.approve(0));
    binderThread.start();
    binderThread.join();
    assertEquals(REQUESTING, scope.state());
    assertTrue(observedLoopers.isEmpty());
    ShadowLooper.idleMainLooper();
    assertEquals(ROOT_PENDING, scope.state());
    assertEquals(List.of(Looper.getMainLooper()), observedLoopers);
    Thread rootWorker = new Thread(() -> restarter.finish(0, RootRestart.Result.REQUESTED));
    rootWorker.start();
    rootWorker.join();
    assertEquals(ROOT_PENDING, scope.state());
    ShadowLooper.idleMainLooper();
    assertEquals(READY, scope.state());
    assertEquals(List.of(Looper.getMainLooper(), Looper.getMainLooper()), observedLoopers);
    assertEquals(List.of(REQUESTING), detached);
  }

  @Test public void resumingBeforeApprovalCallbackDoesNotLoseTheExplicitApplyOperation() {
    startRequest();
    service.packages = List.of(PermissionDialogHook.PACKAGE);
    scope.refresh();
    assertEquals(REQUESTING, scope.state());
    assertTrue(restarter.jobs.isEmpty());
    service.approve(0);
    ShadowLooper.idleMainLooper();
    assertEquals(ROOT_PENDING, scope.state());
    assertEquals(1, restarter.jobs.size());
    assertEquals(1, service.requests.size());
  }

  @Test public void passiveRefreshOfAnExistingScopeNeverRestartsTheController() {
    service.packages = List.of(PermissionDialogHook.PACKAGE);
    scope.bind(service);
    setEnabled(true);
    scope.refresh();
    scope.refresh();
    assertEquals(ADDED, scope.state());
    assertTrue(service.requests.isEmpty());
    assertTrue(restarter.jobs.isEmpty());

    scope.request();
    scope.request();
    scope.refresh();
    assertEquals(ROOT_PENDING, scope.state());
    assertEquals(1, restarter.jobs.size());
    restarter.finish(0, RootRestart.Result.REQUESTED);
    ShadowLooper.idleMainLooper();
    scope.refresh();
    assertEquals(READY, scope.state());
    assertEquals(1, restarter.jobs.size());
  }

  @Test public void rootErrorsOfferAnExplicitRetryWithoutRequestingScopeAgain() {
    RootRestart.Result[] results = {RootRestart.Result.DENIED, RootRestart.Result.TIMEOUT,
        RootRestart.Result.FAILED};
    OptionalSettingsScope.State[] states = {ROOT_DENIED, ROOT_TIMEOUT, ROOT_FAILED};
    for (int i = 0; i < results.length; i++) {
      restarter = new FakeRestarter();
      scope = new PermissionDialogScope(context, restarter);
      service.packages = List.of(PermissionDialogHook.PACKAGE);
      scope.bind(service);
      setEnabled(true);
      scope.request();
      restarter.finish(0, results[i]);
      ShadowLooper.idleMainLooper();
      assertEquals(states[i], scope.state());
      scope.refresh();
      assertEquals(states[i], scope.state());
      assertEquals(1, restarter.jobs.size());
      assertTrue(CombinedQsSettings.enabled(context, CombinedQsSettings.PERMISSION_DIALOG));

      scope.request();
      assertEquals(ROOT_PENDING, scope.state());
      assertEquals(2, restarter.jobs.size());
      assertTrue(service.requests.isEmpty());
      restarter.finish(1, RootRestart.Result.REQUESTED);
      ShadowLooper.idleMainLooper();
      assertEquals(READY, scope.state());
    }
  }

  @Test public void aControllerThatIsNotRunningIsAlreadyReadyForItsNextLaunch() {
    service.packages = List.of(PermissionDialogHook.PACKAGE);
    scope.bind(service);
    setEnabled(true);
    scope.request();
    restarter.finish(0, RootRestart.Result.NOT_RUNNING);
    ShadowLooper.idleMainLooper();
    assertEquals(READY, scope.state());
    scope.refresh();
    assertEquals(1, restarter.jobs.size());
  }

  @Test public void disablingCancelsAnOutstandingRootAuthorizationAndIgnoresItsCompletion() {
    service.packages = List.of(PermissionDialogHook.PACKAGE);
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
    assertFalse(CombinedQsSettings.enabled(context, CombinedQsSettings.PERMISSION_DIALOG));

    setEnabled(true);
    scope.request();
    restarter.finish(0, RootRestart.Result.DENIED);
    ShadowLooper.idleMainLooper();
    assertEquals(ROOT_PENDING, scope.state());
    assertTrue(restarter.jobs.get(1).allowed.getAsBoolean());
    restarter.finish(1, RootRestart.Result.REQUESTED);
    ShadowLooper.idleMainLooper();
    assertEquals(READY, scope.state());
  }

  @Test public void frameworkReconnectDoesNotStartAnotherPendingRootOperation() {
    startRequest();
    service.approve(0);
    ShadowLooper.idleMainLooper();
    scope.bind(null);
    scope.refresh();
    FakeBackend replacement = new FakeBackend();
    replacement.packages = List.of(PermissionDialogHook.PACKAGE);
    scope.bind(replacement);
    scope.request();
    assertEquals(ROOT_PENDING, scope.state());
    assertEquals(1, restarter.jobs.size());
    assertTrue(replacement.requests.isEmpty());
    restarter.finish(0, RootRestart.Result.REQUESTED);
    ShadowLooper.idleMainLooper();
    assertEquals(READY, scope.state());
  }

  @Test public void aFailureStartingTheRootWorkerCanBeRetried() {
    restarter.throwOnStart = true;
    service.packages = List.of(PermissionDialogHook.PACKAGE);
    scope.bind(service);
    setEnabled(true);
    scope.request();
    assertEquals(ROOT_FAILED, scope.state());
    restarter.throwOnStart = false;
    scope.request();
    assertEquals(ROOT_PENDING, scope.state());
    assertEquals(1, restarter.jobs.size());
  }

  @Test public void screenRecreationKeepsOneRequestAndDetachesTheOldScreen() {
    useApplicationScope();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup().visible()) {
      MainActivity oldActivity = controller.get();
      ShadowLooper.idleMainLooper();
      assertEquals(View.GONE, status(oldActivity).getVisibility());
      permissionSwitch(oldActivity).performClick();
      TextView oldStatus = status(oldActivity);
      assertEquals(oldActivity.getString(R.string.permission_scope_requesting),
          oldStatus.getText().toString());

      controller.recreate();
      ShadowLooper.idleMainLooper();
      MainActivity recreated = controller.get();
      assertTrue(permissionSwitch(recreated).isChecked());
      assertEquals(1, service.requests.size());
      service.approve(0);
      ShadowLooper.idleMainLooper();
      assertEquals(recreated.getString(R.string.permission_scope_root_pending),
          status(recreated).getText().toString());
      assertEquals(oldActivity.getString(R.string.permission_scope_requesting),
          oldStatus.getText().toString());
      TextView previousStatus = status(recreated);
      controller.recreate();
      ShadowLooper.idleMainLooper();
      assertEquals(1, service.requests.size());
      assertEquals(1, restarter.jobs.size());
      restarter.finish(0, RootRestart.Result.REQUESTED);
      ShadowLooper.idleMainLooper();
      assertEquals(controller.get().getString(R.string.permission_scope_ready),
          status(controller.get()).getText().toString());
      assertEquals(recreated.getString(R.string.permission_scope_root_pending),
          previousStatus.getText().toString());
    }
  }

  @Test public void screenOffersRetryAfterFailureAndHidesSetupWhenDisabled() {
    useApplicationScope();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup().visible()) {
      MainActivity activity = controller.get();
      ShadowLooper.idleMainLooper();
      permissionSwitch(activity).performClick();
      assertEquals(View.GONE, retry(activity).getVisibility());
      service.requests.get(0).failed();
      ShadowLooper.idleMainLooper();
      assertEquals(activity.getString(R.string.permission_scope_failed),
          status(activity).getText().toString());
      assertEquals(View.VISIBLE, retry(activity).getVisibility());
      retry(activity).performClick();
      assertEquals(2, service.requests.size());
      assertEquals(View.GONE, retry(activity).getVisibility());

      permissionSwitch(activity).performClick();
      service.approve(1);
      ShadowLooper.idleMainLooper();
      assertEquals(View.GONE, status(activity).getVisibility());
      assertEquals(View.GONE, retry(activity).getVisibility());
      assertFalse(permissionSwitch(activity).isChecked());
    }
  }

  @Test public void existingScopeScreenOffersRestartAfterScopeIsGranted() {
    service.packages = List.of(PermissionDialogHook.PACKAGE);
    setEnabled(true);
    useApplicationScope();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup().visible()) {
      MainActivity activity = controller.get();
      ShadowLooper.idleMainLooper();
      assertEquals(activity.getString(R.string.permission_scope_added),
          status(activity).getText().toString());
      assertEquals(View.VISIBLE, retry(activity).getVisibility());
      assertEquals(activity.getString(R.string.restart_permission), ((TextView) retry(activity)).getText().toString());
      assertTrue(restarter.jobs.isEmpty());

      retry(activity).performClick();
      assertEquals(1, restarter.jobs.size());
      assertTrue(service.requests.isEmpty());
      assertEquals(activity.getString(R.string.permission_scope_root_pending),
          status(activity).getText().toString());
      assertFalse(retry(activity).isEnabled());
      restarter.finish(0, RootRestart.Result.DENIED);
      ShadowLooper.idleMainLooper();
      assertEquals(activity.getString(R.string.permission_scope_root_denied),
          status(activity).getText().toString());
      assertEquals(View.VISIBLE, retry(activity).getVisibility());
      retry(activity).performClick();
      assertEquals(2, restarter.jobs.size());
      restarter.finish(1, RootRestart.Result.NOT_RUNNING);
      ShadowLooper.idleMainLooper();
      assertEquals(activity.getString(R.string.permission_scope_ready),
          status(activity).getText().toString());
      assertTrue(retry(activity).isEnabled());
    }
  }

  @Test public void aFreshControllerAfterSuccessfulApplyDoesNotAskToContinueSetup() {
    service.packages = List.of(PermissionDialogHook.PACKAGE);
    scope.bind(service);
    setEnabled(true);
    scope.request();
    restarter.finish(0, RootRestart.Result.REQUESTED);
    ShadowLooper.idleMainLooper();
    assertEquals(READY, scope.state());

    // The saved switch and framework scope survive app death; in-memory READY does not.
    restarter = new FakeRestarter();
    scope = new PermissionDialogScope(context, restarter);
    useApplicationScope();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup().visible()) {
      MainActivity activity = controller.get();
      ShadowLooper.idleMainLooper();
      assertEquals(ADDED, scope.state());
      assertTrue(permissionSwitch(activity).isChecked());
      assertEquals(activity.getString(R.string.permission_scope_added), status(activity).getText().toString());
      assertEquals(View.VISIBLE, retry(activity).getVisibility());
      assertTrue(service.requests.isEmpty());
      assertTrue(restarter.jobs.isEmpty());
      controller.recreate();
      ShadowLooper.idleMainLooper();
      assertEquals(View.VISIBLE, retry(controller.get()).getVisibility());
      assertTrue(service.requests.isEmpty());
      assertTrue(restarter.jobs.isEmpty());
    }
  }

  private void useApplicationScope() {
    ReflectionHelpers.setField(RuntimeEnvironment.getApplication(), "permissionScope", scope);
    scope.bind(service);
  }

  private void startRequest() {
    scope.bind(service);
    setEnabled(true);
    scope.request();
    assertEquals(REQUESTING, scope.state());
  }

  private void setEnabled(boolean enabled) {
    assertTrue(CombinedQsSettings.setEnabled(context, CombinedQsSettings.PERMISSION_DIALOG, enabled));
  }

  private MaterialSwitch permissionSwitch(MainActivity activity) {
    return activity.getWindow().getDecorView().findViewWithTag(CombinedQsSettings.PERMISSION_DIALOG);
  }

  private TextView status(MainActivity activity) {
    return activity.getWindow().getDecorView().findViewWithTag("permission_scope_status");
  }

  private View retry(MainActivity activity) {
    return activity.getWindow().getDecorView().findViewWithTag("permission_scope_retry");
  }

  private static final class FakeBackend implements PermissionDialogScope.Backend {
    List<String> packages = List.of();
    final List<PermissionDialogScope.Result> requests = new ArrayList<>();
    boolean scopeFailure, requestFailure;

    @Override public List<String> scope() {
      if (scopeFailure) throw new IllegalStateException("Framework unavailable");
      return packages;
    }

    @Override public void request(PermissionDialogScope.Result result) {
      if (requestFailure) throw new IllegalStateException("Request unavailable");
      requests.add(result);
    }

    void approve(int request) {
      packages = List.of(PermissionDialogHook.PACKAGE);
      requests.get(request).approved(packages);
    }
  }

  /** Records root requests without ever launching a shell or killing a process. */
  private static final class FakeRestarter implements PermissionDialogScope.Restarter {
    record Job(BooleanSupplier allowed, PermissionDialogScope.RestartResult result) {}
    final List<Job> jobs = new ArrayList<>();
    boolean throwOnStart;

    @Override public void restart(BooleanSupplier allowed, PermissionDialogScope.RestartResult result) {
      if (throwOnStart) throw new IllegalStateException("Unable to start root worker");
      jobs.add(new Job(allowed, result));
    }

    void finish(int job, RootRestart.Result result) {
      jobs.get(job).result.completed(result);
    }
  }
}
