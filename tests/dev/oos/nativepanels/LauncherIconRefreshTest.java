package dev.oos.nativepanels;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Looper;
import java.time.Duration;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class LauncherIconRefreshTest {
  private int accent, reloads;
  private String pack = "example.monet";
  private boolean ready = true;
  private Context context;
  private LauncherIconRefresh watcher;
  private final LauncherIconRefresh.Icons icons = new LauncherIconRefresh.Icons() {
    public String pack(Configuration config) { return pack; }
    public boolean reload() {
      if (ready) reloads++;
      return ready;
    }
  };

  @Before public void setup() {
    Context app = RuntimeEnvironment.getApplication();
    Resources system = app.getResources();
    Resources resources = new Resources(system.getAssets(), system.getDisplayMetrics(),
        new Configuration(system.getConfiguration())) {
      @Override public int getColor(int id, Theme theme) {
        return id == android.R.color.system_accent2_500 ? accent : super.getColor(id, theme);
      }
    };
    context = new ContextWrapper(app) {
      @Override public Resources getResources() { return resources; }
    };
    watcher = new LauncherIconRefresh(context, icons);
  }

  private void settle() {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
  }

  @Test public void packPaletteAndNightChangesRefreshOnceAndRotationDoesNot() {
    watcher.setEnabled(true);
    settle();
    assertEquals(1, reloads);
    Configuration config = context.getResources().getConfiguration();
    config.orientation = Configuration.ORIENTATION_LANDSCAPE;
    watcher.onConfigurationChanged(config);
    settle();
    assertEquals(1, reloads);
    accent++;
    watcher.onConfigurationChanged(config);
    watcher.onConfigurationChanged(config);
    settle();
    assertEquals(2, reloads);
    config.uiMode ^= Configuration.UI_MODE_NIGHT_MASK;
    watcher.onConfigurationChanged(config);
    settle();
    assertEquals(3, reloads);
    pack = "another.pack";
    watcher.onConfigurationChanged(config);
    settle();
    assertEquals(4, reloads);
  }

  @Test public void disabledOrStockSkipsWorkAndUnreadyLauncherCanRetry() {
    assertFalse(CombinedQsSettings.enabled(context, CombinedQsSettings.LAUNCHER_ICONS));
    watcher.onConfigurationChanged(context.getResources().getConfiguration());
    settle();
    pack = null;
    watcher.setEnabled(true);
    settle();
    assertEquals(0, reloads);
    pack = "example.monet";
    ready = false;
    watcher.refreshIfNeeded();
    ready = true;
    watcher.refreshIfNeeded();
    assertEquals(1, reloads);
    LauncherIconRefresh recreated = new LauncherIconRefresh(context, icons);
    recreated.setEnabled(true);
    settle();
    assertEquals(1, reloads);
    accent++;
    recreated.onConfigurationChanged(context.getResources().getConfiguration());
    recreated.setEnabled(false);
    settle();
    assertEquals(1, reloads);
  }

  @Test public void scopeIsRequestedOnlyOnEnableAndOnlyForSystemLauncher() {
    OptionalSettingsScope scope = new OptionalSettingsScope(context, CombinedQsSettings.LAUNCHER_ICONS,
        List.of(LauncherIconRefresh.PACKAGE), null);
    scope.bind(new OptionalSettingsScope.Backend() {
      List<String> packages = List.of();
      public List<String> scope() { return packages; }
      public void request(List<String> missing, OptionalSettingsScope.Result callback) {
        assertEquals(List.of("com.android.launcher"), missing);
        packages = List.copyOf(missing);
        callback.approved(packages);
      }
    });
    assertEquals(OptionalSettingsScope.State.OFF, scope.state());
    assertTrue(CombinedQsSettings.setEnabled(context, CombinedQsSettings.LAUNCHER_ICONS, true));
    scope.refresh();
    assertEquals(OptionalSettingsScope.State.MISSING, scope.state());
    scope.request();
    settle();
    assertEquals(OptionalSettingsScope.State.READY, scope.state());
  }
}
