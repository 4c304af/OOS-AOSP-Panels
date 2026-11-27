package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BatteryAccessibilityTest {
  @Test public void completedLabelTracksPresentationWithoutMutatingNativeDescriptionOrSemantics() {
    View host = new View(RuntimeEnvironment.getApplication());
    host.setContentDescription("native view label");
    AtomicBoolean completed = new AtomicBoolean();
    View.AccessibilityDelegate original = new View.AccessibilityDelegate() {
      @Override public void onInitializeAccessibilityNodeInfo(View view, AccessibilityNodeInfo info) {
        info.setContentDescription("充電中");
        info.setClassName("NativeBatteryMeter");
        info.setCheckable(true);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
      }
      @Override public void onInitializeAccessibilityEvent(View view, AccessibilityEvent event) {
        event.setContentDescription("充電中");
        event.setItemCount(17);
      }
      @Override public void onPopulateAccessibilityEvent(View view, AccessibilityEvent event) {
        event.setContentDescription("充電中");
        event.setItemCount(18);
      }
      @Override public boolean dispatchPopulateAccessibilityEvent(View view, AccessibilityEvent event) {
        event.setContentDescription("充電中");
        event.setItemCount(19);
        return true;
      }
    };
    host.setAccessibilityDelegate(original);
    BatteryAccessibility accessibility = new BatteryAccessibility(host,
        nativeText -> completed.get() ? "バッテリー残量: 100パーセント 充電完了" : nativeText);
    accessibility.attach();
    View.AccessibilityDelegate installed = host.getAccessibilityDelegate();
    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
    installed.onInitializeAccessibilityNodeInfo(host, info);
    assertEquals("充電中", info.getContentDescription());
    completed.set(true);
    installed.onInitializeAccessibilityNodeInfo(host, info);
    assertEquals("バッテリー残量: 100パーセント 充電完了", info.getContentDescription());
    assertEquals("NativeBatteryMeter", info.getClassName());
    assertTrue(info.isCheckable());
    assertTrue(info.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
    assertEquals("native view label", host.getContentDescription());
    AccessibilityEvent event = AccessibilityEvent.obtain();
    installed.onInitializeAccessibilityEvent(host, event);
    assertEquals("バッテリー残量: 100パーセント 充電完了", event.getContentDescription());
    assertEquals(17, event.getItemCount());
    installed.onPopulateAccessibilityEvent(host, event);
    assertEquals("バッテリー残量: 100パーセント 充電完了", event.getContentDescription());
    assertEquals(18, event.getItemCount());
    assertTrue(installed.dispatchPopulateAccessibilityEvent(host, event));
    assertEquals("バッテリー残量: 100パーセント 充電完了", event.getContentDescription());
    assertEquals(19, event.getItemCount());
    completed.set(false);
    installed.onInitializeAccessibilityNodeInfo(host, info);
    assertEquals("充電中", info.getContentDescription());
    accessibility.detach();
    assertSame(original, host.getAccessibilityDelegate());
  }

  @Test public void originalProviderActionsAndEventRoutingAreForwarded() {
    FrameLayout host = new FrameLayout(RuntimeEnvironment.getApplication());
    View child = new View(host.getContext());
    AccessibilityNodeProvider provider = new AccessibilityNodeProvider() {};
    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
    AccessibilityEvent event = AccessibilityEvent.obtain();
    Bundle arguments = new Bundle();
    List<String> calls = new ArrayList<>();
    host.setAccessibilityDelegate(new View.AccessibilityDelegate() {
      @Override public AccessibilityNodeProvider getAccessibilityNodeProvider(View view) {
        assertSame(host, view);
        calls.add("provider");
        return provider;
      }
      @Override public void addExtraDataToAccessibilityNodeInfo(View view, AccessibilityNodeInfo node,
          String key, Bundle bundle) {
        assertSame(host, view);
        assertSame(info, node);
        assertEquals("native-data", key);
        assertSame(arguments, bundle);
        calls.add("extra");
      }
      @Override public boolean onRequestSendAccessibilityEvent(android.view.ViewGroup view,
          View target, AccessibilityEvent sent) {
        assertSame(host, view);
        assertSame(child, target);
        assertSame(event, sent);
        calls.add("request");
        return false;
      }
      @Override public boolean performAccessibilityAction(View view, int action, Bundle bundle) {
        assertSame(host, view);
        assertEquals(42, action);
        assertSame(arguments, bundle);
        calls.add("action");
        return true;
      }
      @Override public void sendAccessibilityEvent(View view, int type) {
        assertSame(host, view);
        assertEquals(AccessibilityEvent.TYPE_VIEW_FOCUSED, type);
        calls.add("send");
      }
      @Override public void sendAccessibilityEventUnchecked(View view, AccessibilityEvent sent) {
        assertSame(host, view);
        assertSame(event, sent);
        calls.add("unchecked");
      }
    });
    BatteryAccessibility accessibility = new BatteryAccessibility(host, nativeText -> nativeText);
    accessibility.attach();
    View.AccessibilityDelegate installed = host.getAccessibilityDelegate();
    assertSame(provider, installed.getAccessibilityNodeProvider(host));
    installed.addExtraDataToAccessibilityNodeInfo(host, info, "native-data", arguments);
    assertFalse(installed.onRequestSendAccessibilityEvent(host, child, event));
    assertTrue(installed.performAccessibilityAction(host, 42, arguments));
    installed.sendAccessibilityEvent(host, AccessibilityEvent.TYPE_VIEW_FOCUSED);
    installed.sendAccessibilityEventUnchecked(host, event);
    assertEquals(List.of("provider", "extra", "request", "action", "send", "unchecked"), calls);
    accessibility.detach();
  }

  @Test public void attachAndDetachRestoreOwnershipWithoutClobberingAnotherDelegate() {
    View host = attachedView();
    host.setContentDescription("native");
    View.AccessibilityDelegate original = new View.AccessibilityDelegate();
    host.setAccessibilityDelegate(original);
    BatteryAccessibility accessibility = new BatteryAccessibility(host, nativeText -> "full");
    accessibility.attach();
    View.AccessibilityDelegate first = host.getAccessibilityDelegate();
    accessibility.attach();
    assertSame(first, host.getAccessibilityDelegate());
    accessibility.detach();
    assertSame(original, host.getAccessibilityDelegate());
    accessibility.detach();
    assertSame(original, host.getAccessibilityDelegate());
    AccessibilityNodeInfo stale = AccessibilityNodeInfo.obtain();
    first.onInitializeAccessibilityNodeInfo(host, stale);
    assertEquals("native", stale.getContentDescription());
    accessibility.attach();
    View.AccessibilityDelegate second = host.getAccessibilityDelegate();
    assertNotSame(first, second);
    first.onInitializeAccessibilityNodeInfo(host, stale);
    assertEquals("native", stale.getContentDescription());
    View.AccessibilityDelegate replacement = new View.AccessibilityDelegate();
    host.setAccessibilityDelegate(replacement);
    second.onInitializeAccessibilityNodeInfo(host, stale);
    assertEquals("native", stale.getContentDescription());
    accessibility.detach();
    assertSame(replacement, host.getAccessibilityDelegate());
    accessibility.attach();
    accessibility.detach();
    assertSame(replacement, host.getAccessibilityDelegate());
  }

  @Test public void absentNativeDelegateKeepsDefaultSemanticsAndReturnsToNull() {
    View host = attachedView();
    host.setContentDescription("charging");
    BatteryAccessibility accessibility = new BatteryAccessibility(host, nativeText -> "full");
    accessibility.attach();
    assertEquals("full", host.createAccessibilityNodeInfo().getContentDescription());
    assertEquals("charging", host.getContentDescription());
    accessibility.detach();
    assertNull(host.getAccessibilityDelegate());
    assertEquals("charging", host.createAccessibilityNodeInfo().getContentDescription());
  }

  private static View attachedView() {
    Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
    View host = new View(activity);
    activity.setContentView(host);
    assertTrue(host.isAttachedToWindow());
    return host;
  }
}
