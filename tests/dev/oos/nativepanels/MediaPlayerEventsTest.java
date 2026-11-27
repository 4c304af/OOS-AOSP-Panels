package dev.oos.nativepanels;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class MediaPlayerEventsTest {
  @Test public void lastPlayerWithdrawalClearsCacheBeforeNotifyingExistingListeners() throws Exception {
    Object owner = new Object();
    MediaPlayerEvents events = new MediaPlayerEvents(() -> owner);
    List<Object> cache = new ArrayList<>(List.of("last player"));
    AtomicInteger notifications = new AtomicInteger();
    assertTrue(events.forwardEmpty(owner, List.of(), () -> cache, () -> {
      assertTrue(cache.isEmpty());
      notifications.incrementAndGet();
    }));
    assertEquals(1, notifications.get());
    assertTrue(cache.isEmpty());
  }

  @Test public void anEmptyCacheStillForwardsWithdrawalForPlayersLoadedDuringInitialization() throws Exception {
    Object owner = new Object();
    MediaPlayerEvents events = new MediaPlayerEvents(() -> owner);
    AtomicInteger notifications = new AtomicInteger();
    // OOS initPlayerInfoList notifies its listeners without filling the manager's cache.
    assertTrue(events.forwardEmpty(owner, List.of(), ArrayList::new, notifications::incrementAndGet));
    assertEquals(1, notifications.get());
  }

  @Test public void nonemptySnapshotsAndUnrelatedManagersKeepNativeBehavior() throws Exception {
    Object owner = new Object();
    AtomicInteger resolutions = new AtomicInteger();
    MediaPlayerEvents events = new MediaPlayerEvents(() -> { resolutions.incrementAndGet(); return owner; });
    List<Object> cache = new ArrayList<>(List.of("paused music", "video"));
    AtomicInteger notifications = new AtomicInteger();
    assertFalse(events.forwardEmpty(owner, List.of("paused music"), () -> cache, notifications::incrementAndGet));
    assertFalse(events.forwardEmpty(owner, null, () -> cache, notifications::incrementAndGet));
    assertEquals(0, resolutions.get());
    assertFalse(events.forwardEmpty(new Object(), List.of(), () -> cache, notifications::incrementAndGet));
    assertEquals(List.of("paused music", "video"), cache);
    assertEquals(0, notifications.get());
  }

  @Test public void disablingAndReplacingTheProviderCannotForwardOldManagersEvents() throws Exception {
    Object previous = new Object(), next = new Object();
    AtomicReference<Object> owner = new AtomicReference<>(previous);
    MediaPlayerEvents events = new MediaPlayerEvents(owner::get);
    List<Object> cache = new ArrayList<>(List.of("previous player's data"));
    AtomicInteger notifications = new AtomicInteger();
    owner.set(null);
    assertFalse(events.forwardEmpty(previous, List.of(), () -> cache, notifications::incrementAndGet));
    assertFalse(events.forwardEmpty(null, List.of(), () -> cache, notifications::incrementAndGet));
    owner.set(next);
    assertFalse(events.forwardEmpty(previous, List.of(), () -> cache, notifications::incrementAndGet));
    assertEquals(1, cache.size());
    assertEquals(0, notifications.get());
    assertTrue(events.forwardEmpty(next, List.of(), ArrayList::new, notifications::incrementAndGet));
    assertEquals(1, notifications.get());
  }

  @Test public void withdrawalWhileComposeIsStoppedCannotReappearWhenItRestarts() throws Exception {
    Object owner = new Object(), data = new Object();
    MediaPlayerEvents events = new MediaPlayerEvents(() -> owner);
    List<Object> cache = new ArrayList<>(List.of("player"));
    java.util.Map<Object, MediaEntryBridge.Entry> sources = new java.util.HashMap<>();
    sources.put("player", new MediaEntryBridge.Entry(data, 1, "token", true));
    java.util.Map<Object, Object> current = new java.util.HashMap<>();
    MediaEntryBridge.Store store = new MediaEntryBridge.Store() {
      public java.util.Map<?, ?> entries() { return current; }
      public void add(Object entry) { current.put(1, entry); }
      public void remove(Object id, Object entry) { current.remove(id, entry); }
    };
    MediaEntryBridge bridge = new MediaEntryBridge();
    sources.forEach((key, entry) -> bridge.loaded(key, null, entry));
    bridge.refresh(store);
    bridge.clear(store); // The shade locks or its view detaches; the feature stays selected.
    assertFalse(sources.isEmpty());
    assertTrue(events.forwardEmpty(owner, List.of(), () -> cache, sources::clear));
    sources.forEach((key, entry) -> bridge.loaded(key, null, entry));
    bridge.refresh(store);
    assertTrue(cache.isEmpty());
    assertTrue(current.isEmpty());
  }
}
