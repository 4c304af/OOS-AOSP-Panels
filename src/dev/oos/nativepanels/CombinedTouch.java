package dev.oos.nativepanels;

import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Let Android keep the touch target for the full gesture, including parent interception. */
final class CombinedTouch {
  private final PanelRuntime r;
  private Class<?> composeView;
  private Method semantics, all, config, contains, bounds, rowAt;
  private Object click, progress, pager, scrollAxis;
  private Method getProperty, scrollValue, scrollMax, scrollReverse, invokeValue;
  private View scrollHost;
  private long scrollGesture = -1;
  private float scrollX, scrollY, initialScroll, initialMax;
  private boolean reversed, committed, disallowed;
  private int claims, edgePasses;
  private String lastSplit = "none";
  private Field canScrollQs;
  private Method scrollForward;
  private final CollapseDrag collapseDrag = new CollapseDrag();

  private Field left, top, right, bottom, clipPath, scroller;
  private final Rect visible = new Rect();
  private final int[] position = new int[2];
  private int parentDowns, parentPasses;
  private long gesture = -1, frameDown = -1;
  private float frameX, frameY;
  private float startX, startY;
  private boolean vertical;
  private int downs, stackPasses, clipPasses, delivered, cancelled;
  private int lastComposeAction = -1;
  private boolean lastComposeHandled;
  private String last = "none";
  private View host;
  private float downX, downY;
  private boolean atControl, inDown;
  private ShadeDecor owner;

  CombinedTouch(PanelRuntime r) { this.r = r; }

