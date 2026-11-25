package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.ref.WeakReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PortraitNotificationClipTest {
  public static class QsFragment extends Fragment {
    public boolean isEditing() { return false; }
  }
  static final class Controller {
    QsFragment fragment = new QsFragment();
    FrameLayout shadePanel;
    Stack listController;
    float shadeFraction = .5f, shadeHeight = 120f, transition;
    int bar;
    boolean dozing, split, policy = true;
  }
  static final class Stack extends FrameLayout {
    Stack(Context context) { super(context); }
    Stack getView() { return this; }
    Stack panel = this;
    boolean pinned, headsUpAway, fromHeadsUp, bouncer, launchingNotification;
    boolean isHeadsUpTransition() { return false; }
  }

  final Controller controller = new Controller();
  final PortraitNotificationClip clip = new PortraitNotificationClip();
  FrameLayout qs;
  Stack stack;
  ShadePullMotion motion;
  ShadeDecor decor;

  @Before public void setUp() throws Exception {
    Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
    FrameLayout panel = new FrameLayout(activity);
    activity.setContentView(panel);
    qs = new FrameLayout(activity);
    stack = new Stack(activity);
    panel.addView(qs);
    panel.addView(stack);
    panel.layout(0, 0, 200, 300);
    qs.layout(0, 0, 200, 200);
    stack.layout(0, 40, 200, 300);
    controller.shadePanel = panel;
    controller.listController = stack;

    PanelRuntime runtime = new PanelRuntime(null, getClass().getClassLoader());
    ShadeLayout layout = runtime.combinedQs.layout;
    layout.select(controller.fragment);
    decor = new ShadeDecor(qs, layout, runtime);
    Reflect.field(ShadeLayout.class, "decor").set(layout, new WeakReference<>(decor));
    Reflect.field(ShadeLayout.class, "editing").set(layout, Reflect.method(QsFragment.class, "isEditing"));
    motion = new ShadePullMotion(runtime);
    bind(Controller.class, "fragment", "bar", "dozing", "transition", "policy", "split", "listController",
        "shadePanel", "shadeHeight", "shadeFraction");
    bind(Stack.class, "panel", "pinned", "headsUpAway", "fromHeadsUp", "bouncer", "launchingNotification");
    Reflect.field(ShadePullMotion.class, "listView").set(motion, Reflect.method(Stack.class, "getView"));
    Reflect.field(ShadePullMotion.class, "headsUp").set(motion, Reflect.method(Stack.class, "isHeadsUpTransition"));
  }

  private void bind(Class<?> type, String... names) throws Exception {
    for (String name : names) Reflect.field(ShadePullMotion.class, name).set(motion, Reflect.field(type, name));
  }

  private void refresh() { motion.updateNotificationClip(controller, qs, clip); }

  @Test public void pendingNativeRowsAndSectionHeadersFollowCollapseAndImmediateReverse() {
    // The host still draws two opaque children at the same positions on every frame.
    assertPixels(240, .8f, true, true);
    assertPixels(120, .4f, true, false);
    assertPixels(45, .15f, false, false);
    qs.setVisibility(View.INVISIBLE);
    assertPixels(120, .4f, false, false);
    assertPixels(0, 0f, false, false);
    qs.setVisibility(View.VISIBLE);
    assertPixels(120, .4f, true, false);
    assertPixels(240, .8f, true, true);
    assertPixels(200, 1f, true, true);
    assertFalse(clip.appliesTo(stack));
  }

  private void assertPixels(int height, float fraction, boolean headerVisible, boolean rowVisible) {
    controller.shadeHeight = height;
    controller.shadeFraction = fraction;
    refresh();
    Bitmap image = Bitmap.createBitmap(200, 260, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(image);
    int save = canvas.save();
    if (clip.appliesTo(stack)) clip.clip(canvas);
    android.graphics.Paint paint = new android.graphics.Paint();
    paint.setColor(Color.WHITE);
    canvas.drawRect(0, 20, 200, 45, paint);
    canvas.drawRect(0, 100, 200, 150, paint);
    canvas.restoreToCount(save);
    assertEquals(headerVisible ? Color.WHITE : 0, image.getPixel(50, 35));
    assertEquals(rowVisible ? Color.WHITE : 0, image.getPixel(50, 125));
    assertEquals(1f, stack.getAlpha(), 0f);
    assertNull(stack.getClipBounds());
  }

  @Test public void nativeVisibilityTakesOverAtTheSameHeightWhenContextChanges() {
    refresh();
    assertTrue(clip.appliesTo(stack));
    controller.bar = CombinedQsExpansion.KEYGUARD;
    refresh();
    assertFalse(clip.appliesTo(stack));
    controller.bar = CombinedQsExpansion.SHADE;
    stack.pinned = true;
    refresh();
    assertFalse(clip.appliesTo(stack));
    stack.pinned = false;
    controller.dozing = true;
    refresh();
    assertFalse(clip.appliesTo(stack));
    controller.dozing = false;
    stack.bouncer = true;
    refresh();
    assertFalse(clip.appliesTo(stack));
    stack.bouncer = false;
    stack.launchingNotification = true;
    refresh();
    assertFalse(clip.appliesTo(stack));
    stack.launchingNotification = false;
    controller.split = true;
    refresh();
    assertFalse(clip.appliesTo(stack));
    controller.split = false;
    refresh();
    assertTrue(clip.appliesTo(stack));
    controller.shadeHeight = Float.NaN;
    refresh();
    assertFalse(clip.appliesTo(stack));
  }

  @Test @Config(qualifiers = "land")
  public void landscapeNeverUsesThePortraitClipEvenIfRomReportsSinglePane() {
    refresh();
    assertFalse(clip.appliesTo(stack));
  }

  @Test public void transformedStackUsesLocalCoordinatesAndRetainsNativeClipping() {
    stack.setTranslationY(10);
    stack.setPivotY(0);
    stack.setScaleY(2f);
    refresh();
    Canvas canvas = new Canvas(Bitmap.createBitmap(200, 260, Bitmap.Config.ARGB_8888));
    canvas.clipRect(10, 12, 110, 220);
    clip.clip(canvas);
    assertEquals(new android.graphics.Rect(10, 12, 110, 35), canvas.getClipBounds());
  }
}
