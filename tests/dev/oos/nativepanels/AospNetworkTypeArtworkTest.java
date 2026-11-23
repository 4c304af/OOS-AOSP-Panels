package dev.oos.nativepanels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.text.Spanned;
import android.util.TypedValue;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AospNetworkTypeArtworkTest {
  @Test public void realLabelsUseTheirOwnArtworkAndUnknownCarrierBrandsRemainNative() {
    assertEquals(R.drawable.aosp17_5g_mobiledata, AospNetworkTypeArtwork.resourceFor("5G"));
    assertEquals(R.drawable.aosp17_5g_plus_mobiledata, AospNetworkTypeArtwork.resourceFor("5G+"));
    assertEquals(R.drawable.aosp17_lte_mobiledata, AospNetworkTypeArtwork.resourceFor("LTE"));
    assertEquals(R.drawable.aosp17_lte_plus_mobiledata, AospNetworkTypeArtwork.resourceFor("LTE+"));
    assertEquals(R.drawable.aosp17_4g_lte_mobiledata, AospNetworkTypeArtwork.resourceFor("4G LTE"));
    assertEquals(R.drawable.aosp17_4g_mobiledata, AospNetworkTypeArtwork.resourceFor("4G"));
    assertEquals(0, AospNetworkTypeArtwork.resourceFor("5G UW"));
    assertEquals(0, AospNetworkTypeArtwork.resourceFor(""));
  }

  @Test public void changingTypeRetainsTheRealSpellingAndRestoresUnknownCarrierStyle() {
    Context context = RuntimeEnvironment.getApplication();
    TextView view = new TextView(context);
    view.setIncludeFontPadding(true);
    view.setTextSize(TypedValue.COMPLEX_UNIT_PX, 27f);
    view.setContentDescription("Actual mobile data network");
    AospNetworkTypeArtwork artwork = new AospNetworkTypeArtwork();
    for (String label : new String[]{"5G", "LTE", "LTE+"}) {
      view.setText(label);
      artwork.apply(view, context.getResources());
      assertEquals(label, view.getText().toString());
      assertEquals("Actual mobile data network", view.getContentDescription());
      assertFalse(view.getIncludeFontPadding());
      AospNetworkTypeArtwork.ArtworkSpan[] spans = ((Spanned) view.getText()).getSpans(
          0, view.length(), AospNetworkTypeArtwork.ArtworkSpan.class);
      assertEquals(1, spans.length);
      Paint.FontMetricsInt metrics = new Paint.FontMetricsInt();
      assertTrue(spans[0].getSize(view.getPaint(), view.getText(), 0, view.length(), metrics) > 0);
      assertEquals(Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
          12f, context.getResources().getDisplayMetrics())), metrics.descent - metrics.ascent);
    }
    view.setText("5G UW");
    artwork.apply(view, context.getResources());
    assertEquals("5G UW", view.getText().toString());
    assertEquals(27f, view.getTextSize(), 0f);
    assertTrue(view.getIncludeFontPadding());
  }

  @Test public void artworkTracksTintChangesWithoutASecondModelBinding() {
    ColorDrawable drawable = new ColorDrawable(Color.WHITE);
    drawable.setBounds(0, 0, 20, 12);
    AospNetworkTypeArtwork.ArtworkSpan span = new AospNetworkTypeArtwork.ArtworkSpan(drawable);
    Bitmap bitmap = Bitmap.createBitmap(20, 12, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    Paint paint = new Paint();
    paint.setColor(Color.RED);
    span.draw(canvas, "LTE", 0, 3, 0, 0, 12, 12, paint);
    assertEquals(Color.RED, bitmap.getPixel(10, 6));
    paint.setColor(Color.GREEN);
    span.draw(canvas, "LTE", 0, 3, 0, 0, 12, 12, paint);
    assertEquals(Color.GREEN, bitmap.getPixel(10, 6));
    bitmap.recycle();
  }
}
