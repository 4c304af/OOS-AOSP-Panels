package dev.oos.nativepanels;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.constraintlayout.widget.ConstraintLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class MobileNetworkTypeOrderTest {
  @Test public void networkTypePrecedesBarsWithoutChangingWidthOrOwnershipInBothDirections()
      throws Exception {
    for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
      Fixture f = new Fixture(direction);
      f.layout();
      f.assertOrder(f.roaming, f.bars, f.type);
      int width = f.group.getMeasuredWidth();
      int prefixStart = direction == View.LAYOUT_DIRECTION_LTR ? f.roaming.getLeft() : f.roaming.getRight();
      ViewGroup.LayoutParams rp = f.roaming.getLayoutParams();
      ViewGroup.LayoutParams bp = f.bars.getLayoutParams();
      ViewGroup.LayoutParams tp = f.type.getLayoutParams();
      int[] margins = f.margins();

      assertTrue(f.order.apply(f.group, f.type));
      f.layout();

      f.assertOrder(f.roaming, f.type, f.bars);
      assertEquals(width, f.group.getMeasuredWidth());
      assertEquals(prefixStart,
          direction == View.LAYOUT_DIRECTION_LTR ? f.roaming.getLeft() : f.roaming.getRight());
      assertSame(f.group, f.roaming.getParent());
      assertSame(f.group, f.bars.getParent());
      assertSame(f.group, f.type.getParent());
      assertSame(rp, f.roaming.getLayoutParams());
      assertSame(bp, f.bars.getLayoutParams());
      assertSame(tp, f.type.getLayoutParams());
      assertSame(f.roaming, f.group.getChildAt(0));
      assertSame(f.bars, f.group.getChildAt(1));
      assertSame(f.type, f.group.getChildAt(2));
      assertArrayEquals(margins, f.margins());
      assertEquals("5G", f.type.getText().toString());
    }
  }

  @Test public void hiddenTypeCollapsesAndReturnsInTheNewOrderWithoutChangingTotalWidth()
      throws Exception {
    for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
      Fixture f = new Fixture(direction);
      f.type.setVisibility(View.GONE);
      f.layout();
      int hiddenWidth = f.group.getMeasuredWidth();
      assertTrue(f.order.apply(f.group, f.type));
      f.layout();
      assertEquals(View.GONE, f.type.getVisibility());
      assertEquals(hiddenWidth, f.group.getMeasuredWidth());
      f.assertOrder(f.roaming, f.bars);

      f.type.setVisibility(View.VISIBLE);
      f.layout();
      f.assertOrder(f.roaming, f.type, f.bars);
      assertTrue(f.group.getMeasuredWidth() > hiddenWidth);
      assertFalse(f.order.apply(f.group, f.type));
    }
  }

  @Test public void repeatedBindingIsIdempotentAndTheSameHelperHandlesFreshGroups()
      throws Exception {
    MobileNetworkTypeOrder order = new MobileNetworkTypeOrder(ConstraintLayout.LayoutParams.class);
    for (int i = 0; i < 2; i++) {
      Fixture f = new Fixture(View.LAYOUT_DIRECTION_LTR);
      assertTrue(order.apply(f.group, f.type));
      f.layout();
      int[] constraints = f.constraints();
      int[] bounds = f.bounds();
      assertFalse(order.apply(f.group, f.type));
      f.layout();
      assertArrayEquals(constraints, f.constraints());
      assertArrayEquals(bounds, f.bounds());
    }
  }

  @Test public void unknownOrCompetingChainsAreUntouched() throws Exception {
    for (int broken = 0; broken < 4; broken++) {
      Fixture f = new Fixture(View.LAYOUT_DIRECTION_LTR);
      if (broken == 0) f.params(f.roaming).endToStart = f.type.getId();
      if (broken == 1) f.params(f.type).endToEnd = ConstraintLayout.LayoutParams.UNSET;
      if (broken == 2) f.params(f.bars).leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID;
      if (broken == 3) f.params(f.type).startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
      int[] constraints = f.constraints();
      assertFalse(f.order.apply(f.group, f.type));
      assertArrayEquals(constraints, f.constraints());
    }

    Fixture f = new Fixture(View.LAYOUT_DIRECTION_LTR);
    TextView unrelated = new TextView(RuntimeEnvironment.getApplication());
    unrelated.setId(View.generateViewId());
    assertFalse(f.order.apply(f.group, unrelated));
    assertFalse(f.order.apply(null, f.type));
    assertFalse(f.order.apply(f.group, null));
  }

  private static final class Fixture {
    final ConstraintLayout group = new ConstraintLayout(RuntimeEnvironment.getApplication());
    final View roaming = new View(group.getContext());
    final View bars = new View(group.getContext());
    final TextView type = new TextView(group.getContext());
    final MobileNetworkTypeOrder order = new MobileNetworkTypeOrder(ConstraintLayout.LayoutParams.class);
    final int direction;

    Fixture(int direction) throws Exception {
      this.direction = direction;
      group.setLayoutDirection(direction);
      group.setPaddingRelative(2, 0, 3, 0);
      roaming.setId(View.generateViewId());
      bars.setId(View.generateViewId());
      type.setId(View.generateViewId());
      type.setText("5G");
      add(roaming, 8, 1, 2);
      add(bars, 12, 3, 4);
      add(type, 22, 5, 6);

      ConstraintLayout.LayoutParams rp = params(roaming);
      rp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
      rp.endToStart = bars.getId();
      rp.horizontalChainStyle = ConstraintLayout.LayoutParams.CHAIN_PACKED;
      ConstraintLayout.LayoutParams bp = params(bars);
      bp.startToEnd = roaming.getId();
      bp.endToStart = type.getId();
      ConstraintLayout.LayoutParams tp = params(type);
      tp.startToEnd = bars.getId();
      tp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
    }

    void add(View view, int width, int start, int end) {
      ConstraintLayout.LayoutParams lp = new ConstraintLayout.LayoutParams(width, 18);
      lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
      lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
      lp.setMarginStart(start);
      lp.setMarginEnd(end);
      group.addView(view, lp);
    }

    void layout() {
      group.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST),
          View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.AT_MOST));
      group.layout(0, 0, group.getMeasuredWidth(), group.getMeasuredHeight());
      assertTrue(group.getMeasuredWidth() > 0);
      assertEquals(direction, group.getLayoutDirection());
    }

    void assertOrder(View... views) {
      for (int i = 1; i < views.length; i++) {
        assertTrue(views[i - 1].getWidth() > 0);
        assertTrue(views[i].getWidth() > 0);
        if (direction == View.LAYOUT_DIRECTION_LTR) {
          assertTrue(views[i - 1].getRight() <= views[i].getLeft());
        } else {
          assertTrue(views[i].getRight() <= views[i - 1].getLeft());
        }
      }
    }

    ConstraintLayout.LayoutParams params(View view) {
      return (ConstraintLayout.LayoutParams) view.getLayoutParams();
    }

    int[] margins() {
      int[] result = new int[6];
      int i = 0;
      for (View view : new View[]{roaming, bars, type}) {
        ConstraintLayout.LayoutParams lp = params(view);
        result[i++] = lp.getMarginStart();
        result[i++] = lp.getMarginEnd();
      }
      return result;
    }

    int[] constraints() {
      int[] result = new int[24];
      int i = 0;
      for (View view : new View[]{roaming, bars, type}) {
        ConstraintLayout.LayoutParams lp = params(view);
        result[i++] = lp.startToStart;
        result[i++] = lp.startToEnd;
        result[i++] = lp.endToStart;
        result[i++] = lp.endToEnd;
        result[i++] = lp.leftToLeft;
        result[i++] = lp.leftToRight;
        result[i++] = lp.rightToLeft;
        result[i++] = lp.rightToRight;
      }
      return result;
    }

    int[] bounds() {
      int[] result = new int[6];
      int i = 0;
      for (View view : new View[]{roaming, bars, type}) {
        result[i++] = view.getLeft();
        result[i++] = view.getRight();
      }
      return result;
    }
  }
}
