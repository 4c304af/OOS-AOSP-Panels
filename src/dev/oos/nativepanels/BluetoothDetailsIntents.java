package dev.oos.nativepanels;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

/** Device-specific navigation contracts verified in the supplied Settings and Wireless Settings APKs. */
final class BluetoothDetailsIntents {
  static final String SETTINGS = "com.android.settings";
  static final String WIRELESS = "com.oplus.wirelesssettings";
  static final String ACTION = "com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS";
  static final String ACTIVITY = "com.android.settings.Settings$BluetoothDeviceDetailActivity";
  static final String PROFILE_FRAGMENT = "com.oplus.wirelesssettings.bluetooth.B";
  static final String SUB_SETTINGS = "com.oplus.wirelesssettings.SubSettingsExt";
  static final String FRAGMENT = ":settings:show_fragment";
  static final String ARGUMENTS = ":settings:show_fragment_args";
  static final String ADDRESS = "device_address";

  static boolean isNativeDetails(Intent original) {
    if (original == null || !ACTION.equals(original.getAction())) return false;
    ComponentName component = original.getComponent();
    return (original.getPackage() == null || SETTINGS.equals(original.getPackage()))
        && (component == null || new ComponentName(SETTINGS, ACTIVITY).equals(component));
  }

  static String selectedNativeAddress(Intent original) {
    if (!isNativeDetails(original)) return null;
    try {
      Bundle arguments = original.getBundleExtra(ARGUMENTS);
      String address = arguments == null ? null : arguments.getString(ADDRESS);
      return BluetoothAdapter.checkBluetoothAddress(address) ? address : null;
    } catch (RuntimeException malformed) {
      return null;
    }
  }

  static boolean isWirelessDetails(Intent original) {
    if (original == null) return false;
    ComponentName component = original.getComponent();
    if (!new ComponentName(WIRELESS, SUB_SETTINGS).equals(component)) return false;
    if (original.getPackage() != null && !WIRELESS.equals(original.getPackage())) return false;
    try {
      // Keep profile-specific links such as absolute volume on their original page.
      return PROFILE_FRAGMENT.equals(original.getStringExtra(FRAGMENT))
          && !original.hasExtra(":settings:fragment_args_key")
          && !original.hasExtra("highlight_args_key");
    } catch (RuntimeException malformed) {
      return false;
    }
  }

  static Intent fromNative(Context context, Intent original, boolean enabled) {
    return enabled ? target(context, original, selectedNativeAddress(original)) : original;
  }

  static Intent fromWireless(Context context, Intent original, boolean enabled) {
    if (!enabled || !isWirelessDetails(original)) return original;
    try {
      Bundle arguments = original.getBundleExtra(ARGUMENTS);
      Object selected = arguments == null ? null : arguments.get("device");
      if (!(selected instanceof BluetoothDevice device)) return original;
      return target(context, original, device.getAddress());
    } catch (RuntimeException malformed) {
      return original;
    }
  }

  private static Intent target(Context context, Intent original, String address) {
    if (context == null || !BluetoothAdapter.checkBluetoothAddress(address)) return original;
    Bundle arguments = new Bundle();
    arguments.putString(ADDRESS, address);
    // The OEM fragment name, title resource IDs and BluetoothDevice parcelable belong
    // to Wireless Settings. Do not pass them to the native Settings activity.
    Intent target;
    if (isNativeDetails(original)) {
      // SystemUI already supplied native arguments, metrics and transition extras.
      target = new Intent(original).setClassName(SETTINGS, ACTIVITY);
    } else {
      target = new Intent(ACTION).setClassName(SETTINGS, ACTIVITY)
          .setFlags(original.getFlags()).putExtra(ARGUMENTS, arguments);
      target.setSourceBounds(original.getSourceBounds());
    }
    try {
      // Intent.resolveActivity returns an explicit component without asking whether
      // it exists. Ask PackageManager so absent/disabled native pages keep OOS routing.
      ResolveInfo resolved = context.getPackageManager().resolveActivity(target, PackageManager.MATCH_DEFAULT_ONLY);
      ActivityInfo activity = resolved == null ? null : resolved.activityInfo;
      boolean available = activity != null && activity.enabled && activity.exported
          && activity.applicationInfo != null && activity.applicationInfo.enabled
          && SETTINGS.equals(activity.packageName) && ACTIVITY.equals(activity.name);
      return available ? target : original;
    } catch (RuntimeException unavailable) {
      return original;
    }
  }

  private BluetoothDetailsIntents() {}
}
