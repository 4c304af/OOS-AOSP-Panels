package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.DrawableWrapper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class StatusIconMetricsTest {
  @Test public void fiveBarsUseTheSelectedAssetsWiderAspectRatioAndKeepTheLevel() {
    var context = RuntimeEnvironment.getApplication();
    ImageView image = new ImageView(context);
    var levels = context.getDrawable(R.drawable.ic_mobile_level_list);
    var glyph = new DrawableWrapper(levels) {};
    // LevelList entries 0..4 are the 17dp four-bar assets; 10..15 are 21dp five-bar assets.
    var four = StatusIconMetrics.mobileSignal(image, glyph, levels, 4);
    int height = StatusIconMetrics.sp(image, 12f);
    assertEquals(Math.round(height * 17f / 12f), four.getIntrinsicWidth());
    var five = StatusIconMetrics.mobileSignal(image, glyph, levels, 15);
    assertEquals(Math.round(height * 21f / 12f), five.getIntrinsicWidth());
    assertEquals(15, five.getLevel());
    assertEquals(15, levels.getLevel());
    assertTrue(five.getIntrinsicWidth() > four.getIntrinsicWidth());
  }

  @Test public void ratGapAndGroupMarginsFollowFontScaleAndPreserveNativeChildren() {
    for (String config : new String[]{"port-mdpi", "land-xxhdpi"}) {
      RuntimeEnvironment.setQualifiers(config);
      var context = RuntimeEnvironment.getApplication();
      LinearLayout outer = new LinearLayout(context);
      outer.setLayoutParams(new FrameLayout.LayoutParams(-2, -1));
      LinearLayout combo = new LinearLayout(context);
      TextView type = new TextView(context);
      type.setText("5G");
      FrameLayout bars = new FrameLayout(context);
      ImageView signal = new ImageView(context);
      bars.addView(signal, new FrameLayout.LayoutParams(-2, -2));
      combo.addView(type, new LinearLayout.LayoutParams(-2, -2));
      var oldBars = new LinearLayout.LayoutParams(-2, -2);
      oldBars.setMarginStart(2);
      combo.addView(bars, oldBars);
      outer.addView(combo);
      var nativeParent = type.getParent();
      var nativeParams = type.getLayoutParams();
      for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
        outer.setLayoutDirection(direction);
        StatusIconMetrics.mobile(outer, type, signal);
        var gp = (FrameLayout.LayoutParams) outer.getLayoutParams();
        var tp = (LinearLayout.LayoutParams) type.getLayoutParams();
        assertEquals(StatusIconMetrics.sp(outer, 3f), gp.getMarginStart());
        assertEquals(StatusIconMetrics.sp(outer, 3f), gp.getMarginEnd());
        assertEquals(0, tp.getMarginStart());
        assertEquals(StatusIconMetrics.sp(type, 6f), tp.getMarginEnd());
        assertEquals(0, oldBars.getMarginStart());
        assertSame(nativeParent, type.getParent());
        assertSame(nativeParams, type.getLayoutParams());
        assertEquals("5G", type.getText().toString());
      }
      type.setVisibility(View.GONE);
      StatusIconMetrics.mobile(outer, type, signal);
      assertEquals(View.GONE, type.getVisibility());
    }
  }

  @Test public void signalKeepsItsAspectRatioAtDifferentTextScales() {
    var context = RuntimeEnvironment.getApplication();
    ImageView image = new ImageView(context);
    float oldScaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
    try {
      for (float scale : new float[]{1f, 1.3f}) {
        context.getResources().getDisplayMetrics().scaledDensity = oldScaledDensity * scale;
        var drawable = StatusIconMetrics.signal(image, new ColorDrawable(), 17f / 12f);
        int height = StatusIconMetrics.sp(image, 12f);
        assertEquals(height, drawable.getIntrinsicHeight());
        assertEquals(Math.round(height * 17f / 12f), drawable.getIntrinsicWidth());
        assertTrue(image.getAdjustViewBounds());
      }
    } finally {
      context.getResources().getDisplayMetrics().scaledDensity = oldScaledDensity;
    }
  }
}
