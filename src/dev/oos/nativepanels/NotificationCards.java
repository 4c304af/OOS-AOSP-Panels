package dev.oos.nativepanels;

import android.app.Notification;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Uses the shade's own background renderer and the existing framework RemoteViews. */
final class NotificationCards {
  private static final String ROW = "com.android.systemui.statusbar.notification.row.";
  private final Map<View, Card> rows = new IdentityHashMap<>();
  private final Set<ViewGroup> rowParents = Collections.newSetFromMap(new IdentityHashMap<>());
  private static final int JOIN_TOP = 1, JOIN_BOTTOM = 2;
  private int peakSeams;
  private Method rowTranslation;
  private Field inShelf, removing;
  private Card paintedRow;
  private int replacedThisPull, boundPasses;
  private String lastPaint = "none";
  private Method actualHeight, bgHeight, bgWidth, alignRight, clipTop, clipBottom;
  private Method gutsExposed, launchRunning, topRadius, bottomRadius;
  private Field appearing, topOverlap, bottomOverlap, bottomRounded, bottomClips;
  // Rows can move between notification hosts while remaining in the same shade window.
  private final Set<View> attached = Collections.newSetFromMap(new WeakHashMap<>());
  private View shadeRoot;
  private int attachEvents, rowDraws, scannedRows, matchedPeak, visiblePeak, drawsThisPull;
  private Class<?> rowType;
  private Method childrenContainer, childrenExpanded, expansionChanging;
  private Field groupHidesBackground, userExpanding;
  private Method groupChild, groupSummary, headsUp, entry, sbn, privateLayout, contracted, expanded;
  private Method entryAdapter, adapterSbn, adapterColorized, adapterBundle, notificationColorized, currentTint, showingLayout, contentTint;
  enum Surface {
    STANDARD, OUTER_ONLY, COLORIZED, UNBOUND, BUNDLE, SUMMARY, CALL, MEDIA, WAITING_FOR_TINT;
    boolean usesShadeBackground() {
      return this == STANDARD || this == OUTER_ONLY || this == BUNDLE || this == SUMMARY;
    }
  }
  private final EnumSet<Surface> seenSurfaces = EnumSet.noneOf(Surface.class);
  private Field onKeyguard, showingPublic, background, corners, drawableAlpha;
  private int outerMargin, columnId, templateId, smartReplyId, normalToken, lightToken, darkToken;
  private boolean ready;
  private int color, lastCount, lastTemplates, lastModified, drawCount, commonRows, restrictedRows, otherStyles;
  private int collapsedLayout, expandedLayout, bigTextLayout;
  private boolean enabled;
  private String problem = "";

