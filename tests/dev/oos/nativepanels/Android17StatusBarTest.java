package dev.oos.nativepanels;

import static org.junit.Assert.assertEquals;

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
  @Test public void replacingArtKeepsTheWrapContentSlotAndCentersTheGlyph() {
    ImageView image = new ImageView(RuntimeEnvironment.getApplication());
    Glyph host = new Glyph(32, 32);
    image.setImageDrawable(host);
    image.measure(0, 0);
    assertEquals(32, image.getMeasuredWidth());
    assertEquals(32, image.getMeasuredHeight());

    Glyph art = new Glyph(34, 24);
    var replacement = new Android17StatusBar.SlotDrawable(art, host);
    image.setImageDrawable(replacement);
    image.measure(0, 0);
    assertEquals(32, image.getMeasuredWidth());
    assertEquals(32, image.getMeasuredHeight());

    image.layout(0, 0, 32, 32);
    assertEquals(new Rect(0, 4, 32, 27), art.getBounds());
  }

  @Test public void largerVendorSlotDoesNotEnlargeTheAndroidArtwork() {
    Glyph host = new Glyph(60, 36);
    Glyph art = new Glyph(34, 24);
    var replacement = new Android17StatusBar.SlotDrawable(art, host);
    replacement.setBounds(0, 0, 60, 36);
    assertEquals(60, replacement.getIntrinsicWidth());
    assertEquals(36, replacement.getIntrinsicHeight());
    assertEquals(new Rect(13, 6, 47, 30), art.getBounds());
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
