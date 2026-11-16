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
  @Test public void openingAndRecreatingUpdatedSettingsCannotCallTheOlderSystemUiReceiver() {
    IntentFilter legacy = new IntentFilter("dev.oos.nativepanels.COMMAND");
    IntentFilter previous = new IntentFilter("dev.oos.nativepanels.COMMAND." + (BuildConfig.VERSION_CODE - 1));
    IntentFilter current = new IntentFilter(Protocol.ACTION);
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
        assertFalse("A previous versioned host is also isolated", previous.matchAction(request.getAction()));
        assertTrue("The currently loaded version can still reply", current.matchAction(request.getAction()));
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
    assertFalse(reply.values().getBoolean("ready", true));
    assertFalse(reply.values().getBoolean("imeReadable", true));
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
    assertFalse(reply.values().getBoolean("ready", true));
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
