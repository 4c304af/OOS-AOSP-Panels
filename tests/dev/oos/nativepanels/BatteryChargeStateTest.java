package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.BatteryManager;
import android.view.View;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BatteryChargeStateTest {
  @Test public void completedStatusIsIndependentOfRoundedPercentageAndChargeLimiting() {
    assertFalse(BatteryChargeState.isFullyCharged(battery(BatteryManager.BATTERY_STATUS_CHARGING, 100)));
    assertTrue(BatteryChargeState.isFullyCharged(battery(BatteryManager.BATTERY_STATUS_FULL, 100)));
    assertTrue(BatteryChargeState.isFullyCharged(battery(BatteryManager.BATTERY_STATUS_FULL, 99)));
    assertFalse(BatteryChargeState.isFullyCharged(battery(BatteryManager.BATTERY_STATUS_NOT_CHARGING, 80)));
    assertFalse(BatteryChargeState.isFullyCharged(battery(BatteryManager.BATTERY_STATUS_DISCHARGING, 100)));
    assertFalse(BatteryChargeState.isFullyCharged(battery(BatteryManager.BATTERY_STATUS_UNKNOWN, 100)));
    assertFalse(BatteryChargeState.isFullyCharged(new Intent(Intent.ACTION_BATTERY_CHANGED)));
    assertFalse(BatteryChargeState.isFullyCharged(null));
    assertFalse(BatteryChargeState.isFullyCharged(battery(BatteryManager.BATTERY_STATUS_FULL, 100)
        .putExtra(BatteryManager.EXTRA_PRESENT, false)));
  }

  @Test public void samePercentageChargeCompletionAndResumptionUpdateWithoutPolling() {
    Fixture fixture = new Fixture(battery(BatteryManager.BATTERY_STATUS_CHARGING, 100));
    assertEquals(0, fixture.context.registrations);
    fixture.attach();
    assertEquals(1, fixture.context.registrations);
    assertTrue(fixture.changes.isEmpty());
    fixture.context.emit(battery(BatteryManager.BATTERY_STATUS_FULL, 100));
    assertEquals(List.of(true), fixture.changes);
    for (int i = 0; i < 100; i++) fixture.context.emit(battery(BatteryManager.BATTERY_STATUS_FULL, 100));
    assertEquals(List.of(true), fixture.changes);
    fixture.context.emit(battery(BatteryManager.BATTERY_STATUS_CHARGING, 100));
    assertEquals(List.of(true, false), fixture.changes);
    fixture.context.emit(battery(BatteryManager.BATTERY_STATUS_FULL, 100));
    fixture.context.emit(battery(BatteryManager.BATTERY_STATUS_DISCHARGING, 100));
    assertEquals(List.of(true, false, true, false), fixture.changes);
    assertEquals(1, fixture.context.registrations);
    fixture.detach();
    assertEquals(1, fixture.context.unregistrations);
  }

  @Test public void stickyFullStateIsAppliedOnAttachAndOldCallbacksCannotSurviveDetach() {
    Fixture fixture = new Fixture(battery(BatteryManager.BATTERY_STATUS_FULL, 100));
    fixture.attach();
    assertEquals(List.of(true), fixture.changes);
    fixture.state.onViewAttachedToWindow(fixture.view);
    assertEquals(1, fixture.context.registrations);
    BroadcastReceiver old = fixture.context.receiver;
    fixture.detach();
    assertEquals(List.of(true, false), fixture.changes);
    assertEquals(1, fixture.context.unregistrations);
    old.onReceive(fixture.context, battery(BatteryManager.BATTERY_STATUS_FULL, 100));
    assertEquals(List.of(true, false), fixture.changes);
    fixture.context.sticky = battery(BatteryManager.BATTERY_STATUS_CHARGING, 100);
    fixture.attach();
    assertEquals(2, fixture.context.registrations);
    assertEquals(List.of(true, false), fixture.changes);
    old.onReceive(fixture.context, battery(BatteryManager.BATTERY_STATUS_FULL, 100));
    assertEquals(List.of(true, false), fixture.changes);
    fixture.context.emit(battery(BatteryManager.BATTERY_STATUS_FULL, 100));
    assertEquals(List.of(true, false, true), fixture.changes);
    fixture.detach();
    assertEquals(2, fixture.context.unregistrations);
  }

  @Test public void missingStickyStateAndUnrelatedBroadcastsKeepNativePresentation() {
    Fixture fixture = new Fixture(null);
    fixture.attach();
    fixture.context.emit(new Intent("unrelated").putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_FULL));
    assertTrue(fixture.changes.isEmpty());
    fixture.context.emit(battery(BatteryManager.BATTERY_STATUS_FULL, 100));
    fixture.context.emit(new Intent(Intent.ACTION_BATTERY_CHANGED));
    assertEquals(List.of(true, false), fixture.changes);
    fixture.detach();
  }

  @Test public void unavailableReceiverFailsToNativeAndCanRetryOnTheNextAttachment() {
    Fixture fixture = new Fixture(battery(BatteryManager.BATTERY_STATUS_FULL, 100));
    fixture.context.reject = true;
    fixture.attach();
    assertTrue(fixture.changes.isEmpty());
    fixture.detach();
    assertEquals(0, fixture.context.unregistrations);
    fixture.context.reject = false;
    fixture.attach();
    assertEquals(List.of(true), fixture.changes);
    fixture.detach();
    assertEquals(1, fixture.context.unregistrations);
  }

  @Test public void onlyARealFullChargingAttributionLosesItsBoltAndChargingPalette() {
    Object bolt = new Object(), defender = new Object(), saver = new Object(), unknown = new Object();
    assertFalse(ShadeBattery.completedCharging(
        BatteryChargeState.isFullyCharged(battery(BatteryManager.BATTERY_STATUS_CHARGING, 100)), bolt, bolt));
    boolean full = BatteryChargeState.isFullyCharged(battery(BatteryManager.BATTERY_STATUS_FULL, 100));
    assertTrue(ShadeBattery.completedCharging(full, bolt, bolt));
    for (Object nativeGlyph : new Object[]{defender, saver, unknown, null})
      assertFalse(ShadeBattery.completedCharging(full, nativeGlyph, bolt));
    assertFalse(ShadeBattery.completedCharging(false, bolt, bolt));
    assertFalse(ShadeBattery.completedCharging(true, null, null));
  }

  @Test public void completedAccessibilityUsesNativeLocalizedLevelAndFullText() {
    NativeStrings strings = new NativeStrings(true, true);
    Context context = withResources(strings);
    assertEquals("バッテリー残量: 100パーセント 充電完了", ShadeBattery.completedDescription(context, 100, "充電中"));
    assertEquals("充電完了", ShadeBattery.completedDescription(context, null, "充電中"));
    assertEquals(Protocol.TARGET, strings.requestedPackage);
  }

  @Test public void completedAccessibilityFallsBackToAvailableNativeResources() {
    assertEquals("バッテリー残量: 100パーセント", ShadeBattery.completedDescription(
        withResources(new NativeStrings(true, false)), 100, "充電中"));
    assertEquals("充電完了", ShadeBattery.completedDescription(
        withResources(new NativeStrings(false, true)), 100, "充電中"));
    assertEquals("native description", ShadeBattery.completedDescription(
        withResources(new NativeStrings(false, false)), 100, "native description"));
  }

  private static Intent battery(int status, int level) {
    return new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_STATUS, status)
        .putExtra(BatteryManager.EXTRA_LEVEL, level).putExtra(BatteryManager.EXTRA_SCALE, 100)
        .putExtra(BatteryManager.EXTRA_PRESENT, true);
  }

  private static Context withResources(Resources resources) {
    return new ContextWrapper(RuntimeEnvironment.getApplication()) {
      @Override public Resources getResources() { return resources; }
    };
  }

  private static final class Fixture {
    final Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
    final FrameLayout root = new FrameLayout(activity);
    final BatteryContext context;
    final View view;
    final List<Boolean> changes = new ArrayList<>();
    final BatteryChargeState state;
    Fixture(Intent sticky) {
      activity.setContentView(root);
      context = new BatteryContext(activity, sticky);
      view = new View(context);
      state = new BatteryChargeState(view, changes::add);
    }
    void attach() { root.addView(view); }
    void detach() { root.removeView(view); }
  }

  private static final class BatteryContext extends ContextWrapper {
    Intent sticky;
    BroadcastReceiver receiver;
    int registrations, unregistrations;
    boolean reject;
    BatteryContext(Context base, Intent sticky) { super(base); this.sticky = sticky; }
    @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
      registrations++;
      assertTrue(filter.hasAction(Intent.ACTION_BATTERY_CHANGED));
      if (reject) throw new SecurityException("unavailable");
      this.receiver = receiver;
      return sticky;
    }
    @Override public void unregisterReceiver(BroadcastReceiver receiver) {
      assertSame(this.receiver, receiver);
      this.receiver = null;
      unregistrations++;
    }
    void emit(Intent intent) {
      assertNotNull(receiver);
      receiver.onReceive(this, intent);
    }
  }

  private static final class NativeStrings extends Resources {
    final boolean level, full;
    String requestedPackage;
    NativeStrings(boolean level, boolean full) {
      super(RuntimeEnvironment.getApplication().getAssets(),
          RuntimeEnvironment.getApplication().getResources().getDisplayMetrics(),
          RuntimeEnvironment.getApplication().getResources().getConfiguration());
      this.level = level;
      this.full = full;
    }
    @Override public int getIdentifier(String name, String type, String packageName) {
      requestedPackage = packageName;
      if (name.equals("accessibility_battery_level")) return level ? 1 : 0;
      if (name.equals("keyguard_charge_full")) return full ? 2 : 0;
      return 0;
    }
    @Override public String getString(int id) {
      return id == 1 ? "バッテリー残量: %dパーセント" : "充電完了";
    }
    @Override public String getString(int id, Object... args) {
      return String.format(Locale.JAPANESE, getString(id), args);
    }
  }
}
