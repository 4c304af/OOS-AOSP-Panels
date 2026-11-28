package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.graphics.Typeface;
import android.widget.TextView;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ClockTypefaceTest {
  @Test public void aospSemiboldReplacesTheOemFamilyWithoutChangingSizePaddingOrAction() {
    TextView clock = new TextView(RuntimeEnvironment.getApplication());
    Typeface nativeFace = Typeface.create("serif", Typeface.ITALIC);
    clock.setTypeface(nativeFace);
    clock.setText("12:34");
    clock.setTextSize(23);
    clock.setPadding(3, 5, 7, 11);
    var params = new android.widget.FrameLayout.LayoutParams(90, 40);
    clock.setLayoutParams(params);
    int[] clicks = {0};
    clock.setOnClickListener(view -> clicks[0]++);
    float size = clock.getTextSize();

    assertTrue(ClockTypeface.apply(clock));
    assertNotEquals(Typeface.create(nativeFace, 600, false), clock.getTypeface());
    assertEquals(600, clock.getTypeface().getWeight());
    assertFalse(clock.getTypeface().isItalic());
    assertEquals(size, clock.getTextSize(), 0f);
    assertEquals(3, clock.getPaddingLeft());
    assertEquals(5, clock.getPaddingTop());
    assertEquals(7, clock.getPaddingRight());
    assertEquals(11, clock.getPaddingBottom());
    assertSame(params, clock.getLayoutParams());
    assertTrue(clock.performClick());
    assertEquals(1, clicks[0]);
    assertFalse(ClockTypeface.apply(clock));
  }

  @Test public void anOemBoldFaceIsReplacedByTheSameAospFaceAsARegularClock() {
    TextView clock = new TextView(RuntimeEnvironment.getApplication());
    Typeface nativeFace = Typeface.create(Typeface.create("serif", Typeface.NORMAL), 700, false);
    clock.setTypeface(nativeFace);
    TextView regular = new TextView(clock.getContext());
    assertTrue(ClockTypeface.apply(clock));
    ClockTypeface.apply(regular);
    assertEquals(regular.getTypeface(), clock.getTypeface());
    assertNotEquals(nativeFace, clock.getTypeface());
    assertEquals(600, clock.getTypeface().getWeight());
    assertFalse(ClockTypeface.apply(clock));
  }

  @Test public void qsClockUsesAospTrackingWithoutChangingTextSizeOrLayout() {
    TextView clock = new TextView(RuntimeEnvironment.getApplication());
    clock.setTypeface(Typeface.create("serif", Typeface.ITALIC));
    clock.setText("12:34");
    clock.setTextSize(14f * 2.57f);
    clock.setLetterSpacing(.12f);
    var params = new android.widget.FrameLayout.LayoutParams(200, 48);
    clock.setLayoutParams(params);
    float textSize = clock.getTextSize();

    assertTrue(ClockTypeface.applyQs(clock));
    assertEquals(600, clock.getTypeface().getWeight());
    assertFalse(clock.getTypeface().isItalic());
    assertEquals(.01f, clock.getLetterSpacing(), 0f);
    assertEquals(textSize, clock.getTextSize(), 0f);
    assertSame(params, clock.getLayoutParams());
    assertEquals("12:34", clock.getText().toString());
    assertFalse(ClockTypeface.applyQs(clock));
  }

  @Test public void statusClockKeepsItsNativeTracking() {
    TextView clock = new TextView(RuntimeEnvironment.getApplication());
    clock.setLetterSpacing(.03f);
    ClockTypeface.apply(clock);
    assertEquals(.03f, clock.getLetterSpacing(), 0f);
  }

  @Test public void directFileLoadingProducesSemiboldAndMissingFilesPermitFallback() throws Exception {
    var file = Files.createTempFile(RuntimeEnvironment.getApplication().getCacheDir().toPath(),
        "aosp-clock-", ".ttf");
    try {
      // Load a real font shipped by Robolectric's native renderer instead of
      // relying on the host computer to have Android's /system/fonts tree.
      try (var font = getClass().getClassLoader().getResourceAsStream("fonts/Roboto-Regular.ttf")) {
        assertNotNull(font);
        Files.copy(font, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      Typeface loaded = ClockTypeface.fromFile(file.toString(), false);
      assertNotNull(loaded);
      assertEquals(600, loaded.getWeight());
      assertFalse(loaded.isItalic());
      android.graphics.Paint paint = new android.graphics.Paint();
      paint.setTypeface(loaded);
      paint.setTextSize(48f);
      assertTrue(paint.measureText("12:34") > 0f);
    } finally {
      // Native Typeface keeps this font memory-mapped for its lifetime on Windows.
      file.toFile().deleteOnExit();
    }
    assertNull(ClockTypeface.fromFile(file.resolveSibling(file.getFileName() + ".missing").toString(), false));
  }
}
