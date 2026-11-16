package dev.oos.nativepanels;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.os.SystemClock;
import android.view.ViewTreeObserver;
import io.github.libxposed.api.XposedInterface;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import android.widget.LinearLayout;
import java.util.HashSet;
import java.util.Set;

final class HomeBatteryHook {
  private final PanelRuntime runtime;
  private final Set<String> warnings = new HashSet<>();
  private volatile Boolean enabled;
  private final IdentityHashMap<ViewGroup, HomeStatusBattery> batteryHosts = new IdentityHashMap<>();
  private Method getSlot, displayedState;
  private Class<?> containerType, mobileType;
  private HomeStatusBattery homeMeter, lockMeter;
  private Field visibleState;
  private int stateTag;
  private boolean neighbourHookInstalled;
  private volatile String status = "unbound";

  private volatile String keyguardStatus = "unbound";

  HomeBatteryHook(PanelRuntime runtime) { this.runtime = runtime; }

  String report() {
    return "AOSP status bar battery: enabled=" + enabled + " home=" + status + " keyguard=" + keyguardStatus
        + "\nbattery spacing: home=" + (homeMeter == null ? "unbound" : homeMeter.spacingReport())
        + " keyguard=" + (lockMeter == null ? "unbound" : lockMeter.spacingReport());
  }

  synchronized void warning(String part, Throwable error) {
    if (part.startsWith("keyguard ")) keyguardStatus = part.substring("keyguard ".length()) + ": " + error;
    else status = part + ": " + error;
    if (warnings.add(part)) runtime.module.log(Log.WARN, "OOSNativePanels", "Status bar battery " + part, error);
  }

  private boolean enabled(Context context) {
    if (enabled != null) return enabled;
    try {
      String key = CombinedQsSettings.HOME_BATTERY;
      Bundle result = context.getContentResolver().call(CombinedQsSettings.URI, key, null, null);
      if (result != null && result.containsKey(key)) enabled = result.getBoolean(key);
    } catch (RuntimeException e) { warning("preference", e); }
    return Boolean.TRUE.equals(enabled);
  }

