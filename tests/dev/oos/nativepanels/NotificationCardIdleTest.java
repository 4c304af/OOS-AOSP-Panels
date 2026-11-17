package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class NotificationCardIdleTest {
  @Test public void clearingAnInactiveCardRendererDoesNotKeepSchedulingDraws() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    NotificationCards cards = new NotificationCards();
    Field nativeCorners = TrackingView.class.getDeclaredField("mCornerRadii");
    nativeCorners.setAccessible(true);
    set(cards, "corners", nativeCorners);
    FrameLayout root = new FrameLayout(context);
    TrackingView row = new TrackingView(context);
    TrackingView background = new TrackingView(context);
    TrackingView overlay = new TrackingView(context);
    root.addView(row);
    root.addView(background);
    root.addView(overlay);
    Drawable nativeBackground = new ColorDrawable(Color.BLUE);
    overlay.setBackground(nativeBackground);
    NotificationOverlayRegistry registry = (NotificationOverlayRegistry)get(cards, "overlayBackgrounds");
    registry.register(nativeBackground, overlay, row);
    Object card = remember(cards, row, background);
    set(cards, "shadeRoot", root);
    int before = overlay.invalidations;

    cards.clear();

    assertTrue("The first clear must erase the last tinted display list", overlay.invalidations > before);
    assertNull(get(cards, "shadeRoot"));
    assertTrue(((Map<?, ?>)get(cards, "rows")).isEmpty());
    assertNull(((RippleDrawable)get(card, "ripple")).getCallback());
    assertSame("OOS can reuse the factory binding on the next pull", row,
        registry.ownerOf(nativeBackground, root));
    int overlayAfterClear = overlay.invalidations;
    int rowAfterClear = row.invalidations;
    int backgroundAfterClear = background.invalidations;

    // ShadeDecor invokes clear from pre-draw while QS is fully expanded or cards are off.
    // An unchanged inactive state must not invalidate a child and cause the next traversal.
    for (int frame = 0; frame < 120; frame++) cards.clear();
    assertEquals(overlayAfterClear, overlay.invalidations);
    assertEquals(rowAfterClear, row.invalidations);
    assertEquals(backgroundAfterClear, background.invalidations);

    remember(cards, row, background);
    set(cards, "shadeRoot", root);
    cards.clear();
    assertTrue("A later active-to-inactive transition still repaints", overlay.invalidations > overlayAfterClear);
    assertSame(row, registry.ownerOf(nativeBackground, root));
  }

  @Test public void anOverlayCreatedWhileInactiveDoesNotStartAClearRedrawLoop() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    NotificationCards cards = new NotificationCards();
    TrackingView overlay = new TrackingView(context);
    View owner = new View(context);
    Drawable nativeBackground = new ColorDrawable(Color.BLUE);
    overlay.setBackground(nativeBackground);
    NotificationOverlayRegistry registry = (NotificationOverlayRegistry)get(cards, "overlayBackgrounds");
    registry.register(nativeBackground, overlay, owner);
    int before = overlay.invalidations;

    for (int frame = 0; frame < 120; frame++) cards.clear();

    assertEquals(before, overlay.invalidations);
    assertSame(owner, registry.ownerOf(nativeBackground));
  }

  @SuppressWarnings("unchecked")
  private static Object remember(NotificationCards cards, View row, View background) throws Exception {
    Class<?> type = Class.forName(NotificationCards.class.getName() + "$Card");
    Constructor<?> constructor = type.getDeclaredConstructor(NotificationCards.class, View.class, View.class);
    constructor.setAccessible(true);
    Object card = constructor.newInstance(cards, row, background);
    ((Map<View, Object>)get(cards, "rows")).put(row, card);
    return card;
  }

  private static Object get(Object object, String name) throws Exception {
    Field field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(object);
  }

  private static void set(Object object, String name, Object value) throws Exception {
    Field field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(object, value);
  }

  private static final class TrackingView extends View {
    int invalidations;
    final float[] mCornerRadii = new float[8];
    TrackingView(Context context) { super(context); }
    @Override public void invalidate() { invalidations++; super.invalidate(); }
  }
}
