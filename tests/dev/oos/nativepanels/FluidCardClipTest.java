package dev.oos.nativepanels;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.ViewOutlineProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FluidCardClipTest {
  private final FluidCardClip clip = new FluidCardClip();
  private final View content = new View(RuntimeEnvironment.getApplication());

  @Test public void backingAndForegroundShareAllFourCornersAndTheActualHeight() {
    content.layout(6, 8, 126, 112);
    Path path = clip.pathFor(content, 88);
    Bitmap output = Bitmap.createBitmap(132, 120, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(output);
    canvas.clipPath(path);
    canvas.drawColor(Color.BLUE);
    Paint paint = new Paint();
    paint.setColor(Color.RED);
    canvas.drawRoundRect(6, 8, 126, 88, 28, 28, paint);
    for (int x : new int[]{8, 123})
      for (int y : new int[]{10, 85}) assertEquals(Color.TRANSPARENT, output.getPixel(x, y));
    assertEquals(Color.RED, output.getPixel(66, 10));
    assertEquals(Color.RED, output.getPixel(8, 48));
    assertEquals(Color.TRANSPARENT, output.getPixel(66, 90));
  }

  @Test public void largerPluginOutlineAndUnmeasuredContentAreRespectedOnRebind() {
    assertNull(clip.pathFor(content, 100));
    content.layout(0, 0, 120, 100);
    content.setOutlineProvider(new ViewOutlineProvider() {
      @Override public void getOutline(View v, Outline out) {
        out.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 44);
      }
    });
    Bitmap output = Bitmap.createBitmap(120, 100, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(output);
    canvas.clipPath(clip.pathFor(content, 100));
    canvas.drawColor(Color.RED);
    assertEquals(Color.TRANSPARENT, output.getPixel(10, 10));
    assertEquals(Color.RED, output.getPixel(60, 3));
    assertNull(clip.pathFor(content, 0));
  }
}
