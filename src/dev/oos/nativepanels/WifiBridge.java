package dev.oos.nativepanels;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;

/** Keeps WifiEntry data and its lifecycle separate from OPlus's legacy AccessPoint scanner. */
final class WifiBridge {
  private final PanelRuntime r;
  private final Map<Object, Session> sessions =
      Collections.synchronizedMap(new IdentityHashMap<>());
  private final ThreadLocal<Object> delivering = new ThreadLocal<>();
  private final ThreadLocal<Session> carrierLookup = new ThreadLocal<>();

  WifiBridge(PanelRuntime r) {
    this.r = r;
  }

  void installCarrierRouting() throws Throwable {
    Class<?> controller =
        r.type("com.android.systemui.qs.tiles.dialog.InternetDetailsContentController");
    for (String name :
        new String[] {
          "connectCarrierNetwork", "isCarrierNetworkActive", "getCarrierNetworkLevel"
        }) {
      r.module
          .hook(Reflect.method(controller, name))
          .setId("native-panels:carrier-" + name)
          .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
          .intercept(
              c -> {
                Session session = sessions.get(c.getThisObject());
                if (session == null || session.closed || session.tracker == null)
                  return c.proceed();
                Session previous = carrierLookup.get();
                carrierLookup.set(session);
                try {
                  // Keep AOSP's mobile-data, lockscreen and already-active checks, and its toast.
                  return c.proceed();
                } finally {
                  if (previous == null) carrierLookup.remove();
                  else carrierLookup.set(previous);
                }
              });
    }
    Class<?> ap = r.type("com.android.systemui.statusbar.connectivity.AccessPointControllerImpl");
    r.module
        .hook(Reflect.method(ap, "getMergedCarrierEntry"))
        .setId("native-panels:carrier-entry")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
        .intercept(
            c -> {
              Session session = carrierLookup.get();
              if (session == null || session.accessPointController != c.getThisObject())
                return c.proceed();
              // The shared OEM tracker never starts its lifecycle. Its WifiTrackerInjector's cached
              // Wi-Fi state remains uninitialized, so MergedCarrierEntry.connect silently skips the
              // temporary autojoin restriction. Supply the entry from our running tracker instead.
              return Reflect.call(session.tracker, "getMergedCarrierEntry");
            });
    r.module
        .hook(
            Reflect.method(
                r.type("android.net.wifi.WifiManager"),
                "startRestrictingAutoJoinToSubscriptionId",
                int.class))
        .setId("native-panels:carrier-request")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
        .intercept(
            c -> {
              Object result = c.proceed();
              if (carrierLookup.get() != null) r.note(R.string.wifi_pause);
              return result;
            });
    r.carrierHook = true;
  }

  void install() throws Throwable {
    Class<?> controller =
        r.type("com.android.systemui.qs.tiles.dialog.InternetDetailsContentController");
    Class<?> callback =
        r.type(
            "com.android.systemui.qs.tiles.dialog.InternetDetailsContentController$InternetDialogCallback");
    Method start =
        Reflect.method(
            controller,
            "onStart",
            callback,
            boolean.class,
            r.type("kotlinx.coroutines.CoroutineScope"));
    r.module
        .hook(start)
        .setId("native-panels:wifi-start")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
        .intercept(
            c -> {
              Object result = c.proceed();
              Object delegate = c.getArg(0);
              if ((Boolean) c.getArg(1)
                  && Reflect.get(delegate, "mInternetDialogManager") == r.internetManager
                  && r.internetManager != null) {
                Object target = c.getThisObject();
                try {
                  start(target);
                } catch (Throwable e) {
                  r.failure("internet", e);
                }
              }
              return result;
            });
    Method update = Reflect.method(controller, "onAccessPointsChanged", List.class);
    r.module
        .hook(update)
        .setId("native-panels:wifi-results")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
        .intercept(
            c -> {
              // The shared OPlus scanner may still emit an empty list. Do not let it overwrite
              // the dedicated tracker, and never send module-owned WifiEntry objects to OEM
              // callbacks.
              if (sessions.containsKey(c.getThisObject()) && delivering.get() != c.getThisObject())
                return null;
              return c.proceed();
            });
    Method scan = Reflect.method(controller, "scanWifiAccessPoints");
    r.module
        .hook(scan)
        .setId("native-panels:wifi-refresh")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
        .intercept(
            c -> {
              Session session = sessions.get(c.getThisObject());
              if (session == null) return c.proceed();
              session.schedulePublish();
              return null;
            });
    Class<?> delegate = r.type("com.android.systemui.qs.tiles.dialog.InternetDialogDelegateLegacy");
    Method stop = Reflect.method(delegate, "onStop", android.app.Dialog.class);
    r.module
        .hook(stop)
        .setId("native-panels:wifi-stop")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
        .intercept(
            c -> {
              Object target = Reflect.get(c.getThisObject(), "mInternetDetailsContentController");
              try {
                return c.proceed();
              } finally {
                stop(target);
              }
            });
  }

