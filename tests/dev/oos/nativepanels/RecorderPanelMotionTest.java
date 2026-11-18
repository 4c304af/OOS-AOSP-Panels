package dev.oos.nativepanels;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class RecorderPanelMotionTest {
  private final RecorderPanelMotion motion = new RecorderPanelMotion();

  @Test public void openingAndClosingNeverSelectTheTaggedListAsTheSourceTile() throws Throwable {
    Context context = RuntimeEnvironment.getApplication();
    FrameLayout shade = new FrameLayout(context);
    FrameLayout card = new FrameLayout(context);
    View list = new View(context);
    list.setTag("screenrecord");
    card.addView(list);
    shade.addView(card);
    PanelRuntime.Work tagLookup = () -> shade.findViewWithTag("screenrecord");
    assertSame(list, tagLookup.run());

    Object panel = new Object();
    assertNull(motion.origin(panel, "screenrecord", true, () -> null, tagLookup));
    assertNull(motion.origin(panel, "screenrecord", false, () -> null, tagLookup));
  }

  @Test public void serviceRecorderPreservesAnExplicitAnchorAndHandlesItsLossAtDismissal() throws Throwable {
    Object panel = new Object();
    String service = "custom(com.oplus.screenrecorder/.floatwindow.services.QuickPanelService)";
    View tile = new View(RuntimeEnvironment.getApplication());
    PanelRuntime.Work fallback = () -> { throw new AssertionError("looked up a replacement tile"); };
    assertSame(tile, motion.origin(panel, service, true, () -> tile, fallback));
    assertNull(motion.origin(panel, service, true, () -> null, fallback));
  }

  @Test public void oosGridAndUnrelatedDialogsKeepTheirOwnOrigins() throws Throwable {
    Object panel = new Object();
    Object nativeOrigin = new Object();
    PanelRuntime.Work unused = () -> { throw new AssertionError("overrode the native transition"); };
    assertSame(nativeOrigin, motion.origin(panel, "screenrecord", false, unused, () -> nativeOrigin));
    assertSame(nativeOrigin, motion.origin(panel, "screenrecord", true, unused, () -> nativeOrigin));
    assertSame(nativeOrigin, motion.origin(new Object(), "screenshot", true, unused, () -> nativeOrigin));
    assertNull(motion.origin(new Object(), "screenrecord", true, () -> null, unused));
  }
}
