package dev.oos.nativepanels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bridges accepted legacy events without taking ownership of native replacements. */
final class MediaEntryBridge {
  record Entry(Object data, Object id, Object token, boolean playing) {}
  private record Supplied(Object stored, Object source) {}
  interface Store {
    Map<?, ?> entries() throws Exception;
    default boolean dismissed() throws Exception { return false; }
    void add(Object data) throws Exception;
    void remove(Object id, Object data) throws Exception;
  }

  private final Map<Object, Entry> sources = new HashMap<>();
  private final Map<Object, Supplied> supplied = new HashMap<>();
  private final Set<Object> updated = new HashSet<>(), reactivated = new HashSet<>(), observed = new HashSet<>();

  void loaded(Object key, Object oldKey, Entry entry) {
    Entry previous = oldKey != null && !oldKey.equals(key) ? sources.remove(oldKey) : sources.get(key);
    // OOS rejects tokenless placeholders after updating LegacyMediaDataFilter.userEntries.
    // Apply that same rule when bootstrapping the already-running sessions.
    if (entry.token() == null) { sources.remove(key); return; }
    sources.put(key, entry);
    updated.add(entry.id());
    if (previous != null && (!Objects.equals(previous.token(), entry.token())
        || !previous.playing() && entry.playing())) reactivated.add(entry.id());
  }

  void removed(Object key) { sources.remove(key); }

  void refresh(Store store) throws Exception {
    Map<Object, Entry> desired = new HashMap<>();
    for (Entry entry : sources.values()) desired.put(entry.id(), entry);
    observed.retainAll(desired.keySet());
    Map<?, ?> current = store.entries();
    for (Object id : new ArrayList<>(supplied.keySet())) {
      if (!desired.containsKey(id)) {
        Object previous = supplied.get(id).stored();
        if (current.get(id) == previous) store.remove(id, previous);
        supplied.remove(id);
      }
    }
    boolean dismissed = store.dismissed();
    for (Entry entry : desired.values()) {
      Object id = entry.id(), data = entry.data();
      Supplied supply = supplied.get(id);
      Object previous = supply == null ? null : supply.stored();
      Object nativeEntry = current.get(id);
      boolean seen = observed.contains(id);
      if (nativeEntry != null) observed.add(id);
      // Retain the old ownership marker when another source takes over, so its later
      // removal cannot cause an unrelated legacy refresh to resurrect the old card.
      if (!CombinedMedia.canSupply(nativeEntry, previous)) continue;
      // Native add rebuilds asynchronously and then clears the global swipe flag.
      // Only fresh playback may do that; removals above still run while dismissed.
      if (dismissed && seen && !reactivated.contains(id)) continue;
      if (nativeEntry == null && seen && !reactivated.contains(id)) continue;
      // MediaData.active/lastActive/resumption can mutate in place. A real load event
      // must rebuild the native immutable card even when the source object is unchanged.
      if (supply != null && nativeEntry == previous && supply.source() == data
          && !updated.contains(id)) continue;
      store.add(data);
      // The repository's StateFlow retains the old object when a copied MediaData
      // compares equal. Track that retained identity so later updates/removals still
      // recognize our card, and track the accepted source to avoid rebuilding on refresh.
      current = store.entries();
      Object retained = current.get(id);
      Object owned = retained == data || retained != null && retained == previous ? retained : data;
      supplied.put(id, new Supplied(owned, data));
      observed.add(id);
    }
    updated.clear();
    reactivated.clear();
  }

  void clear(Store store) throws Exception {
    try {
      if (store != null) {
        Map<?, ?> current = store.entries();
        Exception failure = null;
        for (Map.Entry<Object, Supplied> entry : supplied.entrySet()) {
          Object previous = entry.getValue().stored();
          if (current.get(entry.getKey()) != previous) continue;
          try { store.remove(entry.getKey(), previous); }
          catch (Exception e) {
            if (failure == null) failure = e;
            else if (failure != e) failure.addSuppressed(e);
          }
        }
        if (failure != null) throw failure;
      }
    } finally {
      sources.clear();
      supplied.clear();
      updated.clear();
      reactivated.clear();
      observed.clear();
    }
  }

  int size() { return supplied.size(); }
}