  private void start(Object controller) throws Throwable {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      r.main.post(
          () -> {
            try {
              start(controller);
            } catch (Throwable e) {
              r.failure("internet", e);
            }
          });
      return;
    }
    stop(controller);
    Session session = new Session(controller);
    sessions.put(controller, session);
    try {
      session.start();
    } catch (Throwable e) {
      stop(controller);
      throw e;
    }
  }

  private void stop(Object controller) {
    Session session = sessions.remove(controller);
    if (session != null) session.close();
  }

  private final class Session {
    final Object controller;
    Object owner, lifecycle, tracker, accessPointController;
    Handler worker;
    boolean closed;
    int lastCount = -1;
    final Runnable publish =
        () -> {
          try {
            publish();
          } catch (Throwable e) {
            r.failure("internet", e);
          }
        };

    Session(Object controller) {
      this.controller = controller;
    }

    void start() throws Throwable {
      Class<?> ownerType = r.type("androidx.lifecycle.LifecycleOwner");
      owner =
          Proxy.newProxyInstance(
              r.loader,
              new Class<?>[] {ownerType},
              (proxy, method, args) -> {
                if (method.getName().equals("getLifecycle")) return lifecycle;
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == args[0];
                return "OOSNativeInternetLifecycle";
              });
      lifecycle =
          r.type("androidx.lifecycle.LifecycleRegistry")
              .getConstructor(ownerType)
              .newInstance(owner);
      state("CREATED");
      Class<?> callbackType =
          r.type("com.android.wifitrackerlib.WifiPickerTracker$WifiPickerTrackerCallback");
      Object callback =
          Proxy.newProxyInstance(
              r.loader,
              new Class<?>[] {callbackType},
              (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("hashCode")) return System.identityHashCode(proxy);
                if (name.equals("equals")) return proxy == args[0];
                if (name.equals("toString")) return "OOSNativeInternetWifiCallback";
                if (name.equals("onWifiEntriesChanged") || name.equals("onWifiStateChanged"))
                  schedulePublish();
                return null;
              });
      Object ap = Reflect.get(controller, "mAccessPointController");
      accessPointController = ap;
      Object factory = Reflect.get(ap, "mWifiPickerTrackerFactory");
      Context context = (Context) Reflect.get(controller, "mContext");
      tracker =
          Reflect.invoke(
              Reflect.method(
                  factory.getClass(),
                  "create",
                  Context.class,
                  r.type("androidx.lifecycle.Lifecycle"),
                  callbackType,
                  String.class),
              factory,
              context,
              lifecycle,
              callback,
              "OOSNativePanels");
      if (tracker == null) throw new IllegalStateException(r.text(R.string.wifi_create_error));
      worker = (Handler) Reflect.get(tracker, "mWorkerHandler");
      state("RESUMED");
      schedulePublish();
      r.note(R.string.wifi_start);
    }

    void state(String name) throws Throwable {
      Class<?> stateType = r.type("androidx.lifecycle.Lifecycle$State");
      Object state = stateType.getField(name).get(null);
      Reflect.invoke(
          Reflect.method(lifecycle.getClass(), "setCurrentState", stateType), lifecycle, state);
    }

    void schedulePublish() {
      if (!closed) {
        r.main.removeCallbacks(publish);
        r.main.post(publish);
      }
    }

    void publish() throws Throwable {
      if (closed || tracker == null || sessions.get(controller) != this) return;
      List<?> available = (List<?>) Reflect.call(tracker, "getWifiEntries");
      ArrayList<Object> entries = new ArrayList<>(available);
      Object connected = Reflect.call(tracker, "getConnectedWifiEntry");
      if (connected != null) {
        entries.remove(connected);
        entries.add(0, connected);
      }
      Object previous = delivering.get();
      delivering.set(controller);
      try {
        Reflect.invoke(
            Reflect.method(controller.getClass(), "onAccessPointsChanged", List.class),
            controller,
            entries);
      } finally {
        delivering.set(previous);
      }
      if (lastCount != entries.size()) {
        lastCount = entries.size();
        r.note(R.string.wifi_count, lastCount);
      }
    }

    void close() {
      closed = true;
      r.main.removeCallbacks(publish);
      try {
        if (lifecycle != null) state("DESTROYED");
      } catch (Throwable e) {
        r.module.log(android.util.Log.WARN, "OOSNativePanels", "Wi-Fi lifecycle cleanup", e);
      }
      // DESTROYED delivers ON_STOP and unregisters the tracker's callbacks.
      // Calling BaseWifiTracker.close() as well would schedule onStop twice.
      if (worker != null && worker.getLooper() != Looper.getMainLooper())
        worker.getLooper().quitSafely();
      r.note(R.string.wifi_stop);
    }
  }
}
