package dev.oos.nativepanels;

import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class MediaEntryBridgeTest {
  private static final class Store implements MediaEntryBridge.Store {
    final Map<Object, Object> current = new HashMap<>();
    final Map<Object, Object> ids = new HashMap<>();
    int adds, removes;
    boolean dismissed;
    public Map<?, ?> entries() { return current; }
    public boolean dismissed() { return dismissed; }
    public void add(Object data) { current.put(ids.get(data), data); adds++; dismissed = false; }
    public void remove(Object id, Object data) { current.remove(id, data); removes++; }
    MediaEntryBridge.Entry entry(Object id, Object data, Object token, boolean playing) {
      ids.put(data, id);
      return new MediaEntryBridge.Entry(data, id, token, playing);
    }
  }

  @Test public void stoppingOneOfThreeSourcesRemovesOnlyItsCard() throws Exception {
    MediaEntryBridge bridge = new MediaEntryBridge();
    Store store = new Store();
    for (int i = 0; i < 3; i++) bridge.loaded(i, null, store.entry(i, new Object(), "token" + i, true));
    bridge.refresh(store);
    bridge.removed(1);
    bridge.refresh(store);
    assertEquals(2, store.current.size());
    assertFalse(store.current.containsKey(1));
    assertEquals(1, store.removes);
    assertEquals(3, store.adds);
  }

  @Test public void sameObjectUpdateRebuildsTheCardButOpeningShadeDoesNot() throws Exception {
    MediaEntryBridge bridge = new MediaEntryBridge();
    Store store = new Store();
    Object mutableData = new Object();
    bridge.loaded("key", null, store.entry(1, mutableData, "token", true));
    bridge.refresh(store);
    bridge.loaded("key", "key", store.entry(1, mutableData, "token", false));
    bridge.refresh(store);
    assertEquals(2, store.adds);
    assertSame(mutableData, store.current.get(1));
    bridge.refresh(store);
    assertEquals(2, store.adds);
  }

  @Test public void aTokenlessPlaceholderCannotRestoreAnEndedCard() throws Exception {
    MediaEntryBridge bridge = new MediaEntryBridge();
    Store store = new Store();
    bridge.loaded("old", null, store.entry(1, new Object(), "token", true));
    bridge.refresh(store);
    bridge.loaded("new", "old", store.entry(1, new Object(), null, false));
    bridge.refresh(store);
    assertTrue(store.current.isEmpty());
    bridge.refresh(store);
    assertEquals(1, store.adds);
  }

  @Test public void nativeRemovalStaysRemovedThroughMetadataUntilPlaybackResumes() throws Exception {
    MediaEntryBridge bridge = new MediaEntryBridge();
    Store store = new Store();
    bridge.loaded("key", null, store.entry(1, new Object(), "token", true));
    bridge.refresh(store);
    store.current.remove(1);
    bridge.refresh(store);
    bridge.loaded("key", "key", store.entry(1, new Object(), "token", true));
    bridge.refresh(store);
    assertTrue(store.current.isEmpty());
    bridge.loaded("key", "key", store.entry(1, new Object(), "token", false));
    bridge.refresh(store);
    assertTrue(store.current.isEmpty());
    bridge.loaded("key", "key", store.entry(1, new Object(), "token", true));
    bridge.refresh(store);
    assertEquals(1, store.current.size());
    assertEquals(2, store.adds);
  }

  @Test public void nativeReplacementIsNeitherOverwrittenNorRemovedOnSourceRemovalOrStop() throws Exception {
    for (boolean stop : new boolean[]{false, true}) {
      MediaEntryBridge bridge = new MediaEntryBridge();
      Store store = new Store();
      Object original = new String("equal media data");
      bridge.loaded("key", null, store.entry(1, original, "token", true));
      bridge.refresh(store);
      Object nativeReplacement = new String("equal media data");
      assertEquals(original, nativeReplacement);
      assertNotSame(original, nativeReplacement);
      store.current.put(1, nativeReplacement);
      bridge.loaded("key", "key", store.entry(1, new Object(), "token", false));
      bridge.refresh(store);
      if (stop) bridge.clear(store);
      else { bridge.removed("key"); bridge.refresh(store); }
      assertSame(nativeReplacement, store.current.get(1));
      assertEquals(0, store.removes);
      assertEquals(1, store.adds);
    }
  }

  @Test public void anInitiallyNativeCardCannotBeResurrectedByTheLegacyCache() throws Exception {
    MediaEntryBridge bridge = new MediaEntryBridge();
    Store store = new Store();
    Object data = new Object();
    store.current.put(1, data);
    bridge.loaded("key", null, store.entry(1, data, "token", false));
    bridge.refresh(store);
    store.current.remove(1);
    bridge.refresh(store);
    assertTrue(store.current.isEmpty());
    assertEquals(0, store.adds);
  }

  @Test public void aFreshSessionCanReplaceARemovedSessionWithoutAPlayingFlag() throws Exception {
    MediaEntryBridge bridge = new MediaEntryBridge();
    Store store = new Store();
    bridge.loaded("key", null, store.entry(1, new Object(), "old token", false));
    bridge.refresh(store);
    store.current.remove(1);
    bridge.loaded("key", "key", store.entry(1, new Object(), "new token", false));
    bridge.refresh(store);
    assertEquals(1, store.current.size());
    assertEquals(2, store.adds);
  }

  @Test public void cleanupAttemptsEveryOwnedCardEvenIfOneRemovalFails() throws Exception {
    MediaEntryBridge bridge = new MediaEntryBridge();
    Store store = new Store();
    bridge.loaded("first", null, store.entry(1, new Object(), "token1", true));
    bridge.loaded("second", null, store.entry(2, new Object(), "token2", true));
    bridge.refresh(store);
    java.util.Set<Object> attempted = new java.util.HashSet<>();
    Exception failure = new Exception("repository removal failed");
    Exception actual = assertThrows(Exception.class, () -> bridge.clear(new MediaEntryBridge.Store() {
      public Map<?, ?> entries() { return store.entries(); }
      public void add(Object data) { store.add(data); }
      public void remove(Object id, Object data) throws Exception {
        attempted.add(id);
        if (id.equals(1)) throw failure;
        store.remove(id, data);
      }
    }));
    assertSame(failure, actual);
    assertEquals(java.util.Set.of(1, 2), attempted);
    assertFalse(store.current.containsKey(2));
    assertEquals(0, bridge.size());
  }

  @Test public void metadataCannotClearGlobalDismissalButResumeAndNewMediaCan() throws Exception {
    MediaEntryBridge bridge = new MediaEntryBridge();
    Store store = new Store();
    bridge.loaded("first", null, store.entry(1, new Object(), "token1", true));
    bridge.loaded("second", null, store.entry(2, new Object(), "token2", true));
    bridge.refresh(store);
    store.dismissed = true;
    bridge.loaded("first", "first", store.entry(1, new Object(), "token1", false));
    bridge.removed("second");
    bridge.refresh(store);
    assertTrue(store.dismissed);
    assertFalse(store.current.containsKey(2));
    assertEquals(2, store.adds);
    bridge.loaded("first", "first", store.entry(1, new Object(), "token1", true));
    bridge.refresh(store);
    assertFalse(store.dismissed);
    assertEquals(3, store.adds);
    store.dismissed = true;
    bridge.loaded("third", null, store.entry(3, new Object(), "token3", true));
    bridge.refresh(store);
    assertFalse(store.dismissed);
    assertEquals(4, store.adds);
  }
}
