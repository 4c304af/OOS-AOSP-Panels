package dev.oos.nativepanels;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    set(cards, "attachedGroupChildren", Children.class.getMethod("getAttachedChildren"));
    bindRowMethod("privateLayout", "getPrivateLayout");
    bindRowMethod("actualHeight", "getActualHeight");
    bindRowMethod("userLocked", "isUserLocked");
    bindRowMethod("rowViewState", "getViewState");
    set(cards, "stateTranslationY", StackState.class.getMethod("getYTranslation"));
    set(cards, "stateHeight", StackState.class.getField("height"));
    set(cards, "animatingY", StackState.class.getMethod("isAnimatingY", View.class));
    set(cards, "animatingHeight", StackState.class.getMethod("isAnimatingHeight", View.class, Object.class));
    set(cards, "heightProperty", new Object());
    bindRowMethod("clipTop", "getClipTopAmount");
    bindRowMethod("clipBottom", "getClipBottomAmount");
    bindRowMethod("topRadius", "getTopCornerRadius");
    bindRowMethod("bottomRadius", "getBottomCornerRadius");
    bindRowField("onKeyguard", "keyguard");
    bindRowField("showingPublic", "publicContent");
    bindRowField("groupHidesBackground", "hidesBackground");
    bindRowField("userExpanding", "dragging");
    bindRowField("appearing", "appearing");
    bindRowField("inShelf", "shelf");
    bindRowField("removing", "removing");
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

  @Test public void joinsExpandedGroupChildrenAcrossDecorationsWithoutJoiningOutsideTheGroup() throws Exception {
    summary.expanded = true;
    child.setTranslationY(12);
    Row second = addChild(94), third = addChild(176);
    // Real containers interleave divider/header views with the logical notification rows.
    summary.children.addView(new View(stack.getContext()), 1);
    summary.children.addView(new View(stack.getContext()), 3);
    summary.children.logicalOrder = List.of(child, second, third);
    Object firstCard = remember(child), secondCard = remember(second), thirdCard = remember(third);
    Object outsideCard = remember(standalone);
    join();

    assertEquals(2, get(firstCard, "joined"));
    assertEquals(3, get(secondCard, "joined"));
    assertEquals(1, get(thirdCard, "joined"));
    assertEquals(0, get(outsideCard, "joined"));
    draw(firstCard); draw(secondCard); draw(thirdCard);
    float inner = ((Number)get(firstCard, "innerRadius")).floatValue();
    float[] first = (float[])get(firstCard, "radii"), middle = (float[])get(secondCard, "radii");
    float[] last = (float[])get(thirdCard, "radii");
    assertTrue(first[0] > inner);
    assertEquals(inner, first[4], 0f);
    assertEquals(inner, middle[0], 0f);
    assertEquals(inner, middle[4], 0f);
    assertEquals(inner, last[0], 0f);
    assertTrue(last[4] > inner);
    // Joining the replacement never rewrites the vendor's radius animation state.
    assertArrayEquals(new float[]{16, 16, 16, 16, 16, 16, 16, 16}, child.bg.radii, 0f);
  }

  @Test public void logicalOrderHandlesReorderingAndFallbackUsesCurrentPositions() throws Exception {
    summary.expanded = true;
    child.setTranslationY(164);
    Row second = addChild(0), third = addChild(82);
    summary.children.logicalOrder = List.of(second, third, child);
    Object firstCard = remember(second), middleCard = remember(third), lastCard = remember(child);
    join();
    assertEquals(2, get(firstCard, "joined"));
    assertEquals(3, get(middleCard, "joined"));
    assertEquals(1, get(lastCard, "joined"));
    set(cards, "attachedGroupChildren", null);
    join();
    assertEquals(2, get(firstCard, "joined"));
    assertEquals(3, get(middleCard, "joined"));
    assertEquals(1, get(lastCard, "joined"));
  }

  @Test public void groupJoinsBreakAtUnsupportedRowsAndRealGaps() throws Exception {
    summary.expanded = true;
    Row unsupported = addChild(82), last = addChild(164);
    Object firstCard = remember(child), lastCard = remember(last);
    join();
    assertEquals(0, get(firstCard, "joined"));
    assertEquals(0, get(lastCard, "joined"));

    Object middleCard = remember(unsupported);
    last.setTranslationY(190);
    join();
    assertEquals(2, get(firstCard, "joined"));
    assertEquals(1, get(middleCard, "joined"));
    assertEquals(0, get(lastCard, "joined"));
  }

  @Test public void groupJoinsFollowVisibilityAndSwipesWithoutSplittingForVerticalExpansion() throws Exception {
    summary.expanded = true;
    Row second = addChild(82), third = addChild(164);
    Object firstCard = remember(child), secondCard = remember(second), thirdCard = remember(third);
    join();
    assertEquals(3, get(secondCard, "joined"));
    third.setVisibility(View.GONE);
    join();
    assertEquals(1, get(secondCard, "joined"));
    assertEquals(0, get(thirdCard, "joined"));
    second.setTranslationX(30);
    join();
    assertEquals(0, get(firstCard, "joined"));
    assertEquals(0, get(secondCard, "joined"));
    second.setTranslationX(0);
    second.dragging = true;
    join();
    assertEquals(2, get(firstCard, "joined"));
    assertEquals(1, get(secondCard, "joined"));
    second.dragging = false;
    summary.expanded = false;
    join();
    assertEquals(0, get(firstCard, "joined"));
    assertEquals(0, get(secondCard, "joined"));
  }

  @Test public void groupJoinsYieldToClippingAndPrivacyButSurviveGroupAnimationState() throws Exception {
    summary.expanded = true;
    Row second = addChild(82);
    Object firstCard = remember(child), secondCard = remember(second);
    child.clipBottomAmount = 4;
    join();
    assertEquals(0, get(firstCard, "joined"));
    assertEquals(0, get(secondCard, "joined"));
    child.clipBottomAmount = 0;
    for (String name : new String[]{"keyguard", "publicContent", "headsUp", "guts", "launching", "appearing"}) {
      Row.class.getField(name).setBoolean(summary, true);
      join();
      assertEquals(name, 0, get(firstCard, "joined"));
      assertEquals(name, 0, get(secondCard, "joined"));
      Row.class.getField(name).setBoolean(summary, false);
    }
    join();
    assertEquals(2, get(firstCard, "joined"));
    assertEquals(1, get(secondCard, "joined"));
    for (String name : new String[]{"changing", "dragging", "childrenAnimating", "locked"}) {
      Row.class.getField(name).setBoolean(summary, true);
      join();
      assertEquals(name, 2, get(firstCard, "joined"));
      assertEquals(name, 1, get(secondCard, "joined"));
      Row.class.getField(name).setBoolean(summary, false);
    }
  }

  @Test public void fallbackOnlySummaryCannotJoinTheNextNotificationAcrossAnExpandedGroup() throws Exception {
    summary.expanded = true;
    summary.hidesBackground = true;
    standalone.setTranslationY(summary.getHeight() + 2);
    Object summaryCard = remember(summary), outsideCard = remember(standalone);
    join();
    assertEquals(0, get(summaryCard, "joined"));
    assertEquals(0, get(outsideCard, "joined"));
    summary.expanded = false;
    summary.hidesBackground = false;
    summary.changing = true;
    join();
    assertEquals(0, get(summaryCard, "joined"));
    assertEquals(0, get(outsideCard, "joined"));
  }

  @Test public void vendorAnimationFlagKeepsSummaryAndChildrenOnTheNativeAnimationRenderer() throws Exception {
    // This ROM exposes the animation only through mRowEx, not a method on the row.
    set(cards, "childrenAnimating", null);
    set(cards, "rowExtension", Row.class.getField("extension"));
    assertThrows(NoSuchMethodException.class,
        () -> Row.class.getField("extension").getType().getMethod("isChildrenExpandedAnimating"));
    ((RowExtension)summary.extension).animating = true;
    assertTrue(restricted(summary));
    assertTrue((boolean)call(cards, "canTintBackground", new Class<?>[]{View.class}, summary));
    summary.expanded = true;
    Row second = addChild(82);
    Object firstCard = remember(child), secondCard = remember(second);
    assertTrue(restricted(child));
    assertTrue((boolean)call(cards, "canTintBackground", new Class<?>[]{View.class}, child));
    join();
    assertEquals(2, get(firstCard, "joined"));
    assertEquals(1, get(secondCard, "joined"));
    ((RowExtension)summary.extension).animating = false;
    assertFalse(restricted(child));
    join();
    assertEquals(2, get(firstCard, "joined"));
    assertEquals(1, get(secondCard, "joined"));
    summary.expanded = false;
    assertFalse(restricted(summary));

    set(cards, "childrenAnimating", Row.class.getMethod("isChildrenExpandedAnimating"));
    summary.childrenAnimating = true;
    assertTrue(restricted(summary));
    summary.childrenAnimating = false;

    summary.extension = null;
    assertFalse(restricted(summary));
    set(cards, "rowExtension", null);
    set(cards, "extensionChildrenAnimating", null);
    assertFalse(restricted(summary));
  }

  @Test public void movingNeighborsKeepTheirSeamFromNativeDestinationYAndHeight() throws Exception {
    Row lower = addStandalone(25);
    standalone.layout(0, 12, 200, 92);
    standalone.setTranslationY(200);
    lower.setTranslationY(360);
    standalone.animatingY = lower.animatingY = true;
    standalone.state.y = 400;
    standalone.animatingHeight = true;
    standalone.state.height = 120;
    lower.state.y = 509;
    Object upperCard = remember(standalone), lowerCard = remember(lower);
    join();
    assertEquals(412f, (float)get(upperCard, "stackTop"), 0f);
    assertEquals(532f, (float)get(upperCard, "stackBottom"), 0f);
    assertEquals(534f, (float)get(lowerCard, "stackTop"), 0f);
    assertEquals(2, get(upperCard, "joined"));
    assertEquals(1, get(lowerCard, "joined"));
    // Different springs can temporarily make a wide gap or even overlap.
    for (float y : new float[]{440, 290, 600}) {
      lower.setTranslationY(y);
      join();
      assertEquals(2, get(upperCard, "joined"));
      assertEquals(1, get(lowerCard, "joined"));
    }
    standalone.setTranslationY(400);
    standalone.layout(0, 12, 200, 132);
    lower.setTranslationY(509);
    standalone.animatingY = standalone.animatingHeight = lower.animatingY = false;
    join();
    assertEquals(2, get(upperCard, "joined"));
    assertEquals(1, get(lowerCard, "joined"));
  }

  @Test public void destinationGeometryStillRejectsRealGapsCrossingsAndSectionHeaders() throws Exception {
    Row lower = addStandalone(0);
    lower.setTranslationY(82);
    standalone.animatingY = lower.animatingY = true;
    standalone.state.y = 400;
    lower.state.y = 520;
    Object upperCard = remember(standalone), lowerCard = remember(lower);
    join();
    assertEquals(0, get(upperCard, "joined"));
    assertEquals(0, get(lowerCard, "joined"));
    lower.state.y = 300;
    join();
    assertEquals(0, get(upperCard, "joined"));
    lower.state.y = 482;
    join();
    assertEquals(2, get(upperCard, "joined"));
    View section = new View(stack.getContext());
    stack.addView(section, stack.indexOfChild(lower));
    section.layout(0, 0, 200, 20);
    join();
    assertEquals(0, get(upperCard, "joined"));
    assertEquals(0, get(lowerCard, "joined"));
  }

  @Test public void staleOrUnavailableMotionStateFallsBackToCurrentGeometry() throws Exception {
    Row lower = addStandalone(0);
    lower.setTranslationY(82);
    standalone.state.y = 400;
    standalone.state.height = 140;
    lower.state.y = 700;
    Object upperCard = remember(standalone), lowerCard = remember(lower);
    join();
    assertEquals(2, get(upperCard, "joined"));
    standalone.animatingY = lower.animatingY = true;
    standalone.state.y = 200;
    lower.state.y = 282;
    join();
    // An inactive height animation must not reuse its stale target height.
    assertEquals(280f, (float)get(upperCard, "stackBottom"), 0f);
    assertEquals(2, get(upperCard, "joined"));
    set(cards, "rowViewState", null);
    lower.state.y = 700;
    join();
    assertEquals(2, get(upperCard, "joined"));
    assertEquals(1, get(lowerCard, "joined"));
  }

  @Test public void animatedDestinationDoesNotBypassSwipesClippingOrRemoval() throws Exception {
    Row lower = addStandalone(0);
    lower.setTranslationY(200);
    standalone.animatingY = lower.animatingY = true;
    standalone.state.y = 300;
    lower.state.y = 382;
    Object upperCard = remember(standalone), lowerCard = remember(lower);
    lower.setTranslationX(30);
    join();
    assertEquals(0, get(upperCard, "joined"));
    lower.setTranslationX(0);
    standalone.clipBottomAmount = 1;
    join();
    assertEquals(0, get(lowerCard, "joined"));
    standalone.clipBottomAmount = 0;
    for (String name : new String[]{"shelf", "removing", "guts", "launching", "appearing"}) {
      Row.class.getField(name).setBoolean(standalone, true);
      join();
      assertEquals(name, 0, get(lowerCard, "joined"));
      Row.class.getField(name).setBoolean(standalone, false);
    }
    join();
    assertEquals(2, get(upperCard, "joined"));
    assertEquals(1, get(lowerCard, "joined"));
  }

  @Test public void groupRendererHandoffUsesVisibleOwnedOverlayWithoutChangingPaintingRules() throws Exception {
    summary.expanded = true;
    summary.childrenAnimating = true;
    Row second = addChild(82);
    Object firstCard = remember(child), secondCard = remember(second);
    child.hidesBackground = true;
    child.bg.setVisibility(View.INVISIBLE);
    join();
    assertEquals(0, get(firstCard, "joined"));
    View overlay = new View(stack.getContext());
    overlay.setBackground(new ColorDrawable(Color.RED));
    stack.addView(overlay);
    overlay.layout(0, 0, 200, 80);
    set(cards, "shadeRoot", stack.getRootView());
    NotificationOverlayRegistry registry = (NotificationOverlayRegistry)get(cards, "overlayBackgrounds");
    registry.register(overlay.getBackground(), overlay, child);
    join();
    assertEquals(2, get(firstCard, "joined"));
    assertEquals(1, get(secondCard, "joined"));
    assertTrue(restricted(child));
    assertEquals(Color.TRANSPARENT, draw(firstCard).getPixel(100, 40));
    overlay.setAlpha(0f);
    join();
    assertEquals(0, get(firstCard, "joined"));
    assertEquals(0, get(secondCard, "joined"));
  }

  @Test public void aFullyClippedCurrentRowCannotJoinUsingItsLargerDestinationHeight() throws Exception {
    Row lower = addStandalone(0);
    standalone.animatingY = lower.animatingY = true;
    standalone.animatingHeight = lower.animatingHeight = true;
    standalone.state.y = 300;
    lower.state.y = 422;
    standalone.state.height = lower.state.height = 120;
    Object upperCard = remember(standalone), lowerCard = remember(lower);
    standalone.clipTopAmount = 80;
    join();
    assertEquals(0, get(upperCard, "joined"));
    assertEquals(0, get(lowerCard, "joined"));
    standalone.clipTopAmount = 0;
    lower.clipBottomAmount = 80;
    join();
    assertEquals(0, get(upperCard, "joined"));
    assertEquals(0, get(lowerCard, "joined"));
    lower.clipBottomAmount = 0;
    join();
    assertEquals(2, get(upperCard, "joined"));
    assertEquals(1, get(lowerCard, "joined"));
  }

  private Row addStandalone(int top) {
    Row row = new Row(stack.getContext());
    stack.addView(row);
    layoutRow(row);
    row.layout(0, top, 200, top + 80);
    return row;
  }

  private Row addChild(float y) {
    Row row = new Row(stack.getContext());
    row.child = true;
    row.notificationParent = summary;
    summary.children.addView(row);
    layoutRow(row);
    row.setTranslationY(y);
    return row;
  }

  private Object remember(Row row) throws Exception {
    Object card = card(row, Color.RED);
    ((Map)get(cards, "rows")).put(row, card);
    return card;
  }

  private void join() throws Exception { call(cards, "updateJoinedCorners", new Class<?>[0]); }

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
    public boolean locked, shelf, removing, animatingY, animatingHeight;
    public final StackState state = new StackState();
    public Row notificationParent;
    public ExtensionMarker extension = new RowExtension();
    public int clipTopAmount, clipBottomAmount;
    final Background bg;
    final FrameLayout content;
    final Children children;
    Row(Context context) {
      super(context);
      bg = new Background(context);
      content = new FrameLayout(context);
      children = new Children(context);
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
    public boolean isUserLocked() { return locked; }
    public StackState getViewState() { return state; }
    public Children getChildrenContainer() { return children; }
    public FrameLayout getPrivateLayout() { return content; }
    public int getActualHeight() { return getHeight(); }
    public int getClipTopAmount() { return clipTopAmount; }
    public int getClipBottomAmount() { return clipBottomAmount; }
    public float getTopCornerRadius() { return 16f; }
    public float getBottomCornerRadius() { return 16f; }
  }

  public static final class StackState {
    public int height = 80;
    public float y;
    public float getYTranslation() { return y; }
    public static boolean isAnimatingY(View view) { return ((Row)view).animatingY; }
    public static boolean isAnimatingHeight(View view, Object property) { return ((Row)view).animatingHeight; }
  }

  public static final class Children extends FrameLayout {
    List<Row> logicalOrder;
    Children(Context context) { super(context); }
    public List<Row> getAttachedChildren() {
      if (logicalOrder != null) return logicalOrder;
      List<Row> rows = new ArrayList<>();
      for (int i = 0; i < getChildCount(); i++)
        if (getChildAt(i) instanceof Row row) rows.add(row);
      return rows;
    }
  }

  public interface ExtensionMarker {}

  public static final class RowExtension implements ExtensionMarker {
    public boolean animating;
    public boolean isChildrenExpandedAnimating() { return animating; }
  }

  public static final class Background extends View {
    public float[] radii = {16, 16, 16, 16, 16, 16, 16, 16};
    public int drawableAlpha = 255;
    public int radiusSetCalls;
    public boolean mBottomIsRounded = true;
    Background(Context context) { super(context); }
    public void setRadius(float top, float bottom, boolean animate) {
      radiusSetCalls++;
      for (int i = 0; i < radii.length; i++) radii[i] = i < 4 ? top : bottom;
      mBottomIsRounded = bottom != 0f;
    }
    public int getActualWidth() { return getWidth(); }
    public int getActualHeight() { return getHeight(); }
    public boolean isAlignedToRight() { return false; }
  }
}
