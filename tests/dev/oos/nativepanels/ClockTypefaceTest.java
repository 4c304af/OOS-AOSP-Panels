package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.graphics.Typeface;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ClockTypefaceTest {
  @Test public void batteryTypefaceReplacesTheOemFamilyWithoutChangingSizePaddingOrAction() {
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
    assertEquals(400, clock.getTypeface().getWeight());
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
    assertEquals(400, clock.getTypeface().getWeight());
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
    assertEquals(400, clock.getTypeface().getWeight());
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

  @Test public void everyClockDigitMatchesTheLighterBatteryOutline() {
    TextView clock = new TextView(RuntimeEnvironment.getApplication());
    ClockTypeface.apply(clock);
    android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    paint.setTypeface(clock.getTypeface());
    paint.setTextSize(1300f);
    paint.setHinting(android.graphics.Paint.HINTING_OFF);
    paint.setSubpixelText(true);
    StringBuilder mismatch = new StringBuilder();
    for (int digit = 0; digit < 10; digit++) {
      android.graphics.Path actual = new android.graphics.Path();
      paint.getTextPath(Integer.toString(digit), 0, 1, 0f, 0f, actual);
      android.graphics.Path original = androidx.core.graphics.PathParser.createPathFromPathData(
          BatteryDigitGlyphsTest.PATHS[digit]);
      android.graphics.Path expected = new android.graphics.Path();
      assertTrue(expected.op(original, original, android.graphics.Path.Op.UNION));
      android.graphics.Paint border = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
      border.setStyle(android.graphics.Paint.Style.STROKE);
      border.setStrokeWidth(.6f);
      border.setStrokeCap(android.graphics.Paint.Cap.ROUND);
      border.setStrokeJoin(android.graphics.Paint.Join.ROUND);
      android.graphics.Path edge = new android.graphics.Path();
      assertTrue(border.getFillPath(expected, edge));
      assertTrue(expected.op(edge, android.graphics.Path.Op.DIFFERENCE));
      int[] a = normalized(actual), b = normalized(expected);
      int intersection = 0, union = 0, outsideTolerance = 0;
      for (int i = 0; i < a.length; i++) {
        boolean ai = android.graphics.Color.alpha(a[i]) > 127;
        boolean bi = android.graphics.Color.alpha(b[i]) > 127;
        if (ai || bi) union++;
        if (ai && bi) intersection++;
        if (ai && !neighborInk(b, i)) outsideTolerance++;
        if (bi && !neighborInk(a, i)) outsideTolerance++;
      }
      float match = union == 0 ? 0 : intersection / (float)union;
      // Skia versions and CFF conversion can move an antialiased edge within
      // one pixel at 180px cap height (under 0.2px in the status bar).
      if (match < .97f || outsideTolerance > union * .003f)
        mismatch.append(digit).append('=').append(match).append('/').append(outsideTolerance).append(' ');
      assertTrue("Clock digit " + digit + " must be visibly lighter than the source battery digit",
          ink(a) < ink(normalized(original)) * .85f);
    }
    assertEquals("Clock contours must match the battery: " + mismatch, "", mismatch.toString());
  }

  private static int ink(int[] pixels) {
    int sum = 0;
    for (int pixel : pixels) if (android.graphics.Color.alpha(pixel) > 127) sum++;
    return sum;
  }

  private static boolean neighborInk(int[] pixels, int index) {
    int x = index % 220, y = index / 220;
    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
      int xx = x + dx, yy = y + dy;
      if (xx >= 0 && xx < 220 && yy >= 0 && yy < 220
          && android.graphics.Color.alpha(pixels[yy * 220 + xx]) > 127) return true;
    }
    return false;
  }

  @Test public void clockKeepsLiteralLocalizedTextAndHasAMatchingColon() {
    TextView clock = new TextView(RuntimeEnvironment.getApplication());
    clock.setText("上午 ١٢:٣٤");
    ClockTypeface.applyQs(clock);
    assertEquals("上午 ١٢:٣٤", clock.getText().toString());
    android.graphics.Paint paint = clock.getPaint();
    paint.setSubpixelText(true);
    assertTrue(paint.hasGlyph(":"));
    assertTrue(paint.hasGlyph("0"));
    assertTrue(paint.hasGlyph("١"));
    android.graphics.Path latin = new android.graphics.Path(), arabic = new android.graphics.Path();
    paint.setTextSize(1300f);
    paint.getTextPath("1", 0, 1, 0f, 0f, latin);
    paint.getTextPath("١", 0, 1, 0f, 0f, arabic);
    assertArrayEquals(normalized(latin), normalized(arabic));
    assertTrue(ClockTypeface.report().contains("BatteryGlyph"));
  }

  private static int[] normalized(android.graphics.Path path) {
    // computeBounds includes off-curve control points, which differ between
    // Skia conics and CFF cubics. Compare their visible curves at tight bounds.
    float[] samples = path.approximate(.0001f);
    android.graphics.RectF bounds = new android.graphics.RectF(Float.POSITIVE_INFINITY,
        Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);
    for (int i = 0; i < samples.length; i += 3) {
      bounds.left = Math.min(bounds.left, samples[i + 1]);
      bounds.top = Math.min(bounds.top, samples[i + 2]);
      bounds.right = Math.max(bounds.right, samples[i + 1]);
      bounds.bottom = Math.max(bounds.bottom, samples[i + 2]);
    }
    assertFalse(bounds.isEmpty());
    float scale = 180f / bounds.height();
    android.graphics.Matrix matrix = new android.graphics.Matrix();
    matrix.setValues(new float[]{scale, 0, 20 - bounds.left * scale,
        0, scale, 20 - bounds.top * scale, 0, 0, 1});
    android.graphics.Path fitted = new android.graphics.Path();
    path.transform(matrix, fitted);
    android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(220, 220,
        android.graphics.Bitmap.Config.ARGB_8888);
    android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
    android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    paint.setColor(android.graphics.Color.WHITE);
    canvas.drawPath(fitted, paint);
    int[] pixels = new int[220 * 220];
    bitmap.getPixels(pixels, 0, 220, 0, 0, 220, 220);
    bitmap.recycle();
    return pixels;
  }
}
