package dev.oos.nativepanels;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class NotificationOverlayRegistryTest {
  private final Context context = RuntimeEnvironment.getApplication();

  @Test public void onlyTheRegisteredBackgroundResolvesToItsExactNotificationOwner() {
    NotificationOverlayRegistry registry = new NotificationOverlayRegistry();
    View target = new View(context), owner = new View(context), sibling = new View(context);
    TextView title = new TextView(context);
    Drawable background = new ColorDrawable(Color.RED);
    Drawable unrelated = new ColorDrawable(Color.RED);
    target.setBackground(background);
    sibling.setBackground(unrelated);
    title.setBackground(new ColorDrawable(Color.RED));
    registry.register(background, target, owner);
    assertSame(owner, registry.ownerOf(background));
    assertNull(registry.ownerOf(unrelated));
    assertNull(registry.ownerOf(title.getBackground()));
  }

  @Test public void rebindingATargetRemovesItsOldDrawableAndUpdatesItsOwner() {
    NotificationOverlayRegistry registry = new NotificationOverlayRegistry();
    View target = new View(context), firstOwner = new View(context), nextOwner = new View(context);
    Drawable old = new ColorDrawable(Color.RED), replacement = new ColorDrawable(Color.BLUE);
    target.setBackground(old);
    registry.register(old, target, firstOwner);
    registry.register(old, target, nextOwner);
    assertSame(nextOwner, registry.ownerOf(old));
    target.setBackground(replacement);
    assertNull(registry.ownerOf(old));
    registry.register(replacement, target, nextOwner);
    assertSame(nextOwner, registry.ownerOf(replacement));
    assertNull(registry.ownerOf(old));
  }

  @Test public void sharingADrawableWithAnotherViewFailsClosed() {
    NotificationOverlayRegistry registry = new NotificationOverlayRegistry();
    View first = new View(context), second = new View(context);
    View firstOwner = new View(context), secondOwner = new View(context);
    Drawable shared = new ColorDrawable(Color.RED);
    first.setBackground(shared);
    registry.register(shared, first, firstOwner);
    second.setBackground(shared); // Callback now identifies an unregistered View.
    assertNull(registry.ownerOf(shared));
    registry.register(shared, second, secondOwner);
    assertNull(registry.ownerOf(shared)); // Both still claim this as their background.
    first.setBackground(new ColorDrawable(Color.BLUE));
    // Reassigning the first background clears the shared Drawable's callback too.
    shared.setCallback(second);
    assertSame(secondOwner, registry.ownerOf(shared));
  }

  @Test public void aBackgroundWithAnUnexpectedCallbackIsNeverColored() {
    NotificationOverlayRegistry registry = new NotificationOverlayRegistry();
    View target = new View(context), owner = new View(context);
    Drawable drawable = new ColorDrawable(Color.RED);
    target.setBackground(drawable);
    registry.register(drawable, target, owner);
    drawable.setCallback(null);
    assertNull(registry.ownerOf(drawable));
    drawable.setCallback(target);
    assertSame(owner, registry.ownerOf(drawable));
  }

  @Test public void targetAndOwnerMustBothRemainInTheActiveShadeWindow() {
    NotificationOverlayRegistry registry = new NotificationOverlayRegistry();
    FrameLayout shade = new FrameLayout(context), otherWindow = new FrameLayout(context);
    View target = new View(context), owner = new View(context);
    shade.addView(target); shade.addView(owner);
    Drawable drawable = new ColorDrawable(Color.RED);
    target.setBackground(drawable);
    registry.register(drawable, target, owner);
    assertSame(owner, registry.ownerOf(drawable, shade));
    assertNull(registry.ownerOf(drawable, null));
    assertNull(registry.ownerOf(drawable, otherWindow));
    shade.removeView(target);
    otherWindow.addView(target);
    assertNull(registry.ownerOf(drawable, shade));
    otherWindow.removeView(target);
    shade.addView(target);
    shade.removeView(owner);
    otherWindow.addView(owner);
    assertNull(registry.ownerOf(drawable, shade));
  }

  @Test public void clearingAndRegisteringInvalidateTheOverlayDisplayList() {
    NotificationOverlayRegistry registry = new NotificationOverlayRegistry();
    TrackingView target = new TrackingView(context);
    View owner = new View(context);
    Drawable drawable = new ColorDrawable(Color.RED);
    target.setBackground(drawable);
    int before = target.invalidations;
    registry.register(drawable, target, owner);
    assertTrue(target.invalidations > before);
    before = target.invalidations;
    registry.clear();
    assertTrue(target.invalidations > before);
    assertNull(registry.ownerOf(drawable));
  }

  @Test public void changingOneOwnerInvalidatesOnlyItsOverlayDisplayLists() {
    NotificationOverlayRegistry registry = new NotificationOverlayRegistry();
    View owner = new View(context), unrelatedOwner = new View(context);
    TrackingView first = new TrackingView(context), second = new TrackingView(context);
    TrackingView unrelated = new TrackingView(context);
    for (TrackingView target : new TrackingView[]{first, second, unrelated}) {
      Drawable drawable = new ColorDrawable(Color.RED);
      target.setBackground(drawable);
      registry.register(drawable, target, target == unrelated ? unrelatedOwner : owner);
    }
    int firstBefore = first.invalidations, secondBefore = second.invalidations;
    int unrelatedBefore = unrelated.invalidations;
    registry.invalidateOwner(owner);
    assertTrue(first.invalidations > firstBefore);
    assertTrue(second.invalidations > secondBefore);
    assertEquals(unrelatedBefore, unrelated.invalidations);
    assertSame(owner, registry.ownerOf(first.getBackground()));
    assertSame(unrelatedOwner, registry.ownerOf(unrelated.getBackground()));
  }

  @Test public void closingTheShadeInvalidatesButKeepsReusableFactoryBindings() {
    NotificationOverlayRegistry registry = new NotificationOverlayRegistry();
    FrameLayout shade = new FrameLayout(context);
    TrackingView target = new TrackingView(context);
    View owner = new View(context);
    shade.addView(target); shade.addView(owner);
    Drawable drawable = new ColorDrawable(Color.RED);
    target.setBackground(drawable);
    registry.register(drawable, target, owner);
    assertSame(owner, registry.ownerOf(drawable, shade));
    int before = target.invalidations;
    registry.invalidateAll();
    assertTrue(target.invalidations > before);
    assertNull(registry.ownerOf(drawable, null));
    // OOS can reuse this same animation background without flushing it again.
    assertSame(owner, registry.ownerOf(drawable, shade));
  }

  @Test public void nativeDefaultChildResolvesThroughItsStillInstalledWrapper() throws Exception {
    NotificationOverlayRegistry registry = new NotificationOverlayRegistry();
    FrameLayout shade = new FrameLayout(context);
    View target = new View(context), owner = new View(context);
    shade.addView(target); shade.addView(owner);
    Drawable inner = new ColorDrawable(Color.RED);
    BlurWrapper wrapper = new BlurWrapper(inner);
    target.setBackground(wrapper);
    registry.register(wrapper, target, owner);
    registry.registerDefault(inner, wrapper, target, owner, defaultGetter());
    assertSame(wrapper, inner.getCallback());
    assertSame(owner, registry.ownerOf(wrapper, shade));
    assertSame(owner, registry.ownerOf(inner, shade));
    assertNull(registry.ownerOf(new ColorDrawable(Color.RED), shade));
    assertNull(registry.ownerOf(inner, null));
    assertNull(registry.ownerOf(inner, new FrameLayout(context)));
    registry.invalidateAll();
    assertSame(owner, registry.ownerOf(inner, shade));
    target.setBackground(new ColorDrawable(Color.BLUE));
    assertNull(registry.ownerOf(inner, shade));
    assertNull(registry.ownerOf(wrapper, shade));
  }

  @Test public void defaultGetterIdentityAndBothNativeCallbacksMustStillMatch() throws Exception {
    NotificationOverlayRegistry registry = new NotificationOverlayRegistry();
    View target = new View(context), owner = new View(context), other = new View(context);
    Drawable inner = new ColorDrawable(Color.RED);
    BlurWrapper wrapper = new BlurWrapper(inner);
    target.setBackground(wrapper);
    registry.register(wrapper, target, owner);
    registry.registerDefault(inner, wrapper, target, owner, defaultGetter());
    wrapper.setCallback(other);
    assertNull(registry.ownerOf(inner));
    wrapper.setCallback(target);
    inner.setCallback(other);
    assertNull(registry.ownerOf(inner));
    inner.setCallback(wrapper);
    assertSame(owner, registry.ownerOf(inner));
    wrapper.inner = new ColorDrawable(Color.BLUE);
    wrapper.inner.setCallback(wrapper);
    assertNull(registry.ownerOf(inner));
    assertSame(owner, registry.ownerOf(wrapper));
    registry.registerDefault(wrapper.inner, wrapper, target, owner, defaultGetter());
    assertSame(owner, registry.ownerOf(wrapper.inner));
    registry.clear();
    assertNull(registry.ownerOf(wrapper.inner));
  }

  @Test public void sharingANativeDefaultChildAcrossTargetsFailsClosed() throws Exception {
    NotificationOverlayRegistry registry = new NotificationOverlayRegistry();
    View first = new View(context), second = new View(context);
    View firstOwner = new View(context), secondOwner = new View(context);
    Drawable inner = new ColorDrawable(Color.RED);
    BlurWrapper firstWrapper = new BlurWrapper(inner), secondWrapper = new BlurWrapper(inner);
    first.setBackground(firstWrapper); second.setBackground(secondWrapper);
    registry.register(firstWrapper, first, firstOwner);
    registry.registerDefault(inner, firstWrapper, first, firstOwner, defaultGetter());
    registry.register(secondWrapper, second, secondOwner);
    registry.registerDefault(inner, secondWrapper, second, secondOwner, defaultGetter());
    assertNull(registry.ownerOf(inner));
    first.setBackground(new ColorDrawable(Color.BLUE));
    assertSame(secondOwner, registry.ownerOf(inner));
  }

  @Test public void sharingTheWrapperAlsoExcludesItsDefaultChild() throws Exception {
    NotificationOverlayRegistry registry = new NotificationOverlayRegistry();
    View first = new View(context), second = new View(context);
    View firstOwner = new View(context), secondOwner = new View(context);
    Drawable inner = new ColorDrawable(Color.RED);
    BlurWrapper wrapper = new BlurWrapper(inner);
    first.setBackground(wrapper);
    registry.register(wrapper, first, firstOwner);
    second.setBackground(wrapper);
    registry.register(wrapper, second, secondOwner);
    registry.registerDefault(inner, wrapper, second, secondOwner, defaultGetter());
    assertNull(registry.ownerOf(inner));
    assertNull(registry.ownerOf(wrapper));
  }

  @Test public void unrelatedDrawsDoNotInvokeTheNativeDefaultGetter() throws Exception {
    NotificationOverlayRegistry registry = new NotificationOverlayRegistry();
    View target = new View(context), owner = new View(context);
    Drawable inner = new ColorDrawable(Color.RED);
    BlurWrapper wrapper = new BlurWrapper(inner);
    target.setBackground(wrapper);
    registry.register(wrapper, target, owner);
    registry.registerDefault(inner, wrapper, target, owner, defaultGetter());
    int before = wrapper.getterCalls;
    for (int i = 0; i < 20; i++) assertNull(registry.ownerOf(new ColorDrawable(Color.BLUE)));
    assertEquals(before, wrapper.getterCalls);
  }

  private static Method defaultGetter() throws Exception {
    return BlurWrapper.class.getMethod("getDefaultDrawable");
  }

  public static final class BlurWrapper extends ColorDrawable implements Drawable.Callback {
    Drawable inner;
    int getterCalls;
    BlurWrapper(Drawable inner) {
      this.inner = inner;
      inner.setCallback(this); // Verified AutoBlurDrawable constructor contract.
    }
    public Drawable getDefaultDrawable() { getterCalls++; return inner; }
    @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
    @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
    @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
  }

  private static final class TrackingView extends View {
    int invalidations;
    TrackingView(Context context) { super(context); }
    @Override public void invalidate() { invalidations++; super.invalidate(); }
  }
}
