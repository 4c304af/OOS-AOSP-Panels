package dev.oos.nativepanels;

final class Protocol {
  static final String ACTION = "dev.oos.nativepanels.COMMAND";
  static final String PERMISSION = "dev.oos.nativepanels.permission.CONTROL";
  static final String TARGET = "com.android.systemui";
  static final String[] KEYS = {"internet", "modes", "bluetooth", "media", "channels", "flashlight", "ime", "privacy", "users", "notification_cards"};

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
