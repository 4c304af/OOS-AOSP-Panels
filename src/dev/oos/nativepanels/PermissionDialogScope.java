package dev.oos.nativepanels;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** App-side scope and apply flow. All UI state and observers stay on the main thread. */
final class PermissionDialogScope {
  enum State {
    OFF, CONNECTING, MISSING, REQUESTING, ADDED, FAILED, UNAVAILABLE,
    ROOT_PENDING, READY, ROOT_DENIED, ROOT_TIMEOUT, ROOT_FAILED
  }

  interface Observer { void changed(State state); }
  interface Result {
    void approved(List<String> packages);
    void failed();
  }
  interface Backend {
    List<String> scope();
    void request(Result result);
  }
  interface RestartResult { void completed(RootRestart.Result result); }
  interface Restarter { void restart(BooleanSupplier allowed, RestartResult result); }

  private final Context context;
  private final Restarter restarter;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Set<Observer> observers = new LinkedHashSet<>();
  private Backend backend;
  private State state = State.OFF;
  private boolean started, waitingForService;
  private Object activeRequest;
  private AtomicBoolean activeRestart;
  private final Runnable connectionTimeout = () -> {
    if (enabled() && waitingForService && backend == null) update(State.UNAVAILABLE);
  };

  PermissionDialogScope(Context context) {
    this(context, (allowed, completion) -> {
      Thread worker = new Thread(() -> {
        RootRestart.Result result;
        try { result = RootRestart.restartPermissionController(allowed); }
        catch (java.io.IOException | RuntimeException failure) { result = RootRestart.Result.FAILED; }
        completion.completed(result);
      }, "PermissionControllerRestart");
      worker.setDaemon(true);
      worker.start();
    });
  }

  PermissionDialogScope(Context context, Restarter restarter) {
    this.context = context.getApplicationContext();
    this.restarter = restarter;
  }

  void connect() {
    if (started) return;
    started = true;
    // The app shares service 101's single framework callback between optional features.
    ((PanelApplication)context).scopeService().listen(new XposedServiceHelper.OnServiceListener() {
      private XposedService current;

      @Override public void onServiceBind(XposedService service) {
        main.post(() -> {
          current = service;
          bind(new Backend() {
            @Override public List<String> scope() { return service.getScope(); }

            @Override public void request(Result result) {
              service.requestScope(List.of(PermissionDialogHook.PACKAGE),
                  new XposedService.OnScopeEventListener() {
                    @Override public void onScopeRequestApproved(List<String> packages) {
                      result.approved(packages);
                    }

                    @Override public void onScopeRequestFailed(String message) {
                      result.failed();
                    }
                  });
            }
          });
        });
      }

      @Override public void onServiceDied(XposedService service) {
        main.post(() -> {
          if (current != service) return;
          current = null;
          bind(null);
        });
      }
    });
  }

  void observe(Observer observer) {
    observers.add(observer);
    observer.changed(state);
  }

  void removeObserver(Observer observer) { observers.remove(observer); }
  State state() { return state; }

  void bind(Backend next) {
    if (backend == next) return;
    backend = next;
    activeRequest = null;
    if (next != null && waitingForService && enabled()) request();
    else refresh();
  }

  /** Refreshing or rebuilding the screen never starts a scope or root request. */
  void refresh() {
    if (!enabled()) {
      cancelPending();
      update(State.OFF);
      return;
    }
    // Querying status, returning from a root prompt or recreating the Activity must not
    // replace the progress/result of an explicit apply operation.
    if (activeRestart != null) return;
    if (backend == null) {
      if (state != State.CONNECTING) update(State.UNAVAILABLE);
      return;
    }
    try {
      if (backend.scope().contains(PermissionDialogHook.PACKAGE)) {
        // The approval callback may arrive after onResume. Keep it alive so that the
        // explicit enable operation, rather than this passive query, applies the change.
        if (activeRequest != null) return;
        waitingForService = false;
        main.removeCallbacks(connectionTimeout);
        if (state != State.READY && state != State.ROOT_DENIED
            && state != State.ROOT_TIMEOUT && state != State.ROOT_FAILED) update(State.ADDED);
      } else if (activeRequest == null && state != State.FAILED) update(State.MISSING);
    } catch (RuntimeException unavailable) {
      activeRequest = null;
      update(State.UNAVAILABLE);
    }
  }

  /** Called after a successful preference write, or by the explicit setup retry button. */
  void request() {
    if (!enabled()) {
      refresh();
      return;
    }
    if (activeRequest != null || activeRestart != null) return;
    main.removeCallbacks(connectionTimeout);
    if (backend == null) {
      waitingForService = true;
      update(State.CONNECTING);
      main.postDelayed(connectionTimeout, 3000);
      return;
    }
    waitingForService = false;
    Backend requested = backend;
    Object token = new Object();
    try {
      if (requested.scope().contains(PermissionDialogHook.PACKAGE)) {
        restartController();
        return;
      }
      activeRequest = token;
      update(State.REQUESTING);
      requested.request(new Result() {
        @Override public void approved(List<String> packages) {
          boolean added = packages != null && packages.contains(PermissionDialogHook.PACKAGE);
          main.post(() -> complete(requested, token, added));
        }

        @Override public void failed() {
          main.post(() -> complete(requested, token, false));
        }
      });
    } catch (RuntimeException unavailable) {
      activeRequest = null;
      update(State.FAILED);
    }
  }

  private void complete(Backend requested, Object token, boolean added) {
    if (backend != requested || activeRequest != token || !enabled()) return;
    activeRequest = null;
    if (added) restartController();
    else update(State.FAILED);
  }

  private void restartController() {
    if (!enabled() || activeRestart != null) return;
    AtomicBoolean ticket = new AtomicBoolean(true);
    activeRestart = ticket;
    update(State.ROOT_PENDING);
    try {
      restarter.restart(ticket::get,
          result -> main.post(() -> finishRestart(ticket, result)));
    } catch (RuntimeException failure) {
      finishRestart(ticket, RootRestart.Result.FAILED);
    }
  }

  private void finishRestart(AtomicBoolean ticket, RootRestart.Result result) {
    if (activeRestart != ticket || !ticket.get() || !enabled()) return;
    ticket.set(false);
    activeRestart = null;
    update(switch (result) {
      case REQUESTED, NOT_RUNNING -> State.READY;
      case DENIED -> State.ROOT_DENIED;
      case TIMEOUT -> State.ROOT_TIMEOUT;
      case FAILED -> State.ROOT_FAILED;
    });
  }

  private boolean enabled() {
    return CombinedQsSettings.enabled(context, CombinedQsSettings.PERMISSION_DIALOG);
  }

  private void cancelPending() {
    waitingForService = false;
    activeRequest = null;
    if (activeRestart != null) activeRestart.set(false);
    activeRestart = null;
    main.removeCallbacks(connectionTimeout);
  }

  private void update(State next) {
    state = next;
    for (Observer observer : new ArrayList<>(observers)) observer.changed(next);
  }
}
