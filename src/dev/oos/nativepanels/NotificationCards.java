package dev.oos.nativepanels;

import android.app.Notification;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.service.notification.StatusBarNotification;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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
  private Method rowViewState, stateTranslationY, animatingY, animatingHeight;
  private Field stateHeight;
  private Object heightProperty;
  private Field inShelf, removing;
  private Card paintedRow;
  private int replacedThisPull, tintedThisPull, boundPasses;
  private final NotificationOverlayRegistry overlayBackgrounds = new NotificationOverlayRegistry();
  private final NotificationOverlayCorners overlayCorners = new NotificationOverlayCorners();
  private Method nativeSetRadius;
  private Method nativeSyncClip, nativeClipBackground;
  private Field nativeBackgroundExtension;
  private Method nativeBlurProxy;
  private Card backgroundClipCard;
  private final Set<Method> overlayDrawMethods = new HashSet<>();
  private int overlayFlushes, overlayDraws;
  private String overlayHook = "not installed";
  private Method overlayDefaultDrawable;
  private String lastPaint = "none";
  private Method actualHeight, bgHeight, bgWidth, alignRight, clipTop, clipBottom;
  private Method gutsExposed, launchRunning, userLocked, topRadius, bottomRadius;
  private Field appearing, topOverlap, bottomOverlap, bottomRounded, bottomClips;
  // Rows can move between notification hosts while remaining in the same shade window.
  private final Set<View> attached = Collections.newSetFromMap(new WeakHashMap<>());
  private View shadeRoot;
  private int attachEvents, rowDraws, scannedRows, matchedPeak, visiblePeak, drawsThisPull;
  private Class<?> rowType;
  private Method childrenContainer, childrenExpanded, expansionChanging, notificationParent, childrenAnimating;
  private Field rowExtension;
  private Method extensionChildrenAnimating;
  private Class<?> extensionAnimationType;
  private Method attachedGroupChildren;
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
    attachedGroupChildren = optional(childrenContainer.getReturnType(), "getAttachedChildren");
    childrenExpanded = rowType.getMethod("areChildrenExpanded");
    expansionChanging = rowType.getMethod("isGroupExpansionChanging");
    notificationParent = optional(rowType, "getNotificationParent");
    childrenAnimating = optional(rowType, "isChildrenExpandedAnimating");
    rowExtension = Reflect.optionalField(rowType, "mRowEx");
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
    installStackMotion(r);
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
    installNativeCorners(r, bg);
    installNativeClip(r);
    gutsExposed = rowType.getMethod("areGutsExposed");
    launchRunning = rowType.getMethod("isExpandAnimationRunning");
    userLocked = optional(rowType, "isUserLocked");
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
          Card nativeCard = rows.get(c.getThisObject());
          if (enabled && nativeCard != null && nativeCard.bg == c.getArg(1)
              && shadeRoot != null && nativeCard.row.getRootView() == shadeRoot) {
            boolean tint = false;
            int width = 0, height = 0;
            try {
              tint = canTintBackground(nativeCard.row);
              if (tint) {
                width = (int)bgWidth.invoke(nativeCard.bg);
                height = (int)bgHeight.invoke(nativeCard.bg);
              }
            } catch (ReflectiveOperationException | RuntimeException failure) {
              lastPaint = failure.toString();
            }
            if (tint) {
              tintedThisPull++;
              lastPaint = "native background tinted";
              int nativeWidth = width, nativeHeight = height;
              return withBackgroundClip(nativeCard, () -> nativeCard.nativeTint.draw(
                  (Canvas)c.getArg(0), nativeCard.bg, nativeWidth, nativeHeight, c::proceed));
            }
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
                      && !userExpanding.getBoolean(row) && (userLocked == null || !(boolean)userLocked.invoke(row))
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
    installOverlayBackgrounds(r);
  }

  private void installOverlayBackgrounds(PanelRuntime runtime) {
    try {
      Class<?> overlay = runtime.type("com.oplus.systemui.statusbar.notification.stack.OplusNotificationChildrenContainerOverlayExImpl");
      Method flush = Reflect.method(overlay, "flushBackground", View.class, rowType, String.class);
      try {
        overlayDefaultDrawable = Reflect.method(runtime.type("com.oplusos.systemui.common.blurability.drawable.AutoBlurDrawable"),
            "getDefaultDrawable");
      } catch (ReflectiveOperationException | RuntimeException absent) { /* Optional blur wrapper. */ }
      runtime.module.hook(flush).setId("native-panels:notification-overlay-bind")
          .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            try {
              Object target = chain.getArg(0), owner = chain.getArg(1);
              if (target instanceof View view && owner instanceof View row && rowType.isInstance(row)) {
                Drawable drawable = view.getBackground();
                if (drawable != null) {
                  hookOverlayDrawable(runtime, drawable);
                  overlayBackgrounds.register(drawable, view, row);
                  overlayFlushes++;
                  // Low-Gaussian OOS draws this exact default background again from
                  // its overlay mask, outside AutoBlurDrawable.draw(). Register only
                  // that verified wrapper association, never arbitrary child drawables.
                  if (overlayDefaultDrawable != null && overlayDefaultDrawable.getDeclaringClass().isInstance(drawable)) {
                    Object fallback = overlayDefaultDrawable.invoke(drawable);
                    if (fallback instanceof Drawable inner && inner != drawable) {
                      hookOverlayDrawable(runtime, inner);
                      overlayBackgrounds.registerDefault(inner, drawable, view, row, overlayDefaultDrawable);
                    }
                  }
                  Card card = rows.get(row);
                  if (enabled && card != null && card.shapePrepared && canTintBackground(row)) {
                    // Native flush clones the row's base layer. If it copied our
                    // owned shape, remember the native source for later release.
                    if (card.nativeCornersOwned && overlayBackgrounds.ownerOf(drawable, shadeRoot) == row)
                      overlayCorners.nativeFallbackSource(view, card.appliedTop, card.appliedBottom,
                          card.originalTop, card.originalBottom);
                    card.applyOverlayCorners();
                  }
                }
              }
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
              overlayHook = "background unavailable: " + unavailable;
            }
            return result;
          });
      deoptimizeOverlayMethod(runtime, flush);
      // These direct callers can have inlined flushBackground on an already-running
      // SystemUI. Missing vendor variants only limit this optional animation path.
      for (Method method : overlay.getDeclaredMethods()) {
        String name = method.getName();
        if (name.equals("onUiModeChanged") || name.equals("getOverlayBlurDrawable")
            || name.equals("updateOverlayClipping")) deoptimizeOverlayMethod(runtime, method);
      }
      try {
        Class<?> attach = runtime.type(overlay.getName() + "$createOverlayContainer$2$1");
        deoptimizeOverlayMethod(runtime, Reflect.method(attach, "onViewAttachedToWindow", View.class));
      } catch (ReflectiveOperationException | RuntimeException absent) { /* Optional vendor caller. */ }
      overlayHook = "ready";
    } catch (ReflectiveOperationException | RuntimeException unavailable) {
      // The independent OOS animation surface is optional; base notifications remain usable.
      overlayHook = "unavailable: " + unavailable;
    }
  }

  private void hookOverlayDrawable(PanelRuntime runtime, Drawable drawable) throws ReflectiveOperationException {
    Method draw = Reflect.method(drawable.getClass(), "draw", Canvas.class);
    if (overlayDrawMethods.contains(draw)) return;
    runtime.module.hook(draw).setId("native-panels:notification-overlay-draw:" + draw.getDeclaringClass().getName())
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          Drawable current = (Drawable)chain.getThisObject();
          View owner = enabled && shadeRoot != null ? overlayBackgrounds.ownerOf(current, shadeRoot) : null;
          Card card = owner == null ? null : rows.get(owner);
          boolean tint = false;
          if (card != null) {
            try { tint = canTintBackground(owner); }
            catch (ReflectiveOperationException | RuntimeException ignored) { /* Keep native draw. */ }
          }
          if (!tint) return chain.proceed();
          overlayDraws++;
          lastPaint = "native group overlay tinted";
          return card.nativeTint.draw((Canvas)chain.getArg(0), current.getBounds(), chain::proceed);
        });
    overlayDrawMethods.add(draw);
    deoptimizeOverlayMethod(runtime, draw);
  }

  private void installNativeCorners(PanelRuntime runtime, Class<?> backgroundType) {
    try {
      Method setter = Reflect.method(backgroundType, "setRadius", float.class, float.class, boolean.class);
      runtime.module.hook(setter).setId("native-panels:notification-corners")
          .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            View backgroundView = (View)chain.getThisObject();
            Card card = cardForBackground(backgroundView);
            if (card == null || card.applyingNativeCorners) return chain.proceed();
            card.prepareNativeBlur();
            card.captureNativeCorners((float)chain.getArg(0), (float)chain.getArg(1));
            boolean apply = enabled && shadeRoot != null && card.row.getRootView() == shadeRoot;
            try {
              apply &= canTintBackground(card.row);
              if (apply) card.resolveCorners(SystemClock.uptimeMillis());
            } catch (ReflectiveOperationException | RuntimeException unavailable) { apply = false; }
            if (!apply) {
              card.releaseCorners();
              return chain.proceed();
            }
            card.nativeCornersOwned = true;
            card.appliedTop = card.radii[0];
            card.appliedBottom = card.radii[4];
            Object result = chain.proceed(new Object[]{card.appliedTop, card.appliedBottom, chain.getArg(2)});
            if (card.nativeBlur != null) card.nativeBlur.written();
            // This flag also changes height clipping. Preserve the native decision;
            // owning the cosmetic radius must not change expansion bounds.
            if (bottomRounded != null) bottomRounded.setBoolean(card.bg, card.originalBottom != 0f);
            card.applyOverlayCorners();
            return result;
          });
      nativeSetRadius = setter;
      try {
        nativeBackgroundExtension = Reflect.field(backgroundType, "mExt");
        nativeBlurProxy = Reflect.method(runtime.type(
            "com.oplus.systemui.statusbar.notification.row.NotificationBackgroundViewExtImp"), "getViewBlurProxy");
      } catch (ReflectiveOperationException | RuntimeException absent) { /* Optional hardware blur. */ }
      deoptimizeOverlayMethod(runtime, setter);
      try {
        // This confirmed OOS caller may have inlined setRadius before module load.
        deoptimizeOverlayMethod(runtime, Reflect.method(runtime.type(ROW + "ActivatableNotificationView"),
            "applyRoundnessAndInvalidate"));
      } catch (ReflectiveOperationException | RuntimeException absent) { /* Optional vendor caller. */ }
    } catch (ReflectiveOperationException | RuntimeException unavailable) {
      nativeSetRadius = null;
    }
  }

  private void installNativeClip(PanelRuntime runtime) {
    try {
      Class<?> outline = runtime.type(ROW + "ExpandableOutlineView");
      Method path = Reflect.method(outline, "getRoundedRectPath", int.class, int.class, int.class, int.class,
          float.class, float.class, Path.class);
      runtime.module.hook(path).setId("native-panels:notification-background-clip")
          .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            View row = (View)chain.getThisObject();
            if (backgroundClipCard == null || backgroundClipCard.row != row) return chain.proceed();
            // Keep the native animated rectangle; only this background's corner
            // arguments change. Content and the row outline keep their own paths.
            return chain.proceed(new Object[]{chain.getArg(0), chain.getArg(1), chain.getArg(2), chain.getArg(3),
                backgroundClipRadius(row, (float)chain.getArg(4), true),
                backgroundClipRadius(row, (float)chain.getArg(5), false), chain.getArg(6)});
          });
      Class<?> extension = runtime.type("com.oplus.systemui.statusbar.notification.row.OplusActivatableNotificationViewExImpl");
      Method backgroundView = Reflect.method(extension, "getNotiBgView");
      Method sync = Reflect.method(extension, "syncClipCanvasToBgView",
          float.class, int.class, int.class, float.class, int.class, int.class);
      runtime.module.hook(sync).setId("native-panels:notification-background-clip-sync")
          .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Card card = cardForBackground((View)backgroundView.invoke(chain.getThisObject()));
            if (card == null || card.applyingNativeCorners || !card.nativeCornersOwned || !enabled
                || shadeRoot == null || card.row.getRootView() != shadeRoot || !canTintBackground(card.row)) {
              // A native call outside our surface becomes the current geometry;
              // release must not replay a stale clip rectangle over that write.
              if (card != null && !card.applyingNativeCorners) card.clearNativeClipOwnership();
              return chain.proceed();
            }
            card.prepareNativeBlur();
            if (card.nativeBlur != null) card.nativeBlur.nativeRadius((float)chain.getArg(0), (float)chain.getArg(3));
            Object result = chain.proceed(card.nativeClipArguments(chain.getThisObject(), (float)chain.getArg(0),
                (int)chain.getArg(1), (int)chain.getArg(2), (float)chain.getArg(3),
                (int)chain.getArg(4), (int)chain.getArg(5)));
            if (card.nativeBlur != null) card.nativeBlur.written();
            return result;
          });
      nativeSyncClip = sync;
      nativeClipBackground = backgroundView;
      deoptimizeOverlayMethod(runtime, path);
      deoptimizeOverlayMethod(runtime, sync);
      deoptimizeOverlayMethod(runtime, Reflect.method(outline, "getClipPath", boolean.class));
    } catch (ReflectiveOperationException | RuntimeException absent) { /* Optional native clip support. */ }
  }

  private Card cardForBackground(View view) {
    if (view == null) return null;
    Card card = rows.get(view.getParent());
    if (card != null && card.bg == view) return card;
    for (Card candidate : rows.values()) if (candidate.bg == view) return candidate;
    return null;
  }

  private void installStackMotion(PanelRuntime runtime) {
    try {
      Class<?> state = runtime.type("com.android.systemui.statusbar.notification.stack.ViewState");
      Class<?> property = runtime.type("com.android.systemui.statusbar.notification.PhysicsProperty");
      rowViewState = rowType.getMethod("getViewState");
      stateTranslationY = state.getMethod("getYTranslation");
      stateHeight = Reflect.field(rowViewState.getReturnType(), "height");
      animatingY = state.getMethod("isAnimatingY", View.class);
      animatingHeight = state.getMethod("isAnimating", View.class, property);
      heightProperty = Reflect.field(runtime.type(ROW + "ExpandableView"), "HEIGHT_PROPERTY").get(null);
    } catch (ReflectiveOperationException | RuntimeException absent) {
      // Another ROM can retain the current-position seam policy without these APIs.
      rowViewState = stateTranslationY = animatingY = animatingHeight = null;
      stateHeight = null;
      heightProperty = null;
    }
  }

  private Object withBackgroundClip(Card card, NotificationBackgroundTint.Draw original) throws Throwable {
    Card previous = backgroundClipCard;
    backgroundClipCard = card.nativeCornersOwned ? card : null;
    try { return original.run(); }
    finally { backgroundClipCard = previous; }
  }

  private float backgroundClipRadius(View row, float nativeRadius, boolean top) {
    Card card = backgroundClipCard;
    return card != null && card.row == row ? card.radii[top ? 0 : 4] : nativeRadius;
  }

  private static void deoptimizeOverlayMethod(PanelRuntime runtime, Method method) {
    if (Modifier.isAbstract(method.getModifiers()) || Modifier.isNative(method.getModifiers())) return;
    try { runtime.module.deoptimize(method); }
    catch (RuntimeException unavailable) { /* Keep an already installed hook and its binding. */ }
  }

  private boolean restricted(View row) throws ReflectiveOperationException {
    if (restrictedSurface(row)) return true;
    if ((boolean)groupChild.invoke(row)) {
      View parent = notificationParent == null ? null : (View)notificationParent.invoke(row);
      // The expanded summary normally hides its own background. Only its children
      // get a replacement, after the group's layout and animation have settled.
      return parent == null || restrictedSurface(parent) || groupHidesBackground.getBoolean(row)
          || !(boolean)childrenExpanded.invoke(parent) || (boolean)expansionChanging.invoke(parent)
          || userExpanding.getBoolean(parent)
          || groupAnimationRunning(parent)
          || (boolean)gutsExposed.invoke(parent) || (boolean)launchRunning.invoke(parent)
          || (appearing != null && appearing.getBoolean(parent));
    }
    if (!(boolean)groupSummary.invoke(row)) return false;
    return groupAnimationRunning(row)
        || !ownsCollapsedGroup((boolean)childrenExpanded.invoke(row), (boolean)expansionChanging.invoke(row),
        userExpanding.getBoolean(row), groupHidesBackground.getBoolean(row));
  }

  private boolean groupAnimationRunning(View row) throws ReflectiveOperationException {
    if (childrenAnimating != null && (boolean)childrenAnimating.invoke(row)) return true;
    // OOS 17 keeps this animation flag on the row extension, after the AOSP
    // group-expansion flag may already have settled. Read it without changing it.
    Object extension = rowExtension == null ? null : rowExtension.get(row);
    if (extension == null) return false;
    // The field's interface does not expose this OOS implementation method.
    Class<?> type = extension.getClass();
    if (extensionAnimationType != type) {
      extensionAnimationType = type;
      extensionChildrenAnimating = optional(type, "isChildrenExpandedAnimating");
    }
    return extensionChildrenAnimating != null && (boolean)extensionChildrenAnimating.invoke(extension);
  }

  private boolean restrictedSurface(View row) throws ReflectiveOperationException {
    return onKeyguard.getBoolean(row) || showingPublic.getBoolean(row) || (boolean)headsUp.invoke(row);
  }

  // A transient row can keep the native renderer and still use our color. This
  // never makes a hidden background visible or overrides a group's fade/clip.
  private boolean canTintBackground(View row) throws ReflectiveOperationException {
    if (!safeNativeBackground(row)) return false;
    if (!(boolean)groupChild.invoke(row)) return true;
    View parent = notificationParent == null ? null : (View)notificationParent.invoke(row);
    return parent != null && safeNativeBackground(parent);
  }

  private boolean safeNativeBackground(View row) throws ReflectiveOperationException {
    return !restrictedSurface(row) && !(boolean)gutsExposed.invoke(row)
        && !(boolean)launchRunning.invoke(row) && (appearing == null || !appearing.getBoolean(row));
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
          if (restricted(row) && !canTintBackground(row)) { excluded++; continue; }
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
          if (surface == Surface.STANDARD && !(boolean)groupSummary.invoke(row) && !(boolean)groupChild.invoke(row)) {
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
    int found = 0;
    if (rowType.isInstance(view)) {
      attached.add(view);
      found++;
    }
    // Include children of groups that were already attached before the first scan.
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
      int groupSeams = joinGroupCorners(parent);
      if (groupSeams >= 0) { seams += groupSeams; continue; }
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
    long now = SystemClock.uptimeMillis();
    boolean animate = ValueAnimator.areAnimatorsEnabled();
    for (Card card : rows.values()) {
      card.joined = card.nextJoined;
      card.cornerTransition.target(card.joined, now, animate);
      try { card.updateCorners(now); }
      catch (ReflectiveOperationException | RuntimeException unavailable) { problem = unavailable.toString(); }
    }
  }

  // Group containers interleave their rows with headers and divider views. Use their
  // logical child list, not those decoration views, to find the internal seams.
  private int joinGroupCorners(ViewGroup container) {
    try {
      View owner = null;
      for (int i = 0; i < container.getChildCount(); i++) {
        View child = container.getChildAt(i);
        if (rowType.isInstance(child) && (boolean)groupChild.invoke(child)) {
          owner = notificationParent == null ? null : (View)notificationParent.invoke(child);
          break;
        }
      }
      if (owner == null || childrenContainer.invoke(owner) != container) return -1;
      // Being on the native animation renderer does not split a visible group.
      // A fully collapsed group, however, must not join its hidden child surfaces.
      if (!(boolean)childrenExpanded.invoke(owner) && !(boolean)expansionChanging.invoke(owner)
          && !groupAnimationRunning(owner) && !userExpanding.getBoolean(owner)
          && (userLocked == null || !(boolean)userLocked.invoke(owner))) return 0;
      List<?> children;
      if (attachedGroupChildren != null) children = (List<?>)attachedGroupChildren.invoke(container);
      else {
        // Older vendor containers may not expose their list. Only actual rows are
        // candidates; sorting their current positions also handles reordered views.
        ArrayList<View> visibleOrder = new ArrayList<>();
        for (int i = 0; i < container.getChildCount(); i++)
          if (rowType.isInstance(container.getChildAt(i))) visibleOrder.add(container.getChildAt(i));
        visibleOrder.sort((a, b) -> Float.compare(a.getY(), b.getY()));
        children = visibleOrder;
      }
      Card previous = null;
      int seams = 0;
      for (Object item : children) {
        if (!(item instanceof View child) || child.getParent() != container
            || !rowType.isInstance(child) || !(boolean)groupChild.invoke(child)
            || notificationParent.invoke(child) != owner) { previous = null; continue; }
        if (child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0f || child.getHeight() == 0) continue;
        Card current = rows.get(child);
        if (current == null || !current.hasVisibleGroupBackground()
            || !current.measureStackPosition(true)) { previous = null; continue; }
        if (previous != null && sameSeam(previous.stackBottom, current.stackTop,
            previous.stackX, current.stackX, previous.row.getWidth(), child.getWidth(),
            current.joinGap, current.alignmentTolerance)
            && previous.bottomClip <= 0 && current.topClip <= 0) {
          previous.nextJoined |= JOIN_BOTTOM;
          current.nextJoined |= JOIN_TOP;
          seams++;
        }
        previous = current;
      }
      return seams;
    } catch (ReflectiveOperationException | RuntimeException unavailable) { return 0; }
  }

  static boolean sameSeam(float upperBottom, float lowerTop, float upperX, float lowerX,
      int upperWidth, int lowerWidth, int maxGap, int tolerance) {
    float gap = lowerTop - upperBottom;
    return gap >= -tolerance && gap <= maxGap + 1 && Math.abs(upperX - lowerX) <= tolerance
        && upperWidth > 0 && Math.abs(upperWidth - lowerWidth) <= tolerance;
  }

  void beginPull() {
    peakSeams = 0;
    boundPasses = replacedThisPull = tintedThisPull = 0;
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
    c.releaseCorners();
    c.restore(); c.ripple.jumpToCurrentState(); c.ripple.setCallback(null); c.row.invalidate(); c.bg.invalidate();
    overlayBackgrounds.invalidateOwner(c.row);
  }

  void clear() {
    if (rows.isEmpty() && shadeRoot == null) return;
    for (Card c : rows.values()) remove(c);
    // OOS can reuse an overlay without another factory call on the next shade pull.
    // Keep only weak factory identity metadata; redraw with native colors while inactive.
    overlayBackgrounds.invalidateAll();
    overlayCorners.clear();
    rowParents.clear();
    rows.clear(); shadeRoot = null;
  }

  String report() {
    return (ready ? "enabled=" + enabled + " active=" + rows.size() + " peak=" + lastCount +
        " attachedEvents=" + attachEvents + " scanned=" + scannedRows + " matched=" + matchedPeak + " shown=" + visiblePeak +
        " rowDraws=" + rowDraws + " seamsPeak=" + peakSeams +
        " eligible=" + commonRows + " templates=" + lastTemplates + " insets=" + lastModified +
        " draws=" + drawCount + " drawsThisPull=" + drawsThisPull + " replacedChild=" + replacedThisPull + " tintedChild=" + tintedThisPull + " boundPasses=" + boundPasses + " lastPaint=" + lastPaint + " restricted=" + restrictedRows + " other=" + otherStyles +
        " overlayHook=" + overlayHook + " overlayDrawHooks=" + overlayDrawMethods.size() + " overlayBindings=" + overlayFlushes + " overlayDraws=" + overlayDraws +
        " nativeCorners=" + (nativeSetRadius != null) + "/" + (nativeSyncClip != null) +
        " kinds=" + seenSurfaces + " tint=#" + Integer.toHexString(color) : "unavailable") + (problem.isEmpty() ? "" : " " + problem);
  }

  private final class Card {
    final View row, bg;
    final LayerDrawable art;
    final GradientDrawable base;
    final RippleDrawable ripple;
    final NotificationBackgroundTint nativeTint = new NotificationBackgroundTint();
    final NotificationCornerTransition cornerTransition = new NotificationCornerTransition();
    final NotificationCornerTransition nativeCornerTransition = new NotificationCornerTransition();
    final Set<View> shapedOverlays = Collections.newSetFromMap(new WeakHashMap<>());
    final Drawable.Callback callback;
    final float[] radii = new float[8];
    final ArrayList<Inset> insets = new ArrayList<>();
    View small, large;
    int tint;
    final int innerRadius, outerRadius, joinGap, alignmentTolerance;
    int joined, nextJoined, topClip, bottomClip;
    float stackTop, stackBottom, stackX;
    boolean present;
    boolean shapePrepared, nativeCornersOwned, applyingNativeCorners;
    float originalTop, originalBottom, appliedTop, appliedBottom;
    Object nativeClipOwner;
    Object[] nativeClipSource, nativeClipOwned;
    NotificationNativeBlurSnapshot nativeBlur;

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
      float[] original = (float[])corners.get(bg);
      captureNativeCorners(original[0], original[4]);
    }

    int dp(int dp) { return Math.round(dp * row.getResources().getDisplayMetrics().density); }

    boolean drawInRow(Canvas canvas) throws ReflectiveOperationException {
      boolean child = (boolean)groupChild.invoke(row);
      if (child && (bg.getVisibility() != View.VISIBLE || bg.getAlpha() <= 0f || drawableAlpha.getInt(bg) <= 0)) {
        lastPaint = "group background hidden";
        return false;
      }
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
      prepare(summary, child);
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
      return measureStackPosition(false);
    }

    boolean measureStackPosition(boolean inGroup) {
      try {
        // Group children are joined separately so neither a header nor an adjacent
        // notification outside the group can join across the group's boundary.
        if (!inGroup && (boolean)groupChild.invoke(row)) return false;
        // Some cards are retained only to tint the native animation renderer. They
        // must not supply a stack seam, especially an expanded group's summary.
        if (inGroup ? !canTintBackground(row) : restricted(row)) return false;
        if ((inShelf != null && inShelf.getBoolean(row)) || (removing != null && removing.getBoolean(row))
            || (appearing != null && appearing.getBoolean(row)) || (boolean)gutsExposed.invoke(row)
            || (boolean)launchRunning.invoke(row)
            || (!inGroup && (userExpanding.getBoolean(row)
                || (userLocked != null && (boolean)userLocked.invoke(row))))) return false;
        float slide = rowTranslation == null ? row.getTranslationX() : (float)rowTranslation.invoke(row);
        if (Math.abs(slide) > alignmentTolerance) return false;
        int height = (int)actualHeight.invoke(row);
        topClip = (int)clipTop.invoke(row);
        bottomClip = (int)clipBottom.invoke(row);
        if (height <= Math.max(0, topClip) + Math.max(0, bottomClip)) return false;
        stackTop = row.getY();
        if (rowViewState != null) {
          try {
            Object state = rowViewState.invoke(row);
            if (state != null) {
              float destinationTop = stackTop;
              int destinationHeight = height;
              if ((boolean)animatingY.invoke(null, row)) {
                float translation = (float)stateTranslationY.invoke(state);
                if (Float.isFinite(translation)) destinationTop = row.getTop() + translation;
              }
              if ((boolean)animatingHeight.invoke(null, row, heightProperty)) {
                int target = stateHeight.getInt(state);
                if (target > 0) destinationHeight = target;
              }
              // Neighboring springs need not be at the same fraction of their
              // movement. Their destination, not that temporary gap, defines a seam.
              stackTop = destinationTop;
              height = destinationHeight;
            }
          } catch (ReflectiveOperationException | RuntimeException unavailable) { /* Use current geometry. */ }
        }
        stackBottom = stackTop + height;
        stackX = row.getX();
        return height > Math.max(0, topClip) + Math.max(0, bottomClip);
      } catch (ReflectiveOperationException e) { return false; }
    }

    boolean hasVisibleGroupBackground() throws ReflectiveOperationException {
      if (!groupHidesBackground.getBoolean(row) && bg.getVisibility() == View.VISIBLE
          && bg.getAlpha() > 0f && drawableAlpha.getInt(bg) > 0) return true;
      // OOS can hide the row fill while its registered overlay carries the animation.
      for (View overlay : overlayBackgrounds.targetsForOwner(row, shadeRoot))
        if (overlay.isShown() && overlay.getAlpha() > 0f) return true;
      return false;
    }

    void prepare(boolean summary, boolean child) throws ReflectiveOperationException {
      if (!shapePrepared) {
        long now = SystemClock.uptimeMillis();
        cornerTransition.target(joined, now, false);
        resolveCorners(now);
      }
      int alpha = drawableAlpha.getInt(bg);
      // A vendor can omit its whole background layer on an otherwise visible steady-state row.
      if (child) alpha = Math.round(alpha * bg.getAlpha());
      else if (alpha <= 0 || bg.getVisibility() != View.VISIBLE) alpha = 255;
      if (ripple.getAlpha() != alpha) ripple.setAlpha(alpha);
      ripple.setState(row.getDrawableState());
      ripple.setLayoutDirection(bg.getLayoutDirection());
    }

    void captureNativeCorners(float top, float bottom) {
      originalTop = top;
      originalBottom = bottom;
      if (nativeBlur != null) nativeBlur.nativeRadius(top, bottom);
    }

    void prepareNativeBlur() {
      if (nativeBackgroundExtension == null || nativeBlurProxy == null) return;
      try {
        Object extension = nativeBackgroundExtension.get(bg);
        if (extension == null || !nativeBlurProxy.getDeclaringClass().isInstance(extension)) return;
        Object proxy = nativeBlurProxy.invoke(extension);
        if (nativeBlur != null && !nativeBlur.matches(proxy)) {
          nativeBlur.restore();
          nativeBlur = null;
        }
        if (nativeBlur == null && proxy != null) nativeBlur = NotificationNativeBlurSnapshot.capture(proxy);
        if (nativeBlur != null) nativeBlur.beforeWrite();
      } catch (ReflectiveOperationException | RuntimeException absent) { /* Optional hardware blur. */ }
    }

    void resolveCorners(long now) throws ReflectiveOperationException {
      boolean summary = (boolean)groupSummary.invoke(row);
      boolean hasCorners = originalTop > 0f || originalBottom > 0f;
      float top = hasCorners ? originalTop : (float)topRadius.invoke(row);
      float bottom = hasCorners ? originalBottom : (float)bottomRadius.invoke(row);
      top = summary ? dp(28) : scaleRadius(top, innerRadius, outerRadius, dp(28));
      bottom = summary ? dp(28) : scaleRadius(bottom, innerRadius, outerRadius, dp(28));
      // OOS also resets raw roundness without animation. Smooth that source
      // independently of seam weights so an unchanged join mask cannot jump.
      nativeCornerTransition.target(top, bottom, now, ValueAnimator.areAnimatorsEnabled());
      top = nativeCornerTransition.top(now);
      bottom = nativeCornerTransition.bottom(now);
      top += (innerRadius - top) * cornerTransition.top(now);
      bottom += (innerRadius - bottom) * cornerTransition.bottom(now);
      boolean changed = !shapePrepared;
      for (int i = 0; i < radii.length; i++) {
        float radius = i < 4 ? top : bottom;
        if (radii[i] != radius) { radii[i] = radius; changed = true; }
      }
      shapePrepared = true;
      if (changed) {
        for (int i = 0; i < art.getNumberOfLayers(); i++)
          if (art.getDrawable(i) instanceof GradientDrawable layer) layer.setCornerRadii(radii);
        row.invalidate();
      }
    }

    void updateCorners(long now) throws ReflectiveOperationException {
      float[] current = (float[])corners.get(bg);
      if (!nativeCornersOwned || current[0] != appliedTop || current[4] != appliedBottom) {
        // A direct native rebind is a new source value, never feedback from our write.
        captureNativeCorners(current[0], current[4]);
        nativeCornersOwned = false;
        clearNativeClipOwnership();
      }
      resolveCorners(now);
      if (!canTintBackground(row)) {
        releaseCorners();
        return;
      }
      applyNativeCorners();
      applyOverlayCorners();
      if (cornerTransition.isRunning(now) || nativeCornerTransition.isRunning(now)) {
        row.postInvalidateOnAnimation();
        overlayBackgrounds.invalidateOwner(row);
      }
    }

    void applyNativeCorners() throws ReflectiveOperationException {
      if (nativeSetRadius == null) return;
      if (nativeCornersOwned && appliedTop == radii[0] && appliedBottom == radii[4]) return;
      prepareNativeBlur();
      appliedTop = radii[0];
      appliedBottom = radii[4];
      nativeCornersOwned = true;
      applyingNativeCorners = true;
      try {
        nativeSetRadius.invoke(bg, appliedTop, appliedBottom, false);
        if (nativeBlur != null) nativeBlur.written();
        if (bottomRounded != null) bottomRounded.setBoolean(bg, originalBottom != 0f);
      } finally { applyingNativeCorners = false; }
    }

    void applyOverlayCorners() {
      List<View> current = overlayBackgrounds.targetsForOwner(row, shadeRoot);
      for (Iterator<View> it = shapedOverlays.iterator(); it.hasNext();) {
        View target = it.next();
        if (!current.contains(target)) {
          overlayCorners.restore(target);
          it.remove();
        }
      }
      for (View target : current) {
        overlayCorners.apply(target, radii[0], radii[4]);
        shapedOverlays.add(target);
      }
    }

    Object[] nativeClipArguments(Object owner, float top, int left, int clipTop, float bottom, int right, int clipBottom) {
      nativeClipOwner = owner;
      if (nativeClipSource == null) {
        nativeClipSource = new Object[6];
        nativeClipOwned = new Object[6];
      }
      nativeClipSource[0] = top;
      nativeClipSource[1] = nativeClipOwned[1] = left;
      nativeClipSource[2] = nativeClipOwned[2] = clipTop;
      nativeClipSource[3] = bottom;
      nativeClipSource[4] = nativeClipOwned[4] = right;
      nativeClipSource[5] = nativeClipOwned[5] = clipBottom;
      nativeClipOwned[0] = radii[0];
      nativeClipOwned[3] = radii[4];
      return nativeClipOwned;
    }

    void clearNativeClipOwnership() {
      nativeClipOwner = null;
      nativeClipSource = nativeClipOwned = null;
    }

    void releaseCorners() {
      for (View target : shapedOverlays) overlayCorners.restore(target);
      shapedOverlays.clear();
      if (!nativeCornersOwned) {
        clearNativeClipOwnership();
        if (nativeBlur != null) nativeBlur.restore();
        nativeBlur = null;
        return;
      }
      nativeCornersOwned = false;
      applyingNativeCorners = true;
      try {
        float[] current = (float[])corners.get(bg);
        if (current[0] == appliedTop && current[4] == appliedBottom) {
          if (nativeSetRadius != null) nativeSetRadius.invoke(bg, originalTop, originalBottom, false);
          if (nativeSyncClip != null && nativeClipOwner != null && nativeClipSource != null
              && (nativeClipBackground == null || nativeClipBackground.invoke(nativeClipOwner) == bg))
            nativeSyncClip.invoke(nativeClipOwner, nativeClipSource);
        }
      } catch (ReflectiveOperationException | RuntimeException unavailable) {
        problem = unavailable.toString();
      } finally {
        if (nativeBlur != null) nativeBlur.restore();
        nativeBlur = null;
        clearNativeClipOwnership();
        applyingNativeCorners = false;
      }
    }

    void update(View contracted, View expanded, int fill) {
      if (tint != fill) {
        tint = fill;
        base.setColor(fill);
        nativeTint.setColor(fill);
        row.invalidate();
        overlayBackgrounds.invalidateOwner(row);
      }
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
