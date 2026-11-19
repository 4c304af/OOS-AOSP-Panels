package dev.oos.nativepanels;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PortraitQsScrollTest {
  private static MotionEvent event(int action, long time, int x, int y) {
    return MotionEvent.obtain(100L, time, action, x, y, 0);
  }

  @Test public void oneUpwardSwipeCanScrollThePlayerThenCollapseTheShade() {
    Context context = RuntimeEnvironment.getApplication();
    for (boolean directParentRequest : new boolean[]{true, false}) {
      var drag = new CombinedTouch.CollapseDrag();
      List<Integer> childEvents = new ArrayList<>();
      FrameLayout panel = new FrameLayout(context) {
        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
          return e.getActionMasked() == MotionEvent.ACTION_MOVE;
        }
      };
      FrameLayout frame = new FrameLayout(context);
      frame.setOnTouchListener((view, e) -> {
        childEvents.add(e.getActionMasked());
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
          drag.begin(frame, e);
          (directParentRequest ? panel : frame).requestDisallowInterceptTouchEvent(true);
        }
        return true;
      });
      panel.addView(frame);
      panel.layout(0, 0, 500, 600);
      frame.layout(0, 0, 500, 600);
      panel.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 100, 100, 400));
      MotionEvent scrolling = event(MotionEvent.ACTION_MOVE, 116, 100, 330);
      assertTrue(drag.upwards(scrolling));
      panel.dispatchTouchEvent(scrolling);
      assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE), childEvents);
      MotionEvent atBottom = event(MotionEvent.ACTION_MOVE, 132, 100, 255);
      assertTrue(drag.upwards(atBottom));
      drag.handBack();
      panel.dispatchTouchEvent(atBottom);
      assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL), childEvents);
      assertFalse(drag.upwards(event(MotionEvent.ACTION_MOVE, 148, 100, 170)));
    }
  }

  @Test public void horizontalSeekingKeepsItsGestureAndNewDownStartsFresh() {
    var drag = new CombinedTouch.CollapseDrag();
    FrameLayout frame = new FrameLayout(RuntimeEnvironment.getApplication());
    MotionEvent down = event(MotionEvent.ACTION_DOWN, 100, 100, 400);
    drag.begin(frame, down);
    assertFalse(drag.upwards(event(MotionEvent.ACTION_MOVE, 116, 180, 400)));
    assertFalse(drag.upwards(event(MotionEvent.ACTION_MOVE, 132, 180, 120)));
    drag.begin(frame, down);
    assertTrue(drag.upwards(event(MotionEvent.ACTION_MOVE, 116, 100, 300)));
    assertFalse(drag.upwards(event(MotionEvent.ACTION_MOVE, 132, 100, 330)));
    drag.clear();
    assertFalse(drag.upwards(event(MotionEvent.ACTION_MOVE, 148, 100, 240)));
  }
}
