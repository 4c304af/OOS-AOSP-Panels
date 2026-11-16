package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class NotificationBackgroundTintTest {
  private Context context;
  private FrameLayout row;
  private View background;

  @Before public void setUp() {
    context = RuntimeEnvironment.getApplication();
    row = new FrameLayout(context);
    background = new View(context);
    row.addView(background);
    row.layout(0, 0, 180, 140);
    background.layout(10, 8, 150, 108);
    background.setTranslationX(5);
    background.setTranslationY(7);
    background.setBackgroundColor(Color.BLUE);
  }

  @Test public void nativeHeightClipAndFadeArePreservedAtEveryExpansionFrame() throws Throwable {
    NotificationBackgroundTint tint = new NotificationBackgroundTint();
    int fill = 0xffdf9db9;
    tint.setColor(fill);
    var nativeDrawable = background.getBackground();
    for (int height : new int[]{24, 52, 96}) {
      for (int fade : new int[]{64, 128, 255}) {
        Bitmap original = bitmap();
        drawNative(new Canvas(original), height, fade);
        // A bounded offscreen surface may use a different AA raster origin than
        // drawing directly into the destination. Compare filtering with an otherwise
        // identical native layer; validate absolute clipping/fade separately below.
        Bitmap layered = bitmap();
        Canvas nativeLayer = new Canvas(layered);
        int layer = nativeLayer.saveLayer(new RectF(15, 15, 155, 115), null);
        drawNative(nativeLayer, height, fade);
        nativeLayer.restoreToCount(layer);
        Bitmap replaced = bitmap();
        Canvas canvas = new Canvas(replaced);
        int saves = canvas.getSaveCount();
        AtomicInteger calls = new AtomicInteger();
        Object result = tint.draw(canvas, background, 140, height, () -> {
          calls.incrementAndGet();
          drawNative(canvas, height, fade);
          return Boolean.TRUE;
        });
        assertEquals(Boolean.TRUE, result);
        assertEquals(1, calls.get());
        assertEquals(saves, canvas.getSaveCount());
        for (int y = 0; y < original.getHeight(); y++) {
          for (int x = 0; x < original.getWidth(); x++) {
            int expectedAlpha = Color.alpha(layered.getPixel(x, y));
            assertEquals("native shape/clip/fade at " + x + "," + y,
                expectedAlpha, Color.alpha(replaced.getPixel(x, y)));
            if (x < 15 || x >= 155 || y < 19 || y >= height + 13) {
              assertEquals("outside native clip", 0, Color.alpha(replaced.getPixel(x, y)));
              assertEquals(0, Color.alpha(original.getPixel(x, y)));
            }
          }
        }
        int center = replaced.getPixel(70, 25);
        assertEquals(Color.alpha(original.getPixel(70, 25)), Color.alpha(center));
        assertEquals(fade, Color.alpha(center));
        // Unpremultiplying an 8-bit partially transparent pixel loses channel precision.
        int tolerance = (255 + fade - 1) / fade;
        assertEquals(Color.red(fill), Color.red(center), tolerance);
        assertEquals(Color.green(fill), Color.green(center), tolerance);
        assertEquals(Color.blue(fill), Color.blue(center), tolerance);
      }
    }
    assertSame(nativeDrawable, background.getBackground());
    assertNull(nativeDrawable.getColorFilter());
    assertNull(background.getBackgroundTintList());
  }

  @Test public void translucentSurfaceMultipliesTheNativeFadeWithoutMakingItOpaque() throws Throwable {
    NotificationBackgroundTint tint = new NotificationBackgroundTint();
    tint.setColor(0x80392f36);
    Bitmap image = bitmap();
    Canvas canvas = new Canvas(image);
    tint.draw(canvas, background, 140, 80, () -> {
      drawNative(canvas, 80, 128);
      return Boolean.FALSE;
    });
    assertEquals(64f, Color.alpha(image.getPixel(70, 40)), 1f);
    assertEquals(0, Color.alpha(image.getPixel(0, 0)));
  }

  @Test public void hiddenNativeBackgroundIsNotSynthesizedAndCanvasIsRestoredAfterFailure() throws Throwable {
    NotificationBackgroundTint tint = new NotificationBackgroundTint();
    tint.setColor(Color.RED);
    Bitmap image = bitmap();
    Canvas canvas = new Canvas(image);
    int saves = canvas.getSaveCount();
    assertEquals(Boolean.FALSE, tint.draw(canvas, background, 140, 80, () -> Boolean.FALSE));
    assertEquals(Color.TRANSPARENT, image.getPixel(70, 40));
    AtomicInteger calls = new AtomicInteger();
    IllegalStateException original = new IllegalStateException("native renderer");
    try {
      tint.draw(canvas, background, 140, 80, () -> {
        calls.incrementAndGet();
        throw original;
      });
      fail("native exception must propagate");
    } catch (IllegalStateException failure) {
      assertSame(original, failure);
    }
    assertEquals(1, calls.get());
    assertEquals(saves, canvas.getSaveCount());
    canvas.drawColor(Color.GREEN);
    assertEquals(Color.GREEN, image.getPixel(70, 40));
  }

  @Test public void fallbackAcceptsGroupTransitionsButRespectsRowAndParentPrivacy() throws Exception {
    NotificationCards cards = new NotificationCards();
    for (String[] method : new String[][]{
        {"groupChild", "isChildInGroup"}, {"notificationParent", "getNotificationParent"},
        {"headsUp", "isHeadsUpState"}, {"gutsExposed", "areGutsExposed"},
        {"launchRunning", "isExpandAnimationRunning"}}) {
      set(cards, method[0], NotificationGroupBackgroundTest.Row.class.getMethod(method[1]));
    }
    for (String[] field : new String[][]{
        {"onKeyguard", "keyguard"}, {"showingPublic", "publicContent"}, {"appearing", "appearing"}}) {
      set(cards, field[0], NotificationGroupBackgroundTest.Row.class.getField(field[1]));
    }
    NotificationGroupBackgroundTest.Row parent = new NotificationGroupBackgroundTest.Row(context);
    parent.summary = true;
    NotificationGroupBackgroundTest.Row child = new NotificationGroupBackgroundTest.Row(context);
    child.child = true;
    child.notificationParent = parent;
    for (String transition : new String[]{"changing", "dragging", "childrenAnimating", "hidesBackground"}) {
      NotificationGroupBackgroundTest.Row.class.getField(transition).setBoolean(parent, true);
      assertTrue(transition, canTint(cards, parent));
      assertTrue(transition, canTint(cards, child));
    }
    for (NotificationGroupBackgroundTest.Row unsafe : new NotificationGroupBackgroundTest.Row[]{parent, child}) {
      for (String state : new String[]{"keyguard", "publicContent", "headsUp", "guts", "launching", "appearing"}) {
        Field field = NotificationGroupBackgroundTest.Row.class.getField(state);
        field.setBoolean(unsafe, true);
        assertFalse(state, canTint(cards, child));
        field.setBoolean(unsafe, false);
      }
    }
    child.notificationParent = null;
    assertFalse(canTint(cards, child));
    assertTrue(canTint(cards, new NotificationGroupBackgroundTest.Row(context)));
  }

  @Test public void overlayTintUsesLocalDrawableBoundsAndLeavesItsTitleUntouched() throws Throwable {
    NotificationBackgroundTint tint = new NotificationBackgroundTint();
    tint.setColor(Color.RED);
    GradientDrawable nativeBackground = new GradientDrawable();
    nativeBackground.setColor(Color.BLUE);
    nativeBackground.setBounds(0, 0, 110, 70);
    Bitmap image = bitmap();
    Canvas canvas = new Canvas(image);
    canvas.translate(25, 30); // Overlay draw has already entered the target View's canvas.
    int saves = canvas.getSaveCount();
    tint.draw(canvas, nativeBackground.getBounds(), () -> {
      nativeBackground.draw(canvas);
      return null;
    });
    Paint title = new Paint();
    title.setColor(Color.GREEN);
    canvas.drawRect(20, 15, 80, 35, title);
    assertEquals(Color.RED, image.getPixel(30, 35));
    assertEquals(Color.GREEN, image.getPixel(60, 55));
    assertEquals(Color.TRANSPARENT, image.getPixel(10, 10));
    assertEquals(saves, canvas.getSaveCount());
    assertEquals(Color.BLUE, nativeBackground.getColor().getDefaultColor());
    assertNull(nativeBackground.getColorFilter());
  }

  @Test public void nestedDrawableHooksDoNotTintOrMultiplyAlphaTwice() throws Throwable {
    NotificationBackgroundTint outer = new NotificationBackgroundTint();
    NotificationBackgroundTint inner = new NotificationBackgroundTint();
    outer.setColor(0x80ff0000);
    inner.setColor(0x800000ff);
    Bitmap image = bitmap();
    Canvas canvas = new Canvas(image);
    Rect bounds = new Rect(10, 10, 100, 80);
    AtomicInteger calls = new AtomicInteger();
    Object result = outer.draw(canvas, bounds, () -> inner.draw(canvas, bounds, () -> {
      calls.incrementAndGet();
      Paint paint = new Paint();
      paint.setColor(Color.WHITE);
      canvas.drawRect(bounds, paint);
      return Boolean.TRUE;
    }));
    assertEquals(Boolean.TRUE, result);
    assertEquals(1, calls.get());
    assertEquals(128, Color.alpha(image.getPixel(40, 40)));
    assertEquals(255, Color.red(image.getPixel(40, 40)));
    assertEquals(0, Color.blue(image.getPixel(40, 40)));
  }

  private void drawNative(Canvas canvas, int height, int alpha) {
    int save = canvas.save();
    canvas.translate(background.getX(), background.getY());
    canvas.clipRect(0, 4, 140, height - 2);
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setColor(Color.BLUE);
    paint.setAlpha(alpha);
    canvas.drawRoundRect(new RectF(0, 0, 140, height), 14, 14, paint);
    canvas.restoreToCount(save);
  }

  private static Bitmap bitmap() { return Bitmap.createBitmap(180, 140, Bitmap.Config.ARGB_8888); }

  private static boolean canTint(NotificationCards cards, View row) throws Exception {
    Method method = NotificationCards.class.getDeclaredMethod("canTintBackground", View.class);
    method.setAccessible(true);
    return (boolean)method.invoke(cards, row);
  }

  private static void set(Object object, String name, Object value) throws Exception {
    Field field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(object, value);
  }
}
