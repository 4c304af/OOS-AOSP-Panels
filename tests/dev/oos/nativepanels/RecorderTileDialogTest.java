package dev.oos.nativepanels;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.app.Activity;
import android.content.res.TypedArray;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.content.Context;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class RecorderTileDialogTest {
  @Test public void deliveredClickSuppliesItsOwnSourceEvenWithoutAnAndroidView() throws Throwable {
    RecorderTileDialog bridge = new RecorderTileDialog(null);
    Object compact = new Object(), expanded = new Object();
    Object captured = bridge.withinClick(compact, () -> {
      assertSame(compact, bridge.source());
      try {
        bridge.withinClick(expanded, () -> {
          assertSame(expanded, bridge.source());
          throw new IllegalStateException();
        });
        fail();
      } catch (IllegalStateException expected) { }
      return bridge.source();
    });
    assertSame(compact, captured);
    assertNull(bridge.source());
  }

  @Test public void originalClickableContentStaysTogetherInsideOneDialogSurface() {
    Context ctx = Robolectric.buildActivity(Activity.class).setup().visible().get();
    AlertDialog dialog = new AlertDialog.Builder(ctx).create();
    FrameLayout body = new FrameLayout(ctx);
    Button row = new Button(ctx);
    AtomicInteger clicks = new AtomicInteger();
    row.setOnClickListener(v -> clicks.incrementAndGet());
    row.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
    RecorderTileDialog.setContent(dialog, body, row, "Recorder");
    dialog.show();
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertTrue(row.isAttachedToWindow());
    assertSame(body, row.getParent());
    assertNull(body.getBackground());
    assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, row.getLayoutParams().height);
    assertTrue(row.performClick());
    assertEquals(1, clicks.get());
    dialog.dismiss();
  }

  @Test public void drawableReadByTheAnimatorHasTheRestingWindowRadiusAndColor() {
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert).create();
    RecorderTileDialog.setWindowSurface(dialog);
    GradientDrawable background = (GradientDrawable)dialog.getWindow().getDecorView().getBackground();
    TypedArray theme = dialog.getContext().obtainStyledAttributes(
        new int[]{android.R.attr.dialogCornerRadius, android.R.attr.colorBackground});
    try {
      assertEquals(theme.getDimension(0, -1), background.getCornerRadius(), 0f);
      assertEquals(theme.getColor(1, 0), background.getColor().getDefaultColor());
    } finally { theme.recycle(); }
  }

  @Test public void serviceSuppliedHeightSurvivesHostingInTheAnimatedDialog() {
    Context ctx = RuntimeEnvironment.getApplication();
    FrameLayout body = new FrameLayout(ctx), remote = new FrameLayout(ctx);
    remote.setLayoutParams(new FrameLayout.LayoutParams(-1, 240));
    RecorderTileDialog.setContent(new AlertDialog.Builder(ctx).create(), body, remote, null);
    assertEquals(240, remote.getLayoutParams().height);
  }

  @Test public void phoneCardHasSideMarginsAndKeepsSmallerLandscapeAndFoldWidths() {
    assertEquals(936, RecorderTileDialog.cardWidth(-1, 1080, 72, 936));
    assertEquals(696, RecorderTileDialog.cardWidth(696, 1800, 40, 624));
    assertEquals(624, RecorderTileDialog.cardWidth(-1, 1536, 0, 624));
    assertEquals(452, RecorderTileDialog.cardWidth(696, 500, 24, 624));
  }

  @Test public void recordingWaitsForTheDialogToFinishReturningAndRunsOnlyOnce() {
    Activity ctx = Robolectric.buildActivity(Activity.class).setup().visible().get();
    AlertDialog dialog = new AlertDialog(ctx) {
      @Override public void dismiss() { /* SystemUI owns completion of the return animation. */ }
    };
    RecorderTileDialog.Display display = new RecorderTileDialog.Display(new Object(), dialog);
    dialog.show();
    AtomicInteger launches = new AtomicInteger();
    display.select(launches::incrementAndGet);
    display.select(() -> fail("Second selection replaced the first"));
    assertEquals(0, launches.get());
    display.finish();
    display.finish();
    display.select(() -> fail("Selection after dismissal"));
    assertEquals(1, launches.get());
  }

  @Test public void dismissingWithoutASelectionDoesNotLaunchRecorder() {
    Activity ctx = Robolectric.buildActivity(Activity.class).setup().get();
    AlertDialog dialog = new AlertDialog.Builder(ctx).create();
    RecorderTileDialog.Display display = new RecorderTileDialog.Display(new Object(), dialog);
    display.finish();
    display.select(() -> fail("Back or an outside tap should only close the panel"));
  }
}
