package dev.oos.nativepanels;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Main-thread delivery and cleanup for one native editor metadata query. */
final class EditorQuerySession<T> {
  private final BooleanSupplier isCurrent;
  private final Consumer<T> ready;
  private final Runnable fallback;
  private final Runnable cleanup;
  private boolean finished, cancelled, drained;

  EditorQuerySession(BooleanSupplier isCurrent, Consumer<T> ready, Runnable fallback,
      Runnable cleanup) {
    this.isCurrent = Objects.requireNonNull(isCurrent);
    this.ready = Objects.requireNonNull(ready);
    this.fallback = Objects.requireNonNull(fallback);
    this.cleanup = Objects.requireNonNull(cleanup);
  }

  void complete(T result) {
    if (finished) return;
    finished = true;
    if (isCurrent.getAsBoolean() && !cancelled) ready.accept(result);
  }

  void timeout() {
    if (finished) return;
    finished = true;
    if (isCurrent.getAsBoolean() && !cancelled) fallback.run();
  }

  void cancel() {
    cancelled = true;
    finished = true;
  }

  /** Only a still-pending request can suppress another click on the same editor. */
  boolean pending() { return !finished; }

  /** Called only after the native query's queued work has finished. */
  void drained() {
    if (drained) return;
    drained = true;
    try {
      // A native query can finish without delivering its stock callback.
      timeout();
    } finally {
      // Clearing the helper earlier can cancel its temporary-tile destruction.
      // Keep cleanup separate even when delivery is cancelled or throws.
      cleanup.run();
    }
  }
}