  void observeNeighbour(HomeStatusBattery host, View bar, boolean keyguard) {
    if (keyguard) lockMeter = host; else homeMeter = host;
    try {
      if (containerType == null) {
        containerType = runtime.type("com.android.systemui.statusbar.phone.StatusIconContainer");
      }
      ViewGroup group = findIcons(host, bar);
      if (group == null) return;
      Class<?> display = runtime.type("com.android.systemui.statusbar.StatusIconDisplayable");
      getSlot = display.getMethod("getSlot");
      displayedState = display.getMethod("getVisibleState");
      mobileType = runtime.type("com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView");
      stateTag = bar.getResources().getIdentifier("status_bar_view_state_tag", "id", Protocol.TARGET);
      visibleState = Reflect.field(runtime.type(containerType.getName() + "$StatusIconState"), "visibleState");
      batteryHosts.put(group, host);
      host.watchIcons(group);
      if (!neighbourHookInstalled) {
        runtime.module.hook(containerType.getDeclaredMethod("updateStates"))
            .setId("native-panels:battery-neighbour")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
              Object result = chain.proceed();
              ViewGroup icons = (ViewGroup)chain.getThisObject();
              HomeStatusBattery meter = batteryHosts.get(icons);
              if (meter != null) updateNeighbour(icons, meter);
              return result;
            });
        neighbourHookInstalled = true;
        runtime.module.deoptimize(containerType.getDeclaredMethod("onLayout",
            boolean.class, int.class, int.class, int.class, int.class));
        runtime.module.deoptimize(runtime.type(containerType.getName() + "$$ExternalSyntheticLambda0")
            .getDeclaredMethod("run"));
      }
    } catch (ReflectiveOperationException | RuntimeException e) { warning("adjacent icon", e); }
  }

  private ViewGroup findIcons(View host, View bar) {
    int id = bar.getResources().getIdentifier("statusIcons", "id", Protocol.TARGET);
    for (View branch = host; branch != bar && branch.getParent() instanceof ViewGroup parent; branch = parent) {
      for (int i = parent.indexOfChild(branch) - 1; i >= 0; i--) {
        View sibling = parent.getChildAt(i);
        if (containerType.isInstance(sibling)) return (ViewGroup)sibling;
        View nested = id == 0 ? null : sibling.findViewById(id);
        if (containerType.isInstance(nested)) return (ViewGroup)nested;
      }
    }
    View found = id == 0 ? null : bar.findViewById(id);
    return containerType.isInstance(found) ? (ViewGroup)found : null;
  }

  void forgetNeighbour(HomeStatusBattery host) {
    batteryHosts.values().remove(host);
    if (homeMeter == host) homeMeter = null;
    if (lockMeter == host) lockMeter = null;
  }

  void updateNeighbour(ViewGroup group, HomeStatusBattery host) {
    String slot = "none";
    boolean mobile = false;
    try {
      if (group.getVisibility() == View.VISIBLE) {
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
          View child = group.getChildAt(i);
          if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0
              || !getSlot.getDeclaringClass().isInstance(child)) continue;
          Object state = stateTag == 0 ? null : child.getTag(stateTag);
          int visible = visibleState.getDeclaringClass().isInstance(state)
              ? visibleState.getInt(state) : (int)displayedState.invoke(child);
          if (visible == 2) continue;
          slot = visible == 0 ? (String)getSlot.invoke(child) : "dot";
          mobile = visible == 0 && (mobileSlot(slot) || mobileType.isInstance(child));
          break;
        }
      }
    } catch (ReflectiveOperationException | RuntimeException e) { warning("adjacent icon", e); }
    host.neighbour(slot, mobile);
  }

  static boolean mobileSlot(String slot) {
    return "mobile".equals(slot) || "stacked_mobile".equals(slot);
  }

  void install() throws Exception {
    hookBar("PhoneStatusBarView", false);
    try { hookBar("KeyguardStatusBarView", true); }
    catch (ReflectiveOperationException e) { warning("keyguard hook", e); }
  }

  private void hookBar(String name, boolean keyguard) throws ReflectiveOperationException {
    Class<?> type = runtime.type("com.android.systemui.statusbar.phone." + name);
    runtime.module.hook(type.getDeclaredMethod("onAttachedToWindow"))
        .setId(keyguard ? "native-panels:keyguard-battery" : "native-panels:home-battery")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          Object result = c.proceed();
          View bar = (View)c.getThisObject();
          if (enabled(bar.getContext())) new Binding(bar, keyguard).start();
          return result;
        });
  }

  private final class Binding implements Runnable, View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    final View bar;
    final boolean keyguard;
    boolean posted;
    long nextCheck;
    Binding(View bar, boolean keyguard) { this.bar = bar; this.keyguard = keyguard; }

    void start() {
      bar.addOnAttachStateChangeListener(this);
      bar.getViewTreeObserver().addOnPreDrawListener(this);
      schedule();
    }

    private void schedule() {
      if (posted) return;
      posted = true;
      bar.post(this);
    }

    public void run() {
      posted = false;
      if (!bar.isAttachedToWindow()) { stop(); return; }
      try {
        int id = bar.getResources().getIdentifier("battery", "id", Protocol.TARGET);
        if (!(bar.findViewById(id) instanceof LinearLayout meter)) { state("no meter"); stop(); return; }
        if (meter.getParent() instanceof HomeStatusBattery) { state("bound"); stop(); return; }
        if (runtime.graph == null || !HomeStatusBattery.hasOwners(runtime, meter)) {
          state("waiting for window");
          nextCheck = SystemClock.uptimeMillis() + 500;
          return;
        }
        HomeStatusBattery.install(runtime, meter, bar, keyguard);
        state("bound");
      } catch (Throwable e) { warning(keyguard ? "keyguard attach" : "attach", e); }
      stop();
    }

    private void state(String result) { if (keyguard) keyguardStatus = result; else status = result; }
    private void stop() {
      bar.removeCallbacks(this);
      bar.removeOnAttachStateChangeListener(this);
      if (bar.getViewTreeObserver().isAlive()) bar.getViewTreeObserver().removeOnPreDrawListener(this);
    }
    @Override public boolean onPreDraw() {
      if (SystemClock.uptimeMillis() >= nextCheck) schedule();
      return true;
    }
    @Override public void onViewDetachedFromWindow(View v) { stop(); }
    @Override public void onViewAttachedToWindow(View v) {}
  }
}
