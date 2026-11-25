package dev.oos.nativepanels;

import static dev.oos.nativepanels.ShadePullMotion.Pane.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
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
    boolean mIsExpanded, mIsBeingDragged, mLaunchingNotification;
    View mForcedScroll;
    final Scroller mScroller = new Scroller();
    int ownScrollY, resets;
    int getOwnScrollY() { return ownScrollY; }
    void resetScrollPosition() { ownScrollY = 0; mScroller.running = false; resets++; }
    Stack getView() { return this; }
    boolean isHeadsUpTransition() { return false; }
  }
  static class Scroller {
    boolean running;
    boolean isFinished() { return !running; }
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

  private void expandStack(boolean expanded) throws Throwable {
    XposedInterface.Chain chain = (XposedInterface.Chain)Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> switch (method.getName()) {
          case "getThisObject" -> stack;
          case "getArg" -> expanded;
          case "proceed" -> { stack.mIsExpanded = expanded; yield null; }
          default -> throw new AssertionError(method);
        });
    motion.openingScrollHook(Stack.class).intercept(chain);
  }

  @Test public void newPullRevealsCompactQsAndMediaAfterLongListWasScrolled() throws Throwable {
    // The ROM's closing setter can leave the previous scroll offset in place.
    stack.mIsExpanded = true;
    stack.ownScrollY = 1188;
    expandStack(false);
    assertEquals(1188, stack.ownScrollY);
    expandStack(true);
    assertEquals(0, stack.ownScrollY);
    assertEquals(STOCK, motion.listPane(stack));
    assertEquals(" openingListScroll(before/after)=1188/0", motion.openingScrollReport());
    stack.ownScrollY = 420;
    expandStack(true); // another height update in the same pull
    assertEquals(420, stack.ownScrollY);
    expandStack(false);
    expandStack(true);
    assertEquals(2, stack.resets);
    expandStack(false);
    expandStack(true); // a short list already starts at the top
    assertEquals(2, stack.resets);
  }

  @Test public void preservesIntentionalScrollAndExcludedShadeTransitions() throws Throwable {
    stack.ownScrollY = 800;
    stack.mForcedScroll = stack;
    expandStack(true);
    expandStack(false);
    stack.mForcedScroll = null;
    stack.mIsBeingDragged = true;
    expandStack(true);
    expandStack(false);
    stack.mIsBeingDragged = false;
    stack.mLaunchingNotification = true;
    expandStack(true);
    expandStack(false);
    stack.mLaunchingNotification = false;
    stack.pinned = true;
    expandStack(true);
    expandStack(false);
    stack.pinned = false;
    qs.bar = CombinedQsExpansion.KEYGUARD;
    expandStack(true);
    expandStack(false);
    qs.bar = CombinedQsExpansion.SHADE;
    qs.split = true;
    expandStack(true);
    expandStack(false);
    qs.split = false;
    ShadowBuild.setModel("CPH2653");
    ShadowBuild.setProduct("CPH2653");
    expandStack(true);
    assertEquals(800, stack.ownScrollY);
    assertEquals(0, stack.resets);
  }

  @Test public void unfinishedScrollAtZeroCannotAdvanceTheNextPull() throws Throwable {
    stack.mScroller.running = true;
    expandStack(true);
    assertEquals(1, stack.resets);
    assertFalse(stack.mScroller.running);
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
  public void renoKeepsPreviousLandscapeRoutingForSingleAndSplitPanes() throws Throwable {
    stack.ownScrollY = 800;
    expandStack(true);
    assertEquals(800, stack.ownScrollY);
    assertEquals(PORTRAIT, motion.listPane(stack));
    qs.split = true;
    assertEquals(SPLIT, motion.listPane(stack));
  }
}
