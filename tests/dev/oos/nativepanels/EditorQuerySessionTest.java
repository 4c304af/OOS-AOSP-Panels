package dev.oos.nativepanels;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.Test;

public class EditorQuerySessionTest {
  @Test public void successDeliversOnceAndWaitsForNativeDrainToCleanUp() {
    List<String> events = new ArrayList<>();
    EditorQuerySession<String> session = session(() -> true, events);
    assertTrue(session.pending());
    session.complete("native");
    assertFalse(session.pending());
    session.complete("duplicate");
    session.timeout();
    assertEquals(List.of("ready:native"), events);
    session.drained();
    session.drained();
    session.complete("late");
    assertEquals(List.of("ready:native", "cleanup"), events);
  }

  @Test public void staleUserOrViewDropsResultsWithoutOpeningFallback() {
    List<String> events = new ArrayList<>();
    AtomicBoolean current = new AtomicBoolean(true);
    EditorQuerySession<String> session = session(current::get, events);
    current.set(false);
    session.complete("old user");
    current.set(true);
    session.complete("late");
    session.timeout();
    session.drained();
    assertEquals(List.of("cleanup"), events);
  }

  @Test public void timeoutOpensFallbackOnceButDoesNotCancelNativeDestruction() {
    List<String> events = new ArrayList<>();
    EditorQuerySession<String> session = session(() -> true, events);
    session.timeout();
    session.timeout();
    session.complete("late");
    assertEquals(List.of("fallback"), events);
    session.drained();
    session.drained();
    assertEquals(List.of("fallback", "cleanup"), events);
  }

  @Test public void staleTimeoutDoesNotOpenAnEditorForThePreviousEntry() {
    List<String> events = new ArrayList<>();
    EditorQuerySession<String> session = session(() -> false, events);
    session.timeout();
    assertTrue(events.isEmpty());
    session.drained();
    assertEquals(List.of("cleanup"), events);
  }

  @Test public void cancellationDropsLateCallbacksAndWaitsForDrain() {
    List<String> events = new ArrayList<>();
    EditorQuerySession<String> session = session(() -> true, events);
    session.cancel();
    session.cancel();
    session.complete("late");
    session.timeout();
    assertTrue(events.isEmpty());
    session.drained();
    session.drained();
    assertEquals(List.of("cleanup"), events);
  }

  @Test public void drainWithoutAStockCallbackOpensFallbackThenCleansUp() {
    List<String> events = new ArrayList<>();
    EditorQuerySession<String> session = session(() -> true, events);
    session.drained();
    session.complete("late");
    session.timeout();
    session.drained();
    assertEquals(List.of("fallback", "cleanup"), events);
  }

  @Test public void staleDrainStillCleansUpWithoutOpeningTheEditor() {
    List<String> events = new ArrayList<>();
    EditorQuerySession<String> session = session(() -> false, events);
    session.drained();
    assertEquals(List.of("cleanup"), events);
  }

  @Test public void staleCompletionRetiresTheRequestSoTheSameEditorCanRetry() {
    assertStaleRetirementAllowsRetry(session -> session.complete("stale"), false);
  }

  @Test public void staleTimeoutAllowsRetryBeforeThePreviousNativeQueryDrains() {
    assertStaleRetirementAllowsRetry(EditorQuerySession::timeout, false);
  }

  @Test public void staleDrainWithoutACallbackAllowsTheSameEditorToRetry() {
    assertStaleRetirementAllowsRetry(EditorQuerySession::drained, true);
  }

  @Test public void cancelledSetupFailureCannotReopenTheEditorAfterARetry() {
    List<String> failedEvents = new ArrayList<>(), retryEvents = new ArrayList<>();
    EditorQuerySession<String> failed = session(() -> true, failedEvents);
    assertTrue(failed.pending());
    // Setup can fail after native work was queued. Retire delivery immediately,
    // while keeping cleanup deferred until that work has drained.
    failed.cancel();
    assertFalse(failed.pending());
    assertTrue(failedEvents.isEmpty());
    EditorQuerySession<String> retry = session(() -> true, retryEvents);
    assertTrue(retry.pending());
    failed.complete("late native result");
    failed.timeout();
    assertTrue(retry.pending());
    assertTrue(failedEvents.isEmpty());
    retry.complete("retry");
    failed.drained();
    assertFalse(failed.pending());
    assertFalse(retry.pending());
    assertEquals(List.of("cleanup"), failedEvents);
    assertEquals(List.of("ready:retry"), retryEvents);
    retry.drained();
    assertEquals(List.of("ready:retry", "cleanup"), retryEvents);
  }

