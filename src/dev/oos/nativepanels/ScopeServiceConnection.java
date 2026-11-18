package dev.oos.nativepanels;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/** Service 101 keeps one listener. Share it between the app's optional scope controllers. */
final class ScopeServiceConnection {
  private final Set<XposedServiceHelper.OnServiceListener> listeners = new LinkedHashSet<>();
  private XposedService current;
  private boolean registered;
  private final XposedServiceHelper.OnServiceListener bridge = new XposedServiceHelper.OnServiceListener() {
    @Override public void onServiceBind(XposedService service) { bound(service); }
    @Override public void onServiceDied(XposedService service) { died(service); }
  };

  synchronized void listen(XposedServiceHelper.OnServiceListener listener) {
    if (!listeners.add(listener)) return;
    if (!registered) {
      registered = true;
      // registerListener may synchronously deliver its cached binder to bridge.
      XposedServiceHelper.registerListener(bridge);
    } else if (current != null) {
      listener.onServiceBind(current);
    }
  }

  private synchronized void bound(XposedService service) {
    current = service;
    // Serialize replay and lifecycle events so a late observer cannot get an old binder last.
    for (XposedServiceHelper.OnServiceListener listener : new ArrayList<>(listeners))
      listener.onServiceBind(service);
  }

  private synchronized void died(XposedService service) {
    if (current != service) return;
    current = null;
    for (XposedServiceHelper.OnServiceListener listener : new ArrayList<>(listeners))
      listener.onServiceDied(service);
  }
}
