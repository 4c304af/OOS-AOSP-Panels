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
      return new RuntimeReply(-1, fallback(error), Bundle.EMPTY, error);
    }
  }

  static RuntimeReply withReport(int code, Bundle values, Supplier<String> report) {
    try {
      return new RuntimeReply(code, report.get(), values, null);
    } catch (RuntimeException error) {
      // Formatting is optional. The command has already run and its settings were read back.
      return new RuntimeReply(code, fallback(error), values, error);
    }
  }

  private static String fallback(RuntimeException error) {
    return "AOSP Panels " + BuildConfig.VERSION_NAME + "\nSystemUI diagnostics unavailable: "
        + error.getClass().getSimpleName();
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
