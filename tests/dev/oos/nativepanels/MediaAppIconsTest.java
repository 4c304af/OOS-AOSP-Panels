package dev.oos.nativepanels;

import static org.junit.Assert.*;

import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class MediaAppIconsTest {
  @Test public void launcherFallbackKeepsItsIdentityWhileNotificationGlyphsRemainTintable() {
    Object repository = new Object(), launcher = new Object(), notification = new Object();
    MediaAppIcons icons = new MediaAppIcons(() -> repository);
    icons.remember(repository, launcher, true);
    assertTrue(icons.isFullColor(launcher));
    assertFalse(icons.isFullColor(notification));
    assertFalse(icons.isFullColor(null));
  }

  @Test public void missingPackageResourceFallbackAndForeignRepositoriesStayNative() {
    Object repository = new Object(), resource = new Object(), foreign = new Object();
    MediaAppIcons icons = new MediaAppIcons(() -> repository);
    icons.remember(repository, resource, false);
    icons.remember(new Object(), foreign, true);
    icons.remember(null, resource, true);
    icons.remember(repository, null, true);
    assertFalse(icons.isFullColor(resource));
    assertFalse(icons.isFullColor(foreign));
  }

  @Test public void equalIconModelsCannotBorrowAnotherIconsFullColorProvenance() {
    Object repository = new Object();
    EqualIcon launcher = new EqualIcon(), ordinary = new EqualIcon();
    assertEquals(launcher, ordinary);
    MediaAppIcons icons = new MediaAppIcons(() -> repository);
    icons.remember(repository, launcher, true);
    assertTrue(icons.isFullColor(launcher));
    assertFalse(icons.isFullColor(ordinary));
  }

  @Test public void stoppedAndReplacedRepositoriesCannotUsePreviousOwnership() {
    Object repository = new Object(), icon = new Object();
    AtomicReference<Object> active = new AtomicReference<>(repository);
    MediaAppIcons icons = new MediaAppIcons(active::get);
    icons.remember(repository, icon, true);
    active.set(null);
    assertFalse(icons.isFullColor(icon));
    icons.remember(repository, new Object(), true);
    active.set(new Object());
    assertFalse(icons.isFullColor(icon));
    active.set(repository);
    assertTrue(icons.isFullColor(icon));
  }

  @Test public void tintOverridePreservesAllOtherArgumentsAndInvalidatesOnlyColorFlags() {
    Object icon = new Object(), modifier = new Object(), composer = new Object();
    Object[] original = {icon, modifier, 123L, composer, 0x7fffffff, 0x7fffffff};
    Object[] result = MediaAppIcons.withoutTint(original, 456L);
    assertNotSame(original, result);
    assertSame(icon, result[0]);
    assertSame(modifier, result[1]);
    assertSame(composer, result[3]);
    assertEquals(456L, result[2]);
    assertEquals(0x7fffffff & ~(7 << 6), result[4]);
    assertEquals(0x7fffffff & ~4, result[5]);
    assertEquals(123L, original[2]);
    assertEquals(0x7fffffff, original[4]);
    assertEquals(0x7fffffff, original[5]);
  }

  @Test public void provenanceDoesNotRetainIconsOrRepositoryAndDrainsCollectedKeys() throws Exception {
    Object repository = new Object(), icon = new Object();
    MediaAppIcons icons = new MediaAppIcons(() -> repository);
    icons.remember(repository, icon, true);
    Field field = MediaAppIcons.class.getDeclaredField("fullColor");
    field.setAccessible(true);
    Map<?, ?> entries = (Map<?, ?>)field.get(icons);
    Reference<?> key = (Reference<?>)entries.keySet().iterator().next();
    Reference<?> value = (Reference<?>)entries.values().iterator().next();
    assertSame(icon, key.get());
    assertSame(repository, value.get());
    key.clear();
    assertTrue(key.enqueue());
    assertFalse(icons.isFullColor(icon));
    assertTrue(entries.isEmpty());
  }

  @Test public void backgroundCompletionCanPublishToTheUiThread() throws Exception {
    Object repository = new Object(), icon = new Object();
    MediaAppIcons icons = new MediaAppIcons(() -> repository);
    Thread loader = new Thread(() -> icons.remember(repository, icon, true));
    loader.start();
    loader.join();
    assertTrue(icons.isFullColor(icon));
  }

  private static final class EqualIcon {
    @Override public int hashCode() { return 1; }
    @Override public boolean equals(Object other) { return other instanceof EqualIcon; }
  }
}