  @Test public void readyFailureCannotRetryDeliveryOrSkipLaterCleanup() {
    List<String> events = new ArrayList<>();
    IllegalStateException failure = new IllegalStateException("ready");
    EditorQuerySession<String> session = new EditorQuerySession<>(() -> true, value -> {
      events.add("ready");
      throw failure;
    }, () -> events.add("fallback"), () -> events.add("cleanup"));
    assertSame(failure, assertThrows(IllegalStateException.class, () -> session.complete("native")));
    assertFalse(session.pending());
    session.complete("duplicate");
    session.timeout();
    assertEquals(List.of("ready"), events);
    session.drained();
    session.drained();
    assertEquals(List.of("ready", "cleanup"), events);
  }

  @Test public void fallbackFailureDuringDrainStillCleansUpExactlyOnce() {
    List<String> events = new ArrayList<>();
    IllegalStateException failure = new IllegalStateException("fallback");
    EditorQuerySession<String> session = new EditorQuerySession<>(() -> true,
        value -> events.add("ready"), () -> {
          events.add("fallback");
          throw failure;
        }, () -> events.add("cleanup"));
    assertSame(failure, assertThrows(IllegalStateException.class, session::drained));
    assertFalse(session.pending());
    session.drained();
    session.complete("late");
    assertEquals(List.of("fallback", "cleanup"), events);
  }

  @Test public void eligibilityFailureDuringDrainStillCleansUp() {
    List<String> events = new ArrayList<>();
    IllegalStateException failure = new IllegalStateException("eligibility");
    EditorQuerySession<String> session = new EditorQuerySession<>(() -> {
      throw failure;
    }, value -> events.add("ready"), () -> events.add("fallback"),
        () -> events.add("cleanup"));
    assertSame(failure, assertThrows(IllegalStateException.class, session::drained));
    session.drained();
    assertEquals(List.of("cleanup"), events);
  }

  @Test public void cleanupFailureIsNotRetried() {
    List<String> events = new ArrayList<>();
    IllegalStateException failure = new IllegalStateException("cleanup");
    EditorQuerySession<String> session = new EditorQuerySession<>(() -> true,
        value -> events.add("ready"), () -> events.add("fallback"), () -> {
          events.add("cleanup");
          throw failure;
        });
    session.cancel();
    assertSame(failure, assertThrows(IllegalStateException.class, session::drained));
    session.drained();
    assertEquals(List.of("cleanup"), events);
  }

  @Test public void reentrantCompletionAndDrainCannotDeliverOrCleanUpTwice() {
    List<String> events = new ArrayList<>();
    AtomicReference<EditorQuerySession<String>> reference = new AtomicReference<>();
    EditorQuerySession<String> session = new EditorQuerySession<>(() -> true, value -> {
      events.add("ready");
      reference.get().complete("reentrant");
      reference.get().timeout();
    }, () -> events.add("fallback"), () -> {
      events.add("cleanup");
      reference.get().drained();
    });
    reference.set(session);
    session.complete("native");
    session.drained();
    assertEquals(List.of("ready", "cleanup"), events);
  }

  private static EditorQuerySession<String> session(
      java.util.function.BooleanSupplier isCurrent, List<String> events) {
    return new EditorQuerySession<>(isCurrent, value -> events.add("ready:" + value),
        () -> events.add("fallback"), () -> events.add("cleanup"));
  }

  private static void assertStaleRetirementAllowsRetry(
      Consumer<EditorQuerySession<String>> finishStale, boolean alreadyDrained) {
    AtomicBoolean current = new AtomicBoolean(true);
    List<String> staleEvents = new ArrayList<>(), retryEvents = new ArrayList<>();
    EditorQuerySession<String> stale = session(current::get, staleEvents);
    assertTrue(stale.pending());
    current.set(false);
    finishStale.accept(stale);
    assertFalse(stale.pending());
    assertEquals(alreadyDrained ? List.of("cleanup") : List.of(), staleEvents);

    current.set(true);
    EditorQuerySession<String> retry = session(current::get, retryEvents);
    assertTrue(retry.pending());
    stale.complete("late previous entry");
    stale.timeout();
    stale.drained();
    // Late work from the previous entry must not consume the new request.
    assertTrue(retry.pending());
    assertEquals(List.of("cleanup"), staleEvents);
    retry.complete("retry");
    assertFalse(retry.pending());
    retry.drained();
    assertEquals(List.of("ready:retry", "cleanup"), retryEvents);
  }
}