  void install(PanelRuntime r) throws ReflectiveOperationException {
    rowType = r.type(ROW + "ExpandableNotificationRow");
    groupChild = rowType.getMethod("isChildInGroup");
    groupSummary = rowType.getMethod("isSummaryWithChildren");
    childrenContainer = rowType.getMethod("getChildrenContainer");
    childrenExpanded = rowType.getMethod("areChildrenExpanded");
    expansionChanging = rowType.getMethod("isGroupExpansionChanging");
    groupHidesBackground = Reflect.field(rowType, "mShowNoBackground");
    userExpanding = Reflect.field(rowType, "mIsUserSwipingToExpandRow");
    headsUp = rowType.getMethod("isHeadsUpState");
    entryAdapter = optional(rowType, "getEntryAdapter");
    if (entryAdapter != null) {
      Class<?> adapter = entryAdapter.getReturnType();
      adapterSbn = optional(adapter, "getSbn");
      adapterColorized = optional(adapter, "isColorized");
      adapterBundle = optional(adapter, "isBundle");
    }
    entry = optional(rowType, "getEntry");
    if (entry != null) sbn = optional(entry.getReturnType(), "getSbn");
    if (adapterSbn == null && sbn == null) throw new NoSuchMethodException("notification entry getSbn");
    notificationColorized = optional(Notification.class, "isColorized");
    currentTint = optional(rowType, "getCurrentBackgroundTint");
    showingLayout = optional(rowType, "getShowingLayout");
    if (showingLayout != null) contentTint = optional(showingLayout.getReturnType(), "getBackgroundColorForExpansionState");
    privateLayout = rowType.getMethod("getPrivateLayout");
    contracted = privateLayout.getReturnType().getMethod("getContractedChild");
    expanded = privateLayout.getReturnType().getMethod("getExpandedChild");
    showingPublic = Reflect.field(rowType, "mShowingPublic");
    onKeyguard = Reflect.field(rowType, "mOnKeyguard");
    background = Reflect.field(rowType, "mBackgroundNormal");
    Class<?> bg = background.getType();
    corners = Reflect.field(bg, "mCornerRadii");
    drawableAlpha = Reflect.field(bg, "mDrawableAlpha");
    actualHeight = rowType.getMethod("getActualHeight");
    rowTranslation = optional(rowType, "getTranslation");
    inShelf = Reflect.optionalField(rowType, "mInShelf");
    removing = Reflect.optionalField(rowType, "mInRemovalAnimation");
    clipTop = rowType.getMethod("getClipTopAmount");
    clipBottom = rowType.getMethod("getClipBottomAmount");
    bgWidth = bg.getMethod("getActualWidth");
    bgHeight = bg.getMethod("getActualHeight");
    alignRight = Reflect.method(bg, "isAlignedToRight");
    topOverlap = Reflect.optionalField(bg, "mTopOverlap");
    bottomOverlap = Reflect.optionalField(bg, "mBottomOverlap");
    bottomRounded = Reflect.optionalField(bg, "mBottomIsRounded");
    bottomClips = Reflect.optionalField(bg, "mBottomAmountClips");
    topRadius = rowType.getMethod("getTopCornerRadius");
    bottomRadius = rowType.getMethod("getBottomCornerRadius");
    gutsExposed = rowType.getMethod("areGutsExposed");
    launchRunning = rowType.getMethod("isExpandAnimationRunning");
    appearing = Reflect.optionalField(rowType, "mDrawingAppearAnimation");
    // Only suppress the original child during a pass where its replacement was drawn.
    Method drawChild = Reflect.method(r.type(ROW + "ExpandableOutlineView"), "drawChild", Canvas.class, View.class, long.class);
    r.module.hook(drawChild).setId("native-panels:notification-background-child")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          Card card = paintedRow;
          if (card != null && card.row == c.getThisObject() && card.bg == c.getArg(1)) {
            replacedThisPull++;
            return false;
          }
          return c.proceed();
        });
    r.module.hook(rowType.getDeclaredMethod("onAttachedToWindow"))
        .setId("native-panels:notification-row-attach")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          Object result = c.proceed();
          View row = (View)c.getThisObject();
          attachEvents++;
          attached.add(row);
          return result;
        });
    r.module.hook(Reflect.method(rowType, "onDetachedFromWindow"))
        .setId("native-panels:notification-row-detach")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          View view = (View)c.getThisObject();
          if (rowType.isInstance(view)) {
            attached.remove(view);
            Card card = rows.remove(view);
            if (card != null) remove(card);
          }
          return c.proceed();
        });
    Method rowDraw = rowType.getDeclaredMethod("dispatchDraw", Canvas.class);
    r.module.hook(rowDraw)
        .setId("native-panels:notification-row-draw")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(c -> {
          View row = (View)c.getThisObject();
          Card outer = paintedRow;
          paintedRow = null;
          try {
            if (shadeRoot != null && row.getRootView() == shadeRoot) {
              rowDraws++;
              if (attached.add(row)) shadeRoot.postInvalidateOnAnimation();
              Card card = rows.get(row);
              if (card != null) {
                boundPasses++;
                lastPaint = "transition or restricted";
                try {
                  if (!restricted(row) && !(boolean)gutsExposed.invoke(row) && !(boolean)launchRunning.invoke(row)
                      && (appearing == null || !appearing.getBoolean(row)) && card.drawInRow((Canvas)c.getArg(0))) {
                    lastPaint = "painted";
                    paintedRow = card;
                    drawCount++;
                    drawsThisPull++;
                  }
                } catch (ReflectiveOperationException | RuntimeException e) { lastPaint = e.toString(); }
              }
            }
            return c.proceed();
          } finally { paintedRow = outer; }
        });
    ready = true;
  }

  private boolean restricted(View row) throws ReflectiveOperationException {
    if (onKeyguard.getBoolean(row) || showingPublic.getBoolean(row) || (boolean)headsUp.invoke(row)
        || (boolean)groupChild.invoke(row)) return true;
    if (!(boolean)groupSummary.invoke(row)) return false;
    return !ownsCollapsedGroup((boolean)childrenExpanded.invoke(row), (boolean)expansionChanging.invoke(row),
        userExpanding.getBoolean(row), groupHidesBackground.getBoolean(row));
  }

  // OOS hides the parent's background during split animations and for system bundles
  // whose header paints its own opaque surface.
  static boolean ownsCollapsedGroup(boolean expanded, boolean changing, boolean dragging, boolean hidesBackground) {
    return !expanded && !changing && !dragging && !hidesBackground;
  }

  // A collapsed group can retain the expanded background height or a square OEM edge.
  // Fit the replacement to the summary's own bounds before clipping the row for scrolling.
  static int summaryHeight(int rowHeight, int anchorTop, int overlap) {
    return Math.max(0, rowHeight - anchorTop - Math.max(0, overlap));
  }

  static boolean supportedTemplate(String style) {
    return style == null || style.isEmpty() || Notification.BigTextStyle.class.getName().equals(style);
  }

  private static Method optional(Class<?> type, String name) {
    try { return type.getMethod(name); }
    catch (NoSuchMethodException absent) { return null; }
  }

  static Surface surface(String style, String category, boolean summary, boolean colorized) {
    if (Notification.CATEGORY_CALL.equals(category) || "android.app.Notification$CallStyle".equals(style))
      return Surface.CALL;
    if ("android.app.Notification$MediaStyle".equals(style) ||
        "android.app.Notification$DecoratedMediaCustomViewStyle".equals(style)) return Surface.MEDIA;
    if (colorized) return Surface.COLORIZED;
    if (summary) return Surface.SUMMARY;
    return supportedTemplate(style) ? Surface.STANDARD : Surface.OUTER_ONLY;
  }

  private Surface surfaceForRow(View row) throws ReflectiveOperationException {
    Object adapter = entryAdapter == null ? null : entryAdapter.invoke(row);
    if (adapter != null && adapterBundle != null && (boolean)adapterBundle.invoke(adapter)) return Surface.BUNDLE;
    StatusBarNotification status = adapter == null || adapterSbn == null ? null : (StatusBarNotification)adapterSbn.invoke(adapter);
    if (status == null && sbn != null) {
      Object legacy = entry.invoke(row);
      if (legacy != null) status = (StatusBarNotification)sbn.invoke(legacy);
    }
    Notification n = status == null ? null : status.getNotification();
    if (n == null) return Surface.UNBOUND;
    String style = n.extras == null ? null : n.extras.getString(Notification.EXTRA_TEMPLATE);
    boolean requested = n.extras != null && n.extras.getBoolean(Notification.EXTRA_COLORIZED);
    boolean colorized = requested;
    if (requested) {
      if (adapter != null && adapterColorized != null) colorized = (boolean)adapterColorized.invoke(adapter);
      else if (notificationColorized != null) colorized = (boolean)notificationColorized.invoke(n);
    }
    return surface(style, n.category, (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0, colorized);
  }

  void setEnabled(boolean value) { enabled = value; }

  private int id(Resources res, String name) { return res.getIdentifier(name, "id", "android"); }

  void update(View frame) {
    if (!ready || !enabled) return;
    try {
      if (shadeRoot != frame.getRootView()) {
        clear();
        shadeRoot = frame.getRootView();
        scannedRows = Math.max(scannedRows, discover(shadeRoot));
        Resources res = frame.getResources();
        columnId = id(res, "notification_main_column");
        templateId = id(res, "status_bar_latest_event_content");
        collapsedLayout = res.getIdentifier("notification_2025_template_collapsed_base", "layout", "android");
        expandedLayout = res.getIdentifier("notification_2025_template_expanded_base", "layout", "android");
        bigTextLayout = res.getIdentifier("notification_2025_template_expanded_big_text", "layout", "android");
        smartReplyId = id(res, "smart_reply_container");
        normalToken = res.getIdentifier("customColorSurfaceEffect1", "color", "android");
        darkToken = res.getIdentifier("system_surface_container_dark", "color", "android");
        lightToken = res.getIdentifier("system_surface_container_light", "color", "android");
        outerMargin = res.getIdentifier("notification_2025_margin", "dimen", "android");
      }
      Context ctx = frame.getContext();
      boolean night = (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
      WindowManager wm = ctx.getSystemService(WindowManager.class);
      boolean transparent = wm != null && wm.isCrossWindowBlurEnabled();
      int id = transparent ? normalToken : night ? darkToken : lightToken;
      if (id == 0) { clear(); problem = "missing notification surface color"; return; }
      int resolved = ctx.getColor(id);
      color = transparent && Color.alpha(resolved) == 255 ? (resolved & 0xffffff) | 0x8a000000 : resolved;
      for (Card c : rows.values()) c.present = false;
      int matched = 0, shown = 0, accepted = 0, excluded = 0, styles = 0, templates = 0, modified = 0;
      problem = "";
      for (View row : attached) {
        if (!row.isAttachedToWindow() || row.getRootView() != shadeRoot) continue;
        matched++;
        if (!row.isShown()) continue;
        shown++;
        try {
          if (restricted(row)) { excluded++; continue; }
          Surface surface = surfaceForRow(row);
          seenSurfaces.add(surface);
          int fill = color;
          if (surface == Surface.COLORIZED) {
            fill = currentTint == null ? 0 : (int)currentTint.invoke(row);
            if (Color.alpha(fill) == 0 && contentTint != null) {
              Object content = showingLayout.invoke(row);
              if (content != null) fill = (int)contentTint.invoke(content);
            }
            if (Color.alpha(fill) == 0) { seenSurfaces.add(Surface.WAITING_FOR_TINT); styles++; continue; }
          } else if (!surface.usesShadeBackground()) {
            styles++; continue;
          }
          accepted++;
          View bg = (View)background.get(row);
          if (bg == null) continue;
          View small = null, large = null;
          if (surface == Surface.STANDARD && !(boolean)groupSummary.invoke(row)) {
            Object layout = privateLayout.invoke(row);
            if (layout != null) {
              small = findTemplate((View)contracted.invoke(layout), true);
              large = findTemplate((View)expanded.invoke(layout), false);
            }
          }
          Card c = rows.get(row);
          if (c != null && c.bg != bg) { remove(c); rows.remove(row); c = null; }
          if (c == null) {
            c = new Card(row, bg);
            rows.put(row, c);
            row.invalidate();
          }
          c.present = true;
          c.update(small, large, fill);
          if (small != null || large != null) templates++;
          for (Inset inset : c.insets) if (inset.owned) modified++;
        } catch (ReflectiveOperationException | RuntimeException e) {
          problem = e.toString();
        }
      }
      for (Iterator<Card> it = rows.values().iterator(); it.hasNext();) {
        Card c = it.next(); if (!c.present) { remove(c); it.remove(); }
      }
      updateJoinedCorners();
      matchedPeak = Math.max(matchedPeak, matched);
      visiblePeak = Math.max(visiblePeak, shown);
      lastCount = Math.max(lastCount, rows.size());
      commonRows = Math.max(commonRows, accepted);
      restrictedRows = Math.max(restrictedRows, excluded);
      otherStyles = Math.max(otherStyles, styles);
      lastTemplates = Math.max(lastTemplates, templates);
      lastModified = Math.max(lastModified, modified);
    } catch (RuntimeException e) { clear(); problem = e.toString(); }
  }

  private int discover(View view) {
    if (rowType.isInstance(view)) {
      attached.add(view);
      // Group children also receive their own attach and draw callbacks.
      return 1;
    }
    int found = 0;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) found += discover(group.getChildAt(i));
    return found;
  }

  // Children are positioned with translations in the notification stack. A new header,
  // an unstyled row or an actual section gap breaks the run even if the list is reusing views.
  private void updateJoinedCorners() {
    rowParents.clear();
    int seams = 0;
    for (Card card : rows.values()) {
      card.nextJoined = 0;
      if (card.row.getParent() instanceof ViewGroup parent) rowParents.add(parent);
    }
    for (ViewGroup parent : rowParents) {
      Card previous = null;
      for (int i = 0; i < parent.getChildCount(); i++) {
        View child = parent.getChildAt(i);
        if (child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0f || child.getHeight() == 0) continue;
        Card current = rows.get(child);
        if (current == null || !current.measureStackPosition()) { previous = null; continue; }
        if (previous != null && sameSeam(previous.stackBottom, current.stackTop, previous.stackX, current.stackX,
            previous.row.getWidth(), child.getWidth(), current.joinGap, current.alignmentTolerance)
            && previous.bottomClip <= 0 && current.topClip <= 0) {
          previous.nextJoined |= JOIN_BOTTOM;
          current.nextJoined |= JOIN_TOP;
          seams++;
        }
        previous = current;
      }
    }
    rowParents.clear();
    peakSeams = Math.max(peakSeams, seams);
    for (Card card : rows.values()) {
      if (card.joined != card.nextJoined) {
        card.joined = card.nextJoined;
        card.row.invalidate();
      }
    }
  }

  static boolean sameSeam(float upperBottom, float lowerTop, float upperX, float lowerX,
      int upperWidth, int lowerWidth, int maxGap, int tolerance) {
    float gap = lowerTop - upperBottom;
    return gap >= -tolerance && gap <= maxGap + 1 && Math.abs(upperX - lowerX) <= tolerance
        && upperWidth > 0 && Math.abs(upperWidth - lowerWidth) <= tolerance;
  }

  void beginPull() {
    peakSeams = 0;
    boundPasses = replacedThisPull = 0;
    lastPaint = "none";
    seenSurfaces.clear();
    scannedRows = matchedPeak = visiblePeak = drawsThisPull = rowDraws = 0;
    lastCount = commonRows = restrictedRows = otherStyles = lastTemplates = lastModified = 0;
  }

  private View findTemplate(View root, boolean small) {
    if (root == null) return null;
    if (matchesTemplate(root, small)) return root;
    // Some hosts wrap the inflated RemoteViews in a container.
    View nested = templateId == 0 ? null : root.findViewById(templateId);
    return nested != null && nested != root && matchesTemplate(nested, small) ? nested : null;
  }

  private boolean matchesTemplate(View v, boolean small) {
    Object tag = v.getTag(android.R.id.widget_frame);
    if (tag instanceof Integer boundLayout && boundLayout != 0)
      return matchesLayoutId(small, boundLayout, collapsedLayout, expandedLayout, bigTextLayout);
    if (templateId == 0 || v.getId() != templateId) return false;
    Object type = v.getTag();
    return small ? "base".equals(type) : "big".equals(type) || "bigText".equals(type);
  }

  static boolean matchesLayoutId(boolean collapsed, int boundId, int base, int big, int text) {
    return boundId != 0 && (collapsed ? boundId == base : boundId == big || boundId == text);
  }

  private void remove(Card c) {
    c.restore(); c.ripple.jumpToCurrentState(); c.ripple.setCallback(null); c.row.invalidate(); c.bg.invalidate();
  }

  void clear() {
    for (Card c : rows.values()) remove(c);
    rows.clear(); shadeRoot = null;
  }

  String report() {
    return (ready ? "enabled=" + enabled + " active=" + rows.size() + " peak=" + lastCount +
        " attachedEvents=" + attachEvents + " scanned=" + scannedRows + " matched=" + matchedPeak + " shown=" + visiblePeak +
        " rowDraws=" + rowDraws + " seamsPeak=" + peakSeams +
        " eligible=" + commonRows + " templates=" + lastTemplates + " insets=" + lastModified +
        " draws=" + drawCount + " drawsThisPull=" + drawsThisPull + " replacedChild=" + replacedThisPull + " boundPasses=" + boundPasses + " lastPaint=" + lastPaint + " restricted=" + restrictedRows + " other=" + otherStyles +
        " kinds=" + seenSurfaces + " tint=#" + Integer.toHexString(color) : "unavailable") + (problem.isEmpty() ? "" : " " + problem);
  }

  private final class Card {
    final View row, bg;
    final LayerDrawable art;
    final GradientDrawable base;
    final RippleDrawable ripple;
    final Drawable.Callback callback;
    final float[] radii = new float[8];
    final ArrayList<Inset> insets = new ArrayList<>();
    View small, large;
    int tint;
    final int innerRadius, outerRadius, joinGap, alignmentTolerance;
    int joined, nextJoined, topClip, bottomClip;
    float stackTop, stackBottom, stackX;
    boolean present;

    Card(View row, View bg) throws ReflectiveOperationException {
      this.row = row; this.bg = bg;
      Resources res = row.getResources();
      int shape = res.getIdentifier("notification_material_bg", "drawable", row.getContext().getPackageName());
      Drawable stock = shape == 0 ? null : row.getContext().getDrawable(shape);
      if (stock != null && stock.mutate() instanceof LayerDrawable layers && layers.getNumberOfLayers() > 0 &&
          layers.getDrawable(0) instanceof GradientDrawable fill) {
        art = layers;
        base = fill;
      } else {
        // Resource overlays can replace the layer-list with another drawable type.
        base = new GradientDrawable();
        art = new LayerDrawable(new Drawable[]{base});
      }
      int rippleId = res.getIdentifier("notification_ripple_untinted_color", "color", row.getContext().getPackageName());
      int ink = rippleId != 0 ? row.getContext().getColor(rippleId) : 0x20000000;
      ripple = new RippleDrawable(ColorStateList.valueOf(ink), art, null);
      callback = new Drawable.Callback() {
        public void invalidateDrawable(Drawable who) { row.invalidate(); }
        public void scheduleDrawable(Drawable who, Runnable what, long when) {
          if (bg.getHandler() != null) bg.getHandler().postAtTime(what, who, when);
        }
        public void unscheduleDrawable(Drawable who, Runnable what) { bg.removeCallbacks(what); }
      };
      ripple.setCallback(callback);
      int innerId = res.getIdentifier("notification_corner_radius_small", "dimen", row.getContext().getPackageName());
      innerRadius = innerId == 0 ? dp(4) : res.getDimensionPixelSize(innerId);
      int gapId = res.getIdentifier("notification_divider_height", "dimen", row.getContext().getPackageName());
      joinGap = gapId == 0 ? dp(8) : Math.max(dp(4), Math.min(dp(8), res.getDimensionPixelSize(gapId)));
      alignmentTolerance = Math.max(1, dp(1));
      int radiusId = res.getIdentifier("notification_corner_radius", "dimen", row.getContext().getPackageName());
      outerRadius = radiusId == 0 ? dp(16) : res.getDimensionPixelSize(radiusId);
      Arrays.fill(radii, -1f);
    }

    int dp(int dp) { return Math.round(dp * row.getResources().getDisplayMetrics().density); }

    boolean drawInRow(Canvas canvas) throws ReflectiveOperationException {
      boolean summary = (boolean)groupSummary.invoke(row);
      View content = summary ? (View)childrenContainer.invoke(row) : null;
      if (content == null || !content.isShown() || content.getAlpha() <= 0f)
        content = (View)privateLayout.invoke(row);
      if (content == null || !content.isShown() || content.getAlpha() <= 0f) { lastPaint = "no visible content"; return false; }
      int rowHeight = (int)actualHeight.invoke(row);
      int width = (int)bgWidth.invoke(bg), height = (int)bgHeight.invoke(bg);
      View anchor = bg.getWidth() > 0 && bg.getVisibility() == View.VISIBLE ? bg : content;
      if (anchor == content || width <= 0) width = anchor.getWidth();
      if (anchor == content || height <= 0) height = rowHeight;
      int top = (int)clipTop.invoke(row), bottom = (int)clipBottom.invoke(row);
      int nativeTop = anchor == bg && topOverlap != null ? Math.max(0, topOverlap.getInt(bg)) : 0;
      int nativeBottom = anchor == bg && bottomOverlap != null ? Math.max(0, bottomOverlap.getInt(bg)) : 0;
      if (summary) {
        int origin = Math.round(anchor.getY());
        height = summaryHeight(rowHeight, origin, nativeBottom);
        top = Math.max(nativeTop, top - origin);
        bottom = Math.max(0, bottom - nativeBottom);
      } else {
        top = Math.max(top, nativeTop);
        bottom = Math.max(bottom, nativeBottom);
      }
      int visibleBottom = (summary ? height : Math.min(rowHeight, height)) - Math.max(0, bottom);
      if (width <= 0 || height <= 0 || visibleBottom <= Math.max(0, top)) { lastPaint = "empty bounds"; return false; }
      if (!summary && bottom > 0 && bottomClips != null && bottomRounded != null
          && bottomClips.getBoolean(bg) && bottomRounded.getBoolean(bg)) height = visibleBottom;
      boolean right = (boolean)alignRight.invoke(bg);
      int left = right ? anchor.getWidth() - width : 0;
      prepare(summary);
      int save = canvas.save();
      try {
        canvas.translate(anchor.getLeft(), anchor.getTop());
        canvas.concat(anchor.getMatrix());
        if (!canvas.clipRect(left, Math.max(0, top), left + width, visibleBottom)) { lastPaint = "canvas clipped"; return false; }
        ripple.setBounds(left, 0, left + width, height);
        ripple.draw(canvas);
        return true;
      } finally { canvas.restoreToCount(save); }
    }

    boolean measureStackPosition() {
      try {
        if ((inShelf != null && inShelf.getBoolean(row)) || (removing != null && removing.getBoolean(row))
            || (appearing != null && appearing.getBoolean(row)) || (boolean)gutsExposed.invoke(row)
            || (boolean)launchRunning.invoke(row)) return false;
        float slide = rowTranslation == null ? row.getTranslationX() : (float)rowTranslation.invoke(row);
        if (Math.abs(slide) > alignmentTolerance) return false;
        int height = (int)actualHeight.invoke(row);
        topClip = (int)clipTop.invoke(row);
        bottomClip = (int)clipBottom.invoke(row);
        stackTop = row.getY();
        stackBottom = stackTop + height;
        stackX = row.getX();
        return height > Math.max(0, topClip) + Math.max(0, bottomClip);
      } catch (ReflectiveOperationException e) { return false; }
    }

    void prepare(boolean summary) throws ReflectiveOperationException {
      float[] src = (float[])corners.get(bg);
      boolean hasCorners = false;
      for (float radius : src) if (radius > 0f) { hasCorners = true; break; }
      float top = hasCorners ? 0f : (float)topRadius.invoke(row);
      float bottom = hasCorners ? 0f : (float)bottomRadius.invoke(row);
      boolean changed = false;
      for (int i = 0; i < 8; i++) {
        float radius = scaleRadius(hasCorners ? src[i] : i < 4 ? top : bottom, innerRadius, outerRadius, dp(28));
        if (summary) radius = dp(28);
        if ((joined & (i < 4 ? JOIN_TOP : JOIN_BOTTOM)) != 0) radius = innerRadius;
        if (radii[i] != radius) { radii[i] = radius; changed = true; }
      }
      if (changed) for (int i = 0; i < art.getNumberOfLayers(); i++)
        if (art.getDrawable(i) instanceof GradientDrawable layer) layer.setCornerRadii(radii);
      int alpha = drawableAlpha.getInt(bg);
      // A vendor can omit its whole background layer on an otherwise visible steady-state row.
      if (alpha <= 0 || bg.getVisibility() != View.VISIBLE) alpha = 255;
      if (ripple.getAlpha() != alpha) ripple.setAlpha(alpha);
      ripple.setState(row.getDrawableState());
      ripple.setLayoutDirection(bg.getLayoutDirection());
    }

    void update(View contracted, View expanded, int fill) {
      if (tint != fill) { tint = fill; base.setColor(fill); row.invalidate(); }
      if (small != contracted || large != expanded ||
          (!insets.isEmpty() && insets.get(0).density != row.getResources().getDisplayMetrics().densityDpi)) {
        restore();
        small = contracted; large = expanded;
        if (outerMargin == 0) return;
        int outer = row.getResources().getDimensionPixelSize(outerMargin);
        // The framework sizes and aligns leading icons for base, messaging and conversation templates.
        if (large != null) {
          boolean bigText = "bigText".equals(large.getTag()) || Integer.valueOf(bigTextLayout).equals(large.getTag(android.R.id.widget_frame));
          add(large, columnId, bigText, outer, dp(16));
          add(large, smartReplyId, false, outer, dp(16));
        }
      }
      for (Inset inset : insets) inset.apply();
    }

    void add(View root, int id, boolean padding, int from, int to) {
      if (id == 0 || from == to) return;
      View child = root.findViewById(id);
      if (child != null && child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
        insets.add(new Inset(child, padding, from, to));
      }
    }

    void restore() {
      for (Inset inset : insets) inset.restore();
      insets.clear(); small = large = null;
    }
  }

  static float scaleRadius(float value, float inner, float outer, float target) {
    if (value <= inner || outer <= inner) return value;
    return inner + Math.min(1f, (value - inner) / (outer - inner)) * (target - inner);
  }

  private static final class Inset {
    final View view;
    final int from, to, density;
    final boolean padding;
    boolean owned;
    int previous;
    Inset(View view, boolean padding, int from, int to) {
      this.view = view; this.padding = padding; this.from = from; this.to = to;
      density = view.getResources().getDisplayMetrics().densityDpi;
    }
    int get(ViewGroup.MarginLayoutParams p) {
      return padding ? view.getPaddingEnd() : p.getMarginEnd();
    }
    void set(ViewGroup.MarginLayoutParams p, int n) {
      if (padding) view.setPaddingRelative(view.getPaddingStart(), view.getPaddingTop(), n, view.getPaddingBottom());
      else { p.setMarginEnd(n); view.setLayoutParams(p); }
    }
    void apply() {
      if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams p)) return;
      int value = get(p);
      if (value == to) return;
      owned = false;
      if (value == from) {
        // A wrapper may rebind this view while the shade is open. Restore its latest metrics.
        previous = value;
        set(p, to);
        owned = true;
      }
    }
    void restore() {
      if (owned && view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams p && get(p) == to) set(p, previous);
    }
  }
}
