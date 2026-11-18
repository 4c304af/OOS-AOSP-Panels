package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Insets;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BluetoothDetailsBottomPaddingTest {
  // Contract from the supplied SettingsUtils.getFootViewHeight(Activity): 32dp
  // plus navigationBars.bottom only in gesture mode. The three-button parent
  // already owns navigation padding. Neither native method adds IME height.
  private static final class NativeFooter implements IntSupplier {
    WindowInsets insets = insets(24, 0);
    boolean gestures = true, visible;
    @Override public int getAsInt() {
      return visible ? 0 : 32 + (gestures ? insets.getInsets(WindowInsets.Type.navigationBars()).bottom : 0);
    }
  }

  @Test public void repeatInsetsLayoutAndNavigationChangesDoNotAccumulatePadding() {
    View list = new View(context());
    list.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    list.setPaddingRelative(11, 17, 23, 0);
    NativeFooter nativeFooter = new NativeFooter();
    BluetoothDetailsBottomPadding padding = new BluetoothDetailsBottomPadding(list, nativeFooter);
    for (int i = 0; i < 10; i++) {
      padding.refresh();
      padding.onLayoutChange(list, 0, 0, 300, 200, 0, 0, 300, 199);
      assertEquals(56, list.getPaddingBottom());
    }
    assertEquals(11, list.getPaddingStart());
    assertEquals(17, list.getPaddingTop());
    assertEquals(23, list.getPaddingEnd());

    nativeFooter.insets = insets(24, 400);
    padding.refresh();
    assertEquals("Keyboard resize must not be counted a second time", 56, list.getPaddingBottom());
    nativeFooter.gestures = false;
    nativeFooter.insets = insets(48, 0);
    padding.refresh();
    assertEquals("Three-button navigation is already reserved by the parent", 32, list.getPaddingBottom());
    nativeFooter.gestures = true;
    nativeFooter.insets = insets(0, 0);
    padding.refresh();
    assertEquals("A landscape or hidden navigation bar still retains the footer gap", 32, list.getPaddingBottom());
    padding.dispose();
  }

  @Test public void aFooterRestoredByDeviceConfigurationDoesNotAcquireASecondGap() {
    View list = new View(context());
    list.setPadding(3, 5, 7, 0);
    NativeFooter nativeFooter = new NativeFooter();
    BluetoothDetailsBottomPadding padding = new BluetoothDetailsBottomPadding(list, nativeFooter);
    padding.refresh();
    assertEquals(56, list.getPaddingBottom());
    nativeFooter.visible = true;
    padding.refresh();
    assertEquals(0, list.getPaddingBottom());
    nativeFooter.visible = false;
    padding.refresh();
    assertEquals(56, list.getPaddingBottom());
    padding.dispose();
    assertEquals(0, list.getPaddingBottom());
  }

  @Test public void respectsNewNativePaddingAndRestoresOnlyItsOwnWrite() {
    View list = new View(context());
    list.setPadding(3, 5, 7, 80);
    BluetoothDetailsBottomPadding padding = new BluetoothDetailsBottomPadding(list, () -> 56);
    padding.refresh();
    assertEquals(80, list.getPaddingBottom());
    list.setPadding(9, 12, 15, 8);
    padding.refresh();
    assertEquals(56, list.getPaddingBottom());
    padding.dispose();
    assertEquals(8, list.getPaddingBottom());
    assertEquals(9, list.getPaddingLeft());
    assertEquals(12, list.getPaddingTop());
    assertEquals(15, list.getPaddingRight());

    padding = new BluetoothDetailsBottomPadding(list, () -> 56);
    padding.refresh();
    list.setPadding(9, 12, 15, 100);
    padding.dispose();
    assertEquals("A later native update must not be undone on destroyView", 100, list.getPaddingBottom());
  }

  @Test public void disposalRemovesTheLayoutListenerAndIgnoresPendingUpdates() {
    View list = new View(context());
    AtomicInteger reads = new AtomicInteger();
    BluetoothDetailsBottomPadding padding = new BluetoothDetailsBottomPadding(list, () -> {
      reads.incrementAndGet();
      return 56;
    });
    padding.refresh();
    padding.schedule();
    padding.dispose();
    int before = reads.get();
    list.layout(0, 0, 300, 200);
    padding.schedule();
    padding.refresh();
    assertEquals(before, reads.get());
    assertEquals(0, list.getPaddingBottom());
  }

  @Test public void waitingForAnAdapterDoesNotOverwriteTheOriginalPadding() {
    View list = new View(context());
    list.setPadding(0, 0, 0, 9);
    AtomicInteger desired = new AtomicInteger(-1);
    BluetoothDetailsBottomPadding padding = new BluetoothDetailsBottomPadding(list, desired::get);
    padding.refresh();
    assertEquals(9, list.getPaddingBottom());
    desired.set(56);
    padding.refresh();
    assertEquals(56, list.getPaddingBottom());
    padding.dispose();
    assertEquals(9, list.getPaddingBottom());
  }

  @Test public void theLastPreferenceCanScrollAboveGestureAndThreeButtonNavigation() {
    for (boolean gestures : new boolean[]{true, false}) {
      FrameLayout parent = new FrameLayout(context());
      RecyclerView list = new RecyclerView(context());
      LinearLayoutManager layout = new LinearLayoutManager(context());
      list.setLayoutManager(layout);
      list.setItemAnimator(null);
      list.setClipToPadding(false);
      list.setAdapter(new Rows());
      int navigation = gestures ? 24 : 48;
      // Match the real SettingsBaseActivity parent: only three-button mode
      // reduces the list viewport before the native footer is considered.
      int reservedByParent = gestures ? 0 : navigation;
      parent.addView(list, new FrameLayout.LayoutParams(300, 240 - reservedByParent));
      NativeFooter footer = new NativeFooter();
      footer.gestures = gestures;
      footer.insets = insets(navigation, 0);
      BluetoothDetailsBottomPadding padding = new BluetoothDetailsBottomPadding(list, footer);
      padding.refresh();
      measure(parent, 300, 240);
      list.scrollBy(0, Integer.MAX_VALUE);
      View last = layout.findViewByPosition(11);
      assertNotNull("Last row remains reachable", last);
      assertEquals(list.getHeight() - list.getPaddingBottom(), last.getBottom());
      assertEquals("The final row has one 32px gap above the navigation area",
          32, 240 - navigation - last.getBottom());
      padding.dispose();
    }
  }

  private static Context context() { return RuntimeEnvironment.getApplication(); }

  private static WindowInsets insets(int navigation, int keyboard) {
    return new WindowInsets.Builder()
        .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, navigation))
        .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, keyboard)).build();
  }

  private static void measure(View view, int width, int height) {
    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    view.layout(0, 0, width, height);
  }

  private static final class Rows extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
      View row = new View(parent.getContext());
      row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 80));
      return new RecyclerView.ViewHolder(row) {};
    }
    @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {}
    @Override public int getItemCount() { return 12; }
  }
}
