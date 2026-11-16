package dev.oos.nativepanels;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class NotificationGroupBackgroundTest {
  private NotificationCards cards;
  private FrameLayout stack;
  private Row summary, child, standalone;

  @Before public void setUp() throws Exception {
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    stack = new FrameLayout(activity);
    activity.setContentView(stack);
    summary = new Row(activity);
    summary.summary = true;
    child = new Row(activity);
    child.child = true;
    child.notificationParent = summary;
    standalone = new Row(activity);
    summary.children.addView(child);
    stack.addView(summary);
    stack.addView(standalone);
    stack.layout(0, 0, 200, 400);
    summary.layout(0, 0, 200, 180);
    summary.children.layout(0, 0, 200, 180);
    layoutRow(child);
    layoutRow(standalone);

    cards = new NotificationCards();
    set(cards, "rowType", Row.class);
    bindRowMethod("groupChild", "isChildInGroup");
    bindRowMethod("groupSummary", "isSummaryWithChildren");
    bindRowMethod("notificationParent", "getNotificationParent");
    bindRowMethod("childrenExpanded", "areChildrenExpanded");
    bindRowMethod("expansionChanging", "isGroupExpansionChanging");
    bindRowMethod("childrenAnimating", "isChildrenExpandedAnimating");
    bindRowMethod("headsUp", "isHeadsUpState");
    bindRowMethod("gutsExposed", "areGutsExposed");
    bindRowMethod("launchRunning", "isExpandAnimationRunning");
    bindRowMethod("childrenContainer", "getChildrenContainer");
    bindRowMethod("privateLayout", "getPrivateLayout");
    bindRowMethod("actualHeight", "getActualHeight");
    bindRowMethod("clipTop", "getClipTopAmount");
    bindRowMethod("clipBottom", "getClipBottomAmount");
    bindRowMethod("topRadius", "getTopCornerRadius");
    bindRowMethod("bottomRadius", "getBottomCornerRadius");
    bindRowField("onKeyguard", "keyguard");
    bindRowField("showingPublic", "publicContent");
    bindRowField("groupHidesBackground", "hidesBackground");
    bindRowField("userExpanding", "dragging");
    bindRowField("appearing", "appearing");
    set(cards, "bgWidth", Background.class.getMethod("getActualWidth"));
    set(cards, "bgHeight", Background.class.getMethod("getActualHeight"));
    set(cards, "alignRight", Background.class.getMethod("isAlignedToRight"));
    set(cards, "corners", Background.class.getField("radii"));
    set(cards, "drawableAlpha", Background.class.getField("drawableAlpha"));
  }

  @Test public void switchesFromSummaryToChildrenOnlyAfterTheParentFinishesExpanding() throws Exception {
    assertFalse(restricted(summary));
    assertTrue(restricted(child));
    child.expanded = true; // A child's own expansion state does not expand its group.
    assertTrue(restricted(child));

    summary.expanded = true;
    summary.hidesBackground = true; // Normal for an expanded group: do not paint two surfaces.
    assertTrue(restricted(summary));
    assertFalse(restricted(child));
    summary.changing = true;
    assertTrue(restricted(child));
    summary.changing = false;
    summary.dragging = true;
    assertTrue(restricted(child));
    summary.dragging = false;
    assertFalse(restricted(child));

    summary.expanded = false;
    summary.changing = true;
    assertTrue(restricted(child));
    assertTrue(restricted(summary));
    summary.changing = false;
    summary.hidesBackground = false;
    assertTrue(restricted(child));
    assertFalse(restricted(summary));
    assertFalse(restricted(standalone));
  }

  @Test public void childDoesNotBypassItsParentsPrivacyOrAnimationState() throws Exception {
    summary.expanded = true;
    for (String name : new String[]{"keyguard", "publicContent", "headsUp", "guts", "launching", "appearing", "childrenAnimating"}) {
      Field state = Row.class.getField(name);
      state.setBoolean(summary, true);
      assertTrue(name, restricted(child));
      state.setBoolean(summary, false);
      assertFalse(name, restricted(child));
    }
    for (String name : new String[]{"keyguard", "publicContent", "headsUp", "hidesBackground"}) {
      Field state = Row.class.getField(name);
      state.setBoolean(child, true);
      assertTrue(name, restricted(child));
      state.setBoolean(child, false);
    }
    child.notificationParent = null;
    assertTrue(restricted(child));
    child.notificationParent = summary;
    set(cards, "notificationParent", null);
    assertTrue(restricted(child));
    assertFalse(restricted(standalone));
  }

  @Test public void initialDiscoveryIncludesRowsAlreadyNestedInsideAGroup() throws Exception {
    assertEquals(3, call(cards, "discover", new Class<?>[]{View.class}, stack));
    Set<?> attached = (Set<?>)get(cards, "attached");
    assertTrue(attached.contains(summary));
    assertTrue(attached.contains(child));
    assertTrue(attached.contains(standalone));
  }

  @Test public void expandedChildUsesTheSameRendererAndFillAsAStandaloneNotification() throws Exception {
    summary.expanded = true;
    assertFalse(restricted(child));
    for (int fill : new int[]{0xff392f36, 0xffeee3e9, 0x8a392f36}) {
      Bitmap grouped = draw(card(child, fill));
      Bitmap single = draw(card(standalone, fill));
      assertEquals(Color.alpha(fill), Color.alpha(grouped.getPixel(100, 40)));
      if (Color.alpha(fill) == 255) assertEquals(fill, grouped.getPixel(100, 40));
      assertEquals(single.getPixel(100, 40), grouped.getPixel(100, 40));
    }
    Object grouped = card(child, 0xff392f36);
    assertFalse((boolean)call(grouped, "measureStackPosition", new Class<?>[0]));
    assertTrue((boolean)call(card(standalone, 0xff392f36), "measureStackPosition", new Class<?>[0]));
  }

  @Test public void hiddenChildBackgroundsStayHiddenAndPartialAlphaIsPreserved() throws Exception {
    summary.expanded = true;
    Object grouped = card(child, Color.RED);
    child.bg.setVisibility(View.INVISIBLE);
    assertEquals(Color.TRANSPARENT, draw(grouped).getPixel(100, 40));
    child.bg.setVisibility(View.VISIBLE);
    child.bg.drawableAlpha = 0;
    assertEquals(Color.TRANSPARENT, draw(grouped).getPixel(100, 40));
    child.bg.drawableAlpha = 128;
    assertEquals(128, Color.alpha(draw(grouped).getPixel(100, 40)));
    child.bg.setAlpha(.5f);
    assertEquals(.5f, child.bg.getAlpha(), 0f);
    Bitmap half = draw(grouped);
    assertEquals(64, ((android.graphics.drawable.RippleDrawable)get(grouped, "ripple")).getAlpha());
    // GradientDrawable's fixed-point alpha modulation can rasterize 64 as 63.
    assertEquals(64f, Color.alpha(half.getPixel(100, 40)), 1f);
    child.bg.setAlpha(0f);
    assertEquals(Color.TRANSPARENT, draw(grouped).getPixel(100, 40));
  }

  @Test public void standaloneRowsKeepTheirFallbackWhenTheVendorOmitsItsBackground() throws Exception {
    Object single = card(standalone, Color.RED);
    standalone.bg.drawableAlpha = 0;
    assertEquals(Color.RED, draw(single).getPixel(100, 40));
    standalone.bg.setVisibility(View.INVISIBLE);
    assertEquals(Color.RED, draw(single).getPixel(100, 40));
  }

  private static void layoutRow(Row row) {
    row.layout(0, 0, 200, 80);
    row.bg.layout(0, 0, 200, 80);
    row.content.layout(0, 0, 200, 80);
  }

  private boolean restricted(Row row) throws Exception {
    return (boolean)call(cards, "restricted", new Class<?>[]{View.class}, row);
  }

  private Object card(Row row, int fill) throws Exception {
    Class<?> type = Class.forName(NotificationCards.class.getName() + "$Card");
    Constructor<?> constructor = type.getDeclaredConstructor(NotificationCards.class, View.class, View.class);
    constructor.setAccessible(true);
    Object card = constructor.newInstance(cards, row, row.bg);
    call(card, "update", new Class<?>[]{View.class, View.class, int.class}, null, null, fill);
    return card;
  }

  private Bitmap draw(Object card) throws Exception {
    Bitmap bitmap = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888);
    call(card, "drawInRow", new Class<?>[]{Canvas.class}, new Canvas(bitmap));
    return bitmap;
  }

  private void bindRowMethod(String field, String method) throws Exception {
    set(cards, field, Row.class.getMethod(method));
  }

  private void bindRowField(String field, String rowField) throws Exception {
    set(cards, field, Row.class.getField(rowField));
  }

  private static Object call(Object target, String name, Class<?>[] types, Object... arguments) throws Exception {
    Method method = target.getClass().getDeclaredMethod(name, types);
    method.setAccessible(true);
    return method.invoke(target, arguments);
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

  public static final class Row extends FrameLayout {
    public boolean keyguard, publicContent, headsUp, hidesBackground, dragging, appearing;
    public boolean child, summary, expanded, changing, guts, launching, childrenAnimating;
    public Row notificationParent;
    final Background bg;
    final FrameLayout content, children;
    Row(Context context) {
      super(context);
      bg = new Background(context);
      content = new FrameLayout(context);
      children = new FrameLayout(context);
      addView(bg); addView(content); addView(children);
    }
    public boolean isChildInGroup() { return child; }
    public boolean isSummaryWithChildren() { return summary; }
    public Row getNotificationParent() { return notificationParent; }
    public boolean areChildrenExpanded() { return expanded; }
    public boolean isGroupExpansionChanging() { return changing; }
    public boolean isChildrenExpandedAnimating() { return childrenAnimating; }
    public boolean isHeadsUpState() { return headsUp; }
    public boolean areGutsExposed() { return guts; }
    public boolean isExpandAnimationRunning() { return launching; }
    public FrameLayout getChildrenContainer() { return children; }
    public FrameLayout getPrivateLayout() { return content; }
    public int getActualHeight() { return getHeight(); }
    public int getClipTopAmount() { return 0; }
    public int getClipBottomAmount() { return 0; }
    public float getTopCornerRadius() { return 16f; }
    public float getBottomCornerRadius() { return 16f; }
  }

  public static final class Background extends View {
    public float[] radii = {16, 16, 16, 16, 16, 16, 16, 16};
    public int drawableAlpha = 255;
    Background(Context context) { super(context); }
    public int getActualWidth() { return getWidth(); }
    public int getActualHeight() { return getHeight(); }
    public boolean isAlignedToRight() { return false; }
  }
}
