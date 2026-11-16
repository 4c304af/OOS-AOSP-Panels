package dev.oos.nativepanels;

import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;

/** Applies the SettingsLib M3 Expressive dialog surface without rebuilding its native shape. */
final class PermissionDialogColors {
  static String apply(View root) {
    int id = root.getResources().getIdentifier("grant_dialog", "id", "com.android.permissioncontroller");
    return id == 0 ? null : tint(root.findViewById(id));
  }

  static String tint(View dialog) {
    if (dialog == null || Build.VERSION.SDK_INT < 34) return null;
    Drawable background = dialog.getBackground();
    if (background == null) return null;
    Resources resources = dialog.getResources();
    boolean dark = (resources.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
        == Configuration.UI_MODE_NIGHT_YES;
    int token = dark ? android.R.color.system_surface_container_high_dark
        : android.R.color.system_surface_container_high_light;
    // PermissionController's legacy night theme fixes colorBackground to google_grey_800.
    // SettingsLib/SettingsTheme/res/values-v36/themes_expressive.xml assigns
    // SurfaceContainerHigh to its Expressive AlertDialog. Framework
    // tokens retain the system's Monet palette and contrast settings for both modes.
    int color = resources.getColor(token, dialog.getContext().getTheme());
    String role = resources.getResourceEntryName(token);
    dialog.setBackground(background.mutate());
    dialog.setBackgroundTintMode(PorterDuff.Mode.SRC_IN);
    dialog.setBackgroundTintList(ColorStateList.valueOf(color));
    return role;
  }

  private PermissionDialogColors() {}
}
