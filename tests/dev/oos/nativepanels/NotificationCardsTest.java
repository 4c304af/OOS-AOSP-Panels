package dev.oos.nativepanels;

import static org.junit.Assert.*;
import org.junit.Test;

public class NotificationCardsTest {
  @Test public void summaryCornersEndAtTheVisibleGroupEdge() {
    // A row 80px tall can still have a 180px expanded OEM background.
    assertEquals(80, NotificationCards.summaryHeight(80, 0, 0));
    assertEquals(73, NotificationCards.summaryHeight(80, 3, 4));
    assertEquals(80, NotificationCards.summaryHeight(80, 0, -1));
    assertEquals(0, NotificationCards.summaryHeight(8, 3, 12));
  }

  @Test public void collapsedGroupsYieldTheirBackgroundDuringExpansion() {
    assertTrue(NotificationCards.ownsCollapsedGroup(false, false, false, false));
    assertFalse(NotificationCards.ownsCollapsedGroup(true, false, false, false));
    assertFalse(NotificationCards.ownsCollapsedGroup(false, true, false, false));
    assertFalse(NotificationCards.ownsCollapsedGroup(false, false, true, false));
    assertFalse(NotificationCards.ownsCollapsedGroup(false, false, false, true));
  }
  @Test public void joinsVerticallyAdjacentRowsAtTheirTranslatedPositions() {
    assertTrue(NotificationCards.sameSeam(183.5f, 185f, 8, 8, 192, 192, 8, 1));
    assertTrue(NotificationCards.sameSeam(183.5f, 183f, 8, 8, 192, 192, 8, 1));
    assertFalse(NotificationCards.sameSeam(183.5f, 206f, 8, 8, 192, 192, 8, 1));
    assertFalse(NotificationCards.sameSeam(183.5f, 120f, 8, 8, 192, 192, 8, 1));
  }
  @Test public void separatesRowsDuringSideSwipesOrWidthChanges() {
    assertFalse(NotificationCards.sameSeam(96, 98, 8, 32, 192, 192, 8, 1));
    assertFalse(NotificationCards.sameSeam(96, 98, 8, 8, 192, 144, 8, 1));
  }
  @Test public void retainsInnerCornersAndScalesAnimatedOuterCorners() {
    assertEquals(0f, NotificationCards.scaleRadius(0, 4, 16, 28), 0);
    assertEquals(4f, NotificationCards.scaleRadius(4, 4, 16, 28), 0);
    assertEquals(16f, NotificationCards.scaleRadius(10, 4, 16, 28), 0);
    assertEquals(28f, NotificationCards.scaleRadius(16, 4, 16, 28), 0);
  }
  @Test public void matchesEachInflatedContentSlotIndependently() {
    assertTrue(NotificationCards.matchesLayoutId(true, 11, 11, 12, 13));
    assertTrue(NotificationCards.matchesLayoutId(false, 13, 11, 12, 13));
    assertFalse(NotificationCards.matchesLayoutId(true, 13, 11, 12, 13));
    assertFalse(NotificationCards.matchesLayoutId(false, 99, 11, 12, 13));
    assertFalse(NotificationCards.matchesLayoutId(false, 0, 0, 0, 0));
  }
  @Test public void limitsInsetEditsToKnownTextTemplates() {
    assertTrue(NotificationCards.supportedTemplate(null));
    assertTrue(NotificationCards.supportedTemplate("android.app.Notification$BigTextStyle"));
    assertFalse(NotificationCards.supportedTemplate("android.app.Notification$MessagingStyle"));
    assertFalse(NotificationCards.supportedTemplate("android.app.Notification$MediaStyle"));
    assertFalse(NotificationCards.supportedTemplate("android.app.Notification$DecoratedCustomViewStyle"));
  }
  @Test public void recognizesOuterSurfacesSeparatelyFromTextInsets() {
    assertEquals(NotificationCards.Surface.STANDARD, NotificationCards.surface(null, null, false, false));
    for (String style : new String[]{"DecoratedCustomViewStyle", "MessagingStyle", "BigPictureStyle", "InboxStyle"})
      assertEquals(NotificationCards.Surface.OUTER_ONLY,
          NotificationCards.surface("android.app.Notification$" + style, null, false, false));
    assertEquals(NotificationCards.Surface.STANDARD, NotificationCards.surface(null, "transport", false, false));
    assertEquals(NotificationCards.Surface.MEDIA,
        NotificationCards.surface("android.app.Notification$MediaStyle", "transport", false, true));
    assertEquals(NotificationCards.Surface.MEDIA,
        NotificationCards.surface("android.app.Notification$DecoratedMediaCustomViewStyle", null, false, false));
    assertEquals(NotificationCards.Surface.CALL,
        NotificationCards.surface("android.app.Notification$CallStyle", null, false, true));
    assertEquals(NotificationCards.Surface.CALL, NotificationCards.surface(null, "call", false, false));
    assertEquals(NotificationCards.Surface.COLORIZED, NotificationCards.surface(null, null, false, true));
    assertTrue(NotificationCards.Surface.BUNDLE.usesShadeBackground());
    assertTrue(NotificationCards.surface(null, null, true, false).usesShadeBackground());
    assertFalse(NotificationCards.Surface.UNBOUND.usesShadeBackground());
  }
}
