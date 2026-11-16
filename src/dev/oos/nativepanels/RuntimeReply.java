package dev.oos.nativepanels;

import android.content.res.Resources;
import android.os.Bundle;
import java.util.function.Supplier;

/** Diagnostic formatting must never terminate the SystemUI broadcast receiver. */
record RuntimeReply(int code, String report, Bundle values, RuntimeException failure) {
  static RuntimeReply capture(Supplier<RuntimeReply> action) {
    try {
      return action.get();
    } catch (RuntimeException error) {
      Bundle values = new Bundle();
      values.putBoolean("ready", false);
      values.putBoolean("imeReadable", false);
      return new RuntimeReply(-1, "AOSP Panels " + BuildConfig.VERSION_NAME
          + "\nSystemUI response unavailable: " + error.getClass().getSimpleName(), values, error);
    }
  }

  static String text(Resources resources, int id, Object... arguments) {
    try {
      return resources.getString(id, arguments);
    } catch (RuntimeException unavailable) {
      // Posted toasts can outlive an APK update too. Keep this fallback resource-free.
      return "AOSP Panels";
    }
  }
}
