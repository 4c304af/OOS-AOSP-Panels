package dev.oos.nativepanels;

import android.graphics.drawable.Drawable;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;

/** Exact bindings reported by the native overlay factory, without retaining its Views. */
final class NotificationOverlayRegistry {
  private final ArrayList<Entry> entries = new ArrayList<>();

  void register(Drawable drawable, View target, View owner) {
    prune();
    for (Iterator<Entry> it = entries.iterator(); it.hasNext();) {
      Entry entry = it.next();
      if (entry.target.get() == target) it.remove();
    }
    if (drawable != null && target != null && owner != null && target.getBackground() == drawable)
      entries.add(new Entry(drawable, drawable, target, owner, null));
    // The overlay may live outside the row, with its own cached display list.
    if (target != null) target.invalidate();
  }

  void registerDefault(Drawable inner, Drawable wrapper, View target, View owner, Method getter) {
    prune();
    for (Iterator<Entry> it = entries.iterator(); it.hasNext();) {
      Entry entry = it.next();
      if (entry.target.get() == target && entry.getter != null) it.remove();
    }
    if (inner != null && wrapper != null && inner != wrapper && target != null && owner != null && getter != null
        && target.getBackground() == wrapper) {
      Entry entry = new Entry(inner, wrapper, target, owner, getter);
      if (entry.matchesDefault()) entries.add(entry);
    }
    if (target != null) target.invalidate();
  }

  View ownerOf(Drawable drawable) {
    return ownerOf(drawable, null, false);
  }

  View ownerOf(Drawable drawable, View shadeRoot) {
    return shadeRoot == null ? null : ownerOf(drawable, shadeRoot, true);
  }

  private View ownerOf(Drawable drawable, View shadeRoot, boolean enforceRoot) {
    prune();
    View target = null, owner = null;
    Drawable wrapper = null;
    boolean callbacksMatch = true;
    for (Iterator<Entry> it = entries.iterator(); it.hasNext();) {
      Entry entry = it.next();
      if (entry.drawable.get() != drawable) continue;
      // Only the exact candidate draw needs reflection; other UI drawables do not.
      if (!entry.matchesDefault()) {
        it.remove();
        View stale = entry.target.get();
        if (stale != null) stale.invalidate();
        continue;
      }
      View candidate = entry.target.get();
      // A shared Drawable can keep two Views' background fields while its callback
      // points to only the last one. Neither draw can be identified safely then.
      if (target != null && target != candidate) return null;
      target = candidate;
      owner = entry.owner.get();
      wrapper = entry.background.get();
      callbacksMatch &= wrapper != null && wrapper.getCallback() == target
          && (entry.getter == null || drawable.getCallback() == wrapper);
    }
    if (wrapper != null) {
      for (Entry entry : entries)
        if (entry.background.get() == wrapper && entry.target.get() != target) return null;
    }
    return target != null && owner != null && target.getBackground() == wrapper && callbacksMatch
        && (!enforceRoot || target.getRootView() == shadeRoot && owner.getRootView() == shadeRoot) ? owner : null;
  }

  void clear() {
    invalidateAll();
    entries.clear();
  }

  void invalidateAll() {
    prune();
    for (Entry entry : entries) {
      View target = entry.target.get();
      if (target != null) target.invalidate();
    }
  }

  void invalidateOwner(View owner) {
    prune();
    if (owner == null) return;
    for (Entry entry : entries) {
      if (entry.owner.get() != owner) continue;
      View target = entry.target.get();
      if (target != null) target.invalidate();
    }
  }

  private void prune() {
    for (Iterator<Entry> it = entries.iterator(); it.hasNext();) {
      Entry entry = it.next();
      View target = entry.target.get();
      Drawable drawable = entry.drawable.get();
      Drawable wrapper = entry.background.get();
      if (target == null || drawable == null || wrapper == null || entry.owner.get() == null || target.getBackground() != wrapper) {
        it.remove();
        if (target != null) target.invalidate();
      }
    }
  }

  private static final class Entry {
    final WeakReference<Drawable> drawable, background;
    final WeakReference<View> target, owner;
    final Method getter;
    Entry(Drawable drawable, Drawable background, View target, View owner, Method getter) {
      this.drawable = new WeakReference<>(drawable);
      this.background = new WeakReference<>(background);
      this.target = new WeakReference<>(target);
      this.owner = new WeakReference<>(owner);
      this.getter = getter;
    }
    boolean matchesDefault() {
      if (getter == null) return true;
      Drawable wrapper = background.get(), inner = drawable.get();
      if (wrapper == null || inner == null) return false;
      try { return getter.invoke(wrapper) == inner; }
      catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
    }
  }
}
