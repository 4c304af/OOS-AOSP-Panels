package dev.oos.nativepanels;

import static org.junit.Assert.*;
import static dev.oos.nativepanels.NotificationCornerTransition.*;

import android.view.View;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.RectF;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class NotificationNativeCornerTest {
  private NotificationCards cards;
  private NotificationGroupBackgroundTest.Row summary, child, standalone;

  @Before public void setUp() throws Exception {
    NotificationGroupBackgroundTest fixture = new NotificationGroupBackgroundTest();
    fixture.setUp();
    cards = (NotificationCards)get(fixture, "cards");
    summary = (NotificationGroupBackgroundTest.Row)get(fixture, "summary");
    child = (NotificationGroupBackgroundTest.Row)get(fixture, "child");
    standalone = (NotificationGroupBackgroundTest.Row)get(fixture, "standalone");
    set(cards, "nativeSetRadius", NotificationGroupBackgroundTest.Background.class
        .getMethod("setRadius", float.class, float.class, boolean.class));
    set(cards, "bottomRounded", NotificationGroupBackgroundTest.Background.class.getField("mBottomIsRounded"));
  }

  @Test public void nativeAndSteadyShapesAgreeAcrossGroupHandoffAndImmediateReversal() throws Exception {
    summary.expanded = true;
    Object card = card(child);
    NotificationCornerTransition transition = transition(card);
    transition.target(BOTTOM, 100, false);
    update(card, 100);
    assertSameShape(card, child);
    float joined = child.bg.radii[4];

    summary.changing = true;
    assertTrue((boolean)call(cards, "restricted", new Class<?>[]{View.class}, child));
    transition.target(0, 200, true);
    update(card, 200);
    assertEquals(joined, child.bg.radii[4], 0f);
    assertSameShape(card, child);
    update(card, 290);
    float middle = child.bg.radii[4];
    assertTrue(middle > joined);
    assertTrue(middle < child.bg.radii[0]);
    assertSameShape(card, child);

    summary.changing = false;
    transition.target(BOTTOM, 290, true);
    update(card, 290);
    assertEquals(middle, child.bg.radii[4], 0f);
    assertSameShape(card, child);
    update(card, 470);
    assertEquals(joined, child.bg.radii[4], 0f);
    assertFalse(transition.isRunning(470));
    assertSameShape(card, child);
  }

  @Test public void summaryUsesItsCurrentJoinedShapeDuringTheNativeGroupAnimation() throws Exception {
    Object card = card(summary);
    transition(card).target(TOP, 100, false);
    update(card, 100);
    float joinedTop = summary.bg.radii[0];
    assertTrue(joinedTop < summary.bg.radii[4]);
    summary.expanded = true;
    summary.changing = true;
    transition(card).target(0, 200, true);
    update(card, 200);
    assertEquals(joinedTop, summary.bg.radii[0], 0f);
    assertSameShape(card, summary);
    update(card, 290);
    assertTrue(summary.bg.radii[0] > joinedTop);
    assertTrue(summary.bg.radii[0] < summary.bg.radii[4]);
    assertSameShape(card, summary);
  }

  @Test public void interceptedNativeSourceValuesAreNotRecapturedFromOurScaledWrites() throws Exception {
    Object card = card(standalone);
    transition(card).target(0, 100, false);
    update(card, 100);
    call(card, "captureNativeCorners", new Class<?>[]{float.class, float.class}, 8f, 16f);
    call(card, "resolveCorners", new Class<?>[]{long.class}, 110L);
    call(card, "applyNativeCorners", new Class<?>[0]);
    float expectedTop = standalone.bg.radii[0];
    assertTrue(expectedTop > 8f);
    for (long now = 111; now < 200; now++) update(card, now);
    assertEquals(8f, (float)get(card, "originalTop"), 0f);
    assertEquals(16f, (float)get(card, "originalBottom"), 0f);
    assertEquals(expectedTop, standalone.bg.radii[0], 0f);
    assertSameShape(card, standalone);
    cards.clear();
    assertCorners(standalone, 8f, 16f);
  }

  @Test public void directNativeRebindBecomesTheNewRestorationSource() throws Exception {
    Object card = card(standalone);
    transition(card).target(0, 100, false);
    update(card, 100);
    standalone.bg.setRadius(8f, 12f, false);
    update(card, 110);
    assertTrue(standalone.bg.radii[0] > 8f);
    assertTrue(standalone.bg.radii[4] > 12f);
    assertSameShape(card, standalone);
    cards.clear();
    assertCorners(standalone, 8f, 12f);
  }

  @Test public void releaseDoesNotOverwriteANewerUnownedNativeShape() throws Exception {
    Object card = card(standalone);
    transition(card).target(0, 100, false);
    update(card, 100);
    standalone.bg.setRadius(6f, 10f, false);
    int calls = standalone.bg.radiusSetCalls;
    cards.clear();
    assertCorners(standalone, 6f, 10f);
    assertEquals(calls, standalone.bg.radiusSetCalls);
  }

  @Test public void cosmeticInnerCornerDoesNotEnableTheNativeBottomClippingFlag() throws Exception {
    standalone.bg.setRadius(16f, 0f, false);
    Object card = card(standalone);
    transition(card).target(BOTTOM, 100, false);
    update(card, 100);
    assertTrue(standalone.bg.radii[4] > 0f);
    assertFalse(standalone.bg.mBottomIsRounded);
    assertSameShape(card, standalone);
    cards.clear();
    assertCorners(standalone, 16f, 0f);
    assertFalse(standalone.bg.mBottomIsRounded);
  }

  @Test public void stableFramesAndRepeatedClearDoNotKeepWritingNativeGeometry() throws Exception {
    Object card = card(standalone);
    transition(card).target(TOP, 100, false);
    update(card, 100);
    int calls = standalone.bg.radiusSetCalls;
    for (long now = 101; now < 301; now++) {
      transition(card).target(TOP, now, true);
      update(card, now);
    }
    assertEquals(calls, standalone.bg.radiusSetCalls);
    assertFalse(transition(card).isRunning(301));
    cards.clear();
    calls = standalone.bg.radiusSetCalls;
    cards.clear();
    assertEquals(calls, standalone.bg.radiusSetCalls);
  }

  @Test public void enteringAProtectedSurfaceRestoresTheOriginalNativeShape() throws Exception {
    Object card = card(standalone);
    transition(card).target(TOP | BOTTOM, 100, false);
    update(card, 100);
    assertTrue(standalone.bg.radii[0] < 16f);
    standalone.keyguard = true;
    update(card, 110);
    assertCorners(standalone, 16f, 16f);
    assertFalse((boolean)get(card, "nativeCornersOwned"));
  }

  @Test public void nativeBackgroundClipUsesTheJoinedShapeWithoutChangingContentClipping() throws Exception {
    Object card = card(standalone);
    transition(card).target(TOP | BOTTOM, 100, false);
    update(card, 100);
    Bitmap contentBefore = renderClip(standalone);
    AtomicInteger calls = new AtomicInteger();
    Bitmap nativeBackground = (Bitmap)withScope(card, () -> {
      calls.incrementAndGet();
      return renderClip(standalone);
    });
    assertEquals(1, calls.get());
    // The clip rectangle starts at (7, 9). A 4px corner includes this pixel,
    // whereas the original 16px content/outline corner excludes it.
    assertEquals(Color.TRANSPARENT, contentBefore.getPixel(9, 11));
    assertEquals(Color.RED, nativeBackground.getPixel(9, 11));
    assertEquals(Color.TRANSPARENT, nativeBackground.getPixel(6, 20));
    assertEquals(Color.TRANSPARENT, nativeBackground.getPixel(108, 20));
    assertEquals(Color.RED, nativeBackground.getPixel(50, 30));
    assertEquals(Color.TRANSPARENT, renderClip(standalone).getPixel(9, 11));
  }

  @Test public void nestedBackgroundDrawsAndFailuresRestoreThePreviousClipScope() throws Exception {
    Object outer = card(standalone), inner = card(child);
    transition(outer).target(TOP, 100, false);
    transition(inner).target(BOTTOM, 100, false);
    update(outer, 100);
    update(inner, 100);
    withScope(outer, () -> {
      assertEquals(standalone.bg.radii[0], clipRadius(standalone, true), 0f);
      assertEquals(16f, clipRadius(child, true), 0f);
      withScope(inner, () -> {
        assertEquals(child.bg.radii[4], clipRadius(child, false), 0f);
        assertEquals(16f, clipRadius(standalone, true), 0f);
        return null;
      });
      assertEquals(standalone.bg.radii[0], clipRadius(standalone, true), 0f);
      RuntimeException expected = new RuntimeException("draw failed");
      InvocationTargetException failure = assertThrows(InvocationTargetException.class,
          () -> withScope(inner, () -> { throw expected; }));
      assertSame(expected, failure.getCause());
      assertEquals(standalone.bg.radii[0], clipRadius(standalone, true), 0f);
      return null;
    });
    assertEquals(16f, clipRadius(standalone, true), 0f);
    assertEquals(16f, clipRadius(child, false), 0f);
  }

  @Test public void anUnownedBackgroundCannotBorrowAnotherRowsClipPolicy() throws Exception {
    Object owned = card(standalone), unowned = card(child);
    transition(owned).target(TOP, 100, false);
    update(owned, 100);
    withScope(owned, () -> {
      withScope(unowned, () -> {
        assertEquals(16f, clipRadius(standalone, true), 0f);
        assertEquals(16f, clipRadius(child, true), 0f);
        return null;
      });
      assertEquals(standalone.bg.radii[0], clipRadius(standalone, true), 0f);
      return null;
    });
  }

  @Test public void nativeClipSyncPreservesBoundsAndRestoresTheLatestNativeSource() throws Exception {
    Object card = card(standalone);
    transition(card).target(TOP, 100, false);
    update(card, 100);
    ClipOwner owner = clipOwner(standalone);
    Object[] first = syncArguments(card, owner, 16f, 7, 9, 16f, 107, 69);
    owner.sync(first);
    assertEquals(standalone.bg.radii[0], owner.top, 0f);
    assertEquals(standalone.bg.radii[4], owner.bottom, 0f);
    assertArrayEquals(new int[]{7, 9, 107, 69}, owner.bounds);
    int writes = owner.writes;
    for (int frame = 0; frame < 120; frame++) {
      Object[] again = syncArguments(card, owner, 16f, 7, 9, 16f, 107, 69);
      assertSame(first, again);
      owner.sync(again);
    }
    assertEquals(writes, owner.writes);
    owner.sync(syncArguments(card, owner, 8f, 11, 19, 12f, 110, 90));
    assertArrayEquals(new int[]{11, 19, 110, 90}, owner.bounds);
    cards.clear();
    assertEquals(8f, owner.top, 0f);
    assertEquals(12f, owner.bottom, 0f);
    assertArrayEquals(new int[]{11, 19, 110, 90}, owner.bounds);
  }

  @Test public void aNativeBypassCannotBeOverwrittenByAStaleClipSnapshot() throws Exception {
    Object card = card(standalone);
    transition(card).target(TOP, 100, false);
    update(card, 100);
    ClipOwner owner = clipOwner(standalone);
    owner.sync(syncArguments(card, owner, 16f, 7, 9, 16f, 107, 69));
    call(card, "clearNativeClipOwnership", new Class<?>[0]);
    owner.syncClipCanvasToBgView(6f, 12, 14, 10f, 150, 100);
    int writes = owner.writes;
    cards.clear();
    assertEquals(writes, owner.writes);
    assertEquals(6f, owner.top, 0f);
    assertEquals(10f, owner.bottom, 0f);
    assertArrayEquals(new int[]{12, 14, 150, 100}, owner.bounds);
  }

  @Test public void aReboundNativeExtensionDoesNotReceiveThePreviousRowsClipSnapshot() throws Exception {
    Object card = card(standalone);
    transition(card).target(TOP, 100, false);
    update(card, 100);
    ClipOwner owner = clipOwner(standalone);
    owner.sync(syncArguments(card, owner, 16f, 7, 9, 16f, 107, 69));
    owner.background = child.bg;
    owner.syncClipCanvasToBgView(6f, 12, 14, 10f, 150, 100);
    int writes = owner.writes;
    cards.clear();
    assertEquals(writes, owner.writes);
    assertEquals(6f, owner.top, 0f);
    assertEquals(10f, owner.bottom, 0f);
  }

  private ClipOwner clipOwner(NotificationGroupBackgroundTest.Row row) throws Exception {
    set(cards, "nativeSyncClip", ClipOwner.class.getMethod("syncClipCanvasToBgView",
        float.class, int.class, int.class, float.class, int.class, int.class));
    set(cards, "nativeClipBackground", ClipOwner.class.getMethod("getNotiBgView"));
    return new ClipOwner(row.bg);
  }

  private Object[] syncArguments(Object card, ClipOwner owner, float top, int left, int clipTop,
      float bottom, int right, int clipBottom) throws Exception {
    return (Object[])call(card, "nativeClipArguments",
        new Class<?>[]{Object.class, float.class, int.class, int.class, float.class, int.class, int.class},
        owner, top, left, clipTop, bottom, right, clipBottom);
  }

  private Object withScope(Object card, NotificationBackgroundTint.Draw original) throws Exception {
    return call(cards, "withBackgroundClip",
        new Class<?>[]{card.getClass(), NotificationBackgroundTint.Draw.class}, card, original);
  }

  private float clipRadius(View row, boolean top) throws Exception {
    return (float)call(cards, "backgroundClipRadius", new Class<?>[]{View.class, float.class, boolean.class},
        row, 16f, top);
  }

  private Bitmap renderClip(View row) throws Exception {
    float top = clipRadius(row, true), bottom = clipRadius(row, false);
    Path path = new Path();
    path.addRoundRect(new RectF(7, 9, 107, 69),
        new float[]{top, top, top, top, bottom, bottom, bottom, bottom}, Path.Direction.CW);
    Bitmap bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    canvas.clipPath(path);
    canvas.drawColor(Color.RED);
    return bitmap;
  }

  public static final class ClipOwner {
    View background;
    float top, bottom;
    int[] bounds = new int[4];
    int writes;
    ClipOwner(View background) { this.background = background; }
    public View getNotiBgView() { return background; }
    void sync(Object[] args) {
      syncClipCanvasToBgView((float)args[0], (int)args[1], (int)args[2],
          (float)args[3], (int)args[4], (int)args[5]);
    }
    public void syncClipCanvasToBgView(float top, int left, int clipTop, float bottom, int right, int clipBottom) {
      if (this.top == top && this.bottom == bottom && bounds[0] == left && bounds[1] == clipTop
          && bounds[2] == right && bounds[3] == clipBottom) return;
      this.top = top;
      this.bottom = bottom;
      bounds = new int[]{left, clipTop, right, clipBottom};
      writes++;
    }
  }

  private Object card(NotificationGroupBackgroundTest.Row row) throws Exception {
    Class<?> type = Class.forName(NotificationCards.class.getName() + "$Card");
    Constructor<?> constructor = type.getDeclaredConstructor(NotificationCards.class, View.class, View.class);
    constructor.setAccessible(true);
    Object card = constructor.newInstance(cards, row, row.bg);
    ((Map)get(cards, "rows")).put(row, card);
    return card;
  }

  private NotificationCornerTransition transition(Object card) throws Exception {
    return (NotificationCornerTransition)get(card, "cornerTransition");
  }

  private void update(Object card, long now) throws Exception {
    call(card, "updateCorners", new Class<?>[]{long.class}, now);
  }

  private void assertSameShape(Object card, NotificationGroupBackgroundTest.Row row) throws Exception {
    assertArrayEquals((float[])get(card, "radii"), row.bg.radii, 0f);
    android.graphics.drawable.GradientDrawable art = (android.graphics.drawable.GradientDrawable)get(card, "base");
    assertArrayEquals(row.bg.radii, art.getCornerRadii(), 0f);
  }

  private void assertCorners(NotificationGroupBackgroundTest.Row row, float top, float bottom) {
    assertArrayEquals(new float[]{top, top, top, top, bottom, bottom, bottom, bottom}, row.bg.radii, 0f);
  }

  private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
    Method method = target.getClass().getDeclaredMethod(name, types);
    method.setAccessible(true);
    return method.invoke(target, args);
  }

  private static Object get(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void set(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
