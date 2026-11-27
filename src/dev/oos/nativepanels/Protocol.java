package dev.oos.nativepanels;

import android.os.Bundle;

final class Protocol {
  // Hosts retain their receiver and compiled resource IDs until their process restarts.
  static final String ACTION = "dev.oos.nativepanels.COMMAND." + BuildConfig.SYSTEM_UI_REVISION;
  static final String PERMISSION = "dev.oos.nativepanels.permission.CONTROL";
  static final String TARGET = "com.android.systemui";
  static final String[] KEYS = {"internet", "modes", "bluetooth", "media", "channels", "flashlight", "ime", "privacy", "users", "notification_cards"};

  static final String REVISION = "systemUiRevision";
  static final String AVAILABLE = "availableFeatures";
  static final int UNAVAILABLE = -2;

  static boolean hasLiveState(Bundle state) {
    if (state == null || !BuildConfig.SYSTEM_UI_REVISION.equals(state.getString(REVISION))
        || !state.containsKey("ready")) return false;
    Bundle available = state.getBundle(AVAILABLE);
    if (available == null) return false;
    for (String key : KEYS) {
      if (!state.containsKey(key) || !available.containsKey(key)) return false;
    }
    return true;
  }

  static boolean oosPanel(String key) {
    return switch (key) {
      case "internet", "bluetooth", "modes", "media", "flashlight", "privacy", "users" -> true;
      default -> false;
    };
  }

  static boolean isFeature(String s) {
    for (String k : KEYS) if (k.equals(s)) return true;
    return false;
  }
}
