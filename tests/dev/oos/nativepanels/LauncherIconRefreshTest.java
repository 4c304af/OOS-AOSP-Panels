package dev.oos.nativepanels;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import io.github.libxposed.api.XposedInterface;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class LauncherIconRefreshTest {
  private int accent, reloads;
  private String pack = "example.monet";
  private boolean ready = true;
  private Context context;
  private LauncherIconRefresh watcher;
  private LauncherIconSource source;
  private File choices;
  private final LauncherIconRefresh.Icons icons = new LauncherIconRefresh.Icons() {
    public String pack() { return pack; }
    public boolean reload() {
      if (ready) reloads++;
      return ready;
    }
  };

  @Before public void setup() throws Exception {
    Context app = RuntimeEnvironment.getApplication();
    Resources system = app.getResources();
    Resources resources = new Resources(system.getAssets(), system.getDisplayMetrics(),
        new Configuration(system.getConfiguration())) {
      @Override public int getColor(int id, Theme theme) {
        return id == android.R.color.system_accent2_500 ? accent : super.getColor(id, theme);
      }
      @Override public Drawable getDrawable(int id, Theme theme) {
        if (id == R.drawable.ic_refresh) return new ColorDrawable(accent);
        return super.getDrawable(id, theme);
      }
    };
    context = new ContextWrapper(app) {
      @Override public Resources getResources() { return resources; }
      @Override public Context createPackageContext(String name, int flags) throws android.content.pm.PackageManager.NameNotFoundException {
        return getPackageName().equals(name) ? this : super.createPackageContext(name, flags);
      }
    };
    choices = Files.createTempDirectory(app.getCacheDir().toPath(), "icon-choices").toFile();
    source = new LauncherIconSource(choices);
    watcher = new LauncherIconRefresh(context, icons, source);
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
    LauncherIconRefresh recreated = new LauncherIconRefresh(context, icons, source);
    recreated.setEnabled(true);
    settle();
    assertEquals(1, reloads);
    accent++;
    recreated.onConfigurationChanged(context.getResources().getConfiguration());
    recreated.setEnabled(false);
    settle();
    assertEquals(1, reloads);
  }

  @Test public void individualEditUsesItsOwnPackAndSourceAfterPaletteChangesEvenOnStockTheme() throws Exception {
    pack = null;
    ComponentName component = new ComponentName("example.edited", "example.edited.Home");
    File cfg = new File(choices, "example.edited.cfg");
    String selection = "chosse_icon_pack_name=" + context.getPackageName() + "\nchoose_drawable_res_name=ic_refresh\n";
    Files.write(cfg.toPath(), selection.getBytes(StandardCharsets.UTF_8));
    File png = new File(choices, "example.edited.png");
    byte[] oldPreview = {1, 2, 3};
    Files.write(png.toPath(), oldPreview);
    watcher.setEnabled(true);
    settle();
    assertEquals(1, reloads);
    assertEquals(accent, ((ColorDrawable) source.edited(context, component)).getColor());
    accent++;
    watcher.onConfigurationChanged(context.getResources().getConfiguration());
    settle();
    assertEquals(2, reloads);
    assertEquals(accent, ((ColorDrawable) source.edited(context, component)).getColor());
    assertArrayEquals(selection.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(cfg.toPath()));
    assertArrayEquals(oldPreview, Files.readAllBytes(png.toPath()));
    Files.delete(cfg.toPath());
    assertFalse(source.hasEdits());
    assertNull(source.edited(context, component));
  }

  @Test public void dialerAndContactsKeepSeparateChoicesAndMissingPackFallsBack() throws Exception {
    ComponentName dialer = ComponentName.unflattenFromString("com.android.contacts/.DialtactsActivityAlias");
    ComponentName contacts = ComponentName.unflattenFromString("com.android.contacts/.PeopleActivityAlias");
    Files.write(new File(choices, "dialer_com.android.contacts.cfg").toPath(),
        ("chosse_icon_pack_name=" + context.getPackageName() + "\nchoose_drawable_res_name=ic_refresh\n").getBytes(StandardCharsets.UTF_8));
    Files.write(new File(choices, "com.android.contacts.cfg").toPath(),
        "chosse_icon_pack_name=uninstalled.pack\nchoose_drawable_res_name=some_icon\n".getBytes(StandardCharsets.UTF_8));
    assertNotNull(source.edited(context, dialer));
    assertNull(source.edited(context, contacts));
  }

  private static int evictions;
  public static void clearActivityIcon(android.content.pm.PackageManager pm, ComponentName component) {
    evictions++;
  }

  private interface NativeCall { Object run() throws Throwable; }

  private XposedInterface.Chain chain(ComponentName arg, NativeCall proceed) {
    return (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> switch (method.getName()) {
          case "getArg" -> arg;
          case "proceed" -> proceed.run();
          default -> throw new AssertionError(method);
        });
  }

  @Test public void renderingEvictsOncePerPaletteAndProfileAndScopesTheEditedDrawable() throws Throwable {
    ComponentName component = new ComponentName("example.edited", "example.edited.Main");
    Files.write(new File(choices, "example.edited.cfg").toPath(),
        ("chosse_icon_pack_name=" + context.getPackageName() + "\nchoose_drawable_res_name=ic_refresh\n").getBytes(StandardCharsets.UTF_8));
    Method clear = getClass().getDeclaredMethod("clearActivityIcon", android.content.pm.PackageManager.class, ComponentName.class);
    UserHandle user = Process.myUserHandle();
    Object stock = new Object();
    XposedInterface.Chain pm = chain(component, () -> stock);
    XposedInterface.Chain render = chain(null, () -> source.activityIcon(pm));
    evictions = 0;
    assertSame(stock, source.loadIcon(render, context, user, component, clear));
    assertEquals(0, evictions);

    source.setPalette("light");
    XposedInterface.Chain nested = chain(null, () -> {
      assertSame(stock, source.activityIcon(chain(new ComponentName("other", "Main"), () -> stock)));
      assertSame(stock, source.loadIcon(render, context, UserHandle.getUserHandleForUid(1000000), component, clear));
      return source.activityIcon(pm);
    });
    assertEquals(accent, ((ColorDrawable) source.loadIcon(nested, context, user, component, clear)).getColor());
    source.loadIcon(render, context, user, component, clear);
    assertEquals(2, evictions);
    assertSame(stock, source.activityIcon(pm));

    source.setPalette("dark");
    accent++;
    assertEquals(accent, ((ColorDrawable) source.loadIcon(render, context, user, component, clear)).getColor());
    assertThrows(IllegalStateException.class, () -> source.loadIcon(
        chain(null, () -> { throw new IllegalStateException(); }), context, user, component, clear));
    assertEquals(3, evictions);
    assertSame(stock, source.activityIcon(pm));
    source.setPalette(null);
    assertSame(stock, source.loadIcon(render, context, user, component, clear));
  }

  @Test public void scopeIsRequestedOnlyOnEnableAndOnlyForSystemLauncher() {
    java.util.ArrayList<OptionalSettingsScope.RestartResult> jobs = new java.util.ArrayList<>();
    OptionalSettingsScope scope = new OptionalSettingsScope(context, CombinedQsSettings.LAUNCHER_ICONS,
        List.of(LauncherIconRefresh.PACKAGE), (allowed, result) -> jobs.add(result), false);
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
    assertEquals(OptionalSettingsScope.State.ADDED, scope.state());
    assertTrue(jobs.isEmpty());
    org.robolectric.util.ReflectionHelpers.setField(RuntimeEnvironment.getApplication(), "launcherScope", scope);
    try (var screen = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
      settle();
      android.widget.Button button = screen.get().getWindow().getDecorView().findViewWithTag("launcher_scope_retry");
      assertEquals(screen.get().getString(R.string.restart_launcher), button.getText());
      assertTrue(button.isShown());
      button.performClick();
      assertFalse(button.isEnabled());
      scope.restart();
      assertEquals(1, jobs.size());
      jobs.get(0).completed(RootRestart.Result.REQUESTED);
      settle();
      assertTrue(button.isEnabled());
      assertEquals(OptionalSettingsScope.State.READY, scope.state());
    }
  }

  @Test public void systemLauncherCanReadItsSwitchWithoutASystemUiPermission() {
    LauncherIconSettingsProvider provider = Robolectric.setupContentProvider(LauncherIconSettingsProvider.class);
    PackageInfo info = new PackageInfo();
    info.packageName = LauncherIconRefresh.PACKAGE;
    info.applicationInfo = new ApplicationInfo();
    info.applicationInfo.packageName = info.packageName;
    info.applicationInfo.uid = 10431;
    info.applicationInfo.flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
    shadowOf(context.getPackageManager()).installPackage(info);
    String key = CombinedQsSettings.LAUNCHER_ICONS;
    assertTrue(CombinedQsSettings.setEnabled(context, key, true));
    try {
      ShadowBinder.setCallingUid(info.applicationInfo.uid);
      assertTrue(context.getContentResolver().call(CombinedQsSettings.LAUNCHER_URI, key, null, null).getBoolean(key));
      assertThrows(IllegalArgumentException.class, () -> provider.call(CombinedQsSettings.ABOUT_PHONE, null, null));
      ShadowBinder.setCallingUid(10432);
      assertThrows(SecurityException.class, () -> provider.call(key, null, null));
    } finally { ShadowBinder.reset(); }
  }
}
