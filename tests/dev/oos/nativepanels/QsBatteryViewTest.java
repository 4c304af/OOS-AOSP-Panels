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

  @Test public void nativeHeaderUsesFullIntrinsicSizeAcrossLargeSlotsChargingAndSmallSlots() {
    View compactArtwork = new View(context);
    QsBatteryView compactHost = new QsBatteryView(compactArtwork, icon, legacy, FRAME, true);
    int neutral = FRAME.normalWidth(13);
    compactArtwork.setMinimumWidth(neutral);
    compactArtwork.setMinimumHeight(13);
    legacy.setVisibility(View.GONE);
    icon.setLayoutParams(new LinearLayout.LayoutParams(100, 50));
    compactHost.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
    assertEquals(1f, compactArtwork.getScaleX(), 0f);
    assertEquals(13, compactHost.getMeasuredHeight());

    compactArtwork.setMinimumWidth(neutral + 6);
    compactHost.requestLayout();
    compactHost.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
    assertEquals(1f, compactArtwork.getScaleX(), 0f);
    assertEquals(neutral + 6, compactHost.getMeasuredWidth());

    icon.setLayoutParams(new LinearLayout.LayoutParams(16, 8));
    compactHost.refreshSlot();
    compactHost.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
    assertEquals(1f, compactArtwork.getScaleY(), 0f);
    assertEquals(13, compactHost.getMeasuredHeight());
    assertEquals(neutral + 6, compactHost.getMeasuredWidth());
  }

  @Test public void nativeHeaderWithoutAnOosSlotRetainsTheFullIntrinsicSize() {
    View compactArtwork = new View(context);
    compactArtwork.setMinimumWidth(27);
    compactArtwork.setMinimumHeight(13);
    QsBatteryView compactHost = new QsBatteryView(compactArtwork, null, null, null, true);
    compactHost.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
    assertEquals(1f, compactArtwork.getScaleX(), 0f);
    assertEquals(27, compactHost.getMeasuredWidth());
    assertEquals(13, compactHost.getMeasuredHeight());
  }

  @Test public void nativeHeaderUsesParentConstraintWithoutClippingTheChargingAttribution() {
    View nativeArtwork = new View(context);
    nativeArtwork.setMinimumWidth(66);
    nativeArtwork.setMinimumHeight(26);
    QsBatteryView nativeHost = new QsBatteryView(nativeArtwork, icon, legacy, FRAME, true);
    nativeHost.measure(View.MeasureSpec.makeMeasureSpec(33, View.MeasureSpec.AT_MOST),
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.AT_MOST));
    nativeHost.layout(0, 0, nativeHost.getMeasuredWidth(), nativeHost.getMeasuredHeight());
    assertEquals(.5f, nativeArtwork.getScaleX(), 0f);
    assertEquals(.5f, nativeArtwork.getScaleY(), 0f);
    assertEquals(33, nativeHost.getMeasuredWidth());
    assertEquals(13, nativeHost.getMeasuredHeight());
    assertEquals(66, nativeArtwork.getWidth());
    assertEquals(33f, nativeArtwork.getWidth() * nativeArtwork.getScaleX(), 0f);
  }

  @Test public void nativeHeaderTracksTheComposeThirteenSpCanvasAcrossDisplayConfigurationChanges() {
    View nativeArtwork = new View(context);
    QsBatteryView nativeHost = new QsBatteryView(nativeArtwork, icon, legacy, FRAME, true);
    for (String config : new String[]{"port-mdpi", "land-xxhdpi", "port-xhdpi"}) {
      RuntimeEnvironment.setQualifiers(config);
      nativeHost.dispatchConfigurationChanged(context.getResources().getConfiguration());
      float density = context.getResources().getDisplayMetrics().density;
      int height = Math.round(13 * density), neutral = FRAME.normalWidth(height);
      icon.setLayoutParams(new LinearLayout.LayoutParams(Math.round(8 * density), Math.round(4 * density)));
      nativeArtwork.setMinimumWidth(neutral);
      nativeArtwork.setMinimumHeight(height);
      nativeHost.refreshSlot();
      nativeHost.requestLayout();
      nativeHost.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
      assertEquals(1f, nativeArtwork.getScaleY(), 0f);
      assertEquals(height, nativeHost.getMeasuredHeight());
      assertEquals(neutral, nativeHost.getMeasuredWidth());
    }
  }
}
