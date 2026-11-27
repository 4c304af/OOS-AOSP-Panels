package dev.oos.nativepanels;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.view.View;
import java.util.function.Consumer;

/** The actual charge-complete state, observed only while replacement artwork is attached. */
final class BatteryChargeState implements View.OnAttachStateChangeListener {
  private final Context context;
  private final Consumer<Boolean> changed;
  private boolean full;
  private BroadcastReceiver receiver;

  BatteryChargeState(View view, Consumer<Boolean> changed) {
    context = view.getContext();
    this.changed = changed;
    view.addOnAttachStateChangeListener(this);
    if (view.isAttachedToWindow()) onViewAttachedToWindow(view);
  }

  static boolean isFullyCharged(Intent intent) {
    return intent != null && intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
        && intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            == BatteryManager.BATTERY_STATUS_FULL;
  }

  private void publish(boolean value) {
    if (full == value) return;
    full = value;
    changed.accept(value);
  }

  @Override public void onViewAttachedToWindow(View view) {
    if (receiver != null) return;
    BroadcastReceiver current = new BroadcastReceiver() {
      @Override public void onReceive(Context context, Intent intent) {
        if (receiver == this && intent != null && Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction()))
          publish(isFullyCharged(intent));
      }
    };
    receiver = current;
    try {
      Intent initial = context.registerReceiver(current, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
      // The sticky result covers opening the shade after charging already completed.
      publish(isFullyCharged(initial));
    } catch (RuntimeException unavailable) {
      receiver = null;
      publish(false);
    }
  }

  @Override public void onViewDetachedFromWindow(View view) {
    BroadcastReceiver previous = receiver;
    receiver = null;
    if (previous != null) {
      try { context.unregisterReceiver(previous); }
      catch (RuntimeException unavailable) { /* Keep disposal safe on a replaced context. */ }
    }
    publish(false);
  }
}
