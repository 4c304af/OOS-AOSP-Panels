package dev.oos.nativepanels;

import android.view.View;
import java.util.function.IntSupplier;

/** Keeps the native footer's scrollable space when the device layout hides that footer. */
final class BluetoothDetailsBottomPadding implements View.OnLayoutChangeListener, View.OnAttachStateChangeListener {
  private final View list;
  private final IntSupplier requiredPadding;
  private int originalBottom, appliedBottom;
  private boolean pending, disposed;
  private final Runnable update = () -> {
    pending = false;
    refresh();
  };

  BluetoothDetailsBottomPadding(View list, IntSupplier requiredPadding) {
    this.list = list;
    this.requiredPadding = requiredPadding;
    originalBottom = appliedBottom = list.getPaddingBottom();
    list.addOnLayoutChangeListener(this);
    list.addOnAttachStateChangeListener(this);
    schedule();
  }

  void schedule() {
    if (pending || disposed) return;
    pending = true;
    if (!list.post(update)) pending = false;
  }

  void refresh() {
    if (disposed) return;
    int required;
    try { required = requiredPadding.getAsInt(); }
    catch (RuntimeException unavailable) { return; }
    // The adapter may not yet be bound during fragment creation.
    if (required < 0) return;
    int current = list.getPaddingBottom();
    if (current != appliedBottom) originalBottom = current;
    appliedBottom = Math.max(originalBottom, required);
    if (current != appliedBottom) setBottom(appliedBottom);
  }

  void dispose() {
    if (disposed) return;
    disposed = true;
    pending = false;
    list.removeCallbacks(update);
    list.removeOnLayoutChangeListener(this);
    list.removeOnAttachStateChangeListener(this);
    if (list.getPaddingBottom() == appliedBottom && appliedBottom != originalBottom) setBottom(originalBottom);
  }

  boolean forList(View candidate) { return list == candidate; }

  private void setBottom(int bottom) {
    list.setPaddingRelative(list.getPaddingStart(), list.getPaddingTop(), list.getPaddingEnd(), bottom);
  }

  @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
      int oldLeft, int oldTop, int oldRight, int oldBottom) { refresh(); }

  @Override public void onViewAttachedToWindow(View view) { schedule(); }

  @Override public void onViewDetachedFromWindow(View view) {
    pending = false;
    list.removeCallbacks(update);
  }
}
