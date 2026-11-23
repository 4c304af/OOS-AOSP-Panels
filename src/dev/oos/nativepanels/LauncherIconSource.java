package dev.oos.nativepanels;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.os.UserHandle;
import io.github.libxposed.api.XposedInterface;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/** Supplies fresh input to the launcher's existing bitmap sizing, masking and badging code. */
final class LauncherIconSource {
  private static final ComponentName DIALER = ComponentName.unflattenFromString("com.android.contacts/.DialtactsActivityAlias");
  private final File choices;
  private final ThreadLocal<OverrideIcon> drawing = new ThreadLocal<>();
  private record OverrideIcon(ComponentName component, Drawable drawable) {}
  private record Target(UserHandle user, ComponentName component) {}
  private record Palette(String signature, Set<Target> cleared) {}
  private volatile Palette palette;

  LauncherIconSource(File choices) { this.choices = choices; }

  void setPalette(String signature) {
    Palette current = palette;
    if (signature == null) palette = null;
    else if (current == null || !signature.equals(current.signature)) {
      palette = new Palette(signature, new HashSet<>());
    }
  }

  boolean hasEdits() {
    String[] files = choices.list((dir, name) -> name.endsWith(".cfg"));
    return files != null && files.length != 0;
  }

  Drawable edited(Context context, ComponentName component) {
    // OOS saves both a flattened PNG and the original pack/resource pair.
    String name = (DIALER.equals(component) ? "dialer_" : "") + component.getPackageName();
    File file = new File(choices, name + ".cfg");
    if (!file.isFile()) return null;
    Properties choice = new Properties();
    try (FileInputStream in = new FileInputStream(file)) {
      choice.load(in);
      String pack = choice.getProperty("chosse_icon_pack_name", "");
      String resource = choice.getProperty("choose_drawable_res_name", "");
      if (pack.isEmpty() || resource.isEmpty()) return null;
      Context packContext = context.createPackageContext(pack, 0);
      Resources res = packContext.getResources();
      int id = res.getIdentifier(resource, "drawable", pack);
      if (id == 0 || !pack.equals(res.getResourcePackageName(id))
          || !"drawable".equals(res.getResourceTypeName(id))) return null;
      return res.getDrawable(id, packContext.getTheme());
    } catch (IOException | PackageManager.NameNotFoundException | RuntimeException missingChoice) {
      return null;
    }
  }

  void install(ModuleEntry module, ClassLoader loader) throws ReflectiveOperationException {
    Method load = Reflect.method(loader.loadClass("com.android.launcher3.icons.OplusLauncherActivityCachingLogic"),
        "loadIcon", Context.class, LauncherActivityInfo.class);
    Method getActivityIcon = Reflect.method(loader.loadClass("android.app.ApplicationPackageManager"),
        "getActivityIcon", ComponentName.class);
    Method clear = Reflect.method(loader.loadClass("com.oplus.icon.OplusUxIconManager"),
        "clearCachedIconForActivity", PackageManager.class, ComponentName.class);
    module.deoptimize(load);
    module.hook(getActivityIcon).setId("native-panels:edited-launcher-icon").intercept(this::activityIcon);
    module.hook(load).setId("native-panels:launcher-icon-source").intercept(chain -> {
      LauncherActivityInfo info = (LauncherActivityInfo) chain.getArg(1);
      if (info == null) return chain.proceed();
      return loadIcon(chain, (Context) chain.getArg(0), info.getUser(), info.getComponentName(), clear);
    });
  }

  Object activityIcon(XposedInterface.Chain chain) throws Throwable {
    OverrideIcon edited = drawing.get();
    if (edited != null && edited.component.equals(chain.getArg(0))) return edited.drawable;
    return chain.proceed();
  }

  Object loadIcon(XposedInterface.Chain chain, Context context, UserHandle user, ComponentName component,
      Method clear) throws Throwable {
    Palette current = palette;
    OverrideIcon replacement = null;
    if (current != null && component != null && context != null) {
      Target target = new Target(user, component);
      // OplusLauncherModel.onAppIconUpdated evicts this framework cache before binding.
      try {
        synchronized (current) {
          if (!current.cleared.contains(target)) {
            clear.invoke(null, context.getPackageManager(), component);
            current.cleared.add(target);
          }
        }
      } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
        // A saved pack selection can still supply a fresh drawable.
      }
      if (Process.myUserHandle().equals(user)) {
        Drawable drawable = edited(context, component);
        if (drawable != null) replacement = new OverrideIcon(component, drawable);
      }
    }
    OverrideIcon previous = drawing.get();
    if (previous == null && replacement == null) return chain.proceed();
    drawing.set(replacement);
    try { return chain.proceed(); }
    finally {
      if (previous == null) drawing.remove();
      else drawing.set(previous);
    }
  }
}
