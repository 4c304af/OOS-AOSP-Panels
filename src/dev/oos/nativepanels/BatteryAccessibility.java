package dev.oos.nativepanels;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import java.util.function.UnaryOperator;

/** Correct the existing meter's spoken label without replacing its accessibility behavior. */
final class BatteryAccessibility {
  private final View host;
  private final UnaryOperator<CharSequence> description;
  private View.AccessibilityDelegate original;
  private View.AccessibilityDelegate installed;

  BatteryAccessibility(View host, UnaryOperator<CharSequence> description) {
    this.host = host;
    this.description = description;
  }

  void attach() {
    if (installed != null) return;
    original = host.getAccessibilityDelegate();
    View.AccessibilityDelegate delegate = original == null ? new View.AccessibilityDelegate() : original;
    installed = new View.AccessibilityDelegate() {
      @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
        delegate.onInitializeAccessibilityNodeInfo(host, info);
        info.setContentDescription(describe(info.getContentDescription()));
      }
      @Override public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
        delegate.onInitializeAccessibilityEvent(host, event);
        describe(event);
      }
      @Override public void onPopulateAccessibilityEvent(View host, AccessibilityEvent event) {
        delegate.onPopulateAccessibilityEvent(host, event);
        describe(event);
      }
      @Override public boolean dispatchPopulateAccessibilityEvent(View host, AccessibilityEvent event) {
        boolean populated = delegate.dispatchPopulateAccessibilityEvent(host, event);
        describe(event);
        return populated;
      }
      private void describe(AccessibilityEvent event) {
        event.setContentDescription(describe(event.getContentDescription()));
      }
      private CharSequence describe(CharSequence nativeDescription) {
        return installed == this && BatteryAccessibility.this.host.getAccessibilityDelegate() == this
            ? description.apply(nativeDescription) : nativeDescription;
      }
      @Override public AccessibilityNodeProvider getAccessibilityNodeProvider(View host) {
        return delegate.getAccessibilityNodeProvider(host);
      }
      @Override public void addExtraDataToAccessibilityNodeInfo(View host, AccessibilityNodeInfo info,
          String key, Bundle arguments) {
        delegate.addExtraDataToAccessibilityNodeInfo(host, info, key, arguments);
      }
      @Override public boolean onRequestSendAccessibilityEvent(ViewGroup host, View child, AccessibilityEvent event) {
        return delegate.onRequestSendAccessibilityEvent(host, child, event);
      }
      @Override public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
        return delegate.performAccessibilityAction(host, action, arguments);
      }
      @Override public void sendAccessibilityEvent(View host, int eventType) {
        delegate.sendAccessibilityEvent(host, eventType);
      }
      @Override public void sendAccessibilityEventUnchecked(View host, AccessibilityEvent event) {
        delegate.sendAccessibilityEventUnchecked(host, event);
      }
    };
    host.setAccessibilityDelegate(installed);
  }

  void detach() {
    // Another owner may have installed a new delegate while the meter was attached.
    if (installed != null && host.getAccessibilityDelegate() == installed) host.setAccessibilityDelegate(original);
    original = null;
    installed = null;
  }
}
