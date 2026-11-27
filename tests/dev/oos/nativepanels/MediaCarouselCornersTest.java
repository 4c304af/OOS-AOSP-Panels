package dev.oos.nativepanels;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MediaCarouselCornersTest {
  private final Object owner = new Object(), shape = new Object();
  private final AtomicReference<Object> attached = new AtomicReference<>(owner);
  private final AtomicInteger count = new AtomicInteger(3);
  private final AtomicReference<Boolean> scroll = new AtomicReference<>(true);
  private final MediaCarouselCorners corners = new MediaCarouselCorners(attached::get);

  private boolean rectangle(Object value) throws ReflectiveOperationException {
    return corners.rectangular(value, vm -> ((AtomicInteger)vm).get(), behavior ->
        ((AtomicReference<?>)behavior).get() == Boolean.TRUE);
  }

  @Test public void liveCardCountUpdatesAnAlreadyRememberedLayerInBothDirections() throws Exception {
    corners.bind(shape, owner, count, scroll);
    for (int size : new int[]{1, 2, 3, 2, 1, 0, 2}) {
      count.set(size);
      assertEquals(size > 1, rectangle(shape));
    }
    scroll.set(false); // Native pager renders only the first item in this mode.
    assertFalse(rectangle(shape));
    scroll.set(true);
    assertTrue(rectangle(shape));
  }

  @Test public void shapeOwnershipUsesIdentityAndFollowsTheCurrentAttachedHost() throws Exception {
    Object equalShape = new String("32dp");
    Object otherEqualShape = new String("32dp");
    assertEquals(equalShape, otherEqualShape);
    corners.bind(equalShape, owner, count, scroll);
    assertTrue(rectangle(equalShape));
    assertFalse(rectangle(otherEqualShape));
    attached.set(null);
    assertFalse(rectangle(equalShape));
    attached.set(owner); // Temporary detachment must not destroy the remembered binding.
    assertTrue(rectangle(equalShape));
    Object nextOwner = new Object();
    attached.set(nextOwner);
    assertFalse(rectangle(equalShape));
    corners.bind(equalShape, nextOwner, count, scroll);
    assertTrue(rectangle(equalShape));
    corners.bind(equalShape, null, count, scroll);
    assertFalse(rectangle(equalShape));
  }

  @Test public void replacingTheViewModelOnARememberedShapeDoesNotReadTheOldModel() throws Exception {
    corners.bind(shape, owner, count, scroll);
    AtomicInteger next = new AtomicInteger(1);
    corners.bind(shape, owner, next, scroll);
    assertFalse(rectangle(shape));
    count.set(0);
    next.set(2);
    assertTrue(rectangle(shape));
  }

  @Test public void compositionScopeHandlesEarlyCallbacksNestingAndExceptionalExit() throws Throwable {
    Object earlyShape = new Object(), unrelatedShape = new Object(), afterFailure = new Object();
    corners.compose(owner, count, scroll, () -> {
      assertTrue(rectangle(earlyShape)); // Called before the pager can bind the shape.
      corners.compose(null, count, scroll, () -> {
        assertFalse(rectangle(unrelatedShape));
        return null;
      });
      assertTrue(rectangle(shape));
      return null;
    });
    assertTrue(rectangle(earlyShape)); // Callback may run again after composition.
    assertFalse(rectangle(unrelatedShape));
    try {
      corners.compose(owner, count, scroll, () -> { throw new IllegalStateException("compose failed"); });
      fail("The original failure must propagate");
    } catch (IllegalStateException expected) {}
    assertFalse(rectangle(afterFailure));
  }

  @Test public void repeatedBindingsKeepOnlyWeakReferencesAndExpiredEntriesArePruned() throws Exception {
    for (int i = 0; i < 100; i++) corners.bind(shape, owner, count, scroll);
    Object registry = Reflect.field(MediaCarouselCorners.class, "bindings").get(corners);
    List<?> entries = (List<?>)Reflect.field(registry.getClass(), "values").get(registry);
    assertEquals(1, entries.size());
    Object entry = entries.get(0);
    for (String field : new String[]{"shape", "owner", "model", "behavior"}) {
      assertTrue(Reflect.field(entry.getClass(), field).get(entry) instanceof WeakReference<?>);
    }
    ((WeakReference<?>)Reflect.field(entry.getClass(), "owner").get(entry)).clear();
    assertFalse(rectangle(shape));
    assertTrue(entries.isEmpty());
  }

  @Test public void twoAndThreeCardEndsKeepTheirOwnCornersInBothLayoutDirections() throws Exception {
    corners.bind(shape, owner, count, scroll);
    for (int size : new int[]{2, 3}) {
      count.set(size);
      for (int selected = 0; selected < size; selected++) {
        float[] widths = new float[size];
        for (int i = 0; i < size; i++) widths[i] = i == selected ? 280 : 48;
        for (boolean rtl : new boolean[]{false, true}) {
          Bitmap expected = draw(widths, rtl, true, 0);
          Bitmap actual = draw(widths, rtl, rectangle(shape), 0);
          assertTrue("Item corners must survive at either viewport end", expected.sameAs(actual));
          assertTrue("Regression must reproduce the extra native outer cut",
              opaque(expected) > opaque(draw(widths, rtl, false, 0)));
          assertEquals(Color.TRANSPARENT, actual.getPixel(0, 80));
          assertEquals(Color.TRANSPARENT, actual.getPixel(actual.getWidth() - 1, 80));
        }
      }
    }
  }

  @Test public void changingWidthsAndOverscrollKeepNativeAnimatedMasksAndViewportClipping() throws Exception {
    corners.bind(shape, owner, count, scroll);
    for (int step = 0; step <= 20; step++) {
      float width = 48 + 232 * step / 20f;
      float[] widths = {width, 48, 328 - width};
      for (boolean rtl : new boolean[]{false, true}) {
        for (int offset : new int[]{-24, 0, 24}) {
          Bitmap expected = draw(widths, rtl, true, offset);
          Bitmap actual = draw(widths, rtl, rectangle(shape), offset);
          assertTrue(expected.sameAs(actual));
          assertEquals(Color.TRANSPARENT, actual.getPixel(0, 80));
          assertEquals(Color.TRANSPARENT, actual.getPixel(actual.getWidth() - 1, 80));
        }
      }
    }
    count.set(1);
    assertTrue(draw(new float[]{280}, false, false, 0)
        .sameAs(draw(new float[]{280}, false, rectangle(shape), 0)));
  }

  private static Bitmap draw(float[] widths, boolean rtl, boolean rectangle, int offset) {
    int height = 180, padding = 8;
    float width = (widths.length - 1) * 8;
    for (float item : widths) width += item;
    Bitmap result = Bitmap.createBitmap((int)width + padding * 2, height + padding * 2, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(result);
    canvas.translate(padding, padding);
    if (rectangle) canvas.clipRect(0, 0, width, height);
    else {
      Path outline = new Path();
      outline.addRoundRect(0, 0, width, height, 32, 32, Path.Direction.CW);
      canvas.clipPath(outline);
    }
    Paint paint = new Paint();
    paint.setColor(Color.BLACK);
    float x = offset;
    for (int i = 0; i < widths.length; i++) {
      float item = widths[rtl ? widths.length - 1 - i : i];
      // Compose CornerBasedShape clamps the 32dp item corners to half its width.
      float radius = Math.min(32, item / 2);
      canvas.drawRoundRect(x, 0, x + item, height, radius, radius, paint);
      x += item + 8;
    }
    return result;
  }

  private static int opaque(Bitmap bitmap) {
    int count = 0;
    for (int y = 0; y < bitmap.getHeight(); y++) {
      for (int x = 0; x < bitmap.getWidth(); x++) {
        if (Color.alpha(bitmap.getPixel(x, y)) != 0) count++;
      }
    }
    return count;
  }
}
