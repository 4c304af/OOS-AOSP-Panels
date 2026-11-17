package dev.oos.nativepanels;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class MediaMetadataTextTest {
  @Test public void missingFieldsBecomeEmptyOnlyForTheCurrentSourceConversion() throws Throwable {
    Object repository = new Object(), data = new Object(), anotherData = new Object();
    MediaMetadataText text = new MediaMetadataText(() -> repository);
    assertNull(text.value(data, null));
    text.convert(repository, data, () -> {
      assertEquals("", String.valueOf(text.value(data, null)));
      assertNull(text.value(anotherData, null));
      return null;
    });
    assertNull(text.value(data, null));
  }

  @Test public void genuineNullTextAndNonStringMetadataKeepTheirIdentity() throws Throwable {
    Object repository = new Object(), data = new Object();
    MediaMetadataText text = new MediaMetadataText(() -> repository);
    String literal = new String("null");
    StringBuilder styled = new StringBuilder("演奏者");
    text.convert(repository, data, () -> {
      assertSame(literal, text.value(data, literal));
      assertSame(styled, text.value(data, styled));
      assertEquals("", text.value(data, ""));
      return null;
    });
  }

  @Test public void foreignOrInactiveRepositoriesKeepNativeNullValues() throws Throwable {
    Object repository = new Object(), data = new Object();
    AtomicReference<Object> active = new AtomicReference<>(repository);
    MediaMetadataText text = new MediaMetadataText(active::get);
    text.convert(new Object(), data, () -> {
      assertNull(text.value(data, null));
      return null;
    });
    active.set(null);
    text.convert(repository, data, () -> {
      assertNull(text.value(data, null));
      return null;
    });
    text.convert(null, data, () -> {
      assertNull(text.value(data, null));
      return null;
    });
  }

  @Test public void nestedForeignConversionCannotBorrowTheOuterSource() throws Throwable {
    Object repository = new Object(), data = new Object(), nested = new Object();
    MediaMetadataText text = new MediaMetadataText(() -> repository);
    text.convert(repository, data, () -> {
      assertEquals("", text.value(data, null));
      text.convert(new Object(), data, () -> {
        assertNull(text.value(data, null));
        return null;
      });
      text.convert(repository, nested, () -> {
        assertNull(text.value(data, null));
        assertEquals("", text.value(nested, null));
        return null;
      });
      assertEquals("", text.value(data, null));
      assertNull(text.value(nested, null));
      return null;
    });
    assertNull(text.value(data, null));
  }

  @Test public void exceptionsRestoreOuterScopeAndNeverRerunTheConversion() throws Throwable {
    Object repository = new Object(), outer = new Object(), inner = new Object();
    MediaMetadataText text = new MediaMetadataText(() -> repository);
    IllegalStateException failure = new IllegalStateException("conversion");
    int[] calls = {0};
    text.convert(repository, outer, () -> {
      Throwable caught = null;
      try {
        text.convert(repository, inner, () -> { calls[0]++; throw failure; });
      } catch (Throwable thrown) { caught = thrown; }
      assertSame(failure, caught);
      assertEquals("", text.value(outer, null));
      assertNull(text.value(inner, null));
      return null;
    });
    assertEquals(1, calls[0]);
    assertNull(text.value(outer, null));
  }

  @Test public void suspensionAndThreadChangesNeverLeakTheSourceScope() throws Throwable {
    Object repository = new Object(), data = new Object(), suspended = new Object();
    MediaMetadataText text = new MediaMetadataText(() -> repository);
    AtomicReference<String> saved = new AtomicReference<>();
    assertSame(suspended, text.convert(repository, data, () -> {
      saved.set(String.valueOf(text.value(data, null)));
      return suspended;
    }));
    assertNull(text.value(data, null));
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread resume = new Thread(() -> {
      try {
        assertNull(text.value(data, null));
        text.convert(repository, data, () -> {
          assertEquals("", saved.get());
          assertEquals("", text.value(data, null));
          return null;
        });
        assertNull(text.value(data, null));
      } catch (Throwable thrown) { failure.set(thrown); }
    });
    resume.start();
    resume.join();
    if (failure.get() != null) throw failure.get();
    assertNull(text.value(data, null));
  }
}
