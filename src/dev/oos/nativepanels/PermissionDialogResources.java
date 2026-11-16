package dev.oos.nativepanels;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.ParcelFileDescriptor;
import android.view.ContextThemeWrapper;
import java.io.File;
import java.io.IOException;

/** Reuses the installed APK's resources without changing its application/Activity resources. */
final class PermissionDialogResources {
  private ResourcesLoader loader;
  private String source;

  Context create(Activity activity) throws IOException {
    Resources original = activity.getResources();
    // A new wrapper follows the Activity's display/configuration, but adding a loader
    // forks only its ResourcesImpl. Never modify a shared or application Resources.
    Context isolated = activity.createConfigurationContext(new Configuration());
    Resources resources = isolated.getResources();
    if (resources == original || resources == activity.getApplicationContext().getResources())
      throw new IllegalStateException("Permission UI resources are not isolated");
    // Google's manifest package differs from its original AOSP resource namespace.
    requireStyle(resources, "PermissionGrantButtonExpressive");
    requireStyle(resources, "PermissionGrantDialogMaterial3");
    resources.addLoaders(loader(activity.getApplicationInfo().sourceDir));
    Resources.Theme theme = resources.newTheme();
    theme.setTo(activity.getTheme());
    return new PresentationContext(activity, resources, theme);
  }

  private static void requireStyle(Resources resources, String name) {
    if (resources.getIdentifier(name, "style", "com.android.permissioncontroller") == 0)
      throw new Resources.NotFoundException("Missing native permission style: " + name);
  }

  private synchronized ResourcesLoader loader(String apk) throws IOException {
    if (loader != null) {
      if (!source.equals(apk)) throw new IllegalStateException("Permission APK changed; restart its process");
      return loader;
    }
    // A single immutable provider is shared by presentation contexts for this process.
    // The FD is duplicated by loadFromApk; the loader retains the provider while in use.
    try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(new File(apk), ParcelFileDescriptor.MODE_READ_ONLY)) {
      ResourcesProvider provider = ResourcesProvider.loadFromApk(fd);
      ResourcesLoader candidate = new ResourcesLoader();
      try { candidate.addProvider(provider); }
      catch (RuntimeException failure) { provider.close(); throw failure; }
      source = apk;
      loader = candidate;
      return loader;
    }
  }

  private static final class PresentationContext extends ContextThemeWrapper {
    private final Resources resources;

    PresentationContext(Activity activity, Resources resources, Resources.Theme theme) {
      super(activity, theme);
      this.resources = resources;
    }

    @Override public Resources getResources() { return resources; }
    @Override public AssetManager getAssets() { return resources.getAssets(); }
  }
}
