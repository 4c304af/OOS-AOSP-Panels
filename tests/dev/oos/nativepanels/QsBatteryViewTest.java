package dev.oos.nativepanels;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class QsBatteryViewTest {
  private static final BatteryFrameSize FRAME = new BatteryFrameSize(24f, 13f, 1.5f);
  private final Context context = RuntimeEnvironment.getApplication();
  private final View artwork = new View(context), icon = new View(context), legacy = new View(context);
  private final QsBatteryView host = new QsBatteryView(artwork, icon, legacy, FRAME);

  private void measure() {
    host.refreshSlot();
    host.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
    host.layout(0, 0, host.getMeasuredWidth(), host.getMeasuredHeight());
  }

  @Test public void sameHeaderTracksStatusBarFitAcrossDensityRotationAndCharging() {
    legacy.setVisibility(View.GONE);
    for (String config : new String[]{"port-mdpi", "land-xxhdpi", "port-xhdpi"}) {
      RuntimeEnvironment.setQualifiers(config);
      host.dispatchConfigurationChanged(context.getResources().getConfiguration());
      float density = context.getResources().getDisplayMetrics().density;
      int height = Math.round(13 * density), neutral = FRAME.normalWidth(height);
      int slotWidth = Math.round(29 * density), slotHeight = Math.round(16 * density);
      icon.setLayoutParams(new LinearLayout.LayoutParams(slotWidth, slotHeight));
      artwork.setMinimumWidth(neutral);
      artwork.setMinimumHeight(height);
      measure();
      float scale = BatteryFrameSize.fitScale(neutral, height, slotWidth, slotHeight);
      assertEquals(scale, artwork.getScaleY(), 0f);
      assertEquals((int)Math.ceil(height * scale), host.getMeasuredHeight());

      int chargingWidth = neutral + Math.round(6 * density);
      artwork.setMinimumWidth(chargingWidth);
      measure();
      assertEquals(scale, artwork.getScaleX(), 0f);
      assertEquals((int)Math.ceil(chargingWidth * scale), host.getMeasuredWidth());
    }
  }

  @Test public void unboundHeaderUsesIntrinsicSizeThenFollowsTheVisibleLegacySlot() {
    artwork.setMinimumWidth(27);
    artwork.setMinimumHeight(13);
    icon.setVisibility(View.GONE);
    legacy.setVisibility(View.GONE);
    measure();
    assertEquals(1f, artwork.getScaleX(), 0f);
    assertEquals(13, host.getMeasuredHeight());
    legacy.setLayoutParams(new LinearLayout.LayoutParams(24, 12));
    legacy.setVisibility(View.VISIBLE);
    measure();
    assertEquals(BatteryFrameSize.fitScale(FRAME.normalWidth(13), 13, 24, 12), artwork.getScaleY(), 0f);
    assertSame(artwork, QsBatteryView.forHeader(null, new Object(), artwork));
  }
}
