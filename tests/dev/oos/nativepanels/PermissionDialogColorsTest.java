package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PermissionDialogColorsTest {
  private static final int OLD_GRAY = 0xff3c4043;

  @Test public void lightDialogReadsTheCurrentSystemMonetSurface() {
    assertPalette(false, 0xffffe9ef, 0xffe4f2ed);
  }

  @Test public void darkDialogReadsTheCurrentSystemMonetSurface() {
    assertPalette(true, 0xff35272e, 0xff26332d);
  }

  @Test public void tintPreservesNativeInsetsCornersAndIndependentChildren() {
    Context context = RuntimeEnvironment.getApplication();
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(OLD_GRAY);
    shape.setCornerRadius(20);
    Drawable background = new InsetDrawable(shape, 6);
    Drawable sibling = background.getConstantState().newDrawable();
    LinearLayout dialog = new LinearLayout(context);
    dialog.setBackground(background);
    dialog.setPadding(7, 8, 9, 10);
    Button child = new Button(context);
    child.setTextColor(Color.CYAN);
    child.setBackgroundColor(Color.MAGENTA);
    AtomicInteger clicks = new AtomicInteger();
    child.setOnClickListener(v -> clicks.incrementAndGet());
    dialog.addView(child);
    Drawable childBackground = child.getBackground();

    assertNotNull(PermissionDialogColors.tint(dialog));

    assertSame(background, dialog.getBackground());
    assertTrue(dialog.getBackground() instanceof InsetDrawable);
    assertEquals(20f, ((GradientDrawable)((InsetDrawable)dialog.getBackground()).getDrawable())
        .getCornerRadius(), 0f);
    assertEquals(7, dialog.getPaddingLeft());
    assertEquals(8, dialog.getPaddingTop());
    assertEquals(9, dialog.getPaddingRight());
    assertEquals(10, dialog.getPaddingBottom());
    Bitmap tinted = render(background);
    assertEquals(0, Color.alpha(tinted.getPixel(3, 40)));
    assertEquals(0, Color.alpha(tinted.getPixel(7, 7)));
    assertEquals(context.getColor(android.R.color.system_surface_container_high_light),
        tinted.getPixel(50, 40));
    assertEquals(OLD_GRAY, render(sibling).getPixel(50, 40));
    assertSame(childBackground, child.getBackground());
    assertNull(child.getBackgroundTintList());
    assertEquals(Color.CYAN, child.getCurrentTextColor());
    child.performClick();
    assertEquals(1, clicks.get());
  }

  @Test public void absentDialogOrBackgroundLeavesTheViewUntouched() {
    Context context = RuntimeEnvironment.getApplication();
    View unrelated = new View(context);
    unrelated.setBackgroundColor(OLD_GRAY);
    Drawable original = unrelated.getBackground();
    assertNull(PermissionDialogColors.apply(unrelated));
    assertSame(original, unrelated.getBackground());
    assertNull(unrelated.getBackgroundTintList());
    assertNull(PermissionDialogColors.tint(null));
    assertNull(PermissionDialogColors.tint(new View(context)));
  }

  @Test @Config(sdk = 33) public void olderPlatformsKeepTheirExistingBackground() {
    View dialog = new View(RuntimeEnvironment.getApplication());
    dialog.setBackgroundColor(OLD_GRAY);
    Drawable original = dialog.getBackground();
    assertNull(PermissionDialogColors.tint(dialog));
    assertSame(original, dialog.getBackground());
    assertNull(dialog.getBackgroundTintList());
  }

  private void assertPalette(boolean dark, int first, int changed) {
    Context base = RuntimeEnvironment.getApplication();
    Resources original = base.getResources();
    Configuration config = new Configuration(original.getConfiguration());
    config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
        | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
    int expectedToken = dark ? android.R.color.system_surface_container_high_dark
        : android.R.color.system_surface_container_high_light;
    AtomicInteger palette = new AtomicInteger(first);
    Resources resources = new Resources(original.getAssets(), original.getDisplayMetrics(), config) {
      @Override public int getColor(int id, Theme theme) {
        if (id == android.R.color.system_surface_container_high_dark
            || id == android.R.color.system_surface_container_high_light) {
          assertEquals("Read the surface role for the current day/night mode", expectedToken, id);
          return palette.get();
        }
        return super.getColor(id, theme);
      }
    };
    Context context = new ContextWrapper(base) {
      @Override public Resources getResources() { return resources; }
    };
    View dialog = new View(context);
    dialog.setBackgroundColor(OLD_GRAY);
    assertEquals(dark ? "system_surface_container_high_dark" : "system_surface_container_high_light",
        PermissionDialogColors.tint(dialog));
    assertEquals(first, dialog.getBackgroundTintList().getDefaultColor());
    palette.set(changed);
    PermissionDialogColors.tint(dialog);
    assertEquals(changed, dialog.getBackgroundTintList().getDefaultColor());
  }

  private Bitmap render(Drawable drawable) {
    Bitmap image = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888);
    drawable.setBounds(0, 0, image.getWidth(), image.getHeight());
    drawable.draw(new Canvas(image));
    return image;
  }
}
