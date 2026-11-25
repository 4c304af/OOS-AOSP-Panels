package dev.oos.nativepanels;

import static dev.oos.nativepanels.ShadePullMotion.Pane.*;
import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.ref.WeakReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBuild;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "port")
public class NotificationPullRoutingTest {
  public static class QsFragment extends Fragment {
    public boolean isEditing() { return false; }
  }
  static class Controller {
    QsFragment fragment = new QsFragment();
    Stack listController;
    int bar;
    float transition;
    boolean policy = true, split, dozing;
  }
  static class Stack extends FrameLayout {
    Stack(Context context) { super(context); }
    Stack panel = this;
    Controller panelQs;
    boolean bouncer, fromHeadsUp, pinned, headsUpAway;
    Stack getView() { return this; }
    boolean isHeadsUpTransition() { return false; }
  }

  final Controller qs = new Controller();
  Stack stack;
  ShadePullMotion motion;
  ShadeDecor decor;

  @Before public void setUp() throws Exception {
    ShadowBuild.setVersionRelease("17");
    ShadowBuild.setModel("CPH2825");
    Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
    FrameLayout frame = new FrameLayout(activity);
    activity.setContentView(frame);
    stack = new Stack(activity);
    frame.addView(stack);
    qs.listController = stack;
    stack.panelQs = qs;
    PanelRuntime runtime = new PanelRuntime(null, getClass().getClassLoader());
    ShadeLayout layout = runtime.combinedQs.layout;
    layout.select(qs.fragment);
    decor = new ShadeDecor(frame, layout, runtime);
    Reflect.field(ShadeLayout.class, "decor").set(layout, new WeakReference<>(decor));
    Reflect.field(ShadeLayout.class, "editing").set(layout, Reflect.method(QsFragment.class, "isEditing"));
    motion = new ShadePullMotion(runtime);
    bind(Controller.class, "fragment", "bar", "transition", "policy", "split", "dozing", "listController");
    bind(Stack.class, "panel", "panelQs", "bouncer", "fromHeadsUp", "pinned", "headsUpAway");
    Reflect.field(ShadePullMotion.class, "listView").set(motion, Reflect.method(Stack.class, "getView"));
    Reflect.field(ShadePullMotion.class, "headsUp").set(motion, Reflect.method(Stack.class, "isHeadsUpTransition"));
  }

  private void bind(Class<?> type, String... names) throws Exception {
    for (String name : names) Reflect.field(ShadePullMotion.class, name).set(motion, Reflect.field(type, name));
  }

  @Test public void renoUsesSystemRowsOnHideAndReopenWhileQsKeepsItsOwnMotion() throws Exception {
    for (int visibility : new int[]{View.VISIBLE, View.INVISIBLE, View.VISIBLE}) {
      decor.frame.setVisibility(visibility);
      assertEquals(STOCK, motion.listPane(stack));
      assertEquals(PORTRAIT, motion.pane(qs));
    }
    qs.split = true;
    assertEquals(SPLIT, motion.listPane(stack));
    qs.split = false;
    assertEquals(STOCK, motion.listPane(stack));
  }

  @Test public void sameAndroidVersionOnOnePlusKeepsOriginalRowsAndHeadsUpRouting() throws Exception {
    ShadowBuild.setModel("CPH2653");
    ShadowBuild.setProduct("CPH2653");
    assertEquals(PORTRAIT, motion.listPane(stack));
    stack.pinned = true;
    assertEquals(STOCK, motion.listPane(stack));
    stack.pinned = false;
    qs.bar = CombinedQsExpansion.KEYGUARD;
    assertEquals(STOCK, motion.listPane(stack));
    qs.bar = CombinedQsExpansion.SHADE;
    assertEquals(PORTRAIT, motion.listPane(stack));
  }

  @Test public void matchesExactProductAndAndroidReleaseWhenModelIsAMarketingName() throws Exception {
    ShadowBuild.setModel("OPPO Reno15 5G");
    ShadowBuild.setProduct("CPH2825");
    assertEquals(STOCK, motion.listPane(stack));
    ShadowBuild.setVersionRelease("16");
    assertEquals(PORTRAIT, motion.listPane(stack));
    ShadowBuild.setVersionRelease("17");
    ShadowBuild.setProduct("CPH28250");
    assertEquals(PORTRAIT, motion.listPane(stack));
  }

  @Test @Config(qualifiers = "land")
  public void renoKeepsPreviousLandscapeRoutingForSingleAndSplitPanes() throws Exception {
    assertEquals(PORTRAIT, motion.listPane(stack));
    qs.split = true;
    assertEquals(SPLIT, motion.listPane(stack));
  }
}
