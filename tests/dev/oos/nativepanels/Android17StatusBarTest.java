package dev.oos.nativepanels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.widget.ImageView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class Android17StatusBarTest {
  @Test public void networkTypeRequiresRegisteredCellularService() {
    assertTrue(Android17StatusBar.allowNetworkType(true, true, false, false));
    assertFalse(Android17StatusBar.allowNetworkType(false, true, false, false));
    assertFalse(Android17StatusBar.allowNetworkType(true, false, false, false));
    assertFalse(Android17StatusBar.allowNetworkType(true, true, true, false));
    assertFalse(Android17StatusBar.allowNetworkType(true, true, false, true));
  }

  @Test public void replacingArtUsesTheNativeRectangleInsteadOfTheOemSlot() {
    ImageView image = new ImageView(RuntimeEnvironment.getApplication());
    Glyph host = new Glyph(32, 32);
    image.setImageDrawable(host);
    image.measure(0, 0);
    assertEquals(32, image.getMeasuredWidth());
    assertEquals(32, image.getMeasuredHeight());

    Glyph art = new Glyph(34, 24);
    var replacement = StatusIconMetrics.signal(image, art, 17f / 12f);
    image.setImageDrawable(replacement);
    image.measure(0, 0);
    int height = StatusIconMetrics.sp(image, 12f);
    int width = Math.round(height * 17f / 12f);
    assertEquals(width, image.getMeasuredWidth());
    assertEquals(height, image.getMeasuredHeight());

    image.layout(0, 0, width, height);
    assertEquals(new Rect(0, 0, width, height), art.getBounds());
  }

  @Test public void largerVendorSlotDoesNotEnlargeOrAddWhitespaceToTheAndroidArtwork() {
    ImageView image = new ImageView(RuntimeEnvironment.getApplication());
    image.setLayoutParams(new android.widget.FrameLayout.LayoutParams(60, 36));
    image.setMinimumWidth(60);
    image.setPadding(3, 4, 5, 6);
    Glyph art = new Glyph(34, 24);
    var replacement = StatusIconMetrics.signal(image, art, 17f / 12f);
    image.setImageDrawable(replacement);
    image.measure(0, 0);
    int height = StatusIconMetrics.sp(image, 12f);
    int width = Math.round(height * 17f / 12f);
    assertEquals(width, image.getMeasuredWidth());
    assertEquals(height, image.getMeasuredHeight());
    assertEquals(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, image.getLayoutParams().width);
    assertEquals(height, image.getLayoutParams().height);
    assertEquals(0, image.getPaddingLeft());
    assertEquals(0, image.getPaddingRight());
  }

  private static final class Glyph extends ColorDrawable {
    private final int width;
    private final int height;

    Glyph(int width, int height) {
      this.width = width;
      this.height = height;
    }

    @Override public int getIntrinsicWidth() { return width; }
    @Override public int getIntrinsicHeight() { return height; }
  }
}
