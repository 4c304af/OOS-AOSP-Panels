package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Bundle;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ModuleUpdateTest {
  @Test public void openingAndRecreatingSettingsUsesTheCompatibleSystemUiReceiver() {
    IntentFilter legacy = new IntentFilter("dev.oos.nativepanels.COMMAND");
    IntentFilter previous = new IntentFilter("dev.oos.nativepanels.COMMAND.87");
    IntentFilter current = new IntentFilter("dev.oos.nativepanels.COMMAND." + BuildConfig.SYSTEM_UI_REVISION);
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup().visible()) {
      ShadowLooper.idleMainLooper();
      controller.recreate();
      ShadowLooper.idleMainLooper();
      List<Intent> requests = Shadows.shadowOf(controller.get().getApplication()).getBroadcastIntents();
      int statuses = 0;
      for (Intent request : requests) {
        if (!Protocol.TARGET.equals(request.getPackage())) continue;
        assertEquals("Opening settings only requests a status", "status", request.getStringExtra("command"));
        assertFalse("The pre-upgrade host must not format new APK resources", legacy.matchAction(request.getAction()));
        assertFalse("An incompatible legacy channel is isolated", previous.matchAction(request.getAction()));
        assertTrue("Settings must contact only the matching host revision", current.matchAction(request.getAction()));
        statuses++;
      }
      assertTrue(statuses >= 2);
    }
  }

  @Test public void missingResourcesProduceAFailedReplyWithoutRerunningTheCommand() {
    AtomicInteger calls = new AtomicInteger();
    Resources.NotFoundException missing = new Resources.NotFoundException("APK resource replaced");
    RuntimeReply reply = RuntimeReply.capture(() -> {
      calls.incrementAndGet();
      throw missing;
    });
    assertEquals(1, calls.get());
    assertEquals(-1, reply.code());
    assertFalse(Protocol.hasLiveState(reply.values()));
    assertTrue(reply.values().isEmpty());
    assertSame(missing, reply.failure());
    assertTrue(reply.report().contains("NotFoundException"));
  }

  @Test public void incompatibleFormatStringsCannotEscapeTheReplyBoundary() {
    RuntimeReply reply = RuntimeReply.capture(() -> {
      // An old R ID can now point to a differently formatted string after an APK update.
      String.format("%d", "old argument");
      throw new AssertionError("format must fail");
    });
    assertEquals(-1, reply.code());
    assertTrue(reply.failure() instanceof java.util.IllegalFormatConversionException);
    assertFalse(Protocol.hasLiveState(reply.values()));
  }

  @Test public void notificationCardsAreOnlySavedWhenTheInstalledHookCanApplyThem() throws Exception {
    try (TestSystemUiReceiver host = new TestSystemUiReceiver()) {
      String key = "notification_cards";
      Intent enable = new Intent().putExtra("command", "set").putExtra("feature", key).putExtra("enabled", true);
      RuntimeReply rejected = host.runtime.handleCommand(enable);
      assertEquals(Protocol.UNAVAILABLE, rejected.code());
      assertFalse(host.preferences.contains(key));
      assertFalse(rejected.values().getBundle(Protocol.AVAILABLE).getBoolean(key));
      host.enableCardHook();
      // This host has no diagnostic resources. A formatting error must not undo or hide a write.
      RuntimeReply applied = host.runtime.handleCommand(enable);
      assertNotNull(applied.failure());
      assertEquals(1, applied.code());
      assertTrue(Protocol.hasLiveState(applied.values()));
      assertTrue(applied.values().getBoolean(key));
      assertTrue(host.preferences.getBoolean(key, false));
      host.runtime.combinedQs.status = R.string.combined_oos;
      assertEquals(Protocol.UNAVAILABLE, host.runtime.handleCommand(enable).code());
      assertTrue(host.preferences.getBoolean(key, false));
      RuntimeReply disabled = host.runtime.handleCommand(enable.putExtra("enabled", false));
      assertEquals(1, disabled.code());
      assertFalse(disabled.values().getBoolean(key));
      assertFalse(host.preferences.getBoolean(key, true));
    }
  }

  @Test public void staleOrPartialStatusCannotBeMistakenForLiveSettings() throws Exception {
    try (TestSystemUiReceiver host = new TestSystemUiReceiver()) {
      Bundle state = host.runtime.handleCommand(new Intent().putExtra("command", "status")).values();
      assertTrue(Protocol.hasLiveState(state));
      state.putString(Protocol.REVISION, "89");
      assertFalse(Protocol.hasLiveState(state));
      state.putString(Protocol.REVISION, BuildConfig.SYSTEM_UI_REVISION);
      state.remove("notification_cards");
      assertFalse(Protocol.hasLiveState(state));
      state.putBoolean("notification_cards", false);
      state.getBundle(Protocol.AVAILABLE).remove("notification_cards");
      assertFalse(Protocol.hasLiveState(state));
    }
  }

  @Test public void validRepliesKeepTheirOriginalStateAndDiagnostics() {
    Bundle values = new Bundle();
    values.putBoolean("ready", true);
    values.putBoolean("notification_cards", true);
    RuntimeReply expected = new RuntimeReply(1, "Current SystemUI status", values, null);
    RuntimeReply actual = RuntimeReply.capture(() -> expected);
    assertSame(expected, actual);
    assertTrue(actual.values().getBoolean("notification_cards"));
    assertNull(actual.failure());
  }

  @Test public void delayedToastFormattingSurvivesMissingOrChangedResources() {
    Resources resources = RuntimeEnvironment.getApplication().getResources();
    assertEquals(resources.getString(R.string.enabled_label),
        RuntimeReply.text(resources, R.string.enabled_label));
    assertEquals("AOSP Panels", RuntimeReply.text(resources, 0));
    assertEquals("AOSP Panels", RuntimeReply.text(null, R.string.enabled_label));
    assertEquals("AOSP Panels", RuntimeReply.text(resources,
        com.google.android.material.R.string.character_counter_pattern, "not a number", 2));
  }
}
