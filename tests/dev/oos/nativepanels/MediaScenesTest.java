package dev.oos.nativepanels;

import static dev.oos.nativepanels.CombinedMedia.Scenes.*;
import static org.junit.Assert.*;
import org.junit.Test;

public class MediaScenesTest {
  @Test public void swipingTheCompactCardRemovesItsSlotEvenWithAStaleLegacyHost() {
    assertTrue(fromCards(true, true, false).visible(true));
    assertEquals(EXPANDED_ONLY, fromCards(true, true, true));
    assertFalse(fromCards(true, true, true).visible(true));
    assertTrue(fromCards(true, true, true).visible(false));
  }

  @Test public void resumableAndEmptyListsDoNotReserveCollapsedSpace() {
    assertEquals(EXPANDED_ONLY, fromCards(true, false, false));
    assertFalse(EXPANDED_ONLY.visible(true));
    assertTrue(EXPANDED_ONLY.visible(false));
    assertEquals(EMPTY, fromCards(false, false, false));
    assertFalse(EMPTY.visible(true));
    assertFalse(EMPTY.visible(false));
  }

  @Test public void freshPlaybackSharesBothScenes() {
    assertEquals(SHARED, fromCards(true, true, false));
    assertTrue(SHARED.visible(true));
    assertTrue(SHARED.visible(false));
  }
}