  void install() throws Exception {
    composeView = r.type("androidx.compose.ui.platform.AndroidComposeView");
    semantics = composeView.getMethod("getSemanticsOwner");
    String pkg = "androidx.compose.ui.semantics.";
    Class<?> ownerType = r.type(pkg + "SemanticsOwner");
    all = r.type(pkg + "SemanticsOwnerKt").getMethod("getAllSemanticsNodes", ownerType, boolean.class, boolean.class);
    Class<?> node = r.type(pkg + "SemanticsNode");
    config = node.getMethod("getUnmergedConfig$ui");
    bounds = node.getMethod("getBoundsInWindow");
    contains = r.type(pkg + "SemanticsConfiguration").getMethod("contains", r.type(pkg + "SemanticsPropertyKey"));
    Class<?> actions = r.type(pkg + "SemanticsActions");
    Object actionKeys = actions.getField("INSTANCE").get(null);
    click = actions.getMethod("getOnClick").invoke(actionKeys);
    progress = actions.getMethod("getSetProgress").invoke(actionKeys);
    Class<?> properties = r.type(pkg + "SemanticsProperties");
    pager = properties.getMethod("getHorizontalScrollAxisRange").invoke(properties.getField("INSTANCE").get(null));
    try {
      scrollAxis = properties.getMethod("getVerticalScrollAxisRange").invoke(properties.getField("INSTANCE").get(null));
      getProperty = r.type(pkg + "SemanticsConfiguration").getMethod("get", r.type(pkg + "SemanticsPropertyKey"));
      Class<?> axisType = r.type(pkg + "ScrollAxisRange");
      scrollValue = axisType.getMethod("getValue");
      scrollMax = axisType.getMethod("getMaxValue");
      scrollReverse = axisType.getMethod("getReverseScrolling");
      invokeValue = r.type("kotlin.jvm.functions.Function0").getMethod("invoke");
    } catch (ReflectiveOperationException missing) { scrollAxis = null; r.combinedQs.layout.warn(missing); }
    Class<?> rect = r.type("androidx.compose.ui.geometry.Rect");
    left = rect.getField("left");
    top = rect.getField("top");
    right = rect.getField("right");
    bottom = rect.getField("bottom");
    Class<?> window = r.type("com.android.systemui.shade.NotificationShadeWindowView");
    Class<?> frame = r.type("com.android.systemui.qs.composefragment.FrameLayoutTouchPassthrough");
    try {
      canScrollQs = Reflect.field(frame, "canScrollQs");
      scrollForward = Reflect.method(r.type("com.android.systemui.qs.composefragment.QSFragmentCompose$onCreateView$canScrollQs$1"), "forward");
    } catch (ReflectiveOperationException missing) { r.combinedQs.layout.warn(missing); }
    r.module.hook(window.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class))
        .setId("native-panels:preview-gesture").intercept(c -> {
          MotionEvent e = (MotionEvent)c.getArg(0);
          ShadeDecor target = r.combinedQs.layout.hapticOwner();
          if (target == null || target.frame.getRootView() != ((View)c.getThisObject()).getRootView()) return c.proceed();
          int action = e.getActionMasked();
          if (action == MotionEvent.ACTION_DOWN) {
            r.settingsLaunch.interruptForTouch();
            target.preview.down(e);
            cancelGestures();
            try { prepareSplit(target, (View)c.getThisObject(), e); }
            catch (Exception err) { r.combinedQs.layout.warn(err); }
          } else if (e.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
            cancelGestures();
          } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && owns(e) && !committed) {
            decideSplitDirection(e.getRawX() - scrollX, e.getRawY() - scrollY,
                android.view.ViewConfiguration.get(target.frame.getContext()).getScaledTouchSlop());
          } else if (action == MotionEvent.ACTION_MOVE && collapseDrag.upwards(e)) {
            try {
              if (target != r.combinedQs.layout.touchOwner() || target.frame != collapseDrag.frame) collapseDrag.clear();
              else if (!(Boolean)scrollForward.invoke(canScrollQs.get(target.frame))) collapseDrag.handBack();
            } catch (ReflectiveOperationException missing) { collapseDrag.clear(); r.combinedQs.layout.warn(missing); }
          }
          try { return c.proceed(); }
          finally {
            if (action == MotionEvent.ACTION_DOWN && scrollHost != null && !disallowed) cancelSplitGesture();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
              cancelGestures();
              target.preview.finish(e);
              target.dragging = false;
            }
          }
        });
    clipPath = Reflect.field(frame, "currentClippingPath");
    Class<?> container = r.type("com.android.systemui.shade.NotificationsQuickSettingsContainer");
    scroller = Reflect.field(container, "mStackScroller");
    Class<?> stack = r.type("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout");
    rowAt = stack.getMethod("getChildAtRawPosition", float.class, float.class);

    r.module.hook(container.getMethod("dispatchTouchEvent", MotionEvent.class)).setId("native-panels:qs-gesture").intercept(c -> {
      MotionEvent e = (MotionEvent)c.getArg(0);
      if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return c.proceed();
      ShadeDecor candidate = r.combinedQs.layout.visibleDecor();
      if (candidate == null || !belongs(candidate.frame, (View)c.getThisObject()))
        return c.proceed();
      owner = candidate;
      downs++;
      inDown = true;
      try {
        atControl = false;
        View parent = (View)c.getThisObject();
        parent.getLocationInWindow(position);
        downX = position[0] + e.getX();
        downY = position[1] + e.getY();
        atControl = owns(e) || hitControl(owner, downX, downY);
        last = atControl ? "QS target" : "outside QS controls";
      } catch (Throwable failure) { r.combinedQs.layout.warn(failure); }
      try { return c.proceed(); }
      finally {
        inDown = false; atControl = false; owner = null;
      }
    });

    r.module.hook(stack.getMethod("dispatchTouchEvent", MotionEvent.class)).setId("native-panels:qs-stack-down").intercept(c -> {
      MotionEvent e = (MotionEvent)c.getArg(0);
      if (inDown && atControl && e.getActionMasked() == MotionEvent.ACTION_DOWN) {
        View stackView = (View)c.getThisObject();
        if (owns(e) && owner != null && owner.frame.getRootView() == stackView.getRootView()) {
          stackPasses++;
          return false;
        }
        View parent = stackView.getParent() instanceof View v ? v : null;
        if (parent != null && container.isInstance(parent) && scroller.get(parent) == stackView) {
          View row = (View) rowAt.invoke(stackView, e.getRawX(), e.getRawY());
          // Preserve a visible notification, shelf, or empty-state action in front of QS.
          if (row == null || !row.isShown() || row.getAlpha() <= 0f || !row.getGlobalVisibleRect(visible)
              || !visible.contains((int)e.getRawX(), (int)e.getRawY())) {
            stackPasses++;
            last = "stack yielded";
            return false;
          }
          last = "notification owns point";
        }
      }
      return c.proceed();
    });

    Method framework = Reflect.method(ViewGroup.class, "isTransformedTouchPointInView", float.class, float.class, View.class, PointF.class);
    var baseHit = r.module.getInvoker(framework).setType(io.github.libxposed.api.XposedInterface.Invoker.Type.ORIGIN);
    r.module.hook(frame.getDeclaredMethod("isTransformedTouchPointInView", float.class, float.class, View.class, PointF.class))
        .setId("native-panels:qs-clip-hit").intercept(c -> {
          boolean accepted = (Boolean)c.proceed();
          if (accepted || !inDown || !atControl || owner == null || c.getThisObject() != owner.frame) return accepted;
          View child = (View)c.getArg(2);
          if (owner.frame.getChildCount() == 0 || child != owner.frame.getChildAt(0) || !child.isShown()) return false;
          float x = (Float)c.getArg(0), y = (Float)c.getArg(1);
          Path path = (Path)clipPath.get(owner.frame);
          if (!ownsCurrentDown() && path != null && excludes(path, x, y + owner.frame.getTranslationY())) { last = "drawn clip"; return false; }
          // Use the framework hit test to preserve transforms, bounds and the optional outLocalPoint.
          boolean hit = (Boolean)baseHit.invokeSpecial(owner.frame, x, y, child, c.getArg(3));
          if (hit) { clipPasses++; last = "visible QS outside drawn clip"; }
          return hit;
        });
    r.module.hook(composeView.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class)).setId("native-panels:qs-compose-input").intercept(c -> {
      Object result = c.proceed();
      MotionEvent event = (MotionEvent)c.getArg(0);
      if (event.getActionMasked() == MotionEvent.ACTION_DOWN && Boolean.TRUE.equals(result)) {
        ShadeDecor target = r.combinedQs.layout.touchOwner();
        View receiving = (View)c.getThisObject();
        if (target != null && belongs(receiving, target.frame) && (owns(event) || r.combinedQs.layout.isEditing())
            && receiving.getParent() != null) {
          receiving.getParent().requestDisallowInterceptTouchEvent(true);
          if (owns(event)) disallowed = true;
        }
      }
      if (host != null && c.getThisObject() == host) {
        int action = ((MotionEvent)c.getArg(0)).getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) delivered++;
        if (action == MotionEvent.ACTION_CANCEL) {
          cancelled++;
          ShadeDecor current = r.combinedQs.layout.hapticOwner();
          if (current != null) { current.preview.finish((MotionEvent)c.getArg(0)); current.dragging = false; }
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
          lastComposeAction = action;
          lastComposeHandled = Boolean.TRUE.equals(result);
        }
      }
      return result;
    });
    // The passthrough frame also has its own swipe interceptor, below the QS controller.
    r.module.hook(frame.getDeclaredMethod("onInterceptTouchEvent", MotionEvent.class)).setId("native-panels:qs-frame-input").intercept(c -> {
      MotionEvent e = (MotionEvent)c.getArg(0);
      if (e.getActionMasked() == MotionEvent.ACTION_DOWN && scrollForward != null && e.getPointerCount() == 1) {
        ShadeLayout layout = r.combinedQs.layout;
        ShadeDecor active = layout.touchOwner();
        if (active != null && active.frame == c.getThisObject() && !active.splitLayout()
            && CombinedSettingsLaunch.fullyExpanded(layout.sceneFraction()) && !layout.isEditing())
          collapseDrag.begin(active.frame, e);
      }
      if (owns(e) && e.getActionMasked() == MotionEvent.ACTION_MOVE) return false;
      if (inDown && atControl && owner != null && c.getThisObject() == owner.frame) {
        last = "QS frame received down";
        frameDown = e.getDownTime();
        frameX = e.getX();
        frameY = e.getY();
      }
      if (e.getActionMasked() == MotionEvent.ACTION_MOVE && frameDown == e.getDownTime()) {
        View frameView = (View)c.getThisObject();
        ShadeDecor active = r.combinedQs.layout.visibleDecor();
        int slop = android.view.ViewConfiguration.get(frameView.getContext()).getScaledTouchSlop();
        if (e.getPointerCount() == 1 && active != null && active.frame == frameView
            && !verticalDrag(e.getX() - frameX, e.getY() - frameY, slop)) return false;
        frameDown = -1;
      }
      return c.proceed();
    });
  }

  // The parent QS controller can consume DOWN whenever its expansion fraction is
  // between the endpoints. That happens before NotificationsQuickSettingsContainer.
  // Let it initialize DOWN normally; leave vertical drags under its control.
  void installParent() throws Exception {
    Class<?> type = r.type("com.android.systemui.shade.QuickSettingsControllerImpl");
    Field panel = Reflect.field(type, "mPanelView");
    Field qs = Reflect.field(type, "mQs");
    Method intercept = type.getDeclaredMethod("onIntercept", MotionEvent.class);
    // The parent can be compiled with onIntercept inlined. Keep the call boundary observable.
    try {
      Method shadeIntercept = r.type("com.android.systemui.shade.NotificationPanelViewController$TouchHandler")
          .getDeclaredMethod("onInterceptTouchEvent", MotionEvent.class);
      r.module.deoptimize(shadeIntercept);
      r.module.hook(shadeIntercept).setId("native-panels:split-scroll-owner").intercept(c -> {
        MotionEvent e = (MotionEvent)c.getArg(0);
        if (owns(e) && e.getActionMasked() == MotionEvent.ACTION_MOVE) return false;
        Object result = c.proceed();
        return owns(e) && e.getActionMasked() == MotionEvent.ACTION_DOWN ? false : result;
      });
    } catch (NoSuchMethodException | ClassNotFoundException ignored) { }
    r.module.hook(intercept).setId("native-panels:qs-parent-input").intercept(c -> {
      MotionEvent e = (MotionEvent)c.getArg(0);
      int action = e.getActionMasked();
      if (action == MotionEvent.ACTION_DOWN) {
        gesture = -1;
        vertical = false;
        ShadeDecor target = r.combinedQs.layout.visibleDecor();
        if (target == null || !r.combinedQs.layout.isSelected(qs.get(c.getThisObject()))) return c.proceed();
        View view = (View)panel.get(c.getThisObject());
        if (!belongs(target.frame, view) || e.getPointerCount() != 1) return c.proceed();
        view.getLocationInWindow(position);
        float x = position[0] + e.getX(), y = position[1] + e.getY();
        parentDowns++;
        try {
          if (!owns(e) && !hitControl(target, x, y)) gesture = -1;
          else gesture = e.getDownTime();
        } catch (Exception err) {
          r.combinedQs.layout.warn(err);
          return c.proceed();
        }
        if (gesture < 0) return c.proceed();
        startX = e.getX();
        startY = e.getY();
        boolean intercepted = (Boolean)c.proceed();
        if (intercepted) parentPasses++;
        return false;
      }
      if (owns(e) && action == MotionEvent.ACTION_MOVE) return false;
      if (gesture < 0 || e.getDownTime() != gesture) return c.proceed();
      if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL
          || action == MotionEvent.ACTION_UP) {
        gesture = -1;
        return c.proceed();
      }
      if (action == MotionEvent.ACTION_MOVE && !vertical) {
        View view = (View)panel.get(c.getThisObject());
        int slop = android.view.ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        vertical = verticalDrag(e.getX() - startX, e.getY() - startY, slop);
        if (!vertical && r.combinedQs.layout.gridVisible()) return false;
      }
      return c.proceed();
    });
  }

  private boolean ownsCurrentDown() { return inDown && scrollHost != null; }

  private boolean owns(MotionEvent e) {
    return scrollHost != null && scrollGesture == e.getDownTime();
  }

  private void decideSplitDirection(float dx, float dy, int slop) {
    if (!verticalDrag(dx, dy, slop)) return;
    if (canScroll(initialScroll, initialMax, dy, reversed)) {
      committed = true;
      claims++;
      lastSplit = "scrolled";
    } else {
      edgePasses++;
      lastSplit = "edge";
      cancelSplitGesture();
    }
  }

  static boolean canScroll(float value, float max, float dy, boolean reverse) {
    if (!(max > 0f) || !Float.isFinite(value) || dy == 0f) return false;
    return (dy < 0f) != reverse ? value < max : value > 0f;
  }

  // Only claim the scroll semantics of the active left/right QS pane. In split shade the
  // fragment and notification stack can each have a full-window host on this ROM.
  private void prepareSplit(ShadeDecor target, View window, MotionEvent e) throws Exception {
    if (scrollAxis == null || target != r.combinedQs.layout.touchOwner() || !target.splitLayout()
        || r.combinedQs.layout.isEditing() || e.getPointerCount() != 1) return;
    lastSplit = "outside pane";
    View slot = SplitShadeChrome.qsSlot(target.frame);
    window.getLocationInWindow(position);
    float x = position[0] + e.getX(), y = position[1] + e.getY();
    slot.getLocationInWindow(position);
    if (x < position[0] || x >= position[0] + slot.getWidth() || y < position[1] || y >= position[1] + slot.getHeight()) return;
    if (!SplitShadeChrome.narrow(slot.getWidth(), window.getWidth())
        && ((x < window.getWidth() / 2f) == (slot.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL))) return;
    target.frame.getLocationInWindow(position);
    if (y < position[1] + target.frame.getPaddingTop() + target.gridHeaderHeight()) return;
    if (host == null || !host.isAttachedToWindow() || !belongs(host, target.frame)) host = findHost(target.frame);
    if (host == null) return;
    lastSplit = "no scroll range at point";
    for (Object node : (List<?>)all.invoke(null, semantics.invoke(host), true, true)) {
      Object flags = config.invoke(node);
      if (!(Boolean)contains.invoke(flags, scrollAxis)) continue;
      Object box = bounds.invoke(node);
      if (x < left.getFloat(box) || x >= right.getFloat(box) || y < top.getFloat(box) || y >= bottom.getFloat(box)) continue;
      Object range = getProperty.invoke(flags, scrollAxis);
      float max = ((Number)invokeValue.invoke(scrollMax.invoke(range))).floatValue();
      if (!(max > 0f)) continue;
      initialMax = max;
      initialScroll = ((Number)invokeValue.invoke(scrollValue.invoke(range))).floatValue();
      reversed = (Boolean)scrollReverse.invoke(range);
      scrollX = e.getRawX(); scrollY = e.getRawY();
      lastSplit = "inside scroll region";
      scrollHost = host; scrollGesture = e.getDownTime();
      break;
    }
  }

  void cancelGestures() {
    collapseDrag.clear();
    cancelSplitGesture();
  }

  void cancelSplitGesture() {
    if (disallowed && scrollHost != null && scrollHost.getParent() != null)
      scrollHost.getParent().requestDisallowInterceptTouchEvent(false);
    scrollGesture = -1; scrollHost = null; committed = disallowed = false;
  }

  static boolean verticalDrag(float dx, float dy, int slop) {
    return Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx);
  }

  // Observe the original single-pointer stream at the window, before its parents
  // decide whether to intercept. A vertical scroll may reach the end mid-gesture.
  static final class CollapseDrag {
    private ViewGroup frame;
    private long downTime;
    private float x, y, lastY;
    private int slop;
    private boolean vertical;

    void begin(ViewGroup frame, MotionEvent down) {
      this.frame = frame;
      slop = android.view.ViewConfiguration.get(frame.getContext()).getScaledTouchSlop();
      downTime = down.getDownTime();
      x = down.getRawX();
      lastY = y = down.getRawY();
      vertical = false;
    }

    boolean upwards(MotionEvent move) {
      if (frame == null || move.getDownTime() != downTime || move.getPointerCount() != 1) return false;
      float dx = move.getRawX() - x, dy = move.getRawY() - y;
      boolean up = move.getRawY() < lastY;
      lastY = move.getRawY();
      if (!vertical) {
        if (Math.abs(dx) > slop && Math.abs(dx) >= Math.abs(dy)) { clear(); return false; }
        vertical = verticalDrag(dx, dy, slop);
      }
      return vertical && dy < -slop && up;
    }

    void handBack() {
      // Compose can disallow interception through the frame, and the ROM frame
      // can also disallow it directly on its parent at DOWN.
      frame.requestDisallowInterceptTouchEvent(false);
      if (frame.getParent() != null) frame.getParent().requestDisallowInterceptTouchEvent(false);
      clear();
    }

    void clear() { frame = null; }
  }

  static boolean excludes(Path cutout, float x, float y) {
    RectF extent = new RectF();
    cutout.computeBounds(extent, true);
    if (!extent.contains(x,y)) return false;
    Rect bounds = new Rect();
    extent.roundOut(bounds);
    Region region = new Region();
    region.setPath(cutout, new Region(bounds));
    return region.contains((int)Math.floor(x), (int)Math.floor(y));
  }

  private boolean belongs(View descendant, View parent) {
    for (View v = descendant; v != null; v = v.getParent() instanceof View p ? p : null)
      if (v == parent) return true;
    return false;
  }

  private View findHost(View view) {
    if (composeView.isInstance(view)) return view;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View candidate = findHost(group.getChildAt(i));
        if (candidate != null) return candidate;
      }
    return null;
  }

  private boolean hitControl(ShadeDecor o, float x, float y) throws Exception {
    if (!o.frame.isShown() || !o.frame.isAttachedToWindow() || r.combinedQs.layout.isEditing()) return false;
    if (o.hitClock(x, y)) return true;
    // On keyguard only the clock uses this routing. Its native action handles unlocking.
    if (o != r.combinedQs.layout.touchOwner()) return false;
    if (host == null || !host.isAttachedToWindow() || !belongs(host, o.frame)) host = findHost(o.frame);
    if (host == null) return false;
    if (o.hitControl(x, y)) return true;
    // The full-height Compose host also covers the notification stack. Use placed control bounds.
    Object tree = semantics.invoke(host);
    for (Object n : (List<?>)all.invoke(null, tree, true, true)) {
      Object box = bounds.invoke(n);
      if (x < left.getFloat(box) || y < top.getFloat(box) || x >= right.getFloat(box) || y >= bottom.getFloat(box)) continue;
      Object flags = config.invoke(n);
      if ((Boolean)contains.invoke(flags, click) || (Boolean)contains.invoke(flags, progress) || (Boolean)contains.invoke(flags, pager)) return true;
    }
    return false;
  }

  void reset() { cancelGestures(); claims = edgePasses = 0; gesture = frameDown = -1; parentDowns = parentPasses = 0; host = null; owner = null; atControl = false; inDown = false; downs = stackPasses = clipPasses = delivered = cancelled = 0; last = "none"; lastComposeAction = -1; }
  String report() { return "QS touch: splitScroll=" + claims + " splitEdgePass=" + edgePasses + " splitLast=" + lastSplit + " range=" + initialScroll + "/" + initialMax + " parentDown=" + parentDowns + " parentPass=" + parentPasses + " downs=" + downs + " stackPass=" + stackPasses + " clipPass=" + clipPasses + " last=" + last + " composeDown=" + delivered + " cancel=" + cancelled + " composeLast=" + (lastComposeAction < 0 ? "none" : MotionEvent.actionToString(lastComposeAction) + "/" + lastComposeHandled); }
}
